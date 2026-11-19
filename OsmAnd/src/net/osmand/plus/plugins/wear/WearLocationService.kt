package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.osmand.Location
import net.osmand.PlatformUtil
import net.osmand.StateChangedListener
import net.osmand.plus.OsmAndLocationProvider
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.helpers.LocationCallback
import net.osmand.plus.helpers.LocationServiceHelper
import net.osmand.plus.plugins.PluginsHelper
import net.osmand.plus.plugins.monitoring.OsmandMonitoringPlugin
import net.osmand.plus.settings.enums.LocationSource
import net.osmand.plus.routing.VoiceRouter
import java.util.UUID

/** Keeps phone location available to the watch and, when needed, OsmAnd recording. */
class WearLocationService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as OsmandApplication
    @Volatile private var owner: Pair<String, UUID>? = null
    private var helper: LocationServiceHelper? = null
    private var confirmed = 0L
    private var confirmationGeneration = 0
    @Volatile private var destroyed = false
    private var locationGeneration = 0
    private var checking = false
    private var checkGeneration = 0
    private var checkDeadline: Runnable? = null
    private var recordingFromWatch = false
    private var recordingNode: String? = null
    /**
     * Started with startService() while OsmAnd's own navigation service (a location foreground
     * service) was running and OsmAnd was in the background. Android refuses a second location
     * foreground service then, but the running one keeps location access for the whole process, so
     * this service needs no foreground state of its own. It takes one once OsmAnd is in front again.
     */
    private var borrowed = false
    /** The rate of the running location request, in ms between fixes; 0 is full rate. */
    private var requestedInterval = 0L
    /** Whether startForeground() has succeeded for this service instance. */
    private var inForeground = false
    private val transport get() = PhoneTransportFactory.get(app)
    private val sourceChanged = StateChangedListener<LocationSource> { main.post { requestLocation() } }
    private val voiceMessages = VoiceRouter.VoiceMessageListener { commands, played ->
        val kind = RouteAlertProtocol.voiceKind(commands, played, app.settings.VOICE_MUTE.get(),
            app.settings.SPEAK_ROUTE_RECALCULATION.get(), app.settings.SPEAK_ROUTE_DEVIATION.get())
        val expected = owner
        if (kind != null && expected == null) WearOsPlugin.trace("route alert not sent: no watch session")
        if (kind != null && expected != null) main.post {
            val name = if (kind == RouteAlertProtocol.OFF_ROUTE) "off route" else "route recalculated"
            if (!destroyed && owner == expected && WearOsPlugin.allowed(app) &&
                (kind != RouteAlertProtocol.OFF_ROUTE || WearOsPlugin.offRouteEnabled(app)) &&
                (kind != RouteAlertProtocol.ROUTE_RECALCULATED || WearOsPlugin.routeRecalculatedEnabled(app))) {
                val alert = RouteAlertProtocol.Alert(UUID.randomUUID(), expected.second, kind)
                val sent = transport.sendRouteAlert(RouteAlertProtocol.encode(alert))
                WearOsPlugin.trace("route alert $name: ${if (sent) "sent" else "not sent (watch not connected or queue full)"}")
            } else {
                WearOsPlugin.trace("route alert $name not sent: " + when {
                    destroyed || owner != expected -> "watch session ended"
                    !WearOsPlugin.allowed(app) -> "plugin disabled"
                    else -> "turned off in plugin settings"
                })
            }
        }
    }
    private val disabled = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            WearOsPlugin.trace("location service stopping: plugin disabled")
            stopSelf()
        }
    }
    private val checkSession = Runnable { checkWatch() }
    private val expirePrestart = Runnable {
        if (owner == null && !recordingFromWatch) {
            WearOsPlugin.trace("location prestart expired unclaimed")
            stopSelf()
        }
    }
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        WearOsPlugin.trace("location service created")
        live = this
        ContextCompat.registerReceiver(this, disabled, IntentFilter(WearOsPlugin.DISABLED), ContextCompat.RECEIVER_NOT_EXPORTED)
        app.settings.LOCATION_SOURCE.addListener(sourceChanged)
        app.routingHelper.voiceRouter.addVoiceMessageListener(voiceMessages)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            WearOsPlugin.trace("location service: stop pressed in notification; watch blocked until reopened")
            blocked = owner
            WearOsPlugin.pendingLocation = null
            WearOsPlugin.pendingLocationAt = 0
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == PRESTART) {
            if (!WearOsPlugin.allowed(app) || !OsmAndLocationProvider.isLocationPermissionAvailable(app)) {
                WearOsPlugin.trace("location prestart refused: " +
                    if (!WearOsPlugin.allowed(app)) "plugin disabled" else "location permission missing")
                stopUnclaimedStart()
                return START_NOT_STICKY
            }
            return try {
                startLocationNotification()
                WearOsPlugin.trace("location service prestarted")
                if (owner == null && !recordingFromWatch) {
                    main.removeCallbacks(expirePrestart)
                    main.postDelayed(expirePrestart, PRESTART_TIMEOUT_MS)
                }
                START_NOT_STICKY
            } catch (error: Exception) {
                recordFailure(app, owner, error)
                stopSelf()
                START_NOT_STICKY
            }
        }
        WearOsPlugin.locationStarting = false
        if (intent?.action == BORROW) {
            if (!inForeground) borrowed = true
        } else if (borrowed) {
            // startForegroundService() requires startForeground() even when this service already runs.
            try { startLocationNotification() } catch (error: Exception) {
                recordFailure(app, owner, error); stopSelf(); return START_NOT_STICKY
            }
        }
        val next = WearOsPlugin.pendingLocation
        if (next == null || !WearOsPlugin.allowed(app) || !OsmAndLocationProvider.isLocationPermissionAvailable(app)) {
            // A watch close can clear the pending session before this start arrives. An active
            // lease or watch-started recording stays; otherwise stop through the foreground state.
            if (next == null && WearOsPlugin.allowed(app) && (owner != null || recordingFromWatch)) {
                WearOsPlugin.trace("location service start: no pending session; existing lease kept")
                return START_NOT_STICKY
            }
            WearOsPlugin.trace("location service start not claimed: " + when {
                next == null -> "no pending watch session (closed or already claimed)"
                !WearOsPlugin.allowed(app) -> "plugin disabled"
                else -> "location permission missing"
            })
            stopUnclaimedStart()
            return START_NOT_STICKY
        }
        WearOsPlugin.pendingLocation = null
        WearOsPlugin.pendingLocationAt = 0
        activateSession(next)
        return START_NOT_STICKY
    }

    /**
     * Every non-STOP start comes from startForegroundService(). Android crashes the process when
     * such a service stops before startForeground(), so promote it first, then stop it.
     */
    private fun stopUnclaimedStart() {
        WearOsPlugin.trace("location service start unclaimed; stopping")
        if (borrowed) { stopSelf(); return }
        try {
            startLocationNotification()
        } catch (error: Exception) {
            recordFailure(app, null, error)
        }
        stopSelf()
    }

    /** Main thread only. Both ordinary starts and prestart claims must arm the same lease check. */
    private fun activateSession(next: Pair<String, UUID>): Boolean {
        val needsLocation = owner == null
        val changed = owner != next
        owner = next
        confirmed = SystemClock.elapsedRealtime()
        confirmationGeneration++
        failed = null
        main.removeCallbacks(expirePrestart)
        return try {
            // Routine refreshes must not postpone a probe or repeat foreground promotion.
            if (needsLocation || changed) {
                WearOsPlugin.trace((if (needsLocation) "location lease started" else "location lease moved to new watch session") +
                    if (borrowed) " (using OsmAnd's navigation service: ${WearDeviceEvents.osmandService(app)})" else "")
                if (!borrowed) startLocationNotification()
                if (needsLocation && !requestLocation()) return false
                scheduleSessionCheck()
            }
            true
        } catch (error: Exception) {
            recordFailure(app, next, error)
            stopSelf()
            false
        }
    }

    private fun scheduleSessionCheck() {
        main.removeCallbacks(checkSession)
        val delay = if (owner == null) SESSION_CHECK_INTERVAL_MS else {
            (SESSION_TIMEOUT_MS - (SystemClock.elapsedRealtime() - confirmed))
                .coerceIn(0L, SESSION_CHECK_INTERVAL_MS)
        }
        main.postDelayed(checkSession, delay)
    }

    private fun releaseSession(reason: String) {
        LOG.info("Watch location lease released")
        WearOsPlugin.trace("location lease released: $reason (recordingFromWatch=$recordingFromWatch)")
        checkGeneration++
        checking = false
        checkDeadline?.let { main.removeCallbacks(it) }
        checkDeadline = null
        if (recordingFromWatch) {
            owner = null
            WearOsPlugin.latestLocation = null
            scheduleSessionCheck()
        } else {
            stopSelf()
        }
    }

    private fun startLocationNotification() {
        val channel = "wear-map-location"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,
            getString(R.string.wear_os_location_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MapActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, WearLocationService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_action_my_location)
            .setContentTitle(getString(R.string.wear_os_location_title))
            .setContentText(getString(if (owner == null && !recordingFromWatch)
                R.string.wear_os_location_waiting else R.string.wear_os_location_text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setLocalOnly(true)
            .addAction(0, getString(R.string.wear_os_location_stop), stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION, notification)
        inForeground = true
        borrowed = false
    }

    /**
     * Full rate while the watch display is awake or the watch started a recording; one fix every
     * [ASLEEP_INTERVAL_MS] while the display sleeps. That keeps the last fix within the 30-second
     * freshness window, so the first frame after waking still shows a fresh position.
     */
    private fun desiredInterval(): Long = if (watchAwake || recordingFromWatch) 0L else ASLEEP_INTERVAL_MS

    /** Main thread only. Restarts updates when the wanted rate changed; the last fix is kept. */
    internal fun updateRate() {
        if (helper != null && requestedInterval != desiredInterval()) {
            LOG.info("Watch location rate: ${if (desiredInterval() == 0L) "full" else "reduced"}")
            WearOsPlugin.trace("location rate: ${if (desiredInterval() == 0L) "full" else "one fix per ${ASLEEP_INTERVAL_MS / 1000} s"}" +
                " (watch ${if (watchAwake) "awake" else "asleep"}, recordingFromWatch=$recordingFromWatch)")
            requestLocation(keepFix = true)
        }
    }

    private fun requestLocation(keepFix: Boolean = false): Boolean {
        runCatching { helper?.removeLocationUpdates() }
        helper = null
        if (destroyed || (owner == null && !recordingFromWatch) || !WearOsPlugin.allowed(app)) {
            // Its caller then answers PHONE_REQUIRED; record why.
            WearOsPlugin.trace("location updates not started: " + when {
                destroyed -> "service already stopping"
                !WearOsPlugin.allowed(app) -> "plugin disabled"
                else -> "no watch session or recording"
            })
            return false
        }
        if (!OsmAndLocationProvider.isLocationPermissionAvailable(app)) {
            WearOsPlugin.trace("location service stopping: location permission missing")
            stopSelf()
            return false
        }
        val epoch = ++locationGeneration
        if (!keepFix) WearOsPlugin.latestLocation = null
        val interval = desiredInterval()
        requestedInterval = interval
        return try {
            helper = app.createLocationServiceHelper().also { location ->
                location.requestLocationUpdates(object : LocationCallback() {
                    override fun onLocationResult(locations: List<Location>) {
                        if (destroyed || locationGeneration != epoch || !WearOsPlugin.allowed(app)) return
                        val fix = locations.lastOrNull() ?: return
                        WearOsPlugin.traceCount("location fix")
                        if (owner != null) WearOsPlugin.latestLocation = Location(fix)
                        // An already-running location FGS can serve recording when Android
                        // will not allow OsmAnd to create a second location FGS under lock.
                        if (recordingFromWatch && app.navigationService == null &&
                            PluginsHelper.getActivePlugin(OsmandMonitoringPlugin::class.java)?.isRecordingTrack == true) {
                            app.locationProvider.setLocationFromService(fix)
                        }
                    }
                }, interval)
            }
            WearOsPlugin.trace("location updates started (${helper?.javaClass?.simpleName}, " +
                "${if (interval == 0L) "full rate" else "one fix per ${interval / 1000} s"}, source ${app.settings.LOCATION_SOURCE.get()})")
            true
        } catch (error: Exception) {
            recordFailure(app, owner, error)
            stopSelf()
            false
        }
    }
    /**
     * Main thread only. A borrowing service depends on OsmAnd's navigation service. When that stops,
     * take an own foreground state if OsmAnd is in front; otherwise stop. Returns whether it still runs.
     */
    internal fun keepBorrowed(): Boolean {
        if (!borrowed || app.navigationService != null) return true
        if (app.isAppInForegroundOnRootDevice) promote("OsmAnd's navigation service stopped")
        if (!borrowed) return true
        WearOsPlugin.trace("location service stopping: OsmAnd's navigation service stopped with OsmAnd in background")
        stopSelf()
        return false
    }

    /** Main thread only. Takes this service's own foreground state; only possible with OsmAnd in front. */
    private fun promote(reason: String) {
        try {
            startLocationNotification()
            WearOsPlugin.trace("location service took its own foreground state ($reason)")
        } catch (error: Exception) {
            WearOsPlugin.trace("location service could not take its own foreground state: ${LinkDiagnostics.describe(error)}")
        }
    }

    private fun checkWatch() {
        if (!keepBorrowed()) return
        if (recordingFromWatch) {
            val monitor = PluginsHelper.getPlugin(OsmandMonitoringPlugin::class.java)
            if (monitor == null || (!monitor.isRecordingTrack && !monitor.hasDataToSave() && !monitor.isSaving)) {
                disableRecording()
            }
        }
        val expected = owner ?: run {
            if (recordingFromWatch) scheduleSessionCheck()
            return
        }
        if (checking) return
        checking = true
        val generation = ++checkGeneration
        val confirmationAtStart = confirmationGeneration
        val startedAt = SystemClock.elapsedRealtime()
        val overdue = startedAt - confirmed >= SESSION_TIMEOUT_MS
        if (overdue) {
            LOG.info("Watch location lease overdue; making final bounded probe")
            WearOsPlugin.trace("location lease overdue; final probe")
        }
        val expire = Runnable {
            if (destroyed || generation != checkGeneration) return@Runnable
            checking = false
            checkGeneration++
            checkDeadline = null
            if (owner == expected && SystemClock.elapsedRealtime() - confirmed >= SESSION_TIMEOUT_MS) {
                LOG.info("Watch location lease expired after unanswered probe")
                releaseSession("expired after unanswered probe")
            } else scheduleSessionCheck()
        }
        checkDeadline = expire
        main.postDelayed(expire, SESSION_PROBE_TIMEOUT_MS)
        // Bind the probe to its owner, rather than whichever peer happens to be connected now.
        val peer = transport.activePeer()?.takeIf { it.key == expected.first }
        if (peer == null) WearOsPlugin.traceCount("lease probe skipped: watch not connected")
        val probe = if (peer != null) transport.checkSession(peer, expected.second)
            else java.util.concurrent.CompletableFuture<Boolean>().also {
                it.completeExceptionally(LinkException(LinkFailure.DISCONNECTED))
            }
        probe
            .whenComplete { active, error ->
                main.post {
                    if (destroyed || generation != checkGeneration) return@post
                    checking = false
                    main.removeCallbacks(expire)
                    checkDeadline = null
                    if (owner != expected) {
                        scheduleSessionCheck()
                        return@post
                    }
                    val now = SystemClock.elapsedRealtime()
                    // A newer session-open request supersedes this probe, including a late negative.
                    if (confirmationGeneration != confirmationAtStart) {
                        scheduleSessionCheck()
                        return@post
                    }
                    if (error == null && active && now - startedAt <= SESSION_PROBE_TIMEOUT_MS) confirmed = now
                    WearOsPlugin.traceCount(when {
                        error != null -> "lease probe failed: ${failureName(error)}"
                        active -> "lease probe ok"
                        else -> "lease probe: watch session closed"
                    })
                    if ((error == null && !active) || now - confirmed >= SESSION_TIMEOUT_MS) {
                        LOG.info(if (error == null && !active) "Watch session closed by peer"
                            else "Watch location lease expired after failed probe")
                        releaseSession(if (error == null && !active) "watch reports session closed"
                            else "expired: no answered probe for ${(now - confirmed) / 1000} s")
                    } else {
                        scheduleSessionCheck()
                    }
                }
            }
    }

    override fun onDestroy() {
        WearOsPlugin.trace("location service destroyed (lease=${owner != null} recordingFromWatch=$recordingFromWatch)")
        destroyed = true
        WearOsPlugin.locationStarting = false
        main.removeCallbacks(checkSession)
        main.removeCallbacks(expirePrestart)
        checkGeneration++
        checkDeadline?.let { main.removeCallbacks(it) }
        // A stopped location source must not leave OsmAnd claiming to record with no fixes.
        if (recordingFromWatch && app.navigationService == null) {
            PluginsHelper.getPlugin(OsmandMonitoringPlugin::class.java)
                ?.takeIf { it.isRecordingTrack }?.let {
                    WearOsPlugin.trace("trip recording stopped: its location source (this service) stopped")
                    it.stopRecording(false)
                }
        }
        runCatching { helper?.removeLocationUpdates() }
        helper = null
        app.routingHelper.voiceRouter.removeVoiceMessageListener(voiceMessages)
        app.settings.LOCATION_SOURCE.removeListener(sourceChanged)
        unregisterReceiver(disabled)
        WearOsPlugin.latestLocation = null
        if (live === this) { live = null; watchAwake = true }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private const val NOTIFICATION = 10031
        private const val STOP = "net.osmand.wear.STOP_LOCATION"
        private const val PRESTART = "net.osmand.wear.PRESTART_LOCATION"
        /** A plain start while OsmAnd's navigation service holds location; see [borrowed]. */
        internal const val BORROW = "net.osmand.wear.BORROW_LOCATION"
        private const val PRESTART_TIMEOUT_MS = 180_000L
        private const val SESSION_CHECK_INTERVAL_MS = 60_000L
        private const val SESSION_TIMEOUT_MS = 180_000L
        private const val SESSION_PROBE_TIMEOUT_MS = 10_000L
        private val LOG = PlatformUtil.getLog(WearLocationService::class.java)
        @Volatile private var live: WearLocationService? = null
        private var blocked: Pair<String, UUID>? = null
        private data class Failure(val session: Pair<String, UUID>, val foregroundRequired: Boolean, val retryAt: Long)
        private var failed: Failure? = null
        /** Main thread only. The display state the watch sent with its latest session open. */
        private var watchAwake = true
        /** Location interval while the watch display sleeps; within the 30-second freshness window. */
        internal const val ASLEEP_INTERVAL_MS = 15_000L

        internal fun recordFailure(app: OsmandApplication, session: Pair<String, UUID>?, error: Exception) {
            val foregroundRequired = error is SecurityException ||
                (Build.VERSION.SDK_INT >= 31 && (error is android.app.ForegroundServiceStartNotAllowedException ||
                    error is android.app.BackgroundServiceStartNotAllowedException))
            if (session != null) failed = Failure(session, foregroundRequired, SystemClock.elapsedRealtime() + 5_000)
            WearOsPlugin.trace("location service start failed: ${LinkDiagnostics.describe(error)}" +
                (if (foregroundRequired) " (needs OsmAnd in foreground)" else " (retryable)") +
                "; ${WearDeviceEvents.state(app)}")
            // The diagnostic line scrubs the message; the platform log keeps only the class.
            LOG.warn("Watch location activation failed: reason=${if (foregroundRequired) "foreground_required" else "retryable"} type=${error.javaClass.simpleName}")
        }

        /** The once-a-minute lease summary; null when no service runs. */
        internal fun describeLease(): String? = live?.takeIf { !it.destroyed }?.let {
            val age = (SystemClock.elapsedRealtime() - it.confirmed) / 1000
            "${if (it.owner != null) "held, confirmed ${age}s ago" else "idle"} recordingFromWatch=${it.recordingFromWatch} " +
                "watchAwake=$watchAwake updates=${it.helper != null} " +
                (if (it.borrowed) "using OsmAnd's navigation service" else "own foreground=${it.inForeground}")
        }

        private fun failureName(error: Throwable): String {
            var cause: Throwable? = error
            while (cause is java.util.concurrent.CompletionException && cause.cause != null) cause = cause.cause
            return (cause as? LinkException)?.reason?.name ?: LinkDiagnostics.describe(cause)
        }

        private fun statusName(code: Int) = when (code) {
            MapProtocol.SESSION_READY -> "READY"
            MapProtocol.SESSION_PHONE_REQUIRED -> "PHONE_REQUIRED"
            MapProtocol.SESSION_STOPPED -> "STOPPED"
            MapProtocol.SESSION_DENIED -> "DENIED"
            MapProtocol.SESSION_CONNECTING -> "CONNECTING"
            MapProtocol.SESSION_BUSY -> "BUSY"
            else -> "status $code"
        }

        private fun failureStatus(session: Pair<String, UUID>): Int =
            if (failed?.takeIf { it.session == session }?.foregroundRequired == false) MapProtocol.SESSION_CONNECTING
            else MapProtocol.SESSION_PHONE_REQUIRED
        /** Read from the phone UI thread for the plugin status panel. */
        fun hasActiveSession(): Boolean = live?.let { !it.destroyed && it.owner != null } == true
        /** Debug/test-only: the live watch-owned session used by the loopback alert trigger. */
        internal fun activeSession(): Pair<String, UUID>? = live?.let { if (it.destroyed) null else it.owner }
        /** Main thread only. Widget control requires the live watch location session. */
        fun owns(node: String, id: UUID): Boolean = live?.let { !it.destroyed && it.owner == (node to id) } == true

        /** Main thread only. Keep this already-running location FGS for recording. */
        fun enableRecording(node: String, id: UUID): Boolean = live?.let {
            if (it.destroyed || it.owner != (node to id)) {
                false
            } else {
                WearOsPlugin.trace("recording from watch: location service kept for recording")
                it.recordingFromWatch = true
                it.recordingNode = node
                // OsmAnd's recorder uses these fixes, so recording always runs at full rate.
                it.updateRate()
                true
            }
        } == true

        /** Main thread only. Release the extra hold after Finish or a failed Start. */
        fun disableRecording() {
            live?.let {
                WearOsPlugin.trace("recording from watch ended (lease=${it.owner != null})")
                it.recordingFromWatch = false
                it.recordingNode = null
                if (it.owner == null) it.stopSelf() else it.updateRate()
            }
        }
        /**
         * Main thread only; node identity has already been checked by the authenticated transport.
         * [awake] is the watch display state; while it sleeps, phone location runs at a reduced rate.
         */
        fun refresh(app: OsmandApplication, node: String, id: UUID, awake: Boolean = true): Int {
            if (!WearOsPlugin.allowed(app)) {
                WearOsPlugin.trace("session refused: plugin disabled")
                return MapProtocol.SESSION_DENIED
            }
            if (app.getSystemService(ActivityManager::class.java).appTasks.isEmpty()) {
                WearOsPlugin.trace("session refused: OsmAnd task removed")
                return MapProtocol.SESSION_PHONE_REQUIRED
            }
            val next = node to id
            if (blocked == next) {
                WearOsPlugin.trace("session refused: location stopped from the phone notification")
                return MapProtocol.SESSION_STOPPED
            }
            watchAwake = awake
            val current = live
            if (current != null && !current.destroyed && !current.keepBorrowed()) return MapProtocol.SESSION_PHONE_REQUIRED
            if (current != null && !current.destroyed && current.owner == null) {
                if (!OsmAndLocationProvider.isLocationPermissionAvailable(app)) {
                    WearOsPlugin.trace("session refused: location permission missing")
                    current.stopSelf()
                    return MapProtocol.SESSION_PHONE_REQUIRED
                }
                return if (current.activateSession(next)) { current.updateRate(); MapProtocol.SESSION_READY }
                    else failureStatus(next).also { WearOsPlugin.trace("session lease on idle service failed: ${statusName(it)}") }
            }
            if (current != null && !current.destroyed && (current.owner?.first == node ||
                    (current.owner == null && current.recordingFromWatch && current.recordingNode == node))) {
                if (!OsmAndLocationProvider.isLocationPermissionAvailable(app)) {
                    WearOsPlugin.trace("session refused: location permission missing")
                    current.stopSelf()
                    return MapProtocol.SESSION_PHONE_REQUIRED
                }
                return if (current.activateSession(next)) { current.updateRate(); MapProtocol.SESSION_READY }
                    else failureStatus(next).also { WearOsPlugin.trace("session lease renewal failed: ${statusName(it)}") }
            }
            failed?.takeIf { it.session == next && !app.isAppInForegroundOnRootDevice }?.let {
                // A refused start is retried too, because Android's decision can change (lock state,
                // exemptions). OsmAnd's own navigation service, started since, lets a plain start succeed at once.
                if (it.foregroundRequired && app.navigationService == null && SystemClock.elapsedRealtime() < it.retryAt) {
                    WearOsPlugin.traceCount("session refused: Android refused the location start; retrying")
                    return MapProtocol.SESSION_PHONE_REQUIRED
                }
                if (!it.foregroundRequired && SystemClock.elapsedRealtime() < it.retryAt) {
                    WearOsPlugin.trace("session waiting: retrying a failed location start in ${(it.retryAt - SystemClock.elapsedRealtime()) / 1000} s")
                    return MapProtocol.SESSION_CONNECTING
                }
            }
            // The service's state before a new start: why no existing lease could serve this watch.
            WearOsPlugin.trace("session needs a location start: " + when {
                current == null -> "service not running"
                current.destroyed -> "service stopping"
                current.owner != null -> "service leased to another watch session"
                current.recordingFromWatch -> "service held for recording from another watch"
                else -> "service not claimable"
            } + "; earlier failure=${failed?.let { if (it.session == next) (if (it.foregroundRequired) "foreground required" else "retryable") else "other session" } ?: "none"}")
            failed = null
            blocked = null
            WearOsPlugin.pendingLocation = next
            WearOsPlugin.pendingLocationAt = SystemClock.elapsedRealtime()
            return if (WearOsPlugin.startPendingLocation(app)) MapProtocol.SESSION_CONNECTING else failureStatus(next).also {
                if (it == MapProtocol.SESSION_PHONE_REQUIRED) WearOsPlugin.trace("session needs the phone: location start not possible now")
            }
        }
        /** Main thread only. With OsmAnd in front again, a borrowing service takes its own foreground state. */
        fun promoteBorrowed() {
            live?.takeIf { !it.destroyed && it.borrowed }?.promote("OsmAnd in front")
        }
        /** Main thread only. Start the location FGS while the app is still foreground, before lock. */
        fun prestart(app: OsmandApplication) {
            if (!WearOsPlugin.allowed(app) || !OsmAndLocationProvider.isLocationPermissionAvailable(app)) {
                WearOsPlugin.trace("location prestart skipped: " +
                    if (!WearOsPlugin.allowed(app)) "plugin disabled" else "location permission missing")
                return
            }
            // Only a paired watch on a running link can claim the prestart. Otherwise every
            // screen-off would show the location notification for nothing.
            val transport = PhoneTransportFactory.get(app)
            val link = transport.state()
            if ((link != LinkState.READY && link != LinkState.CONNECTING) || !transport.hasPairedPeer()) {
                WearOsPlugin.trace("location prestart skipped: link $link, paired watch ${transport.hasPairedPeer()}")
                return
            }
            val current = live
            if (current != null && !current.destroyed) {
                WearOsPlugin.trace("location prestart skipped: service already running (lease=${current.owner != null})")
                return
            }
            try {
                val intent = Intent(app, WearLocationService::class.java).setAction(PRESTART)
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
                WearOsPlugin.trace("location prestart requested")
            } catch (error: Exception) { recordFailure(app, null, error) }
        }
        fun release(node: String, id: UUID? = null) {
            WearOsPlugin.trace("location lease release requested (watch closed or stop)")
            if (WearOsPlugin.pendingLocation?.let { it.first == node && (id == null || it.second == id) } == true) {
                WearOsPlugin.pendingLocation = null
                WearOsPlugin.pendingLocationAt = 0
            }
            val holder = live?.takeIf { it.owner?.let { owner -> owner.first == node && (id == null || owner.second == id) } == true }
            WearOsPlugin.trace("location lease release: " + when {
                holder == null -> "no matching lease"
                holder.recordingFromWatch -> "lease ended; service kept for recording"
                else -> "service stopping"
            })
            holder?.let {
                if (it.recordingFromWatch) {
                    it.owner = null
                    WearOsPlugin.latestLocation = null
                    watchAwake = true
                } else {
                    it.stopSelf()
                }
            }
        }
    }
}

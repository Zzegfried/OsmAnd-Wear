package net.osmand.plus.plugins.wear

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import net.osmand.Location
import net.osmand.plus.OsmAndLocationProvider
import net.osmand.plus.activities.MapActivity
import java.util.UUID
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.plugins.OsmandPlugin
import net.osmand.plus.plugins.wear.protocol.LinkDiagnostics
import net.osmand.plus.settings.fragments.SettingsScreenType

/** Native plugin switch owns map sharing and the watch phone-location session. */
class WearOsPlugin(app: OsmandApplication) : OsmandPlugin(app) {
    val watchOffRoute = registerBooleanPreference("wear_watch_off_route", true).makeProfile()
    val watchRouteRecalculated = registerBooleanPreference("wear_watch_route_recalculated", true).makeProfile()
    val watchLabelScale = registerIntPreference("wear_watch_label_scale_percent", 100).makeProfile()
    val watchInitialZoom = registerIntPreference("wear_watch_initial_zoom_offset", 0).makeProfile()
    val watchRuler = registerBooleanPreference("wear_watch_ruler", true).makeProfile()
    val watchClock = registerBooleanPreference("wear_watch_clock", true).makeProfile()
    val watchRecordingControls = registerBooleanPreference("wear_watch_recording_controls", true).makeGlobal()
    /** Diagnostic logging of the watch link on both devices; the watch follows this setting. */
    val diagnosticLogging = registerBooleanPreference("wear_diagnostic_logging", false).makeGlobal()
    private val diagnosticsChanged = net.osmand.StateChangedListener<Boolean> { applyDiagnostics("setting changed") }

    init {
        diagnostics = LinkDiagnostics(java.io.File(app.filesDir, DIAGNOSTICS_DIR), "phone-link", header = {
            "app ${app.packageName} ${app.getString(R.string.wear_companion_version, app.getString(R.string.wear_companion_version_name),
                app.getString(R.string.wear_companion_build))}, OsmAnd ${net.osmand.plus.Version.getFullVersion(app)}, " +
                "Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; " +
                "${WearDeviceEvents.process(app)}; ${WearDeviceEvents.limits(app)}"
        }, heartbeat = { heartbeat(app) })
        diagnosticLogging.addListener(diagnosticsChanged)
        applyDiagnostics("app start")
    }

    private fun applyDiagnostics(reason: String) {
        val on = diagnosticLogging.get()
        diagnostics?.setEnabled(on, reason)
        WearDeviceEvents.update(app, on)
    }

    override fun getId() = ID
    override fun getName(): String = app.getString(R.string.wear_os_companion_name)
    override fun getDescription(linksEnabled: Boolean): CharSequence = app.getString(R.string.wear_os_companion_description)
    override fun getAssetResourceImage(): Drawable? =
        ContextCompat.getDrawable(app, R.drawable.plugin_wear_os_header)
    override fun getSettingsScreenType() = SettingsScreenType.WEAR_OS_SETTINGS
    override fun init(app: OsmandApplication, activity: Activity?): Boolean {
        if (!supported(app)) return false
        if (activity != null) {
            // Switched on from a screen, so OsmAnd is in front and may start the link service now.
            // OsmAnd records the plugin as enabled only after init returns, hence the post.
            trace("plugin enabled")
            app.runInUIThread { startLink(app, "plugin enabled") }
        }
        return true
    }
    override fun mapActivityResume(activity: MapActivity) {
        trace("OsmAnd map resumed")
        activity.window.decorView.post {
            // Removing the phone task stops the link service; reopening the map restarts it.
            startLink(app, "map resume")
            startPendingLocation(app)
            WearLocationService.promoteBorrowed()
        }
    }
    /** Starts the link service when it is off; a service waiting to listen again (UNAVAILABLE) tries at once. */
    private fun startLink(app: OsmandApplication, reason: String) {
        val transport = PhoneTransportFactory.get(app)
        if (allowed(app) && (transport.state() == LinkState.OFF || transport.state() == LinkState.UNAVAILABLE)) {
            val started = runCatching { transport.start() }
            trace("link service start on $reason: " +
                started.fold({ if (it) "requested" else "refused (see previous line)" }, { LinkDiagnostics.describe(it) }))
        }
    }
    override fun mapActivityPause(activity: MapActivity) {
        trace("OsmAnd map paused")
    }
    override fun mapActivityScreenOff(activity: MapActivity) {
        trace("OsmAnd map screen off; location prestart")
        // Start the watch location FGS while the phone app is still foreground, so a watch
        // opened after the lock screen appears can claim it without a background start.
        WearLocationService.prestart(app)
    }
    override fun disable(app: OsmandApplication) {
        trace("plugin disabled")
        pendingLocation = null
        pendingLocationAt = 0
        latestLocation = null
        PhoneTransportFactory.get(app).stop()
        app.stopService(Intent().setClassName(app, LOCATION_SERVICE))
        super.disable(app)
        app.sendBroadcast(Intent(DISABLED).setPackage(app.packageName))
    }
    companion object {
        private fun plugin() = net.osmand.plus.plugins.PluginsHelper.getPlugin(WearOsPlugin::class.java)
        fun offRouteEnabled(app: OsmandApplication) = plugin()?.watchOffRoute?.getModeValue(app.settings.applicationMode) ?: true
        fun routeRecalculatedEnabled(app: OsmandApplication) = plugin()?.watchRouteRecalculated?.getModeValue(app.settings.applicationMode) ?: true
        fun labelScale(app: OsmandApplication): Float = when (plugin()?.watchLabelScale?.getModeValue(app.settings.applicationMode)) {
            125 -> 1.25f
            150 -> 1.5f
            else -> 1f
        }
        fun initialZoomOffset(app: OsmandApplication): Int = when (plugin()?.watchInitialZoom?.getModeValue(app.settings.applicationMode)) {
            -1 -> -1
            -2 -> -2
            else -> 0
        }
        fun rulerEnabled(app: OsmandApplication) = plugin()?.watchRuler?.getModeValue(app.settings.applicationMode) ?: true
        fun clockEnabled(app: OsmandApplication) = plugin()?.watchClock?.getModeValue(app.settings.applicationMode) ?: true
        fun recordingControlsEnabled() = plugin()?.watchRecordingControls?.get() ?: true
        const val LOCATION_SERVICE = "net.osmand.plus.plugins.wear.WearLocationService"
        @Volatile internal var pendingLocation: Pair<String, UUID>? = null
        @Volatile internal var pendingLocationAt = 0L
        internal var locationStarting = false
            set(value) { field = value; locationStartingAt = if (value) android.os.SystemClock.elapsedRealtime() else 0L }
        /** When [locationStarting] was set, for the log: a start that never arrives keeps it set. */
        private var locationStartingAt = 0L
        @Volatile internal var latestLocation: Location? = null
        internal fun location(app: OsmandApplication): Location? = if (allowed(app)) latestLocation?.let { Location(it) } else null
        internal fun startPendingLocation(app: OsmandApplication): Boolean {
            if (pendingLocation == null) return false
            val age = android.os.SystemClock.elapsedRealtime() - pendingLocationAt
            if (age > 30_000) {
                trace("location start skipped: pending watch session is ${age / 1000} s old (limit 30 s)")
                return false
            }
            if (!allowed(app)) {
                trace("location start skipped: plugin disabled")
                return false
            }
            if (locationStarting) {
                val waiting = android.os.SystemClock.elapsedRealtime() - locationStartingAt
                // Logged on each attempt once a start has waited over 10 s: one that never arrives keeps this set.
                if (waiting > 10_000) trace("location start still waiting for the service after ${waiting / 1000} s")
                return true
            }
            val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            val screenOff = !app.getSystemService(android.os.PowerManager::class.java).isInteractive
            val foreground = app.isAppInForegroundOnRootDevice
            // OsmAnd's own navigation service (navigation or trip recording) is a running location
            // foreground service. Android then refuses a second one from the background but allows a
            // plain service, which shares the process's location access.
            val borrow = !foreground && app.navigationService != null
            // The watch must connect as soon as it opens, locked or not, so every other state tries
            // the start too and leaves the decision to Android; a refusal is recorded below.
            if (!OsmAndLocationProvider.isLocationPermissionAvailable(app)) {
                trace("location start refused: location permission missing")
                return false
            }
            // ProcessLifecycleOwner can briefly report foreground after Recents removed the task.
            if (app.getSystemService(ActivityManager::class.java).appTasks.isEmpty()) {
                trace("location start refused: OsmAnd task removed")
                return false
            }
            trace("location service start requested (foreground=$foreground locked=$locked screenOff=$screenOff" +
                (if (borrow) "; plain start using OsmAnd's navigation service: ${WearDeviceEvents.osmandService(app)})" else ")"))
            locationStarting = true
            return try {
                val intent = Intent().setClassName(app, LOCATION_SERVICE)
                if (borrow) {
                    app.startService(intent.setAction(WearLocationService.BORROW))
                } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
                true
            } catch (error: Exception) {
                WearLocationService.recordFailure(app, pendingLocation, error)
                locationStarting = false
                false
            }
        }
        const val ID = "osmand.wear-os"
        const val DISABLED = "net.osmand.wear.DISABLED"
        @JvmStatic fun allowed(app: OsmandApplication): Boolean = ID in app.settings.enabledPlugins
        @JvmStatic fun supported(app: OsmandApplication): Boolean = PhoneTransportFactory.supported(app)
        /** Folder, under the app's private files, that holds the phone's diagnostic log. */
        const val DIAGNOSTICS_DIR = "wear-diagnostics"
        @Volatile private var diagnostics: LinkDiagnostics? = null
        /** Whether diagnostic logging is on; sent to the watch with every session reply. */
        internal fun diagnosticsEnabled(): Boolean = diagnostics?.enabled == true
        /** One diagnostic event (no positions, keys, identities or codes); no-op while logging is off. */
        internal fun trace(event: String) { diagnostics?.log(event) }
        /** Counts a routine event for the per-minute summary line. */
        internal fun traceCount(event: String) { diagnostics?.count(event) }

        /** The once-a-minute state line: link, lease and pending location start. Null while idle. */
        private fun heartbeat(app: OsmandApplication): String? {
            val link = WearBluetoothService.state
            val lease = WearLocationService.describeLease()
            if (link == WearBluetoothService.State.OFF && lease == null && pendingLocation == null) return null
            val pending = pendingLocation?.let { " pendingLocation=${(android.os.SystemClock.elapsedRealtime() - pendingLocationAt) / 1000}s" } ?: ""
            return "link=$link lease=${lease ?: "none"}$pending starting=$locationStarting " +
                "lastRequest=${WearRequestRouter.lastRequestAge()} osmandService=${WearDeviceEvents.osmandService(app)} ${WearDeviceEvents.limits(app)}"
        }
    }
}

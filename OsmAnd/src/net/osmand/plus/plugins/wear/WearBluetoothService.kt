package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-side connected-device foreground service. A visible OsmAnd screen starts it; the watch
 * initiates the authenticated RFCOMM link. The app pairing prompt is shown on the settings screen
 * and in the persistent notification.
 */
class WearBluetoothService : Service() {
    enum class State { OFF, PERMISSION, UNAVAILABLE, LISTENING, CONNECTED, PAIRING }

    private data class PairingRequest(val code: String, val fingerprint: ByteArray, val answer: CompletableFuture<Boolean>)

    private class SocketTransport(private val socket: BluetoothSocket) : WearBluetoothTransport {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream
        override fun close() { runCatching { socket.close() } }
    }

    /** Debug/test-only loopback socket. Production devices never take this path. */
    private class TcpTransport(private val socket: java.net.Socket) : WearBluetoothTransport {
        override val input: InputStream get() = socket.getInputStream()
        override val output: OutputStream get() = socket.getOutputStream()
        override fun close() { runCatching { socket.close() } }
    }

    private val main = Handler(Looper.getMainLooper())
    private val activePeer = AtomicReference<String?>(null)
    private val pairing = AtomicReference<PairingRequest?>(null)
    private val closed = AtomicBoolean()
    private lateinit var identity: WearBluetoothIdentity
    private lateinit var watchPin: WatchPairingPin
    private var router: WearRequestRouter? = null
    private var server: WearBluetoothServer? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var testServer: java.net.ServerSocket? = null
    private var testAlert: Thread? = null
    @Volatile private var testAlertStopped = false
    private var started = false
    /** Whether this service listens on the debug/test loopback transport instead of RFCOMM. */
    private var testMode = false

    override fun onCreate() {
        super.onCreate()
        WearOsPlugin.trace("link service created")
        instance = this
        identity = WearBluetoothIdentity(this)
        watchPin = WatchPairingPin(identity::pinnedWatch, identity::pinWatch)
        createChannel()
        ContextCompat.registerReceiver(this, adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                WearOsPlugin.trace("link service: stop pressed in notification")
                WearLocationService.activeSession()?.let { (peer, session) -> WearLocationService.release(peer, session) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONFIRM_PAIR -> {
                WearOsPlugin.trace("first pairing answered in notification: ${if (intent.getBooleanExtra(EXTRA_ACCEPT, false)) "accept" else "decline"}" +
                    if (pairing.get() == null) " (no pairing waiting)" else "")
                pairing.get()?.answer?.complete(intent.getBooleanExtra(EXTRA_ACCEPT, false))
                return START_NOT_STICKY
            }
        }
        if (started && server?.isAlive() == true) {
            WearOsPlugin.traceCount("link service start: already listening")
            return START_NOT_STICKY
        }
        WearOsPlugin.trace("link service starting${if (started) " (listener had ended)" else ""}")
        if (started) {
            // The accept thread ended without being able to report; rebuild instead of ignoring starts.
            releaseListener()
            started = false
        }
        started = true
        // Debug/test-only adapter: a local file gate (see WearTestTransport) replaces the RFCOMM
        // listener with a loopback ServerSocket and auto-confirms the first-pair prompt. The same
        // authenticated WearBluetoothServer/WearBluetoothLink/WearRequestRouter path runs underneath.
        val testTransport = WearTestTransport.read(this)
        // BLUETOOTH_CONNECT is a connected-device foreground-service prerequisite. Check it before
        // startForeground: a missing permission throws and would crash the map on reopen.
        if (testTransport == null && Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            WearOsPlugin.trace("link service stopped: Bluetooth permission missing")
            started = false
            setState(State.PERMISSION)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat()
        } catch (e: SecurityException) {
            WearOsPlugin.trace("link service foreground start refused: ${LinkDiagnostics.describe(e)}; ${WearDeviceEvents.state(application as OsmandApplication)}")
            started = false
            setState(State.PERMISSION)
            stopSelf()
            return START_NOT_STICKY
        } catch (error: RuntimeException) {
            // Foreground-service start restrictions (for example after a permission change).
            WearOsPlugin.trace("link service foreground start refused: ${LinkDiagnostics.describe(error)}; ${WearDeviceEvents.state(application as OsmandApplication)}")
            started = false
            setState(State.UNAVAILABLE)
            stopSelf()
            return START_NOT_STICKY
        }
        lastError = null
        testMode = testTransport != null
        main.removeCallbacks(relisten)
        if (!listen(testTransport)) waitToListen()
        if (testTransport != null && testAlert == null) startTestAlertTrigger()
        return START_NOT_STICKY
    }

    /**
     * The service keeps running while it cannot listen (for example, phone Bluetooth off), so the
     * link returns without OsmAnd in front: on Bluetooth on, or on a retry every [RELISTEN_MS].
     */
    private val relisten = Runnable {
        if (closed.get() || !started || server?.isAlive() == true) return@Runnable
        val test = if (testMode) WearTestTransport.read(this) else null
        if (testMode && test == null) { waitToListen(); return@Runnable }
        WearOsPlugin.trace("link listener: trying again")
        releaseListener()
        if (!listen(test)) waitToListen()
    }

    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) return
            if (state != State.UNAVAILABLE) return
            WearOsPlugin.trace("link listener: phone Bluetooth on")
            main.removeCallbacks(relisten)
            main.post(relisten)
        }
    }

    private fun waitToListen() {
        if (closed.get()) return
        setState(State.UNAVAILABLE)
        main.removeCallbacks(relisten)
        main.postDelayed(relisten, RELISTEN_MS)
    }

    /** Opens the RFCOMM (or test) listener and its accept loop; false when it cannot listen now. */
    private fun listen(testTransport: WearTestTransport?): Boolean {
        if (testTransport == null) {
            try {
                openServerSocket()
            } catch (error: Exception) {
                WearOsPlugin.trace("link listener not opened (${LinkDiagnostics.describe(error)}); " +
                    "waiting for Bluetooth or ${RELISTEN_MS / 1000} s")
                return false
            }
        } else if (!startTestListener(testTransport)) {
            return false
        }
        router = WearRequestRouter(application as OsmandApplication) { peer -> peer == activePeer.get() }
        val createdRef = AtomicReference<WearBluetoothServer>()
        val newServer = WearBluetoothServer(
            accept = { if (testTransport == null) acceptTransport() else acceptTestTransport() },
            identity = { identity.identity() },
            pinnedWatch = { watchPin.forConnection() },
            allowFirstPair = { watchPin.forConnection() == null },
            confirmFirstPair = { code, fingerprint ->
                if (testTransport == null) confirmPair(code, fingerprint) else {
                    WearTestTransport.log("first-pair auto-confirm")
                    true
                }
            },
            handler = router!!,
            onPeer = { peer ->
                WearOsPlugin.trace(if (peer == null) "watch disconnected" else "watch connected")
                activePeer.set(peer)
                if (testTransport != null) WearTestTransport.log(if (peer == null) "peer disconnected" else "authenticated peer connected")
                setState(if (peer == null) State.LISTENING else State.CONNECTED)
            },
            onWatchIdentity = { public -> watchPin.admit(public) },
            onStopped = {
                main.post {
                    if (server === createdRef.get() && !closed.get()) {
                        // No listener remains: report it and keep the service to listen again.
                        WearOsPlugin.trace("link listener ended; waiting for Bluetooth or ${RELISTEN_MS / 1000} s")
                        releaseListener()
                        waitToListen()
                    }
                }
            },
            trace = WearOsPlugin::trace,
            diagnostics = WearOsPlugin::diagnosticsEnabled,
            traceCount = WearOsPlugin::traceCount
        )
        createdRef.set(newServer)
        server = newServer
        newServer.start()
        WearOsPlugin.trace("link service listening")
        setState(State.LISTENING)
        return true
    }

    /**
     * Debug/test-only alert trigger. The emulator has no configured voice provider, so the real
     * voice-prompt event cannot be produced there. This host-armed marker sends the same
     * session-bound RouteAlertProtocol envelope through the live authenticated link; the watch's
     * session/peer validation and notification card remain the production path.
     */
    private fun startTestAlertTrigger() {
        val marker = java.io.File(filesDir, WearTestTransport.ALERT_FILE)
        testAlert = Thread({
            var last = ""
            var pending = ""
            var attempts = 0
            WearTestTransport.log("test alert watcher ready")
            while (!closed.get() && !testAlertStopped) {
                val token = if (marker.isFile) runCatching { marker.readText().trim() }.getOrDefault("") else ""
                if (token.isNotEmpty() && token != last) {
                    last = token
                    pending = token
                    attempts = 0
                    WearTestTransport.log("test alert token accepted")
                }
                if (pending.isNotEmpty()) {
                    attempts++
                    val session = WearLocationService.activeSession()?.second
                    if (session == null) {
                        if (attempts == 1 || attempts % 40 == 0) WearTestTransport.log("test alert waiting for session")
                    } else {
                        val kind = if (pending.equals("recalc", true)) RouteAlertProtocol.ROUTE_RECALCULATED else RouteAlertProtocol.OFF_ROUTE
                        val sent = sendAlert(RouteAlertProtocol.encode(RouteAlertProtocol.Alert(java.util.UUID.randomUUID(), session, kind)))
                        WearTestTransport.log(if (sent) "test alert sent" else "test alert send failed")
                        pending = ""
                    }
                }
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
        }, "WearTestAlert").also { it.start() }
    }

    private fun startTestListener(test: WearTestTransport): Boolean {
        testServer = try {
            bindTestListener(test)
        } catch (_: Exception) {
            WearTestTransport.log("test listener bind failed")
            return false
        }
        WearTestTransport.log("test listener ready on loopback")
        return true
    }

    private fun bindTestListener(test: WearTestTransport): java.net.ServerSocket =
        java.net.ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), test.port))
        }

    /**
     * A failed accept leaves the socket unusable, so the next attempt binds a new listener.
     * The exception is rethrown to let the server back off; the service is not restarted.
     */
    private fun acceptTestTransport(): WearBluetoothTransport? {
        if (closed.get()) return null
        val listener = testServer ?: run {
            val test = WearTestTransport.read(this) ?: throw IllegalStateException("Test transport disabled")
            bindTestListener(test).also { testServer = it }
        }
        if (closed.get()) {
            runCatching { listener.close() }
            if (testServer === listener) testServer = null
            return null
        }
        val socket = try {
            listener.accept()
        } catch (e: Exception) {
            runCatching { listener.close() }
            if (testServer === listener) testServer = null
            throw e
        }
        if (closed.get()) {
            runCatching { socket.close() }
            return null
        }
        WearTestTransport.log("test connection accepted")
        return TapTransport(TcpTransport(socket), "phone->watch", "watch->phone")
    }

    private fun openServerSocket(): BluetoothServerSocket {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IllegalStateException("Bluetooth adapter unavailable")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth adapter disabled")
        return adapter.listenUsingRfcommWithServiceRecord("OsmAnd Wear", BluetoothWire.SERVICE_UUID)
            .also { serverSocket = it }
    }

    /**
     * Accepts one RFCOMM connection. A failed accept discards that socket and throws, so the
     * retry creates a fresh listener instead of retrying a permanently broken one.
     */
    private fun acceptTransport(): WearBluetoothTransport? {
        if (closed.get()) return null
        val listener = serverSocket ?: openServerSocket()
        if (closed.get()) {
            runCatching { listener.close() }
            if (serverSocket === listener) serverSocket = null
            return null
        }
        val socket = try {
            listener.accept()
        } catch (e: Exception) {
            runCatching { listener.close() }
            if (serverSocket === listener) serverSocket = null
            throw e
        }
        if (closed.get()) {
            runCatching { socket.close() }
            return null
        }
        return SocketTransport(socket)
    }

    /** Ends the accept thread and releases every listener/session object owned by it. */
    private fun releaseListener() {
        pairing.get()?.answer?.complete(false)
        server?.close()
        server = null
        serverSocket?.close()
        serverSocket = null
        testServer?.let { runCatching { it.close() } }
        testServer = null
        router?.close()
        router = null
        activePeer.set(null)
    }

    private fun confirmPair(code: String, fingerprint: ByteArray): Boolean {
        val request = PairingRequest(code, fingerprint.copyOf(), CompletableFuture())
        WearOsPlugin.trace("first pairing requested; waiting for confirmation")
        pairing.set(request)
        setState(State.PAIRING)
        return try {
            request.answer.get(115, TimeUnit.SECONDS).also {
                WearOsPlugin.trace("first pairing ${if (it) "accepted" else "declined"} on the phone")
            }
        } catch (error: Exception) {
            WearOsPlugin.trace("first pairing not confirmed: ${if (error is java.util.concurrent.TimeoutException) "no answer in 115 s" else LinkDiagnostics.describe(error)}")
            false
        } finally {
            pairing.compareAndSet(request, null)
            setState(if (activePeer.get() == null) State.LISTENING else State.CONNECTED)
        }
    }

    private fun setState(value: State) {
        // A link/accept callback may still fire while the service is being destroyed; it must not
        // resurrect a non-OFF state on a stopped service.
        if (closed.get() && value != State.OFF) return
        if (state != value) WearOsPlugin.trace("link service state $state -> $value")
        state = value
        if (value == State.PERMISSION || value == State.UNAVAILABLE) lastError = value
        if (!started) return
        main.post { updateNotification() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.wear_os_companion_name),
                    NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!started) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val code = pairing.get()?.code
        val text = when {
            code != null -> getString(R.string.wear_watch_bluetooth_pairing, code)
            state == State.CONNECTED -> getString(R.string.wear_watch_bluetooth_connected)
            state == State.LISTENING -> getString(R.string.wear_watch_bluetooth_listening)
            state == State.PERMISSION -> getString(R.string.wear_watch_bluetooth_permission)
            state == State.UNAVAILABLE -> getString(R.string.wear_watch_bluetooth_unavailable)
            else -> getString(R.string.wear_watch_bluetooth_off)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MapActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, WearBluetoothService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val confirm = PendingIntent.getService(this, 0, Intent(this, WearBluetoothService::class.java)
            .setAction(ACTION_CONFIRM_PAIR).putExtra(EXTRA_ACCEPT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_action_my_location)
            .setContentTitle(getString(R.string.wear_os_companion_name))
            .setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setLocalOnly(true)
            .apply { if (code != null) addAction(0, getString(R.string.shared_string_ok), confirm) }
            .addAction(0, getString(R.string.shared_string_cancel), stop)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        WearOsPlugin.trace("link service stopping: OsmAnd task removed")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        WearOsPlugin.trace("link service destroyed")
        closed.set(true)
        main.removeCallbacks(relisten)
        runCatching { unregisterReceiver(adapterState) }
        testAlertStopped = true
        testAlert?.interrupt()
        testAlert = null
        releaseListener()
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        instance = null
        state = State.OFF
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Forget only the pinned watch identity. The listener stays available for a new first pairing;
     * the phone identity, system Bluetooth bond and OsmAnd data are untouched.
     */
    internal fun forgetWatch(): Boolean {
        WearOsPlugin.trace("watch forgotten in settings")
        pairing.get()?.answer?.complete(false)
        WearLocationService.activeSession()?.let { (node, session) ->
            WearLocationService.release(node, session)
        }
        identity.clearPairing()
        watchPin.forget()
        server?.activeLink()?.close()
        activePeer.set(null)
        setState(State.LISTENING)
        return true
    }

    companion object {
        private const val CHANNEL = "wear-bluetooth-link"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "net.osmand.plus.wear.BLUETOOTH_STOP"
        private const val ACTION_CONFIRM_PAIR = "net.osmand.plus.wear.BLUETOOTH_CONFIRM"
        private const val EXTRA_ACCEPT = "accept"
        /** Retry interval for a listener that cannot open, besides the Bluetooth-on broadcast. */
        internal const val RELISTEN_MS = 15_000L
        @Volatile internal var state = State.OFF
        @Volatile private var lastError: State? = null
        @Volatile private var instance: WearBluetoothService? = null

        fun start(context: Context): Boolean {
            // Never start the connected-device service without its Bluetooth prerequisite. The
            // debug/test transport is the only exception because it does not touch the radio.
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                WearTestTransport.read(context) == null) {
                WearOsPlugin.trace("link service not started: Bluetooth permission missing")
                return false
            }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, WearBluetoothService::class.java))
                true
            } catch (error: RuntimeException) {
                // For example ForegroundServiceStartNotAllowedException when OsmAnd is in the background.
                WearOsPlugin.trace("link service not started: ${LinkDiagnostics.describe(error)}; " +
                    "${(context.applicationContext as? OsmandApplication)?.let(WearDeviceEvents::state)}")
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WearBluetoothService::class.java))
        }

        /** Clears the pinned watch identity and disconnects the active peer, if any. */
        fun forgetWatch(context: Context): Boolean {
            val current = instance
            if (current != null) return current.forgetWatch()
            WearOsPlugin.trace("watch forgotten in settings (link service not running)")
            return runCatching {
                WearBluetoothIdentity(context).clearPairing()
                true
            }.getOrDefault(false)
        }

        fun isRunning() = state != State.OFF

        /**
         * Whether a watch identity is pinned, including one retained in memory after a failed
         * write. An unreadable pin counts as paired: it is an error, never "no watch".
         */
        internal fun hasPairedWatch(context: Context): Boolean {
            instance?.let { service ->
                return runCatching { service.watchPin.forConnection() != null }.getOrDefault(true)
            }
            return runCatching { WearBluetoothIdentity(context).pinnedWatch() != null }.getOrDefault(true)
        }

        /** Transport-neutral state for [PhoneTransport]; the Bluetooth setup text stays below. */
        internal fun linkState(): LinkState = state.toLinkState()

        /** The admitted peer key, already backend-qualified by the Bluetooth link. */
        internal fun activePeerKey(): String? = instance?.activePeer?.get()

        internal fun activeLink(): WearBluetoothLink? = instance?.server?.activeLink()

        fun pairingCode(): String? = instance?.pairing?.get()?.code

        fun confirmPairing(accept: Boolean) {
            instance?.pairing?.get()?.answer?.complete(accept)
        }

        fun statusText(context: Context): String {
            val code = pairingCode()
            return when {
                code != null -> context.getString(R.string.wear_watch_bluetooth_pairing, code)
                state == State.CONNECTED -> context.getString(R.string.wear_watch_bluetooth_connected)
                state == State.LISTENING -> context.getString(R.string.wear_watch_bluetooth_listening)
                state == State.PAIRING -> context.getString(R.string.wear_watch_bluetooth_listening)
                state == State.PERMISSION -> context.getString(R.string.wear_watch_bluetooth_permission)
                state == State.UNAVAILABLE -> context.getString(R.string.wear_watch_bluetooth_unavailable)
                lastError == State.PERMISSION -> context.getString(R.string.wear_watch_bluetooth_permission)
                lastError == State.UNAVAILABLE -> context.getString(R.string.wear_watch_bluetooth_unavailable)
                else -> context.getString(R.string.wear_watch_bluetooth_off)
            }
        }

        /**
         * Adapter-internal best-effort alert frame (also used by the debug/test alert trigger).
         * Application callers use [PhoneTransport.sendRouteAlert], which checks the peer first.
         */
        fun sendAlert(payload: ByteArray): Boolean =
            instance?.server?.activeLink()?.sendAlert(payload) ?: false
    }
}

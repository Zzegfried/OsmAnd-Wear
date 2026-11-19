package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

/**
 * The current phone adapter: the existing WearBluetoothService/WearBluetoothServer/WearBluetoothLink
 * path behind [PhoneTransport]. Inbound v1 requests still enter through WearRequestHandler, and
 * bonding, pinning and `OWB1` framing stay inside the Bluetooth classes. Pairing state and the
 * first-pair prompt remain Bluetooth setup capability, not port operations. A future Play-only
 * source set supplies a Data Layer adapter for the same port; no runtime branch is added here.
 */
internal class BluetoothPhoneTransport(context: Context) : PhoneTransport {
    private val appContext = context.applicationContext

    override fun start(): Boolean = WearBluetoothService.start(appContext)

    override fun stop() = WearBluetoothService.stop(appContext)

    override fun state(): LinkState = WearBluetoothService.linkState()

    override fun activePeer(): AuthenticatedPeer? =
        WearBluetoothService.activePeerKey()?.let { AuthenticatedPeer(TransportKind.BLUETOOTH, it) }

    override fun hasPairedPeer(): Boolean = WearBluetoothService.hasPairedWatch(appContext)

    override fun checkSession(peer: AuthenticatedPeer, session: UUID): CompletableFuture<Boolean> {
        val answer = CompletableFuture<Boolean>()
        val link = WearBluetoothService.activeLink()
        if (link == null || activePeer()?.key != peer.key) {
            WearOsPlugin.traceCount(if (link == null) "session check skipped: no link" else "session check skipped: different watch connected")
            answer.completeExceptionally(LinkException(LinkFailure.DISCONNECTED))
            return answer
        }
        // The link owns the bounded deadline and correlation cleanup; this adapter only maps the
        // result to the transport-neutral failure vocabulary.
        link.check(MapProtocol.session(session, true), SESSION_CHECK_TIMEOUT_MS).whenComplete { payload, error ->
            if (error == null) {
                answer.complete(payload?.contentEquals(byteArrayOf(1)) == true)
            } else {
                var cause: Throwable? = error
                while (cause is CompletionException && cause.cause != null) cause = cause.cause
                answer.completeExceptionally(LinkException(
                    if (cause is TimeoutException) LinkFailure.TIMEOUT else LinkFailure.DISCONNECTED))
            }
        }
        return answer
    }

    override fun sendRouteAlert(peer: AuthenticatedPeer, payload: ByteArray): Boolean {
        if (activePeer()?.key != peer.key) {
            WearOsPlugin.trace("route alert not sent: watch not connected")
            return false
        }
        // One best-effort frame. A false result is never queued for reconnect.
        return WearBluetoothService.sendAlert(payload)
    }

    override fun setup(): PhoneTransport.Setup = bluetoothSetup

    companion object {
        private const val SESSION_CHECK_TIMEOUT_MS = 10_000L
    }

    private val bluetoothSetup = object : PhoneTransport.Setup {
        override fun permission(): String = Manifest.permission.BLUETOOTH_CONNECT

        override fun statusText(context: Context): String = WearBluetoothService.statusText(context)

        override fun pairingCode(): String? = WearBluetoothService.pairingCode()

        override fun confirmPairing(accept: Boolean) = WearBluetoothService.confirmPairing(accept)

        override fun forgetPairing(): Boolean = WearBluetoothService.forgetWatch(appContext)
    }
}

/** Backend-qualified peer key; another transport's peer can never satisfy a Bluetooth session. */
internal object BluetoothPeerKey {
    const val PREFIX = "bluetooth:"

    fun of(fingerprint: String): String = PREFIX + fingerprint
}

/** Bluetooth service states and unavailable reasons map to transport-neutral UI states. */
internal fun WearBluetoothService.State.toLinkState(): LinkState = when (this) {
    WearBluetoothService.State.OFF -> LinkState.OFF
    WearBluetoothService.State.PERMISSION -> LinkState.PERMISSION_REQUIRED
    WearBluetoothService.State.UNAVAILABLE -> LinkState.UNAVAILABLE
    // The phone listens for a watch-initiated link; there is no admitted peer until CONNECTED.
    WearBluetoothService.State.LISTENING -> LinkState.CONNECTING
    WearBluetoothService.State.PAIRING -> LinkState.SETUP_REQUIRED
    WearBluetoothService.State.CONNECTED -> LinkState.READY
}

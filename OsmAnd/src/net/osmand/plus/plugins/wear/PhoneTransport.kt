package net.osmand.plus.plugins.wear

import android.content.Context
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** The transport selected for a matching phone/watch pair; exactly one per built pair. */
internal enum class TransportKind { BLUETOOTH, DATA_LAYER }

/** Created only after the selected adapter has admitted the peer. [key] is opaque and kind-qualified. */
internal data class AuthenticatedPeer(
    val kind: TransportKind,
    val key: String
)

/** Transport-neutral availability. READY means an admitted peer, not a ready watch session. */
internal enum class LinkState {
    OFF, CONNECTING, READY, SETUP_REQUIRED, PERMISSION_REQUIRED, UNAVAILABLE
}

internal enum class LinkFailure { DISCONNECTED, TIMEOUT, CANCELLED, INVALID_REPLY }

internal class LinkException(val reason: LinkFailure) : Exception(reason.name)

/**
 * Reply vocabulary shared with the watch contract. The phone's inbound router keeps [WearReply]
 * internally; a transport adapter encodes it to v1 bytes without changing them.
 */
internal data class TransportReply(val payload: ByteArray, val image: ByteArray? = null)

/**
 * App-scoped gateway used by WearOsPlugin, WearLocationService and settings. Its selected adapter
 * receives inbound v1 requests through the existing WearRequestHandler.
 */
internal interface PhoneTransport {
    fun start(): Boolean
    fun stop()
    fun state(): LinkState
    fun activePeer(): AuthenticatedPeer?

    /**
     * Whether a watch has completed pairing with this phone, connected or not. Used to avoid
     * phone-side work, such as the screen-off location prestart, when no watch can claim it.
     */
    fun hasPairedPeer(): Boolean = activePeer() != null
    fun checkSession(peer: AuthenticatedPeer, session: UUID): CompletableFuture<Boolean>
    fun sendRouteAlert(peer: AuthenticatedPeer, payload: ByteArray): Boolean

    /** Uses the selected adapter's current admitted peer; no transport type crosses this boundary. */
    fun checkSession(session: UUID): CompletableFuture<Boolean> {
        val peer = activePeer() ?: return CompletableFuture<Boolean>().also {
            it.completeExceptionally(LinkException(LinkFailure.DISCONNECTED))
        }
        return checkSession(peer, session)
    }

    /** Best-effort delivery to the current admitted peer; returns false when there is none. */
    fun sendRouteAlert(payload: ByteArray): Boolean =
        activePeer()?.let { sendRouteAlert(it, payload) } ?: false

    /**
     * Adapter-specific setup capability for the settings page. A Bluetooth build supplies the pairing
     * prompt/permission/status; a future Data Layer build supplies its companion-install guidance and
     * no Bluetooth prompt. Null means the adapter has no setup UI.
     */
    fun setup(): Setup? = null

    interface Setup {
        fun permission(): String?
        fun statusText(context: Context): String
        fun pairingCode(): String?
        fun confirmPairing(accept: Boolean)

        /**
         * Clears only this transport's persisted peer pairing and disconnects the current peer.
         * The system Bluetooth bond and all OsmAnd data must remain untouched. Returns false when
         * this transport has no resettable pairing state.
         */
        fun forgetPairing(): Boolean = false
    }
}

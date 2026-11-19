package net.osmand.plus.plugins.wear

import java.util.concurrent.CompletableFuture

/** A bounded transport-independent answer for one authenticated v1 request. */
internal data class WearReply(val payload: ByteArray, val image: ByteArray? = null)

/** The phone-side handlers behind any authenticated transport; peer IDs are backend-qualified keys. */
internal interface WearRequestHandler {
    /** Returns null only for an unknown v1 path. */
    fun request(peer: String, path: String, data: ByteArray): CompletableFuture<WearReply>?

    fun cancel(peer: String, data: ByteArray)

    fun disconnected(peer: String)
}

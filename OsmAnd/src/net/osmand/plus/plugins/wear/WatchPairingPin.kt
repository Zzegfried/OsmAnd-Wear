package net.osmand.plus.plugins.wear

/**
 * Phone-side pin admission (mirrors the watch's PhonePairingPin). An authenticated watch identity
 * is written durably only when it is not already stored; if the write fails it is retained in
 * memory, so the next connection still authenticates against it and the save is retried. A
 * stored pin that cannot be read is an error, never "no pin": that would reopen first pairing.
 */
internal class WatchPairingPin(
    private val read: () -> ByteArray?,
    private val save: (ByteArray) -> Unit
) {
    private var verified: ByteArray? = null

    @Synchronized fun forConnection(): ByteArray? = (verified ?: read())?.copyOf()

    /** Returns once [identity] is durable; throws (retaining it) if it cannot be saved. */
    @Synchronized fun admit(identity: ByteArray) {
        require(identity.isNotEmpty()) { "Empty watch identity" }
        val retained = verified
        val stored = retained ?: read()
        require(stored == null || stored.contentEquals(identity)) { "Verified watch identity changed" }
        if (retained == null && stored != null) return // Already durable: do not rewrite.
        verified = identity.copyOf()
        save(identity.copyOf())
        verified = null
    }

    /** Drops a retained identity; the caller clears the durable pin. */
    @Synchronized fun forget() { verified = null }
}

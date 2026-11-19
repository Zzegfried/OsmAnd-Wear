package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import java.io.Closeable
import java.security.KeyPair

/** One accepted phone/watch stream. The Android service wraps a BluetoothSocket; tests use pipes. */
internal interface WearBluetoothTransport : BluetoothStreams

/**
 * Single-link accept loop above [WearBluetoothLink]. The Android service owns the RFCOMM server
 * socket; this class owns admission, identity/pin handoff and one active peer.
 */
internal class WearBluetoothServer(
    private val accept: () -> WearBluetoothTransport?,
    private val identity: () -> KeyPair,
    private val pinnedWatch: () -> ByteArray?,
    private val allowFirstPair: () -> Boolean,
    private val confirmFirstPair: (String, ByteArray) -> Boolean,
    private val handler: WearRequestHandler,
    private val onPeer: (String?) -> Unit = {},
    private val onWatchIdentity: (ByteArray) -> Unit = {},
    /** Called on the server thread when the accept loop ends without [close]. */
    private val onStopped: () -> Unit = {},
    /** Diagnostic sink (see LinkDiagnostics); event names and classes only. */
    private val trace: (String) -> Unit = {},
    private val diagnostics: () -> Boolean = { false },
    private val traceCount: (String) -> Unit = {}
) : Closeable {
    @Volatile private var closed = false
    @Volatile private var active: WearBluetoothLink? = null
    private var thread: Thread? = null

    fun start() {
        check(thread == null) { "Server already started" }
        thread = Thread({ run() }, "WearBluetoothServer").also { it.start() }
    }

    internal fun isAlive(): Boolean = thread?.isAlive == true

    private fun run() {
        var failures = 0
        while (!closed) {
            val transport = try {
                accept()
            } catch (error: Exception) {
                if (closed) break
                failures++
                trace("accept failed: ${LinkDiagnostics.describe(error)} (failure $failures of $MAX_ACCEPT_FAILURES)")
                if (failures >= MAX_ACCEPT_FAILURES) break
                if (!pauseBeforeRetry(failures)) break
                continue
            } ?: break
            trace("watch socket accepted")
            if (closed) {
                runCatching { transport.close() }
                break
            }
            // Stream access and identity/pin loading can throw (socket or Keystore failure): own
            // the accepted transport first so it is always closed, and count the failure like an
            // accept failure so a persistent one ends in onStopped instead of a dead thread.
            val link = try {
                WearBluetoothLink(transport.input, transport.output, identity(), pinnedWatch(),
                    allowFirstPair(), confirmFirstPair, handler,
                    { peer, watchPublic ->
                        // Durable (or throws, closing the link) before the peer is announced.
                        onWatchIdentity(watchPublic)
                        onPeer(peer)
                    },
                    { onPeer(null) }, trace, diagnostics, traceCount)
            } catch (error: Exception) {
                trace("link setup failed (identity or pin storage, or socket streams): ${LinkDiagnostics.describe(error)}")
                runCatching { transport.close() }
                if (closed) break
                failures++
                if (failures >= MAX_ACCEPT_FAILURES || !pauseBeforeRetry(failures)) break
                continue
            }
            failures = 0
            active = link
            try {
                if (closed) link.close() else link.run()
            } finally {
                active = null
                runCatching { transport.close() }
            }
        }
        // A listener that ends without close() must not leave the service claiming to listen.
        if (!closed) {
            trace("listener stopped after $failures failures")
            runCatching { onStopped() }
        }
    }

    /** Interruptible capped backoff; false means the server is closing or was interrupted. */
    private fun pauseBeforeRetry(failures: Int): Boolean {
        val delay = ACCEPT_RETRY_BASE_MS shl (failures - 1).coerceIn(0, 4)
        return try {
            Thread.sleep(delay.coerceAtMost(ACCEPT_RETRY_MAX_MS))
            !closed
        } catch (_: InterruptedException) {
            false
        }
    }

    internal fun activeLink(): WearBluetoothLink? = active

    override fun close() {
        if (closed) return
        closed = true
        active?.close()
        thread?.interrupt()
    }

    private companion object {
        /** Roughly sixteen seconds of capped backoff before an unusable listener gives up. */
        const val MAX_ACCEPT_FAILURES = 8
        const val ACCEPT_RETRY_BASE_MS = 250L
        const val ACCEPT_RETRY_MAX_MS = 4_000L
    }
}

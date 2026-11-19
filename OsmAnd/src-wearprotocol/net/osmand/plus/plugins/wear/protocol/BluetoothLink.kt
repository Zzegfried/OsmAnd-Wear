package net.osmand.plus.plugins.wear.protocol

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The stream pair one authenticated link runs over: an RFCOMM socket or a test pipe. */
internal interface BluetoothStreams : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * One authenticated v1 link, shared by the phone and the watch (edited only in the phone repository;
 * the watch imports it unchanged). It owns the handshake call, strictly sequenced framing in both
 * directions, an ordered control queue written ahead of at most one lazily chunked image transfer,
 * key erasure and teardown. Role behaviour (which handshake, what frames mean) is supplied by the
 * caller through [handshake] and [Events].
 *
 * Teardown ownership: any thread may [close] the link, and [close] returns once local teardown has
 * finished; but only the thread running [run] reports the loss ([Events.onClosed]), after teardown
 * and before [run] returns. An owner that admits a replacement only after [run] returns can never
 * receive a stale notification from the old link.
 */
internal class BluetoothLink(
    private val streams: BluetoothStreams,
    private val handshake: (InputStream, OutputStream, () -> Unit) -> BluetoothPairing.Session,
    private val events: Events,
    queueCapacity: Int,
    /** True: a full control queue means a stalled peer and closes the link; false: [send] fails. */
    private val closeOnOverflow: Boolean,
    private val name: String,
    /** Optional diagnostic sink: link start, handshake result and why the link ended. */
    private val trace: (String) -> Unit = {}
) : Closeable {
    interface Events {
        /** Link thread, before any frame is read. Throwing refuses the link (it closes). */
        fun onConnected(session: BluetoothPairing.Session) {}

        /** Link thread, in wire order. Throwing treats the frame as a protocol violation and closes. */
        fun onFrame(frame: BluetoothWire.Frame)

        /** Link thread: the handshake ended for a known reason. Not called once [close] has run. */
        fun onHandshakeFailed(failure: BluetoothPairing.HandshakeFailure) {}

        /** The closing thread, inside teardown, after I/O has stopped: fail local pending work. */
        fun onTeardown() {}

        /** Link thread, exactly once, after teardown and before [run] returns. */
        fun onClosed() {}
    }

    private data class Outbound(val kind: Int, val correlation: UUID, val payload: ByteArray)

    /**
     * One map image in flight: its header, then chunks the writer builds one at a time, so an image
     * never occupies the control queue and stops at the next frame once cancelled or superseded.
     */
    private class Transfer(val correlation: UUID, private val header: ByteArray, private val image: ByteArray) {
        @Volatile var cancelled = false
        private var headerSent = false
        private var offset = 0

        /** The next frame, or null once finished or cancelled. Called only by the writer thread. */
        fun next(): Outbound? {
            if (cancelled) return null
            if (!headerSent) {
                headerSent = true
                return Outbound(BluetoothWire.MAP, correlation, header)
            }
            if (offset >= image.size) return null
            val count = minOf(BluetoothWire.MAX_CHUNK_BYTES, image.size - offset)
            val payload = BluetoothWire.imageChunk(image.size, offset, image.copyOfRange(offset, offset + count))
            offset += count
            return Outbound(BluetoothWire.IMAGE_CHUNK, correlation, payload)
        }
    }

    /** Control frames and replies, written in order and ahead of any image chunk. */
    private val outgoing = LinkedBlockingQueue<Outbound>(queueCapacity)
    /** The receiver assembles one map image at a time, so a newer image replaces an older transfer. */
    private val transfer = AtomicReference<Transfer?>(null)
    @Volatile private var closed = false
    @Volatile private var session: BluetoothPairing.Session? = null
    @Volatile private var writer: Thread? = null
    private val closing = AtomicBoolean()
    private val tornDown = CountDownLatch(1)
    @Volatile private var closer: Thread? = null
    /** The first cause of teardown, for [trace]; see [LinkDiagnostics.describe]. */
    @Volatile private var endReason: String? = null
    /** Link statistics for the closing trace line. */
    private val startedAt = System.currentTimeMillis()
    @Volatile private var authenticatedAt = 0L
    @Volatile private var lastReceivedAt = 0L
    @Volatile private var framesIn = 0L
    @Volatile private var bytesIn = 0L
    @Volatile private var framesOut = 0L
    @Volatile private var bytesOut = 0L
    @Volatile private var queuedAtClose = 0

    val isClosed: Boolean get() = closed

    /** Runs on one dedicated connection thread; blocks until the link closes. */
    fun run() {
        try {
            val input = streams.input
            val output = streams.output
            val authenticated = handshake(input, output) { close() }
            if (closed) {
                authenticated.eraseKeys()
                return
            }
            session = authenticated
            authenticatedAt = System.currentTimeMillis()
            trace("$name authenticated (handshake ${authenticatedAt - startedAt} ms)")
            writer = Thread({ writeLoop(output, authenticated) }, "$name-writer").also { it.start() }
            events.onConnected(authenticated)
            var expectedSequence = 0L
            while (!closed) {
                val frame = BluetoothWire.readFrame(input, authenticated.receiveKey,
                    authenticated.receiveSalt, authenticated.linkId, expectedSequence)
                expectedSequence++
                lastReceivedAt = System.currentTimeMillis()
                framesIn++
                bytesIn += frame.payload.size
                events.onFrame(frame)
            }
        } catch (failure: BluetoothPairing.HandshakeException) {
            noteEnd("handshake failed: ${failure.failure} after ${System.currentTimeMillis() - startedAt} ms")
            if (!closed) runCatching { events.onHandshakeFailed(failure.failure) }
        } catch (error: Exception) {
            // A failed handshake, malformed frame or lost link all require a fresh authenticated link.
            noteEnd(if (authenticatedAt == 0L) "handshake ended after ${System.currentTimeMillis() - startedAt} ms: ${LinkDiagnostics.describe(error)}"
                else "read ended: ${LinkDiagnostics.describe(error)}")
        } finally {
            close()
            // The writer may still be inside one frame write; let it unwind before reporting.
            runCatching { writer?.join(WRITER_JOIN_MS) }
            runCatching { trace("$name closed: ${endReason ?: "unknown"}; ${summary()}") }
            runCatching { events.onClosed() }
        }
    }

    /**
     * Queues one control frame or reply. False when not yet authenticated, closed, oversized, an
     * image chunk (those come only from [sendImage]) or, without [closeOnOverflow], a full queue.
     */
    fun send(kind: Int, correlation: UUID, payload: ByteArray): Boolean {
        if (closed || session == null || kind == BluetoothWire.IMAGE_CHUNK) return false
        if (payload.size > BluetoothWire.maxPayload(kind)) return false
        if (outgoing.offer(Outbound(kind, correlation, payload.copyOf()))) return true
        if (closeOnOverflow) {
            noteEnd("control queue full")
            close()
        }
        return false
    }

    /** Sends [header] then [image] in chunks, superseding any earlier transfer. */
    fun sendImage(correlation: UUID, header: ByteArray, image: ByteArray): Boolean {
        if (closed || session == null) return false
        if (header.size > BluetoothWire.maxPayload(BluetoothWire.MAP)) return false
        if (image.isEmpty() || image.size > BluetoothWire.MAX_IMAGE_BYTES) return false
        transfer.getAndSet(Transfer(correlation, header.copyOf(), image))?.cancelled = true
        outgoing.offer(WAKE) // A full queue already keeps the writer busy; it polls the transfer next.
        return true
    }

    /** Stops the transfer for [correlation] at its next frame; the frame being written completes. */
    fun cancelImage(correlation: UUID) {
        transfer.get()?.takeIf { it.correlation == correlation }?.cancelled = true
    }

    /** The current transfer's next frame; a finished or cancelled transfer is released. */
    private fun nextImageFrame(): Outbound? {
        val current = transfer.get() ?: return null
        return current.next() ?: run {
            transfer.compareAndSet(current, null)
            null
        }
    }

    private fun writeLoop(output: OutputStream, authenticated: BluetoothPairing.Session) {
        var sequence = 0L
        try {
            while (!closed) {
                // Control frames first; an image chunk only when none waits; else sleep until either.
                val item = outgoing.poll() ?: nextImageFrame() ?: outgoing.take()
                if (item === WAKE) continue
                BluetoothWire.writeFrame(output, BluetoothWire.Frame(item.kind, authenticated.linkId,
                    item.correlation, sequence, item.payload), authenticated.sendKey, authenticated.sendSalt)
                sequence++
                framesOut++
                bytesOut += item.payload.size
            }
        } catch (error: Exception) {
            noteEnd("write ended: ${LinkDiagnostics.describe(error)}")
            close()
        }
    }

    private fun noteEnd(reason: String) {
        if (endReason == null) endReason = reason
    }

    /** How long the link was up and what it carried; tells a link that went silent from one that failed at once. */
    private fun summary(): String {
        val now = System.currentTimeMillis()
        if (authenticatedAt == 0L) return "not authenticated, ${now - startedAt} ms after start"
        val quiet = if (lastReceivedAt == 0L) "nothing received" else "last frame received ${(now - lastReceivedAt) / 1000} s before"
        return "up ${(now - authenticatedAt) / 1000} s; in $framesIn frames ${bytesIn / 1024} KiB; " +
            "out $framesOut frames ${bytesOut / 1024} KiB; $quiet; $queuedAtClose frames unsent"
    }

    /**
     * Local teardown only: stops I/O, drops queued work, erases keys and runs [Events.onTeardown].
     * Returns once teardown has finished, whichever thread performs it (bounded; never self-waiting).
     */
    override fun close() {
        if (!closing.compareAndSet(false, true)) {
            if (closer !== Thread.currentThread()) {
                try { tornDown.await(TEARDOWN_WAIT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            return
        }
        closer = Thread.currentThread()
        noteEnd("closed locally")
        closed = true
        try {
            queuedAtClose = outgoing.size
            outgoing.clear()
            transfer.getAndSet(null)?.cancelled = true
            runCatching { streams.close() }
            writer?.interrupt()
            session?.eraseKeys(); session = null
            // Last: callers' continuations run on this thread and may call back into the link.
            runCatching { events.onTeardown() }
        } finally {
            tornDown.countDown()
        }
    }

    private companion object {
        const val WRITER_JOIN_MS = 2_000L
        const val TEARDOWN_WAIT_MS = 5_000L
        /** Wakes a writer waiting on the control queue when a transfer starts; never written. */
        val WAKE = Outbound(0, UUID(0L, 0L), ByteArray(0))
    }
}

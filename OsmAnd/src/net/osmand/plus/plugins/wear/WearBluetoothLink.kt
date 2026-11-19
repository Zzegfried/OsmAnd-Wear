package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The phone's role on one authenticated link ([BluetoothLink] owns framing, ordering, image
 * transfer and teardown). This class owns request admission and routing, replies, session checks
 * and cancellation. The socket and identity/pin policy are owned by the server.
 *
 * Only the thread running [run] reports the loss ([WearRequestHandler.disconnected], then
 * onDisconnected), after teardown and before [run] returns; the server admits a replacement only
 * after [run] returns, so a closing link can never notify after its successor has connected.
 */
internal class WearBluetoothLink(
    input: InputStream,
    output: OutputStream,
    private val identity: KeyPair,
    private val pinnedWatch: ByteArray?,
    private val allowFirstPair: Boolean,
    private val confirmFirstPair: (String, ByteArray) -> Boolean,
    private val handler: WearRequestHandler,
    private val onConnected: (String, ByteArray) -> Unit = { _, _ -> },
    private val onDisconnected: () -> Unit = {},
    /** Diagnostic sink (see LinkDiagnostics); event names and classes only. */
    private val trace: (String) -> Unit = {},
    /** The diagnostics flag carried by session replies that this link answers itself. */
    private val diagnostics: () -> Boolean = { false },
    /** Routine-event counter for the per-minute diagnostic summary. */
    private val traceCount: (String) -> Unit = {}
) : Closeable {
    /** Recently cancelled map requests: a render finishing after its cancel is not sent. */
    private val cancelledMaps = ConcurrentHashMap.newKeySet<UUID>()
    private val checks = ConcurrentHashMap<UUID, CompletableFuture<ByteArray>>()
    @Volatile private var peer: String? = null

    private val link = BluetoothLink(
        streams = object : BluetoothStreams {
            override val input = input
            override val output = output
            override fun close() {
                try { input.close() } catch (_: Exception) { }
                try { output.close() } catch (_: Exception) { }
            }
        },
        handshake = { streamIn, streamOut, abort ->
            BluetoothPairing.respond(streamIn, streamOut, identity, pinnedWatch, allowFirstPair, confirmFirstPair, abort)
        },
        events = PhoneEvents(),
        queueCapacity = 128,
        closeOnOverflow = true,
        name = "WearBluetooth",
        trace = trace
    )

    private inner class PhoneEvents : BluetoothLink.Events {
        override fun onConnected(session: BluetoothPairing.Session) {
            val key = BluetoothPeerKey.of(session.peerFingerprint.joinToString("") { "%02x".format(it) })
            peer = key
            trace("watch authenticated (${if (session.firstPair) "first pairing" else "pinned"})")
            onConnected(key, session.peerIdentityPublic.copyOf())
        }

        override fun onFrame(frame: BluetoothWire.Frame) = accept(frame)

        override fun onTeardown() {
            val pending = checks.values.toList()
            checks.clear()
            pending.forEach { it.completeExceptionally(IllegalStateException("Bluetooth link closed")) }
        }

        override fun onClosed() {
            peer?.let { runCatching { handler.disconnected(it) } }
            runCatching { onDisconnected() }
        }
    }

    /** Runs on one dedicated connection thread; blocks until the link closes. */
    fun run() = link.run()

    /** Phone-initiated control or alert frame. Image data is sent only through request replies. */
    fun send(kind: Int, correlation: UUID, payload: ByteArray): Boolean = link.send(kind, correlation, payload)

    /** Asks the watch whether it still owns [payload]'s session; the answer is 0 or 1. */
    fun check(payload: ByteArray): CompletableFuture<ByteArray> =
        check(payload, DEFAULT_CHECK_TIMEOUT_MS)

    /**
     * Bounded session check. The link owns the deadline and removes exactly this correlation on
     * timeout, response, cancellation or send failure. The pending map is capped so a silent peer
     * cannot accumulate checks across sessions.
     */
    fun check(payload: ByteArray, timeoutMs: Long): CompletableFuture<ByteArray> {
        val correlation = UUID.randomUUID()
        val answer = CompletableFuture<ByteArray>()
        if (link.isClosed || checks.size >= MAX_PENDING_CHECKS) {
            trace("session check not sent: ${if (link.isClosed) "link closed" else "$MAX_PENDING_CHECKS checks already pending"}")
            answer.completeExceptionally(TimeoutException(if (link.isClosed) {
                "Bluetooth link closed"
            } else {
                "Too many pending session checks"
            }))
            return answer
        }
        checks[correlation] = answer
        val timeout = checkTimeouts.schedule({
            if (checks.remove(correlation, answer)) {
                trace("session check timed out after $timeoutMs ms")
                answer.completeExceptionally(TimeoutException("Session check timed out"))
            }
        }, timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        answer.whenComplete { _, _ ->
            checks.remove(correlation, answer)
            timeout.cancel(false)
        }
        if (!send(BluetoothWire.SESSION_CHECK, correlation, payload)) {
            trace("session check not sent: link unavailable")
            checks.remove(correlation, answer)
            timeout.cancel(false)
            answer.completeExceptionally(IllegalStateException("Bluetooth link unavailable"))
        }
        return answer
    }

    /** Test/diagnostic visibility for the bounded pending-check set. */
    internal fun pendingCheckCount(): Int = checks.size

    /** One phone-initiated route alert; the watch's link manager handles it as an incoming alert. */
    fun sendAlert(payload: ByteArray): Boolean = send(BluetoothWire.ALERT, UUID.randomUUID(), payload)

    private fun accept(frame: BluetoothWire.Frame) {
        val activePeer = peer ?: return
        when (frame.kind) {
            BluetoothWire.SESSION_CHECK -> checks.remove(frame.correlationId)?.let { answer ->
                val reply = frame.payload
                if (reply.size == 1 && reply[0] in 0..1) answer.complete(reply.copyOf())
                else {
                    trace("session check reply malformed (${reply.size} bytes)")
                    answer.completeExceptionally(IllegalStateException("Malformed session check reply"))
                }
            } ?: trace("session check reply for no pending check (late or after timeout)")
            BluetoothWire.CANCEL -> {
                // The cancel names its request twice; a mismatch cancels nothing.
                if (MapProtocol.cancelId(frame.payload) != frame.correlationId) {
                    trace("cancel ignored: names a different request")
                    return
                }
                traceCount("cancel received")
                // Stop the image already rendered for this request, not only the render job.
                if (cancelledMaps.size >= MAX_CANCELLED_MAPS) cancelledMaps.clear()
                cancelledMaps.add(frame.correlationId)
                link.cancelImage(frame.correlationId)
                handler.cancel(activePeer, frame.payload)
            }
            BluetoothWire.SESSION, BluetoothWire.MAP, BluetoothWire.WIDGET,
            BluetoothWire.RECORDING, BluetoothWire.PROFILE -> {
                val path = when (frame.kind) {
                    BluetoothWire.SESSION -> MapProtocol.SESSION_PATH
                    BluetoothWire.MAP -> MapProtocol.REQUEST_PATH
                    BluetoothWire.WIDGET -> WidgetProtocol.SNAPSHOT_PATH
                    BluetoothWire.RECORDING -> WidgetProtocol.ACTION_PATH
                    else -> WidgetProtocol.PROFILE_PATH
                }
                if (!correlates(frame)) {
                    trace("rejected malformed request kind=${frame.kind}")
                    // Answer on the frame's own correlation so the watch fails just this request.
                    send(frame.kind, frame.correlationId, rejection(frame))
                    return
                }
                val future = handler.request(activePeer, path, frame.payload) ?: run {
                    trace("request kind=${frame.kind} has no handler")
                    return
                }
                future.whenComplete { reply, error ->
                    when {
                        error != null -> trace("request kind=${frame.kind} failed: ${LinkDiagnostics.describe(error)}")
                        reply == null -> trace("request kind=${frame.kind} produced no reply")
                        link.isClosed -> traceCount("reply dropped: link closed")
                        else -> enqueueReply(frame, reply)
                    }
                }
            }
            else -> Unit
        }
    }

    /**
     * Admission check: a request whose payload carries its own request ID must name the frame's
     * correlation, so a reply or cancel can never be attributed to another request. Session
     * payloads carry a session ID instead, so only their shape is checked.
     */
    private fun correlates(frame: BluetoothWire.Frame): Boolean = when (frame.kind) {
        BluetoothWire.MAP -> MapProtocol.request(frame.payload)?.id == frame.correlationId
        BluetoothWire.WIDGET, BluetoothWire.PROFILE -> WidgetProtocol.request(frame.payload)?.id == frame.correlationId
        BluetoothWire.RECORDING -> WidgetProtocol.action(frame.payload)?.id == frame.correlationId
        BluetoothWire.SESSION -> MapProtocol.session(frame.payload) != null
        else -> false
    }

    /** A decodable refusal for a request rejected at admission, in that request's own reply form. */
    private fun rejection(frame: BluetoothWire.Frame): ByteArray {
        val id = frame.correlationId
        return when (frame.kind) {
            BluetoothWire.MAP -> MapProtocol.encode(MapReply(id, MapProtocol.UNAVAILABLE))
            BluetoothWire.WIDGET -> WidgetProtocol.encode(WidgetProtocol.Snapshot(id, WidgetProtocol.UNAVAILABLE))
            BluetoothWire.PROFILE -> WidgetProtocol.encode(WidgetProtocol.ProfileReply(id, WidgetProtocol.UNAVAILABLE))
            BluetoothWire.RECORDING -> WidgetProtocol.encode(WidgetProtocol.Result(id, WidgetProtocol.UNAVAILABLE))
            else -> MapProtocol.sessionReply(MapProtocol.SESSION_DENIED, diagnostics())
        }
    }

    private fun enqueueReply(frame: BluetoothWire.Frame, reply: WearReply) {
        if (frame.kind == BluetoothWire.MAP && frame.correlationId in cancelledMaps) {
            traceCount("map reply dropped: cancelled")
            return
        }
        val image = reply.image
        val fits = reply.payload.size <= BluetoothWire.maxPayload(frame.kind)
        if (image == null && fits) {
            // A reply that cannot be queued leaves the watch waiting out its timeout.
            if (!send(frame.kind, frame.correlationId, reply.payload)) trace("reply kind=${frame.kind} not sent: " +
                if (link.isClosed) "link closed" else "send queue full")
            return
        }
        if (frame.kind != BluetoothWire.MAP) {
            trace("reply kind=${frame.kind} dropped: ${reply.payload.size} bytes exceeds the frame limit")
            return
        }
        // Never let an unsendable reply vanish (the watch would wait out its timeout): a map gets
        // an explicit status instead.
        val sent = frame.kind == BluetoothWire.MAP && image != null && fits &&
            link.sendImage(frame.correlationId, reply.payload, image)
        if (!sent && frame.kind == BluetoothWire.MAP) {
            trace("map reply not sendable; sent UNAVAILABLE")
            send(BluetoothWire.MAP, frame.correlationId,
                MapProtocol.encode(MapReply(frame.correlationId, MapProtocol.UNAVAILABLE)))
        }
    }

    /**
     * Local teardown only (I/O, keys, pending checks); returns once teardown has finished,
     * whichever thread performs it. The loss is reported by the thread running [run].
     */
    override fun close() = link.close()

    companion object {
        private const val DEFAULT_CHECK_TIMEOUT_MS = 10_000L
        private const val MAX_PENDING_CHECKS = 64
        private const val MAX_CANCELLED_MAPS = 32
        private val checkTimeouts = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "WearBluetoothCheckTimeout").apply { isDaemon = true }
        }
    }
}

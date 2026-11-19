package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WearBluetoothLinkTest {
    private fun identity(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private class FakeHandler : WearRequestHandler {
        val widgetPayload = byteArrayOf(1, 2, 3)
        val image = ByteArray(70_000) { (it * 7).toByte() }
        var mapHeader: ByteArray = ByteArray(0)
        val cancelled = CountDownLatch(1)
        val disconnected = CountDownLatch(1)

        override fun request(peer: String, path: String, data: ByteArray): CompletableFuture<WearReply>? =
            when (path) {
                WidgetProtocol.SNAPSHOT_PATH -> CompletableFuture.completedFuture(WearReply(widgetPayload))
                MapProtocol.REQUEST_PATH -> CompletableFuture.completedFuture(WearReply(mapHeader, image))
                else -> null
            }

        override fun cancel(peer: String, data: ByteArray) {
            cancelled.countDown()
        }

        override fun disconnected(peer: String) {
            disconnected.countDown()
        }
    }

    @Test(timeout = 30_000)
    fun linkPairsHandlesWidgetMapImageAndCancel() {
        val phoneIdentity = identity()
        val watchIdentity = identity()
        val watchToPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(watchToPhone, 1 shl 20)
        val phoneToWatch = PipedOutputStream()
        val watchIn = PipedInputStream(phoneToWatch, 1 shl 20)
        val handler = FakeHandler()
        val now = 100_000L
        val view = MapView(47.142, 9.5205, 15, 400, 400)
        val base = BaseMap("a".repeat(64), view.copy(width = 512, height = 512))
        handler.mapHeader = MapProtocol.encode(MapReply(UUID.randomUUID(), MapProtocol.OK, view, now,
            true, 1, handler.image.size, 1, view.latitude, view.longitude, now - 1_000, true, base))
        val connected = AtomicReference<String>()
        val link = WearBluetoothLink(phoneIn, phoneToWatch, phoneIdentity, null, true,
            { _, _ -> true }, handler, { peer, _ -> connected.set(peer) }, {})
        val linkThread = Thread({ link.run() }, "wear-bt-link-test")
        try {
            linkThread.start()
            val session = BluetoothPairing.initiate(watchIn, watchToPhone, watchIdentity, null,
                { _, _ -> true }, {})
            val expectedPeer = BluetoothPeerKey.of(BluetoothPairing.fingerprint(watchIdentity.public.encoded)
                .joinToString("") { "%02x".format(it) })
            val until = System.currentTimeMillis() + 5_000
            while (connected.get() == null && System.currentTimeMillis() < until) Thread.sleep(10)
            assertEquals(expectedPeer, connected.get())

            val widgetId = UUID.randomUUID()
            BluetoothWire.writeFrame(watchToPhone, BluetoothWire.Frame(BluetoothWire.WIDGET,
                session.linkId, widgetId, 0, WidgetProtocol.encode(WidgetProtocol.Request(widgetId, UUID.randomUUID()))),
                session.sendKey, session.sendSalt)
            val widgetReply = BluetoothWire.readFrame(watchIn, session.receiveKey, session.receiveSalt,
                session.linkId, 0)
            assertEquals(BluetoothWire.WIDGET, widgetReply.kind)
            assertEquals(widgetId, widgetReply.correlationId)
            assertArrayEquals(handler.widgetPayload, widgetReply.payload)

            val mapId = UUID.randomUUID()
            BluetoothWire.writeFrame(watchToPhone, BluetoothWire.Frame(BluetoothWire.MAP, session.linkId,
                mapId, 1, MapProtocol.encode(MapRequest(mapId, null, true))), session.sendKey, session.sendSalt)
            val header = BluetoothWire.readFrame(watchIn, session.receiveKey, session.receiveSalt,
                session.linkId, 1)
            assertEquals(BluetoothWire.MAP, header.kind)
            assertEquals(mapId, header.correlationId)
            val reply = checkNotNull(MapProtocol.reply(header.payload))
            assertEquals(handler.image.size, reply.bytes)
            val assembler = BluetoothWire.ImageAssembler(reply.bytes)
            var sequence = 2L
            while (assembler.result() == null) {
                val chunk = BluetoothWire.readFrame(watchIn, session.receiveKey, session.receiveSalt,
                    session.linkId, sequence++)
                assertEquals(BluetoothWire.IMAGE_CHUNK, chunk.kind)
                assertEquals(mapId, chunk.correlationId)
                assembler.append(BluetoothWire.parseImageChunk(chunk.payload))
            }
            assertArrayEquals(handler.image, assembler.result())

            val cancelId = UUID.randomUUID()
            BluetoothWire.writeFrame(watchToPhone, BluetoothWire.Frame(BluetoothWire.CANCEL, session.linkId,
                cancelId, 2, MapProtocol.cancel(cancelId)), session.sendKey, session.sendSalt)
            assertTrue(handler.cancelled.await(3, TimeUnit.SECONDS))

            val checkPayload = MapProtocol.session(UUID.randomUUID(), true)
            val checkFuture = link.check(checkPayload)
            val checkFrame = BluetoothWire.readFrame(watchIn, session.receiveKey, session.receiveSalt,
                session.linkId, sequence++)
            assertEquals(BluetoothWire.SESSION_CHECK, checkFrame.kind)
            assertArrayEquals(checkPayload, checkFrame.payload)
            BluetoothWire.writeFrame(watchToPhone, BluetoothWire.Frame(BluetoothWire.SESSION_CHECK,
                session.linkId, checkFrame.correlationId, 3, byteArrayOf(1)), session.sendKey, session.sendSalt)
            assertArrayEquals(byteArrayOf(1), checkFuture.get(3, TimeUnit.SECONDS))

            val alertPayload = byteArrayOf(21, 22)
            assertTrue(link.sendAlert(alertPayload))
            val alertFrame = BluetoothWire.readFrame(watchIn, session.receiveKey, session.receiveSalt,
                session.linkId, sequence++)
            assertEquals(BluetoothWire.ALERT, alertFrame.kind)
            assertArrayEquals(alertPayload, alertFrame.payload)

            link.close()
            // Closing a real RFCOMM socket wakes the blocked reader, which then reports the loss;
            // a pipe needs its writer closed.
            watchToPhone.close()
            assertTrue(handler.disconnected.await(3, TimeUnit.SECONDS))
            linkThread.join(3_000)
        } finally {
            link.close()
            runCatching { watchToPhone.close() }
            runCatching { phoneToWatch.close() }
        }
    }

    /**
     * An alert accepted before peer admission must not be replayed later: after pairing, the first
     * phone-initiated frame is the widget reply, never a queued alert.
     */
    @Test(timeout = 30_000)
    fun alertBeforeAdmissionIsNotReplayedOnConnect() {
        val phoneIdentity = identity()
        val watchIdentity = identity()
        val watchToPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(watchToPhone, 1 shl 20)
        val phoneToWatch = PipedOutputStream()
        val watchIn = PipedInputStream(phoneToWatch, 1 shl 20)
        val handler = FakeHandler()
        val connected = AtomicReference<String>()
        val link = WearBluetoothLink(phoneIn, phoneToWatch, phoneIdentity, null, true,
            { _, _ -> true }, handler, { peer, _ -> connected.set(peer) }, {})
        val linkThread = Thread({ link.run() }, "wear-bt-link-replay-test")
        try {
            assertFalse("An alert must not be accepted without an admitted peer",
                link.sendAlert(byteArrayOf(31, 32)))
            linkThread.start()
            val session = BluetoothPairing.initiate(watchIn, watchToPhone, watchIdentity, null,
                { _, _ -> true }, {})
            val until = System.currentTimeMillis() + 5_000
            while (connected.get() == null && System.currentTimeMillis() < until) Thread.sleep(10)
            assertNotNull(connected.get())

            val widgetId = UUID.randomUUID()
            BluetoothWire.writeFrame(watchToPhone, BluetoothWire.Frame(BluetoothWire.WIDGET,
                session.linkId, widgetId, 0,
                WidgetProtocol.encode(WidgetProtocol.Request(widgetId, UUID.randomUUID()))),
                session.sendKey, session.sendSalt)
            val reply = BluetoothWire.readFrame(watchIn, session.receiveKey, session.receiveSalt,
                session.linkId, 0)
            assertEquals("A pre-admission alert must not be queued ahead of the first reply",
                BluetoothWire.WIDGET, reply.kind)
            assertArrayEquals(handler.widgetPayload, reply.payload)
        } finally {
            link.close()
            linkThread.join(3_000)
            runCatching { watchToPhone.close() }
            runCatching { phoneToWatch.close() }
        }
    }

    @Test(timeout = 30_000)
    fun timedOutChecksAreRemovedAndPendingSetIsBounded() {
        val phoneIdentity = identity()
        val watchIdentity = identity()
        val watchToPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(watchToPhone, 1 shl 20)
        val phoneToWatch = PipedOutputStream()
        val watchIn = PipedInputStream(phoneToWatch, 1 shl 20)
        val connected = AtomicReference<String>()
        val link = WearBluetoothLink(phoneIn, phoneToWatch, phoneIdentity, null, true,
            { _, _ -> true }, FakeHandler(), { peer, _ -> connected.set(peer) }, {})
        val linkThread = Thread({ link.run() }, "wear-bt-link-timeout-test")
        try {
            linkThread.start()
            BluetoothPairing.initiate(watchIn, watchToPhone, watchIdentity, null, { _, _ -> true }, {})
            val until = System.currentTimeMillis() + 5_000
            while (connected.get() == null && System.currentTimeMillis() < until) Thread.sleep(10)
            assertNotNull(connected.get())

            val payload = MapProtocol.session(UUID.randomUUID(), true)
            repeat(100) { link.check(payload, 25) }
            Thread.sleep(500)
            assertEquals("Timed-out checks must not remain in the pending map",
                0, link.pendingCheckCount())

            repeat(100) { link.check(payload, 5_000) }
            assertTrue("Pending checks must be capped", link.pendingCheckCount() <= 64)
        } finally {
            link.close()
            linkThread.join(3_000)
            runCatching { watchToPhone.close() }
            runCatching { phoneToWatch.close() }
        }
    }

    /**
     * BT2: whichever thread starts teardown, the link's own thread must deliver the disconnect
     * notifications before run() returns. The server admits a replacement only after run()
     * returns, so a stale notification can never reach the replacement's state.
     */
    @Test(timeout = 30_000)
    fun teardownNotifiesOnLinkThreadBeforeRunReturns() {
        val watchToPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(watchToPhone, 1 shl 20)
        val phoneToWatch = PipedOutputStream()
        val watchIn = PipedInputStream(phoneToWatch, 1 shl 20)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val release = CountDownLatch(1)
        val handler = object : WearRequestHandler {
            override fun request(peer: String, path: String, data: ByteArray): CompletableFuture<WearReply>? = null
            override fun cancel(peer: String, data: ByteArray) = Unit
            override fun disconnected(peer: String) {
                release.await(5, TimeUnit.SECONDS)
                events.add("handler:" + Thread.currentThread().name)
            }
        }
        val connected = AtomicReference<String>()
        val link = WearBluetoothLink(phoneIn, phoneToWatch, identity(), null, true,
            { _, _ -> true }, handler, { peer, _ -> connected.set(peer) },
            { events.add("onDisconnected:" + Thread.currentThread().name) })
        val linkThread = Thread({ link.run(); events.add("run returned") }, "wear-bt-link-owner")
        try {
            linkThread.start()
            BluetoothPairing.initiate(watchIn, watchToPhone, identity(), null, { _, _ -> true }, {})
            val until = System.currentTimeMillis() + 5_000
            while (connected.get() == null && System.currentTimeMillis() < until) Thread.sleep(10)
            assertNotNull(connected.get())

            val closer = Thread({ link.close() }, "wear-bt-other-closer").apply { start() }
            Thread.sleep(100)
            // Closing a real RFCOMM socket wakes the blocked reader; a pipe needs its writer closed.
            watchToPhone.close()
            Thread.sleep(300)
            assertTrue("run() must not return while disconnect notifications are outstanding",
                linkThread.isAlive)
            release.countDown()
            linkThread.join(5_000)
            closer.join(5_000)
            assertEquals(listOf("handler:wear-bt-link-owner", "onDisconnected:wear-bt-link-owner",
                "run returned"), events.toList())
        } finally {
            release.countDown()
            link.close()
            linkThread.join(3_000)
            runCatching { watchToPhone.close() }
            runCatching { phoneToWatch.close() }
        }
    }

    /** A watch-side view of one paired link over pipes; phone-to-watch is small, so writes block. */
    private inner class PairedLink(handler: WearRequestHandler, phoneToWatchBuffer: Int) : AutoCloseable {
        val watchToPhone = PipedOutputStream()
        private val phoneIn = PipedInputStream(watchToPhone, 1 shl 20)
        private val phoneToWatch = PipedOutputStream()
        val watchIn = PipedInputStream(phoneToWatch, phoneToWatchBuffer)
        private val connected = AtomicReference<String>()
        val link = WearBluetoothLink(phoneIn, phoneToWatch, identity(), null, true,
            { _, _ -> true }, handler, { peer, _ -> connected.set(peer) }, {})
        private val thread = Thread({ link.run() }, "wear-bt-link-transfer-test").apply { start() }
        val session = BluetoothPairing.initiate(watchIn, watchToPhone, identity(), null, { _, _ -> true }, {})
        private var sendSequence = 0L
        private var receiveSequence = 0L

        init {
            val until = System.currentTimeMillis() + 5_000
            while (connected.get() == null && System.currentTimeMillis() < until) Thread.sleep(10)
            assertNotNull(connected.get())
        }

        fun write(kind: Int, correlation: UUID, payload: ByteArray) = BluetoothWire.writeFrame(watchToPhone,
            BluetoothWire.Frame(kind, session.linkId, correlation, sendSequence++, payload),
            session.sendKey, session.sendSalt)

        fun read(): BluetoothWire.Frame = BluetoothWire.readFrame(watchIn, session.receiveKey,
            session.receiveSalt, session.linkId, receiveSequence++)

        val isOpen: Boolean get() = thread.isAlive

        override fun close() {
            link.close()
            runCatching { watchToPhone.close() }
            thread.join(3_000)
            runCatching { phoneToWatch.close() }
        }
    }

    /** Answers every map with [image] behind a small opaque header, and every widget request. */
    private class ImageHandler(val image: ByteArray) : WearRequestHandler {
        val widgetPayload = byteArrayOf(7, 7)
        val disconnects = java.util.concurrent.atomic.AtomicInteger()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val cancels = java.util.concurrent.atomic.AtomicInteger()
        override fun request(peer: String, path: String, data: ByteArray): CompletableFuture<WearReply>? = when (path.also { requests.incrementAndGet() }) {
            MapProtocol.REQUEST_PATH -> CompletableFuture.completedFuture(WearReply(byteArrayOf(1, 2, 3), image))
            WidgetProtocol.SNAPSHOT_PATH -> CompletableFuture.completedFuture(WearReply(widgetPayload))
            else -> null
        }
        override fun cancel(peer: String, data: ByteArray) { cancels.incrementAndGet() }
        override fun disconnected(peer: String) { disconnects.incrementAndGet() }
    }

    /** BT5: a cancelled map stops sending its chunks; later traffic is not stuck behind them. */
    @Test(timeout = 30_000)
    fun cancelledMapStopsItsQueuedChunks() {
        val handler = ImageHandler(ByteArray(1_000_000) { it.toByte() })
        PairedLink(handler, 1 shl 16).use { pair ->
            val mapId = UUID.randomUUID()
            pair.write(BluetoothWire.MAP, mapId, MapProtocol.encode(MapRequest(mapId, null, true)))
            val header = pair.read()
            assertEquals(BluetoothWire.MAP, header.kind)
            pair.write(BluetoothWire.CANCEL, mapId, MapProtocol.cancel(mapId))
            Thread.sleep(200)
            val widgetId = UUID.randomUUID()
            pair.write(BluetoothWire.WIDGET, widgetId,
                WidgetProtocol.encode(WidgetProtocol.Request(widgetId, UUID.randomUUID())))
            var staleChunks = 0
            while (true) {
                val frame = pair.read()
                if (frame.kind == BluetoothWire.WIDGET) {
                    assertEquals(widgetId, frame.correlationId)
                    break
                }
                assertEquals(BluetoothWire.IMAGE_CHUNK, frame.kind)
                staleChunks++
            }
            assertTrue("Only chunks already in flight may follow a cancel, was $staleChunks", staleChunks <= 4)
            Thread.sleep(300)
            assertEquals("No chunk of the cancelled map may follow", 0, pair.watchIn.available())
            assertTrue(pair.isOpen)
        }
    }

    /** BT5: several large maps answered in a row neither overflow the queue nor close the link. */
    @Test(timeout = 60_000)
    fun newerMapSupersedesOlderTransferWithoutOverflow() {
        val handler = ImageHandler(ByteArray(BluetoothWire.MAX_IMAGE_BYTES) { (it * 3).toByte() })
        PairedLink(handler, 1 shl 16).use { pair ->
            val ids = List(3) { UUID.randomUUID() }
            ids.forEach { pair.write(BluetoothWire.MAP, it, MapProtocol.encode(MapRequest(it, null, true))) }
            Thread.sleep(500)
            assertTrue("Queued images must not overflow and close the link", pair.isOpen)
            val last = ids.last()
            var assembler: BluetoothWire.ImageAssembler? = null
            while (assembler?.result() == null) {
                val frame = pair.read()
                if (frame.correlationId != last) continue
                if (frame.kind == BluetoothWire.MAP) {
                    assembler = BluetoothWire.ImageAssembler(handler.image.size)
                } else {
                    assertEquals(BluetoothWire.IMAGE_CHUNK, frame.kind)
                    checkNotNull(assembler).append(BluetoothWire.parseImageChunk(frame.payload))
                }
            }
            assertArrayEquals(handler.image, assembler?.result())
            assertEquals(0, handler.disconnects.get())
        }
    }

    /** BT6: a request naming another request ID is refused on its own correlation, unhandled. */
    @Test(timeout = 30_000)
    fun requestNamingAnotherIdIsRefusedOnItsOwnCorrelation() {
        val handler = ImageHandler(ByteArray(1_000) { it.toByte() })
        PairedLink(handler, 1 shl 20).use { pair ->
            val frameId = UUID.randomUUID()
            pair.write(BluetoothWire.MAP, frameId, MapProtocol.encode(MapRequest(UUID.randomUUID(), null, true)))
            val reply = pair.read()
            assertEquals(BluetoothWire.MAP, reply.kind)
            assertEquals(frameId, reply.correlationId)
            val decoded = checkNotNull(MapProtocol.reply(reply.payload))
            assertEquals(frameId, decoded.id)
            assertEquals(MapProtocol.UNAVAILABLE, decoded.status)

            val widgetId = UUID.randomUUID()
            pair.write(BluetoothWire.WIDGET, widgetId,
                WidgetProtocol.encode(WidgetProtocol.Request(UUID.randomUUID(), UUID.randomUUID())))
            val widget = pair.read()
            assertEquals(widgetId, widget.correlationId)
            assertEquals(widgetId, WidgetProtocol.snapshot(widget.payload)?.id)
            assertEquals("Mismatched requests must not reach the handler", 0, handler.requests.get())
            assertTrue(pair.isOpen)
        }
    }

    /** BT6: a cancel whose payload names another request cancels nothing. */
    @Test(timeout = 30_000)
    fun cancelNamingAnotherIdCancelsNothing() {
        val handler = ImageHandler(ByteArray(300_000) { (it * 5).toByte() })
        PairedLink(handler, 1 shl 16).use { pair ->
            val mapId = UUID.randomUUID()
            pair.write(BluetoothWire.MAP, mapId, MapProtocol.encode(MapRequest(mapId, null, true)))
            assertEquals(BluetoothWire.MAP, pair.read().kind)
            pair.write(BluetoothWire.CANCEL, mapId, MapProtocol.cancel(UUID.randomUUID()))
            val assembler = BluetoothWire.ImageAssembler(handler.image.size)
            while (assembler.result() == null) {
                val chunk = pair.read()
                assertEquals(BluetoothWire.IMAGE_CHUNK, chunk.kind)
                assembler.append(BluetoothWire.parseImageChunk(chunk.payload))
            }
            assertArrayEquals(handler.image, assembler.result())
            assertEquals(0, handler.cancels.get())
        }
    }
}

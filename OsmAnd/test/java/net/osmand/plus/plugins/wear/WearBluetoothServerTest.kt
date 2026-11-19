package net.osmand.plus.plugins.wear

import net.osmand.plus.plugins.wear.protocol.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WearBluetoothServerTest {
    private fun identity(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private class FakeTransport(
        override val input: InputStream,
        override val output: OutputStream
    ) : WearBluetoothTransport {
        override fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private class Handler : WearRequestHandler {
        val widget = byteArrayOf(4, 5, 6)
        val disconnected = CountDownLatch(1)

        override fun request(peer: String, path: String, data: ByteArray): CompletableFuture<WearReply>? =
            if (path == WidgetProtocol.SNAPSHOT_PATH)
                CompletableFuture.completedFuture(WearReply(widget)) else null

        override fun cancel(peer: String, data: ByteArray) = Unit

        override fun disconnected(peer: String) {
            disconnected.countDown()
        }
    }

    @Test(timeout = 30_000)
    fun serverAcceptsOnePeerPinsIdentityAndReusesLink() {
        val phoneIdentity = identity()
        val watchIdentity = identity()
        val watchToPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(watchToPhone, 1 shl 20)
        val phoneToWatch = PipedOutputStream()
        val watchIn = PipedInputStream(phoneToWatch, 1 shl 20)
        val accepted = AtomicInteger()
        val peer = AtomicReference<String>()
        val pinned = AtomicReference<ByteArray>()
        val handler = Handler()
        val server = WearBluetoothServer(
            { if (accepted.getAndIncrement() == 0) FakeTransport(phoneIn, phoneToWatch) else null },
            { phoneIdentity }, { null }, { true }, { _, _ -> true }, handler,
            { peer.set(it) }, { pinned.set(it) })
        try {
            server.start()
            val session = BluetoothPairing.initiate(watchIn, watchToPhone, watchIdentity, null,
                { _, _ -> true }, {})
            val expected = BluetoothPeerKey.of(BluetoothPairing.fingerprint(watchIdentity.public.encoded)
                .joinToString("") { "%02x".format(it) })
            val until = System.currentTimeMillis() + 5_000
            // Peer and watch identity are delivered by consecutive callbacks on the link thread.
            while ((peer.get() == null || pinned.get() == null) && System.currentTimeMillis() < until) Thread.sleep(10)
            assertEquals(expected, peer.get())
            assertArrayEquals(watchIdentity.public.encoded, pinned.get())

            val id = UUID.randomUUID()
            BluetoothWire.writeFrame(watchToPhone, BluetoothWire.Frame(BluetoothWire.WIDGET, session.linkId,
                id, 0, WidgetProtocol.encode(WidgetProtocol.Request(id, UUID.randomUUID()))),
                session.sendKey, session.sendSalt)
            val reply = BluetoothWire.readFrame(watchIn, session.receiveKey, session.receiveSalt,
                session.linkId, 0)
            assertEquals(BluetoothWire.WIDGET, reply.kind)
            assertEquals(id, reply.correlationId)
            assertArrayEquals(handler.widget, reply.payload)

            server.close()
            assertTrue(handler.disconnected.await(3, TimeUnit.SECONDS))
        } finally {
            server.close()
            runCatching { watchToPhone.close() }
            runCatching { phoneToWatch.close() }
        }
    }

    @Test(timeout = 30_000)
    fun acceptExceptionIsRetriedWithoutEndingTheListener() {
        val attempts = AtomicInteger()
        val stopped = CountDownLatch(1)
        val server = WearBluetoothServer(
            {
                when (attempts.incrementAndGet()) {
                    1 -> throw java.io.IOException("transient accept failure")
                    2 -> FakeTransport(java.io.ByteArrayInputStream(ByteArray(0)), java.io.ByteArrayOutputStream())
                    else -> null
                }
            },
            { identity() }, { null }, { true }, { _, _ -> true }, Handler(),
            onStopped = { stopped.countDown() })
        try {
            server.start()
            val until = System.currentTimeMillis() + 10_000
            while (attempts.get() < 3 && System.currentTimeMillis() < until) Thread.sleep(10)
            assertEquals("Accept was not retried after an exception", 3, attempts.get())
            assertTrue("Terminal loop exit was not reported", stopped.await(5, TimeUnit.SECONDS))
        } finally {
            server.close()
        }
    }

    @Test(timeout = 45_000)
    fun repeatedAcceptFailuresStopBoundedlyAndReport() {
        val attempts = AtomicInteger()
        val stopped = CountDownLatch(1)
        val server = WearBluetoothServer(
            { attempts.incrementAndGet(); throw java.io.IOException("radio unavailable") },
            { identity() }, { null }, { true }, { _, _ -> true }, Handler(),
            onStopped = { stopped.countDown() })
        try {
            server.start()
            assertTrue("Listener did not surrender", stopped.await(30, TimeUnit.SECONDS))
            assertTrue("Surrender was not bounded", attempts.get() in 2..8)
            val until = System.currentTimeMillis() + 2_000
            while (server.isAlive() && System.currentTimeMillis() < until) Thread.sleep(10)
            assertFalse("Listener thread is still alive", server.isAlive())
        } finally {
            server.close()
        }
    }

    private class LinkPair {
        val watchToPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(watchToPhone, 1 shl 20)
        val phoneToWatch = PipedOutputStream()
        val watchIn = PipedInputStream(phoneToWatch, 1 shl 20)

        fun transport() = FakeTransport(phoneIn, phoneToWatch)

        fun close() {
            runCatching { watchToPhone.close() }
            runCatching { phoneToWatch.close() }
        }
    }

    @Test(timeout = 60_000)
    fun changedWatchIsRejectedUntilPinIsForgottenThenNewWatchPairs() {
        val phoneIdentity = identity()
        val watchA = identity()
        val watchB = identity()
        val first = LinkPair()
        val changed = LinkPair()
        val replacement = LinkPair()
        val transports = LinkedBlockingQueue<FakeTransport>()
        transports.add(first.transport())
        val pinned = AtomicReference<ByteArray?>()
        val server = WearBluetoothServer(
            { transports.take() },
            { phoneIdentity },
            { pinned.get() },
            { pinned.get() == null },
            { _, _ -> true },
            Handler(),
            {}, { pinned.set(it) })
        fun waitForPin(expected: ByteArray) {
            val until = System.currentTimeMillis() + 5_000
            while (!expected.contentEquals(pinned.get()) && System.currentTimeMillis() < until) Thread.sleep(10)
            assertArrayEquals(expected, pinned.get())
        }
        fun waitForLinkClosed() {
            val until = System.currentTimeMillis() + 5_000
            while (server.activeLink() != null && System.currentTimeMillis() < until) Thread.sleep(10)
            assertNull(server.activeLink())
        }
        try {
            server.start()
            BluetoothPairing.initiate(first.watchIn, first.watchToPhone, watchA, null, { _, _ -> true }, {})
            waitForPin(watchA.public.encoded)
            first.close()
            waitForLinkClosed()

            transports.add(changed.transport())
            // A changed watch key still has the old phone pin, but the phone pins watch A.
            assertThrows(Exception::class.java) {
                BluetoothPairing.initiate(changed.watchIn, changed.watchToPhone, watchB,
                    phoneIdentity.public.encoded, { _, _ -> true }, {})
            }
            changed.close()
            waitForLinkClosed()

            // Explicit Forget removes only the pin; a new watch has no phone pin and pairs fresh.
            pinned.set(null)
            transports.add(replacement.transport())
            BluetoothPairing.initiate(replacement.watchIn, replacement.watchToPhone, watchB, null,
                { _, _ -> true }, {})
            waitForPin(watchB.public.encoded)
        } finally {
            server.close()
            first.close()
            changed.close()
            replacement.close()
        }
    }
}

package net.osmand.plus.plugins.wear

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Exercises the real foreground service with the loopback test transport. These checks cover the
 * recovery paths that the server-loop unit tests cannot: a dead accept thread being rebuilt by a
 * later start, and a failed listener socket being replaced while the service keeps running.
 */
@RunWith(AndroidJUnit4::class)
class WearBluetoothServiceRecoveryTest {
    private val test = InstrumentationRegistry.getInstrumentation()
    private val context get() = test.targetContext
    private val transportFile get() = File(context.filesDir, WearTestTransport.FILE_NAME)
    private var port = 0

    @Before
    fun armTestTransport() {
        check(Build.HARDWARE == "ranchu" && context.packageName == "net.osmand.dev")
        check(InstrumentationRegistry.getArguments().getString("isolatedPreview") == "true")
        WearBluetoothService.stop(context)
        waitFor("service off") { !WearBluetoothService.isRunning() }
        port = ServerSocket(0).use { it.localPort }
        transportFile.writeText("port=$port\n")
    }

    @After
    fun stopServiceAndDisarm() {
        WearBluetoothService.stop(context)
        waitFor("service off after test") { !WearBluetoothService.isRunning() }
        transportFile.delete()
    }

    @Test(timeout = 60_000)
    fun deadListenerIsRebuiltByTheNextStart() {
        assertTrue(WearBluetoothService.start(context))
        waitFor("initial listening state") { WearBluetoothService.state == WearBluetoothService.State.LISTENING }
        val first = server()
        waitFor("first accept thread") { first.isAlive() }
        val firstSocket = checkNotNull(testListener())
        // Terminate the loop the way close() does: mark the server closed, then unblock accept().
        first.close()
        firstSocket.close()
        waitFor("first listener dead") { !first.isAlive() }
        assertEquals("A dead listener must not change the reported state by itself",
            WearBluetoothService.State.LISTENING, WearBluetoothService.state)

        assertTrue(WearBluetoothService.start(context))
        waitFor("rebuilt listener") { serverOrNull()?.let { it !== first && it.isAlive() } == true }
        waitFor("listening again") { WearBluetoothService.state == WearBluetoothService.State.LISTENING }
        assertTrue("Rebuilt listener is not bound", checkNotNull(testListener()).isBound)
    }

    @Test(timeout = 60_000)
    fun failedListenerSocketIsReplacedWithoutRestartingTheService() {
        assertTrue(WearBluetoothService.start(context))
        waitFor("initial listening state") { WearBluetoothService.state == WearBluetoothService.State.LISTENING }
        val first = server()
        val oldSocket = checkNotNull(testListener())
        oldSocket.close()
        waitFor("replacement listener socket") {
            testListenerOrNull()?.let { it !== oldSocket && it.isBound } == true
        }
        waitFor("same accept thread after replacement") { serverOrNull() === first && first.isAlive() }
        assertEquals(WearBluetoothService.State.LISTENING, WearBluetoothService.state)

        Socket("127.0.0.1", port).use { client ->
            client.getOutputStream().write(byteArrayOf(1))
            client.getOutputStream().flush()
        }
        waitFor("listening after a fresh connection") {
            WearBluetoothService.state == WearBluetoothService.State.LISTENING && first.isAlive()
        }
    }

    /** A listener that gives up must not stop the service: it waits and listens again by itself. */
    @Test(timeout = 90_000)
    fun endedListenerKeepsTheServiceAndListensAgain() {
        assertTrue(WearBluetoothService.start(context))
        waitFor("initial listening state") { WearBluetoothService.state == WearBluetoothService.State.LISTENING }
        val first = server()
        // With the test transport disarmed, every accept fails until the loop gives up.
        transportFile.delete()
        checkNotNull(testListener()).close()
        waitFor("listener ended", 30_000) { WearBluetoothService.state == WearBluetoothService.State.UNAVAILABLE }
        assertTrue("The link service must keep running", service() != null && WearBluetoothService.isRunning())
        transportFile.writeText("port=$port\n")
        waitFor("listening again without a start", WearBluetoothService.RELISTEN_MS + 10_000) {
            WearBluetoothService.state == WearBluetoothService.State.LISTENING &&
                serverOrNull()?.let { it !== first && it.isAlive() } == true
        }
        assertTrue("Relistening listener is not bound", checkNotNull(testListener()).isBound)
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("Timed out waiting for $what", condition())
    }

    private fun service(): WearBluetoothService? = runCatching {
        WearBluetoothService::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.get(null) as? WearBluetoothService
    }.getOrNull()

    private fun field(name: String): Any? {
        val service = checkNotNull(service()) { "Bluetooth service is not running" }
        return WearBluetoothService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(service)
    }

    private fun server(): WearBluetoothServer = checkNotNull(serverOrNull()) { "No accept server" }

    private fun serverOrNull(): WearBluetoothServer? =
        runCatching { field("server") as? WearBluetoothServer }.getOrNull()

    private fun testListener(): ServerSocket? = checkNotNull(testListenerOrNull()) { "No test listener" }

    private fun testListenerOrNull(): ServerSocket? =
        runCatching { field("testServer") as? ServerSocket }.getOrNull()
}

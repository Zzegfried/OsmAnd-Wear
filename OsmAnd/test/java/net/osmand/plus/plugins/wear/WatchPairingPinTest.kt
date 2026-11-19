package net.osmand.plus.plugins.wear

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WatchPairingPinTest {
    private class Store(var pin: ByteArray? = null, var failWrites: Boolean = false, var unreadable: Boolean = false) {
        var writes = 0
        fun read(): ByteArray? { if (unreadable) throw IOException("corrupt pin"); return pin?.copyOf() }
        fun save(value: ByteArray) { writes++; if (failWrites) throw IOException("disk full"); pin = value.copyOf() }
    }

    @Test fun unchangedPinIsNotRewritten() {
        val store = Store(pin = byteArrayOf(1, 2))
        val pin = WatchPairingPin(store::read, store::save)
        pin.admit(byteArrayOf(1, 2))
        assertEquals(0, store.writes)
    }

    @Test fun failedSaveRetainsIdentityAndRetriesOnNextAdmission() {
        val store = Store(failWrites = true)
        val pin = WatchPairingPin(store::read, store::save)
        assertThrows(IOException::class.java) { pin.admit(byteArrayOf(3)) }
        assertArrayEquals("A verified identity survives a failed save", byteArrayOf(3), pin.forConnection())
        assertThrows(IllegalArgumentException::class.java) { pin.admit(byteArrayOf(4)) }
        store.failWrites = false
        pin.admit(byteArrayOf(3))
        assertArrayEquals(byteArrayOf(3), store.pin)
        assertEquals(2, store.writes)
    }

    @Test fun unreadablePinIsAnErrorNotFirstPairing() {
        val pin = WatchPairingPin(Store(unreadable = true)::read) { fail("must not save") }
        assertThrows(IOException::class.java) { pin.forConnection() }
    }

    @Test fun forgetDropsRetainedIdentity() {
        val store = Store(failWrites = true)
        val pin = WatchPairingPin(store::read, store::save)
        runCatching { pin.admit(byteArrayOf(5)) }
        pin.forget()
        assertNull(pin.forConnection())
    }
}

package net.osmand.plus.plugins.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Contract checks for the transport-neutral phone vocabulary and backend-qualified peer keys. */
@RunWith(AndroidJUnit4::class)
class PhoneTransportContractTest {
    @Test
    fun bluetoothStatesMapToNeutralLinkStates() {
        assertEquals(LinkState.OFF, WearBluetoothService.State.OFF.toLinkState())
        assertEquals(LinkState.CONNECTING, WearBluetoothService.State.LISTENING.toLinkState())
        assertEquals(LinkState.READY, WearBluetoothService.State.CONNECTED.toLinkState())
        assertEquals(LinkState.SETUP_REQUIRED, WearBluetoothService.State.PAIRING.toLinkState())
        assertEquals(LinkState.PERMISSION_REQUIRED, WearBluetoothService.State.PERMISSION.toLinkState())
        assertEquals(LinkState.UNAVAILABLE, WearBluetoothService.State.UNAVAILABLE.toLinkState())
    }

    @Test
    fun peerKeysAreBackendQualified() {
        val fingerprint = "0123456789abcdef"
        assertEquals("bluetooth:$fingerprint", BluetoothPeerKey.of(fingerprint))
        val bluetooth = AuthenticatedPeer(TransportKind.BLUETOOTH, BluetoothPeerKey.of(fingerprint))
        val dataLayer = AuthenticatedPeer(TransportKind.DATA_LAYER, BluetoothPeerKey.of(fingerprint))
        assertEquals(TransportKind.BLUETOOTH, bluetooth.kind)
        assertEquals(TransportKind.DATA_LAYER, dataLayer.kind)
        assertNotEquals(bluetooth.kind, dataLayer.kind)
        assertTrue(bluetooth.key.startsWith(BluetoothPeerKey.PREFIX))
    }
}

package net.osmand.plus.plugins.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

@RunWith(AndroidJUnit4::class)
class WearBluetoothIdentityTest {
    @Test(timeout = 20_000)
    fun keystoreIdentityIsStableAndPinIsNoBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "wear_bluetooth_test_identity"
        val pin = File(context.cacheDir, "wear_bluetooth_test_pin")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            store.deleteEntry(alias)
            pin.delete()
            assertEquals(context.noBackupFilesDir, WearBluetoothIdentity.defaultPinFile(context).parentFile)

            val identity = WearBluetoothIdentity(context, alias, pin)
            val first = identity.identity()
            assertTrue(first.public is ECPublicKey)
            assertEquals(256, (first.public as ECPublicKey).params.curve.field.fieldSize)
            val data = "wear-bluetooth-identity-test".toByteArray()
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(first.private); update(data); sign()
            }
            assertTrue(Signature.getInstance("SHA256withECDSA").run {
                initVerify(first.public); update(data); verify(signature)
            })
            val second = identity.identity()
            assertArrayEquals(first.public.encoded, second.public.encoded)

            assertNull(identity.pinnedWatch())
            val watch = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair().public.encoded
            identity.pinWatch(watch)
            assertArrayEquals(watch, identity.pinnedWatch())
            assertArrayEquals(watch, WearBluetoothIdentity(context, alias, pin).pinnedWatch())
            identity.clearPairing()
            assertNull(identity.pinnedWatch())
        } finally {
            store.deleteEntry(alias)
            pin.delete()
        }
    }
}

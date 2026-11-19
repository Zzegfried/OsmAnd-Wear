package net.osmand.plus.plugins.wear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Persistent P-256 app identity and the pinned watch identity. The Keystore key is not exported and
 * the pin lives in no-backup storage so neither reinstall restoration nor cloud backup can pair a
 * different watch silently.
 */
internal class WearBluetoothIdentity(
    context: Context,
    private val alias: String = ALIAS,
    private val pinFile: File = defaultPinFile(context)
) {
    fun identity(): KeyPair {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build())
            }.generateKeyPair()
        }
        val certificate = checkNotNull(keyStore.getCertificate(alias)) { "Missing Bluetooth identity certificate" }
        val privateKey = checkNotNull(keyStore.getKey(alias, null) as? PrivateKey) { "Missing Bluetooth identity key" }
        return KeyPair(certificate.publicKey, privateKey)
    }

    /**
     * The pinned watch identity, or null only when no pin exists. An unreadable or empty pin
     * throws instead: treating it as "no pin" would reopen first pairing for any watch.
     */
    fun pinnedWatch(): ByteArray? {
        val bytes = try {
            AtomicFile(pinFile).readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        val text = String(bytes, Charsets.US_ASCII).trim()
        check(text.isNotEmpty()) { "Empty watch pin" }
        return Base64.decode(text, Base64.NO_WRAP).also { check(it.isNotEmpty()) { "Empty watch pin" } }
    }

    /** Atomic: an interrupted write leaves the previous pin intact. */
    fun pinWatch(identityPublic: ByteArray) {
        require(identityPublic.isNotEmpty()) { "Empty watch identity" }
        pinFile.parentFile?.mkdirs()
        val file = AtomicFile(pinFile)
        val output = file.startWrite()
        try {
            output.write(Base64.encodeToString(identityPublic, Base64.NO_WRAP).toByteArray(Charsets.US_ASCII))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun clearPairing() {
        AtomicFile(pinFile).delete()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "wear_bluetooth_phone_identity"
        private const val PIN_FILE = "wear_bluetooth_watch_identity"

        internal fun defaultPinFile(context: Context): File = File(context.noBackupFilesDir, PIN_FILE)
    }
}

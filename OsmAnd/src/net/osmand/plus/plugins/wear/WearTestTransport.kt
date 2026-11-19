package net.osmand.plus.plugins.wear

import android.content.Context
import android.util.Log
import net.osmand.plus.BuildConfig
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/**
 * Debug/test-only TCP loopback listener for the paired emulator check.
 *
 * Active only when [BuildConfig.DEBUG] is true **and** the app-private file
 * `files/bluetooth-test-transport` exists with a valid `port`. The file is created with
 * `adb shell run-as net.osmand.plus`, so activation requires a debuggable build and local device
 * access; there is no UI, activity, receiver or exported component behind it. Release builds ignore
 * the file and the production RFCOMM listener is untouched. No host, port, code or key is logged.
 */
internal class WearTestTransport private constructor(val port: Int) {
    companion object {
        const val FILE_NAME = "bluetooth-test-transport"
        const val ALERT_FILE = "bluetooth-test-alert"
        private const val TAG = "WearBtTest"

        fun read(context: Context): WearTestTransport? {
            if (!BuildConfig.DEBUG) return null
            val file = File(context.filesDir, FILE_NAME)
            if (!file.isFile) return null
            return runCatching {
                val properties = Properties()
                file.inputStream().use(properties::load)
                val port = properties.getProperty("port")?.trim()?.toIntOrNull()
                require(port != null && port in 1..65535)
                WearTestTransport(port)
            }.onFailure { Log.w(TAG, "Ignoring invalid test transport file") }.getOrNull()
        }

        fun log(message: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, message)
        }
    }
}

/**
 * Test-only protocol observer under the authenticated link. It records handshake record kinds and
 * encrypted frame kinds with payload lengths only; no key, code, address, UUID or payload is logged.
 */
internal class TapTransport(base: WearBluetoothTransport, outbound: String, inbound: String) : WearBluetoothTransport {
    private val base = base
    override val input: InputStream = TapInputStream(base.input, inbound)
    override val output: OutputStream = TapOutputStream(base.output, outbound)
    override fun close() {
        base.close()
    }
}

private class TapInputStream(input: InputStream, direction: String) : FilterInputStream(input) {
    private val tap = RecordTap(direction)

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) tap.feed(byteArrayOf(value.toByte()), 0, 1)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(bytes, offset, length)
        if (count > 0) tap.feed(bytes, offset, count)
        return count
    }
}

private class TapOutputStream(output: OutputStream, direction: String) : FilterOutputStream(output) {
    private val tap = RecordTap(direction)

    override fun write(value: Int) {
        out.write(value)
        tap.feed(byteArrayOf(value.toByte()), 0, 1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        tap.feed(bytes, offset, length)
    }
}

/** Bounded, allocation-light v1 record parser: handshake records first, then OWB1 frames. */
private class RecordTap(private val direction: String) {
    private var step = STEP_MAGIC
    private var need = 4
    private var fill = 0
    private var kind = 0
    private val header = ByteArray(52)

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        if (step == STEP_DEAD) return
        runCatching {
            var index = offset
            var left = length
            while (left > 0 && step != STEP_DEAD) {
                if (step == STEP_HANDSHAKE_BODY || step == STEP_FRAME_BODY) {
                    val count = minOf(need, left)
                    need -= count
                    index += count
                    left -= count
                    if (need == 0) advance()
                    continue
                }
                val count = minOf(need - fill, left)
                System.arraycopy(bytes, index, header, fill, count)
                index += count
                left -= count
                fill += count
                if (fill == need) advance()
            }
        }.onFailure { step = STEP_DEAD }
    }

    private fun advance() {
        when (step) {
            STEP_MAGIC -> when (readInt(header, 0)) {
                HANDSHAKE_MAGIC -> { fill = 0; step = STEP_HANDSHAKE_HEADER; need = 3 }
                // Keep the magic in place and read the remaining 48 header bytes after it.
                FRAME_MAGIC -> { fill = 4; step = STEP_FRAME_HEADER; need = 52 }
                else -> step = STEP_DEAD
            }
            STEP_HANDSHAKE_HEADER -> {
                kind = header[0].toInt() and 0xff
                val length = ((header[1].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
                Log.d(TAG, "$direction handshake kind=$kind bytes=$length")
                fill = 0
                if (length == 0) { step = STEP_MAGIC; need = 4 } else { step = STEP_HANDSHAKE_BODY; need = length }
            }
            STEP_HANDSHAKE_BODY -> { fill = 0; step = STEP_MAGIC; need = 4 }
            STEP_FRAME_HEADER -> {
                kind = header[4].toInt() and 0xff
                val length = readInt(header, 48)
                if (length < 0 || length > MAX_FRAME_BYTES) { step = STEP_DEAD; return }
                Log.d(TAG, "$direction frame kind=$kind bytes=$length")
                fill = 0
                step = STEP_FRAME_BODY
                need = length + TAG_BYTES
            }
            STEP_FRAME_BODY -> { fill = 0; step = STEP_MAGIC; need = 4 }
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private companion object {
        const val TAG = "WearBtTest"
        const val HANDSHAKE_MAGIC = 0x4f574831
        const val FRAME_MAGIC = 0x4f574231
        const val TAG_BYTES = 16
        const val MAX_FRAME_BYTES = 2_097_152 + 32_768
        const val STEP_MAGIC = 0
        const val STEP_HANDSHAKE_HEADER = 1
        const val STEP_HANDSHAKE_BODY = 2
        const val STEP_FRAME_HEADER = 3
        const val STEP_FRAME_BODY = 4
        const val STEP_DEAD = 5
    }
}

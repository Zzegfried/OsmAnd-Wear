package net.osmand.plus.plugins.wear.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The only v1 Bluetooth envelope. It has no Android or Google dependency. */
object BluetoothWire {
    val SERVICE_UUID: UUID = UUID.fromString("d2aef3b2-8fbb-49c3-9c0e-7c35cc1c04e1")
    const val SESSION = 1
    const val SESSION_CHECK = 2
    const val MAP = 3
    const val IMAGE_CHUNK = 4
    const val CANCEL = 5
    const val WIDGET = 6
    const val RECORDING = 7
    const val PROFILE = 8
    const val ALERT = 9
    const val STATUS = 10
    const val HEADER_BYTES = 52
    const val TAG_BYTES = 16
    const val MAX_INLINE_BYTES = 96_000
    const val MAX_IMAGE_BYTES = 2_097_152
    const val MAX_CHUNK_BYTES = 32_768
    const val MAX_CONTROL_BYTES = 8_192
    const val MAX_HANDSHAKE_BYTES = 4_096
    const val PAIR_COMMIT = 1
    const val PAIR_REVEAL = 2
    const val PAIR_PROOF = 3
    const val AUTH_HELLO = 4
    const val AUTH_PROOF = 5
    /** Responder has no pin for this peer: re-pair with confirmation (never silently). Empty. */
    const val PAIR_REQUIRED = 6
    /** Responder is pinned to a different peer identity (or refuses first pairing). Empty. */
    const val PAIR_REFUSED = 7
    private const val FRAME_MAGIC = 0x4f574231 // OWB1
    private const val HANDSHAKE_MAGIC = 0x4f574831 // OWH1

    data class Frame(
        val kind: Int,
        val linkId: UUID,
        val correlationId: UUID,
        val sequence: Long,
        val payload: ByteArray
    )

    data class HandshakeRecord(val kind: Int, val payload: ByteArray)
    data class ImageChunk(val totalLength: Int, val offset: Int, val data: ByteArray)

    fun maxPayload(kind: Int): Int = when (kind) {
        MAP -> MAX_INLINE_BYTES
        IMAGE_CHUNK -> MAX_CHUNK_BYTES + 8
        SESSION, SESSION_CHECK, CANCEL, WIDGET, RECORDING, PROFILE, ALERT, STATUS -> MAX_CONTROL_BYTES
        else -> throw IllegalArgumentException("Unknown v1 frame kind $kind")
    }

    fun writeHandshake(output: OutputStream, kind: Int, payload: ByteArray) {
        require(kind in PAIR_COMMIT..PAIR_REFUSED && payload.size <= MAX_HANDSHAKE_BYTES) { "Bad handshake record to send" }
        DataOutputStream(output).apply {
            writeInt(HANDSHAKE_MAGIC)
            writeByte(kind)
            writeShort(payload.size)
            write(payload)
            flush()
        }
    }

    fun readHandshake(input: InputStream): HandshakeRecord {
        val data = DataInputStream(input)
        require(data.readInt() == HANDSHAKE_MAGIC) { "Wrong handshake version" }
        val kind = data.readUnsignedByte()
        val length = data.readUnsignedShort()
        require(kind in PAIR_COMMIT..PAIR_REFUSED && length <= MAX_HANDSHAKE_BYTES) { "Bad handshake record kind $kind or length $length" }
        return HandshakeRecord(kind, ByteArray(length).also(data::readFully))
    }

    fun imageChunk(totalLength: Int, offset: Int, data: ByteArray): ByteArray {
        validateImageChunk(totalLength, offset, data.size)
        return ByteBuffer.allocate(8 + data.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(totalLength).putInt(offset).put(data).array()
    }

    fun parseImageChunk(payload: ByteArray): ImageChunk {
        require(payload.size in 9..MAX_CHUNK_BYTES + 8) { "Bad image chunk length ${payload.size}" }
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val total = input.int
        val offset = input.int
        val data = ByteArray(payload.size - 8).also(input::get)
        validateImageChunk(total, offset, data.size)
        return ImageChunk(total, offset, data)
    }

    private fun validateImageChunk(total: Int, offset: Int, bytes: Int) {
        require(total in 1..MAX_IMAGE_BYTES && offset >= 0 && bytes in 1..MAX_CHUNK_BYTES) { "Bad image chunk fields" }
        require(offset.toLong() + bytes <= total.toLong()) { "Image chunk beyond image end" }
    }

    class ImageAssembler(totalLength: Int) {
        private val buffer: ByteArray
        private var nextOffset = 0
        init {
            require(totalLength in 1..MAX_IMAGE_BYTES) { "Bad image length $totalLength" }
            buffer = ByteArray(totalLength)
        }
        fun append(chunk: ImageChunk): Boolean {
            require(chunk.totalLength == buffer.size && chunk.offset == nextOffset) { "Image chunk out of order" }
            require(nextOffset.toLong() + chunk.data.size <= buffer.size.toLong()) { "Image chunk overflows image" }
            chunk.data.copyInto(buffer, nextOffset)
            nextOffset += chunk.data.size
            return nextOffset == buffer.size
        }
        fun result(): ByteArray? = if (nextOffset == buffer.size) buffer.copyOf() else null
        fun abort() { buffer.fill(0); nextOffset = 0 }
    }

    fun writeFrame(output: OutputStream, frame: Frame, key: ByteArray, salt: ByteArray) {
        output.write(encodeFrame(frame, key, salt))
        output.flush()
    }

    fun encodeFrame(frame: Frame, key: ByteArray, salt: ByteArray): ByteArray {
        require(key.size == 32 && salt.size == 4 && frame.sequence >= 0)
        require(frame.payload.size <= maxPayload(frame.kind))
        if (frame.kind == IMAGE_CHUNK) parseImageChunk(frame.payload)
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(FRAME_MAGIC)
            put(frame.kind.toByte())
            put(0) // flags
            putShort(0) // reserved
            putLong(frame.linkId.mostSignificantBits); putLong(frame.linkId.leastSignificantBits)
            putLong(frame.correlationId.mostSignificantBits); putLong(frame.correlationId.leastSignificantBits)
            putLong(frame.sequence)
            putInt(frame.payload.size)
        }.array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(salt, frame.sequence)))
        cipher.updateAAD(header)
        return header + cipher.doFinal(frame.payload)
    }

    fun readFrame(input: InputStream, key: ByteArray, salt: ByteArray,
                  expectedLinkId: UUID, expectedSequence: Long): Frame {
        require(key.size == 32 && salt.size == 4 && expectedSequence >= 0)
        val header = ByteArray(HEADER_BYTES).also { DataInputStream(input).readFully(it) }
        val fields = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        require(fields.int == FRAME_MAGIC) { "Wrong frame version" }
        val kind = fields.get().toInt() and 0xff
        require(fields.get().toInt() == 0 && fields.short.toInt() == 0) { "Unknown flags" }
        val linkId = UUID(fields.long, fields.long)
        val correlationId = UUID(fields.long, fields.long)
        val sequence = fields.long
        val length = fields.int
        require(linkId == expectedLinkId) { "Wrong link ID" }
        require(sequence == expectedSequence) { "Sequence $sequence, expected $expectedSequence" }
        require(length in 0..maxPayload(kind)) { "Frame length $length too large for kind $kind" } // validate before allocating or reading ciphertext
        val ciphertext = ByteArray(length + TAG_BYTES).also { DataInputStream(input).readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(salt, sequence)))
        cipher.updateAAD(header)
        val payload = cipher.doFinal(ciphertext)
        if (kind == IMAGE_CHUNK) parseImageChunk(payload)
        return Frame(kind, linkId, correlationId, sequence, payload)
    }

    fun decodeFrame(encoded: ByteArray, key: ByteArray, salt: ByteArray,
                    expectedLinkId: UUID, expectedSequence: Long): Frame {
        val input = ByteArrayInputStream(encoded)
        val frame = readFrame(input, key, salt, expectedLinkId, expectedSequence)
        require(input.available() == 0) { "Trailing frame data" }
        return frame
    }

    private fun nonce(salt: ByteArray, sequence: Long): ByteArray = ByteBuffer.allocate(12)
        .order(ByteOrder.BIG_ENDIAN).put(salt).putLong(sequence).array()
}

/** App identity and per-connection keys; the caller owns Keystore and pin persistence. */
object BluetoothPairing {
    const val ROLE_PHONE = 1
    const val ROLE_WATCH = 2
    private const val PAIR_TIMEOUT_MS = 120_000L
    private const val AUTH_TIMEOUT_MS = 10_000L
    private val domain = "OsmAndWearBluetoothV1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    /** Why a handshake ended without a session, where the cause is known and actionable. */
    enum class HandshakeFailure {
        /** The peer holds no pin for us: the next attempt must re-pair, with confirmation. */
        PEER_UNPAIRED,
        /** The peer's identity differs from the pinned one: only forgetting the pin recovers. */
        IDENTITY_CHANGED,
        /** First pairing was not allowed on this side. */
        FIRST_PAIR_REFUSED,
        /** The pairing code was rejected or not confirmed in time. */
        NOT_CONFIRMED,
        /** The peer is pinned to a different identity: it must forget that pairing first. */
        PEER_REFUSED
    }

    class HandshakeException(val failure: HandshakeFailure) : java.io.IOException(failure.name)

    data class Hello(val identityPublic: ByteArray, val ephemeralPublic: ByteArray, val nonce: ByteArray)
    data class Session(
        val peerIdentityPublic: ByteArray,
        val peerFingerprint: ByteArray,
        val linkId: UUID,
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val sendSalt: ByteArray,
        val receiveSalt: ByteArray,
        val firstPair: Boolean
    ) {
        fun eraseKeys() { sendKey.fill(0); receiveKey.fill(0); sendSalt.fill(0); receiveSalt.fill(0) }
    }

    fun generateEphemeral(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    fun fingerprint(identityPublic: ByteArray): ByteArray = sha256(identityPublic)

    fun encodeHello(hello: Hello): ByteArray {
        require(hello.identityPublic.size in 60..256 && hello.ephemeralPublic.size in 60..256 && hello.nonce.size == 32) { "Bad hello field sizes" }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeShort(hello.identityPublic.size); write(hello.identityPublic)
                writeShort(hello.ephemeralPublic.size); write(hello.ephemeralPublic)
                write(hello.nonce)
            }
        }.toByteArray()
    }

    fun decodeHello(bytes: ByteArray): Hello {
        require(bytes.size in 156..548) { "Bad hello length ${bytes.size}" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val identityLength = input.readUnsignedShort()
        require(identityLength in 60..256) { "Bad hello identity length" }
        val identity = ByteArray(identityLength).also(input::readFully)
        val ephemeralLength = input.readUnsignedShort()
        require(ephemeralLength in 60..256) { "Bad hello ephemeral length" }
        val ephemeral = ByteArray(ephemeralLength).also(input::readFully)
        val nonce = ByteArray(32).also(input::readFully)
        require(input.available() == 0) { "Trailing hello data" }
        ecPublic(identity); ecPublic(ephemeral)
        return Hello(identity, ephemeral, nonce)
    }

    fun commitment(role: Int, hello: Hello): ByteArray {
        require(role == ROLE_PHONE || role == ROLE_WATCH) { "Unknown handshake role" }
        return sha256(domain, byteArrayOf(1, role.toByte()), encodeHello(hello))
    }

    fun verifyCommitment(role: Int, hello: Hello, commitment: ByteArray): Boolean =
        commitment.size == 32 && MessageDigest.isEqual(commitment(role, hello), commitment)

    fun transcript(phone: Hello, watch: Hello): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            write(domain)
            writeByte(ROLE_PHONE); val p = encodeHello(phone); writeShort(p.size); write(p)
            writeByte(ROLE_WATCH); val w = encodeHello(watch); writeShort(w.size); write(w)
        }
    }.toByteArray()

    fun agree(ephemeralPrivate: PrivateKey, peerEphemeralPublic: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(ephemeralPrivate)
            doPhase(ecPublic(peerEphemeralPublic), true)
            generateSecret()
        }

    fun sas(transcript: ByteArray, sharedSecret: ByteArray): String {
        require(sharedSecret.size >= 16) { "Short shared secret" }
        val digest = sha256(domain, byteArrayOf(2), transcript, sharedSecret)
        val number = ByteBuffer.wrap(digest).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        return (number % 100_000_000L).toString().padStart(8, '0')
    }

    /**
     * Watch side. Without a pin, or with [repair] after the phone reported [HandshakeFailure.PEER_UNPAIRED],
     * this runs the confirmed pairing flow; a held pin then still requires the same phone identity.
     */
    fun initiate(input: InputStream, output: OutputStream, identity: KeyPair,
                 pinnedPeer: ByteArray?, confirmFirstPair: (String, ByteArray) -> Boolean,
                 abort: () -> Unit, repair: Boolean = false): Session {
        val pair = pinnedPeer == null || repair
        return withDeadline(if (pair) PAIR_TIMEOUT_MS else AUTH_TIMEOUT_MS, abort) { deadline ->
            runHandshake(true, input, output, identity, pinnedPeer, pair, null, confirmFirstPair, deadline)
        }
    }

    /**
     * Phone side. The watch's first record selects the flow. A pairing attempt from a pinned
     * watch re-pairs with confirmation and must present the pinned identity (recovering a pin
     * saved on one side only); a pinned reconnect from a watch this phone holds no pin for is
     * answered with PAIR_REQUIRED so the watch can re-pair instead of retrying blindly.
     */
    fun respond(input: InputStream, output: OutputStream, identity: KeyPair,
                pinnedPeer: ByteArray?, allowFirstPair: Boolean,
                confirmFirstPair: (String, ByteArray) -> Boolean,
                abort: () -> Unit): Session {
        val first = withDeadline(AUTH_TIMEOUT_MS, abort) { BluetoothWire.readHandshake(input) }
        val pair = when (first.kind) {
            BluetoothWire.PAIR_COMMIT -> {
                if (pinnedPeer == null && !allowFirstPair) refuse(output, HandshakeFailure.FIRST_PAIR_REFUSED)
                true
            }
            BluetoothWire.AUTH_HELLO -> {
                if (pinnedPeer == null) {
                    BluetoothWire.writeHandshake(output, BluetoothWire.PAIR_REQUIRED, ByteArray(0))
                    throw HandshakeException(HandshakeFailure.PEER_UNPAIRED)
                }
                false
            }
            else -> throw IllegalArgumentException("Unexpected handshake record")
        }
        return withDeadline(if (pair) PAIR_TIMEOUT_MS else AUTH_TIMEOUT_MS, abort) { deadline ->
            try {
                runHandshake(false, input, output, identity, pinnedPeer, pair, first.payload, confirmFirstPair, deadline)
            } catch (error: HandshakeException) {
                if (error.failure == HandshakeFailure.IDENTITY_CHANGED) refuse(output, error.failure)
                throw error
            }
        }
    }

    /** Tells the watch why (so it stops retrying), then fails. The record is advisory and unauthenticated. */
    private fun refuse(output: OutputStream, failure: HandshakeFailure): Nothing {
        runCatching { BluetoothWire.writeHandshake(output, BluetoothWire.PAIR_REFUSED, ByteArray(0)) }
        throw HandshakeException(failure)
    }

    /**
     * [pair] selects the confirmed commit/reveal flow, else the pinned authentication flow.
     * [expectedPeer], when held, must equal the peer's identity in either flow. The responder
     * passes the payload of the first record it already read as [firstRecord].
     */
    private fun runHandshake(initiator: Boolean, input: InputStream, output: OutputStream,
                             identity: KeyPair, expectedPeer: ByteArray?, pair: Boolean, firstRecord: ByteArray?,
                             confirm: (String, ByteArray) -> Boolean, deadline: Long): Session {
        require(identity.public.encoded.isNotEmpty())
        require(pair || expectedPeer != null)
        require(initiator == (firstRecord == null))
        ecPublic(identity.public.encoded)
        val ephemeral = generateEphemeral()
        val local = Hello(identity.public.encoded, ephemeral.public.encoded, ByteArray(32).also(random::nextBytes))
        val firstPair = pair
        // Checked as soon as the peer's identity is known, before this side reveals itself or
        // shows a code: a pinned side never pairs a different identity.
        fun admitted(hello: Hello): Hello {
            if (expectedPeer != null && !MessageDigest.isEqual(expectedPeer, hello.identityPublic)) {
                throw HandshakeException(HandshakeFailure.IDENTITY_CHANGED)
            }
            return hello
        }
        val peer: Hello
        if (pair) {
            val localCommit = commitment(if (initiator) ROLE_WATCH else ROLE_PHONE, local)
            val remoteCommit: ByteArray
            if (initiator) {
                BluetoothWire.writeHandshake(output, BluetoothWire.PAIR_COMMIT, localCommit)
                remoteCommit = expect(input, BluetoothWire.PAIR_COMMIT)
                BluetoothWire.writeHandshake(output, BluetoothWire.PAIR_REVEAL, encodeHello(local))
                peer = admitted(decodeHello(expect(input, BluetoothWire.PAIR_REVEAL)))
            } else {
                remoteCommit = checkNotNull(firstRecord)
                BluetoothWire.writeHandshake(output, BluetoothWire.PAIR_COMMIT, localCommit)
                peer = admitted(decodeHello(expect(input, BluetoothWire.PAIR_REVEAL)))
                BluetoothWire.writeHandshake(output, BluetoothWire.PAIR_REVEAL, encodeHello(local))
            }
            require(verifyCommitment(if (initiator) ROLE_PHONE else ROLE_WATCH, peer, remoteCommit)) { "Pairing commitment mismatch" }
        } else {
            if (initiator) {
                BluetoothWire.writeHandshake(output, BluetoothWire.AUTH_HELLO, encodeHello(local))
                peer = admitted(decodeHello(expect(input, BluetoothWire.AUTH_HELLO)))
            } else {
                peer = admitted(decodeHello(checkNotNull(firstRecord)))
                BluetoothWire.writeHandshake(output, BluetoothWire.AUTH_HELLO, encodeHello(local))
            }
        }
        val ordered = if (initiator) transcript(peer, local) else transcript(local, peer)
        val shared = agree(ephemeral.private, peer.ephemeralPublic)
        val secrets = try { derive(shared, ordered) } catch (error: Exception) { shared.fill(0); throw error }
        try {
            if (firstPair) {
                val code = sas(ordered, shared)
                if (!confirmBeforeDeadline(confirm, code, fingerprint(peer.identityPublic), deadline)) {
                    throw HandshakeException(HandshakeFailure.NOT_CONFIRMED)
                }
            }
            val localRole = if (initiator) ROLE_WATCH else ROLE_PHONE
            val peerRole = if (initiator) ROLE_PHONE else ROLE_WATCH
            val kind = if (firstPair) BluetoothWire.PAIR_PROOF else BluetoothWire.AUTH_PROOF
            val proof = proof(identity.private, secrets.confirmKey, ordered, localRole)
            if (initiator) {
                BluetoothWire.writeHandshake(output, kind, proof)
                verifyProof(expect(input, kind), peer.identityPublic, secrets.confirmKey, ordered, peerRole)
            } else {
                verifyProof(expect(input, kind), peer.identityPublic, secrets.confirmKey, ordered, peerRole)
                BluetoothWire.writeHandshake(output, kind, proof)
            }
            return Session(peer.identityPublic.copyOf(), fingerprint(peer.identityPublic), secrets.linkId,
                if (initiator) secrets.watchToPhoneKey else secrets.phoneToWatchKey,
                if (initiator) secrets.phoneToWatchKey else secrets.watchToPhoneKey,
                if (initiator) secrets.watchToPhoneSalt else secrets.phoneToWatchSalt,
                if (initiator) secrets.phoneToWatchSalt else secrets.watchToPhoneSalt,
                firstPair)
        } finally {
            shared.fill(0)
            secrets.confirmKey.fill(0)
        }
    }

    private fun expect(input: InputStream, kind: Int): ByteArray = BluetoothWire.readHandshake(input).also {
        if (it.kind == BluetoothWire.PAIR_REQUIRED) throw HandshakeException(HandshakeFailure.PEER_UNPAIRED)
        if (it.kind == BluetoothWire.PAIR_REFUSED) throw HandshakeException(HandshakeFailure.PEER_REFUSED)
        require(it.kind == kind) { "Unexpected handshake record" }
    }.payload

    private fun proof(privateKey: PrivateKey, confirmKey: ByteArray, transcript: ByteArray, role: Int): ByteArray {
        val signed = proofInput(transcript, role)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(signed)
            sign()
        }
        require(signature.size in 64..128) { "Bad signature length" }
        val mac = hmac(confirmKey, signed, signature)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply { writeShort(signature.size); write(signature); write(mac) }
        }.toByteArray()
    }

    private fun verifyProof(bytes: ByteArray, encodedKey: ByteArray, confirmKey: ByteArray,
                            transcript: ByteArray, role: Int) {
        require(bytes.size in 98..162) { "Bad proof length ${bytes.size}" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val size = input.readUnsignedShort()
        require(size in 64..128 && input.available() == size + 32) { "Bad proof signature length" }
        val signature = ByteArray(size).also(input::readFully)
        val mac = ByteArray(32).also(input::readFully)
        val signed = proofInput(transcript, role)
        require(MessageDigest.isEqual(mac, hmac(confirmKey, signed, signature))) { "Wrong proof MAC" }
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(ecPublic(encodedKey)); update(signed); verify(signature)
        }) { "Wrong identity signature" }
    }

    private fun proofInput(transcript: ByteArray, role: Int): ByteArray =
        domain + byteArrayOf(3, role.toByte()) + transcript

    private data class Derived(
        val phoneToWatchKey: ByteArray, val watchToPhoneKey: ByteArray,
        val phoneToWatchSalt: ByteArray, val watchToPhoneSalt: ByteArray,
        val linkId: UUID, val confirmKey: ByteArray
    )

    private fun derive(secret: ByteArray, transcript: ByteArray): Derived {
        val extractSalt = sha256(domain, byteArrayOf(4), transcript)
        val prk = hmac(extractSalt, secret)
        val bytes = hkdfExpand(prk, domain + byteArrayOf(5), 120)
        prk.fill(0)
        val link = ByteBuffer.wrap(bytes, 72, 16).order(ByteOrder.BIG_ENDIAN)
        return Derived(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64),
            bytes.copyOfRange(64, 68), bytes.copyOfRange(68, 72),
            UUID(link.long, link.long), bytes.copyOfRange(88, 120)).also { bytes.fill(0) }
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, count: Int): ByteArray {
        require(count in 1..255 * 32) { "Bad key derivation length" }
        val result = ByteArrayOutputStream(count)
        var previous = ByteArray(0)
        var counter = 1
        while (result.size() < count) {
            previous = hmac(prk, previous, info, byteArrayOf(counter.toByte()))
            result.write(previous)
            counter++
        }
        previous.fill(0)
        return result.toByteArray().copyOf(count)
    }

    private fun ecPublic(encoded: ByteArray): PublicKey {
        require(encoded.size in 60..256) { "Bad public key length" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        require(key is ECPublicKey) { "Not an EC public key" }
        val expected = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        require(key.params.curve == expected.curve && key.params.generator == expected.generator &&
            key.params.order == expected.order && key.params.cofactor == expected.cofactor) { "Wrong EC curve" }
        return key
    }

    private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        data.forEach(::update)
        doFinal()
    }

    private fun sha256(vararg data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        data.forEach(::update)
        digest()
    }

    private fun confirmBeforeDeadline(callback: (String, ByteArray) -> Boolean, code: String,
                                      fingerprint: ByteArray, deadline: Long): Boolean {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw TimeoutException("Pairing timed out")
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "wear-pair-confirm").apply { isDaemon = true }
        }
        val future = executor.submit<Boolean> { callback(code, fingerprint.copyOf()) }
        return try { future.get(remaining, TimeUnit.NANOSECONDS) }
        finally { future.cancel(true); executor.shutdownNow() }
    }

    private fun <T> withDeadline(timeoutMs: Long, abort: () -> Unit, work: (Long) -> T): T {
        val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "wear-bt-timeout").apply { isDaemon = true }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val expired = AtomicBoolean(false)
        val task = timer.schedule({ expired.set(true); abort() }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            val result = work(deadline)
            // Only a fired abort voids the result: it closed the link, so the peer cannot have completed
            // either. A success that merely finished near the deadline is kept, so a completed pairing is
            // not discarded on one side only.
            if (expired.get()) throw TimeoutException("Handshake timed out")
            result
        } finally { task.cancel(false); timer.shutdownNow() }
    }
}

package net.osmand.plus.plugins.wear.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class MapView(val latitude: Double, val longitude: Double, val zoom: Int, val width: Int, val height: Int) {
    fun valid() = latitude.isFinite() && latitude in -85.0..85.0 && longitude.isFinite() && longitude in -180.0..180.0 &&
        zoom in 6..20 && width in 64..512 && height in 64..512
}

/** Only basemap identity. Route/position data must never enter a persistent map cache. */
data class BaseMap(val revision: String, val view: MapView) {
    fun valid() = revision.matches(Regex("[a-f0-9]{64}")) && view.valid()
    fun covers(target: MapView): Boolean {
        if (!valid() || !target.valid() || view.zoom != target.zoom || view.width < target.width || view.height < target.height) return false
        val world = 256.0 * Math.pow(2.0, target.zoom.toDouble())
        val longitude = ((view.longitude - target.longitude + 540) % 360) - 180
        fun mercator(latitude: Double): Double { val sine = Math.sin(Math.toRadians(latitude)); return 0.5 - Math.log((1 + sine) / (1 - sine)) / (4 * Math.PI) }
        return kotlin.math.abs(longitude / 360 * world) <= (view.width - target.width) / 2.0 + 0.00001 &&
            kotlin.math.abs((mercator(view.latitude) - mercator(target.latitude)) * world) <= (view.height - target.height) / 2.0 + 0.00001
    }
}

/** [cachedOverlay] is the revision of the overlay layer the watch holds; the phone omits that layer while it is unchanged. */
data class MapRequest(val id: UUID, val view: MapView?, val follow: Boolean, val width: Int = 400, val height: Int = 400, val cachedBase: BaseMap? = null,
    val cachedOverlay: Long? = null) {
    fun valid() = width in 64..512 && height in 64..512 && (view == null || view.valid() && view.width == width && view.height == height) && (cachedBase == null || cachedBase.valid()) &&
        cachedOverlay != 0L
}

data class MapReply(val id: UUID, val status: Int, val view: MapView? = null, val capturedUtc: Long = 0,
    val navigation: Boolean = false, val tracks: Int = 0, val bytes: Int = 0, val positionStatus: Int = MapProtocol.POSITION_UNAVAILABLE,
    val positionLatitude: Double = 0.0, val positionLongitude: Double = 0.0, val positionUtc: Long = 0, val following: Boolean = false, val base: BaseMap? = null, val positionBearing: Float? = null,
    val rulerLabel: String? = null, val rulerRatio: Float = 0f, val clockText: String? = null,
    /** Revision of this frame's overlay layer. An empty overlay layer means "unchanged": the request's cached overlay. */
    val overlay: Long? = null) {
    fun valid(): Boolean {
        if (clockText != null && (clockText.isBlank() || clockText.length > 24 || clockText.any { it.code < 0x20 || it.code == 0x7f })) return false
        if (positionBearing != null && (!positionBearing.isFinite() || positionBearing < 0f || positionBearing >= 360f || positionStatus != MapProtocol.POSITION_FRESH || base == null)) return false
        if (overlay == 0L) return false
        if (status != MapProtocol.OK) return status in MapProtocol.ERRORS && view == null && bytes == 0 && base == null && overlay == null
        if (base == null || view?.valid() != true || capturedUtc <= 0 || tracks !in 0..128 || bytes !in 1..MapProtocol.MAX_BYTES) return false
        if (base != null && (!base.valid() || !base.covers(view))) return false
        if (rulerLabel == null) { if (rulerRatio != 0f) return false }
        else if (rulerLabel.isBlank() || rulerLabel.length > 24 || !rulerRatio.isFinite() || rulerRatio !in 0.05f..0.5f) return false
        if (positionStatus == MapProtocol.POSITION_UNAVAILABLE) return positionUtc == 0L && positionLatitude == 0.0 && positionLongitude == 0.0 && !following
        if (positionStatus !in MapProtocol.POSITION_FRESH..MapProtocol.POSITION_STALE || !positionLatitude.isFinite() || positionLatitude !in -85.0..85.0 ||
            !positionLongitude.isFinite() || positionLongitude !in -180.0..180.0 || positionUtc <= 0 || positionUtc > capturedUtc) return false
        return (capturedUtc - positionUtc <= 30_000) == (positionStatus == MapProtocol.POSITION_FRESH) &&
            (!following || positionStatus == MapProtocol.POSITION_FRESH)
    }

    fun matches(request: MapRequest): Boolean = valid() && id == request.id && (status != MapProtocol.OK ||
        view!!.width == request.width && view.height == request.height && (!following || request.follow) &&
        (request.view == null || view.zoom == request.view.zoom && (following ||
            kotlin.math.abs(view.latitude - request.view.latitude) < 0.000001 && kotlin.math.abs(view.longitude - request.view.longitude) < 0.000001)))
}

/** Small native control headers; compact RPC images or a correlated bounded channel. */
object MapProtocol {
    const val REQUEST_PATH = "/map/v1/request"
    const val SESSION_PATH = "/map/v1/session"
    const val SESSION_CHECK_PATH = "/map/v1/session/check"
    const val SESSION_READY = 0
    const val SESSION_PHONE_REQUIRED = 1
    const val SESSION_STOPPED = 2
    const val SESSION_DENIED = 3
    const val SESSION_CONNECTING = 4
    const val SESSION_BUSY = 5
    private const val SESSION_MAGIC = 0x4f575331
    private const val MOTION_MAGIC = 0x4f574231
    private const val CLOCK_MAGIC = 0x4f574331
    const val LAYER_MAGIC = 0x4f574c31
    const val MAX_INLINE_BYTES = 96_000
    const val CANCEL_PATH = "/map/v1/cancel"
    private const val CANCEL_PREFIX = "OWC1:"
    const val IMAGE_PATH = "/map/v1/image/"
    const val MAX_BYTES = 2_097_152
    /** Reply headers carry the base identity, overlay revision, ruler and clock; 512 bytes bounds their longest labels. */
    const val MAX_HEADER = 512
    const val TIMEOUT_MS = 25_000L
    const val OK = 5
    const val DENIED = 1
    const val INVALID_REQUEST = 2
    const val NOT_READY = 3
    const val BUSY = 4
    const val NO_MAP = 6
    const val LIMIT_EXCEEDED = 7
    const val CONTENT_CHANGED = 8
    const val CANCELLED = 9
    const val TIMEOUT = 10
    const val RENDER_ERROR = 11
    const val POSITION_UNAVAILABLE = 0
    const val POSITION_FRESH = 1
    const val POSITION_STALE = 2
    const val UNSUPPORTED = 12
    const val UNAVAILABLE = 13
    const val INVALID = 14
    val ERRORS = setOf(
        DENIED, INVALID_REQUEST, NOT_READY, BUSY, NO_MAP, LIMIT_EXCEEDED, CONTENT_CHANGED,
        CANCELLED, TIMEOUT, RENDER_ERROR, UNSUPPORTED, UNAVAILABLE, INVALID
    )
    private const val REQUEST_MAGIC = 0x4f574d31
    private const val REPLY_MAGIC = 0x4f575231

    private fun DataOutputStream.id(id: UUID) { writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
    private fun DataInputStream.id() = UUID(readLong(), readLong())
    private fun DataOutputStream.view(view: MapView) { writeDouble(view.latitude); writeDouble(view.longitude); writeInt(view.zoom); writeInt(view.width); writeInt(view.height) }
    private fun DataInputStream.view() = MapView(readDouble(), readDouble(), readInt(), readInt(), readInt())
    private fun write(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
    private fun <T> read(bytes: ByteArray, block: DataInputStream.() -> T): T? = try {
        require(bytes.size <= MAX_HEADER)
        DataInputStream(ByteArrayInputStream(bytes)).use { input -> input.block().also { require(input.available() == 0) } }
    } catch (_: Exception) { null }

    /** State byte: 0 closes, 1 opens with the watch display awake, 2 opens with it asleep. */
    fun session(id: UUID, open: Boolean, awake: Boolean = true): ByteArray =
        write { writeInt(SESSION_MAGIC); id(id); writeByte(if (!open) 0 else if (awake) 1 else 2) }
    fun session(bytes: ByteArray): Pair<UUID, Boolean>? = read(bytes) {
        require(bytes.size == 21 && readInt() == SESSION_MAGIC)
        val id = id(); val state = readUnsignedByte(); require(state in 0..2)
        id to (state != 0)
    }
    /** For a valid open [session] message: false while the watch display sleeps. */
    fun sessionAwake(bytes: ByteArray): Boolean = session(bytes)?.second == true && bytes.last().toInt() != 2

    /** Session reply flag: the phone's diagnostic logging setting, which the watch follows. */
    const val SESSION_FLAG_DIAGNOSTICS = 1
    /** Session reply: the status byte, then a flags byte. */
    fun sessionReply(status: Int, diagnostics: Boolean): ByteArray {
        require(status in SESSION_READY..SESSION_BUSY)
        return byteArrayOf(status.toByte(), (if (diagnostics) SESSION_FLAG_DIAGNOSTICS else 0).toByte())
    }
    /** The status and diagnostics flag of a [sessionReply], or null when malformed. */
    fun sessionReply(bytes: ByteArray): Pair<Int, Boolean>? {
        if (bytes.size != 2) return null
        val status = bytes[0].toInt()
        val flags = bytes[1].toInt() and 0xff
        if (status !in SESSION_READY..SESSION_BUSY || flags and SESSION_FLAG_DIAGNOSTICS.inv() != 0) return null
        return status to (flags and SESSION_FLAG_DIAGNOSTICS != 0)
    }

    private fun DataOutputStream.base(base: BaseMap) { writeUTF(base.revision); view(base.view) }
    private fun DataInputStream.base() = BaseMap(readUTF(), view())
    fun encode(request: MapRequest): ByteArray { require(request.valid()); return write {
        writeInt(REQUEST_MAGIC); id(request.id); writeBoolean(request.follow); writeInt(request.width); writeInt(request.height)
        writeBoolean(request.view != null); request.view?.let { view(it) }
        writeBoolean(true); writeBoolean(request.cachedBase != null); request.cachedBase?.let { base(it) }
        writeBoolean(request.cachedOverlay != null); request.cachedOverlay?.let { writeLong(it) }
    } }
    fun request(bytes: ByteArray): MapRequest? = read(bytes) {
        require(readInt() == REQUEST_MAGIC)
        val id = id(); val follow = readBoolean(); val width = readInt(); val height = readInt()
        val view = if (readBoolean()) view() else null
        require(readBoolean())
        val cached = if (readBoolean()) base() else null
        val cachedOverlay = if (readBoolean()) readLong() else null
        MapRequest(id, view, follow, width, height, cached, cachedOverlay).also { require(it.valid()) }
    }
    fun encode(reply: MapReply): ByteArray { require(reply.valid()); return write {
        writeInt(REPLY_MAGIC); id(reply.id); writeInt(reply.status)
        if (reply.status == OK) {
            view(reply.view!!); writeLong(reply.capturedUtc); writeBoolean(reply.navigation); writeInt(reply.tracks); writeInt(reply.bytes)
            writeInt(reply.positionStatus); writeDouble(reply.positionLatitude); writeDouble(reply.positionLongitude); writeLong(reply.positionUtc); writeBoolean(reply.following)
            writeBoolean(reply.rulerLabel != null)
            reply.rulerLabel?.let { writeFloat(reply.rulerRatio); writeUTF(it) }
            writeBoolean(true); base(reply.base!!)
            writeBoolean(reply.overlay != null); reply.overlay?.let { writeLong(it) }
            reply.positionBearing?.let { writeInt(MOTION_MAGIC); writeFloat(it) }
            reply.clockText?.let { writeInt(CLOCK_MAGIC); writeUTF(it) }
        }
    } }
    fun reply(bytes: ByteArray): MapReply? = read(bytes) {
        require(readInt() == REPLY_MAGIC)
        val id = id(); val status = readInt()
        val reply = if (status == OK) {
            val view = view(); val captured = readLong(); val navigation = readBoolean(); val tracks = readInt(); val bytes = readInt()
            val positionStatus = readInt(); val latitude = readDouble(); val longitude = readDouble(); val positionUtc = readLong()
            val following = readBoolean()
            val hasRuler = readBoolean()
            val rulerRatio = if (hasRuler) readFloat() else 0f
            val rulerLabel = if (hasRuler) readUTF() else null
            MapReply(id, status, view, captured, navigation, tracks, bytes, positionStatus, latitude, longitude, positionUtc,
                following, rulerLabel = rulerLabel, rulerRatio = rulerRatio)
        } else MapReply(id, status)
        var result = if (status == OK) {
            require(readBoolean()); val base = base()
            reply.copy(base = base, overlay = if (readBoolean()) readLong() else null)
        } else reply
        if (status == OK && available() > 0) {
            when (val marker = readInt()) {
                MOTION_MAGIC -> {
                    result = result.copy(positionBearing = readFloat())
                    if (available() > 0) {
                        require(readInt() == CLOCK_MAGIC)
                        result = result.copy(clockText = readUTF())
                    }
                }
                CLOCK_MAGIC -> result = result.copy(clockText = readUTF())
                else -> error("Unknown reply extension $marker")
            }
        }
        result.also { require(it.valid()) }
    }
    fun inlineImage(header: ByteArray, image: ByteArray): ByteArray {
        val metadata = requireNotNull(reply(header))
        require(metadata.status == OK && metadata.bytes == image.size && 2 + header.size + image.size <= MAX_INLINE_BYTES)
        return write { writeShort(header.size); write(header); write(image) }
    }
    fun inlineHeader(packet: ByteArray): ByteArray? = try {
        require(packet.size in 3..MAX_INLINE_BYTES)
        DataInputStream(ByteArrayInputStream(packet)).use { input ->
            val length = input.readUnsignedShort()
            require(length in 1..MAX_HEADER && length <= input.available())
            ByteArray(length).also { header ->
                input.readFully(header)
                val metadata = requireNotNull(reply(header))
                require(metadata.status == OK && metadata.bytes == input.available())
            }
        }
    } catch (_: Exception) { null }
    fun imageSignature(bytes: ByteArray, compact: Boolean): Boolean {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return true
        return compact && bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) &&
            java.nio.ByteBuffer.wrap(bytes, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int == bytes.size - 8
    }
    /** An empty [overlay] means the overlay is unchanged: the receiver keeps the one it holds. */
    fun layerImage(base: ByteArray?, overlay: ByteArray): ByteArray {
        require(12L + (base?.size ?: 0) + overlay.size <= MAX_BYTES)
        return write { writeInt(LAYER_MAGIC); writeInt(base?.size ?: 0); writeInt(overlay.size); base?.let { write(it) }; write(overlay) }
    }
    /** Base and overlay images; either is null when omitted because the receiver already holds it. */
    fun layerImages(packet: ByteArray): Pair<ByteArray?, ByteArray?>? = try {
        require(packet.size in 12..MAX_BYTES)
        DataInputStream(ByteArrayInputStream(packet)).use { input ->
            require(input.readInt() == LAYER_MAGIC)
            val base = input.readInt(); val overlay = input.readInt()
            require(base in 0..MAX_BYTES && overlay in 0..MAX_BYTES && base.toLong() + overlay == input.available().toLong())
            val baseImage = if (base == 0) null else ByteArray(base).also { input.readFully(it); require(imageSignature(it, true)) }
            val overlayImage = if (overlay == 0) null else ByteArray(overlay).also { input.readFully(it); require(imageSignature(it, false)) }
            baseImage to overlayImage
        }
    } catch (_: Exception) { null }
    fun cancel(id: UUID): ByteArray = "$CANCEL_PREFIX$id".toByteArray(Charsets.US_ASCII)
    fun cancelId(bytes: ByteArray): UUID? = try {
        if (bytes.size != 41) null else String(bytes, Charsets.US_ASCII).let { text ->
            if (!text.startsWith(CANCEL_PREFIX)) null else UUID.fromString(text.substring(CANCEL_PREFIX.length))
                .takeIf { cancel(it).contentEquals(bytes) }
        }
    } catch (_: IllegalArgumentException) { null }
    fun channelId(path: String): UUID? = if (!path.startsWith(IMAGE_PATH)) null else try {
        val text = path.substring(IMAGE_PATH.length)
        UUID.fromString(text).takeIf { it.toString() == text }
    } catch (_: IllegalArgumentException) { null }
}

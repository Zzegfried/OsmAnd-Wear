package net.osmand.plus.plugins.wear.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** A bounded v1 extension for phone-owned widgets. It never enters the basemap cache. */
object WidgetProtocol {
    const val SNAPSHOT_PATH = "/map/v1/widgets"
    const val ACTION_PATH = "/map/v1/widgets/action"
    const val PROFILE_PATH = "/map/v1/route/profile"
    const val MAX_BYTES = 8_192
    const val OK = 0
    const val DENIED = 1
    const val UNAVAILABLE = 2
    const val INVALID = 3
    const val STALE = 4
    const val PHONE_REQUIRED = 5
    const val CURRENT_TIME = 1
    const val ALTITUDE = 2
    const val RECORDING_DISTANCE = 3
    const val TIME_TO_DESTINATION = 4
    const val DISTANCE_TO_DESTINATION = 5
    const val INACTIVE = 0
    const val RECORDING = 1
    const val PAUSED = 2
    const val SAVING = 3
    const val START = 1
    const val PAUSE = 2
    const val RESUME = 3
    const val FINISH = 4
    private const val REQUEST_MAGIC = 0x4f575731
    private const val SNAPSHOT_MAGIC = 0x4f575831
    private const val ACTION_MAGIC = 0x4f574131
    private const val RESULT_MAGIC = 0x4f575931
    private const val PROFILE_MAGIC = 0x4f575031

    data class Request(val id: UUID, val session: UUID)

    /** Metres along the route and altitude; no geographic coordinates. */
    data class Profile(val totalMeters: Int, val positionMeters: Int,
        val points: List<Pair<Int, Int>>, val minLabel: String, val maxLabel: String,
        val distanceLabel: String) {
        fun valid() = totalMeters in 1..20_000_000 && positionMeters in -1..totalMeters &&
            points.size in 2..128 && points.last().first > points.first().first &&
            points.all { it.first in 0..totalMeters && it.second in -500..10_000 } &&
            points.zipWithNext().all { (a, b) -> a.first <= b.first } &&
            safe(minLabel, 24, false) && safe(maxLabel, 24, false) &&
            safe(distanceLabel, 24, false)
    }
    data class ProfileReply(val id: UUID, val status: Int, val profile: Profile? = null) {
        fun valid() = status in OK..PHONE_REQUIRED && (profile == null ||
            (status == OK && profile.valid()))
    }

    data class Row(val kind: Int, val title: String, val value: String, val unit: String,
        val iconKey: String, val nightMode: Boolean, val labelColor: Int, val valueColor: Int,
        val accentColor: Int = 0, val boldText: Boolean = false, val sizeMode: String = "") {
        fun valid() = kind in CURRENT_TIME..DISTANCE_TO_DESTINATION &&
            safe(title, 64, false) && safe(value, 64, true) && safe(unit, 24, true) &&
            safe(iconKey, 64, true) && safe(sizeMode, 24, true)
    }

    data class Snapshot(val id: UUID, val status: Int, val capturedUtc: Long = 0,
        val rows: List<Row> = emptyList(), val recordingState: Int = INACTIVE,
        val startupDialog: Boolean = true, val recordingControlsAllowed: Boolean = true) {
        fun valid(): Boolean {
            if (status !in OK..PHONE_REQUIRED || recordingState !in INACTIVE..SAVING) return false
            if (status != OK) return capturedUtc == 0L && rows.isEmpty()
            return capturedUtc > 0L && rows.size <= 5 && rows.all { it.valid() } &&
                rows.map { it.kind }.distinct().size == rows.size
        }
    }

    data class Action(val id: UUID, val session: UUID, val command: Int,
        val expectedState: Int) {
        fun valid() = command in START..FINISH && expectedState in INACTIVE..PAUSED
    }

    data class Result(val id: UUID, val status: Int, val recordingState: Int = INACTIVE) {
        fun valid() = status in OK..PHONE_REQUIRED && recordingState in INACTIVE..SAVING
    }

    private fun safe(value: String, max: Int, empty: Boolean) = value.length <= max &&
        (empty || value.isNotEmpty()) && value.none { it.code < 0x20 || it.code == 0x7f }

    private fun DataOutputStream.id(value: UUID) { writeLong(value.mostSignificantBits); writeLong(value.leastSignificantBits) }
    private fun DataInputStream.id() = UUID(readLong(), readLong())
    private fun write(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray().also { require(it.size <= MAX_BYTES) }
    private fun <T> read(bytes: ByteArray, block: DataInputStream.() -> T): T? = try {
        require(bytes.size in 1..MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input -> input.block().also { require(input.available() == 0) } }
    } catch (_: Exception) { null }

    fun encode(request: Request) = write { writeInt(REQUEST_MAGIC); id(request.id); id(request.session) }
    fun request(bytes: ByteArray): Request? = read(bytes) {
        require(readInt() == REQUEST_MAGIC); Request(id(), id())
    }

    fun encode(reply: ProfileReply): ByteArray {
        require(reply.valid())
        return write {
            writeInt(PROFILE_MAGIC); id(reply.id); writeByte(reply.status)
            writeBoolean(reply.profile != null)
            reply.profile?.let { p ->
                writeInt(p.totalMeters); writeInt(p.positionMeters); writeByte(p.points.size)
                p.points.forEach { (distance, height) -> writeInt(distance); writeInt(height) }
                writeUTF(p.minLabel); writeUTF(p.maxLabel); writeUTF(p.distanceLabel)
            }
        }
    }
    fun profileReply(bytes: ByteArray): ProfileReply? = read(bytes) {
        require(readInt() == PROFILE_MAGIC)
        val id = id(); val status = readUnsignedByte()
        val profile = if (readBoolean()) {
            val total = readInt(); val position = readInt(); val size = readUnsignedByte()
            require(size in 2..128)
            Profile(total, position, (0 until size).map { readInt() to readInt() },
                readUTF(), readUTF(), readUTF())
        } else null
        ProfileReply(id, status, profile).also { require(it.valid()) }
    }

    fun encode(snapshot: Snapshot): ByteArray {
        require(snapshot.valid())
        return write {
            writeInt(SNAPSHOT_MAGIC); id(snapshot.id); writeByte(snapshot.status)
            if (snapshot.status == OK) {
                writeLong(snapshot.capturedUtc); writeByte(snapshot.recordingState)
                writeBoolean(snapshot.startupDialog); writeByte(snapshot.rows.size)
                for (row in snapshot.rows) {
                    writeByte(row.kind); writeUTF(row.title); writeUTF(row.value); writeUTF(row.unit)
                    writeUTF(row.iconKey); writeBoolean(row.nightMode)
                    writeInt(row.labelColor); writeInt(row.valueColor); writeInt(row.accentColor)
                    writeBoolean(row.boldText); writeUTF(row.sizeMode)
                }
                writeBoolean(snapshot.recordingControlsAllowed)
            }
        }
    }
    fun snapshot(bytes: ByteArray): Snapshot? = read(bytes) {
        require(readInt() == SNAPSHOT_MAGIC)
        val id = id(); val status = readUnsignedByte()
        val snapshot = if (status == OK) {
            val captured = readLong(); val recording = readUnsignedByte(); val startup = readBoolean()
            val count = readUnsignedByte(); require(count <= 5)
            val rows = (0 until count).map {
                Row(readUnsignedByte(), readUTF(), readUTF(), readUTF(), readUTF(), readBoolean(),
                    readInt(), readInt(), readInt(), readBoolean(), readUTF())
            }
            Snapshot(id, status, captured, rows, recording, startup, readBoolean())
        } else Snapshot(id, status)
        snapshot.also { require(it.valid()) }
    }

    fun encode(action: Action): ByteArray {
        require(action.valid())
        return write { writeInt(ACTION_MAGIC); id(action.id); id(action.session)
            writeByte(action.command); writeByte(action.expectedState) }
    }
    fun action(bytes: ByteArray): Action? = read(bytes) {
        require(readInt() == ACTION_MAGIC)
        Action(id(), id(), readUnsignedByte(), readUnsignedByte()).also { require(it.valid()) }
    }
    fun encode(result: Result): ByteArray {
        require(result.valid())
        return write { writeInt(RESULT_MAGIC); id(result.id); writeByte(result.status); writeByte(result.recordingState) }
    }
    fun result(bytes: ByteArray): Result? = read(bytes) {
        require(readInt() == RESULT_MAGIC)
        Result(id(), readUnsignedByte(), readUnsignedByte()).also { require(it.valid()) }
    }
}

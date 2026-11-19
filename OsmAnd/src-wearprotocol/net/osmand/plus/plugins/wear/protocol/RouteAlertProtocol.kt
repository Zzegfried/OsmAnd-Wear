package net.osmand.plus.plugins.wear.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** A session-bound voice-prompt event. No route or location data crosses the wire. */
object RouteAlertProtocol {
    const val PATH = "/map/v1/alert"
    const val ROUTE_RECALCULATED = 1
    const val OFF_ROUTE = 2
    private const val MAGIC = 0x4f574e31
    private const val SIZE = 37

    data class Alert(val id: UUID, val session: UUID, val kind: Int) {
        fun valid() = kind == ROUTE_RECALCULATED || kind == OFF_ROUTE
    }

    fun voiceKind(commands: List<String>, played: List<String>, muted: Boolean,
                  recalculateEnabled: Boolean, deviationEnabled: Boolean): Int? {
        if (muted || played.isEmpty()) return null
        return when {
            recalculateEnabled && "route_recalc" in commands -> ROUTE_RECALCULATED
            deviationEnabled && "off_route" in commands -> OFF_ROUTE
            else -> null
        }
    }

    fun encode(alert: Alert): ByteArray {
        require(alert.valid())
        return ByteArrayOutputStream(SIZE).also { output ->
            DataOutputStream(output).use {
                it.writeInt(MAGIC)
                it.writeLong(alert.id.mostSignificantBits); it.writeLong(alert.id.leastSignificantBits)
                it.writeLong(alert.session.mostSignificantBits); it.writeLong(alert.session.leastSignificantBits)
                it.writeByte(alert.kind)
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): Alert? = try {
        require(bytes.size == SIZE)
        DataInputStream(ByteArrayInputStream(bytes)).use {
            require(it.readInt() == MAGIC)
            Alert(UUID(it.readLong(), it.readLong()), UUID(it.readLong(), it.readLong()), it.readUnsignedByte())
                .also { alert -> require(alert.valid() && it.available() == 0) }
        }
    } catch (_: Exception) { null }
}

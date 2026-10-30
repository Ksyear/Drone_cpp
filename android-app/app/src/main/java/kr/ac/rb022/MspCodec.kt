package kr.ac.rb022

/** MultiWii serial protocol as implemented by the AirCopter firmware: `$M<` len cmd payload xor(len,cmd,payload). */
object Msp {
    const val STATUS = 101
    const val RC = 105
    const val RC_TUNING = 111
    const val BOX = 113
    const val RC_COMPACT = 150
    const val ARM = 151
    const val DISARM = 152
    /** One angle-trim step each, saved to EEPROM by the firmware (pitch +/-, roll -/+). */
    const val TRIM_UP = 153
    const val TRIM_DOWN = 154
    const val TRIM_LEFT = 155
    const val TRIM_RIGHT = 156
    /** Optical Plus readings from this project's firmware (firmware/OpticalSensors.hpp); older builds answer `$M!`. */
    const val OPTICAL = 198
    const val AIR = 199
    /** Level reference from the current pose, saved to EEPROM; the firmware ignores it while ARMED. */
    const val ACC_CALIBRATION = 205
    /** 199 "version" of this project's protective firmware (firmware/ArmGuard.hpp): 232 adds the height hold, 233 the position hold. */
    const val CONTROL_FIRMWARE_ID = 231
    const val HOLD_FIRMWARE_ID = 232
    const val POSITION_FIRMWARE_ID = 233

    fun request(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(command in 0..255 && payload.size <= 64)
        var checksum = payload.size xor command
        payload.forEach { checksum = checksum xor (it.toInt() and 0xff) }
        return byteArrayOf('$'.code.toByte(), 'M'.code.toByte(), '<'.code.toByte(), payload.size.toByte(), command.toByte()) +
            payload + checksum.toByte()
    }

    fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
}

class MspFrame(val command: Int, val payload: ByteArray, val error: Boolean) {
    private fun u8(i: Int) = payload[i].toInt() and 0xff
    fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    fun s16(i: Int) = u16(i).toShort().toInt()
    fun s8(i: Int) = payload[i].toInt()
    fun byte(i: Int) = u8(i)
}

/** Stream parser for `$M>` replies and `$M!` errors split across BLE notifications. */
class MspParser {
    private var state = 0
    private var size = 0
    private var command = 0
    private var checksum = 0
    private var error = false
    private val buffer = ByteArray(64)
    private var offset = 0
    var checksumErrors = 0; private set

    fun feed(bytes: ByteArray): List<MspFrame> {
        val out = ArrayList<MspFrame>()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            when (state) {
                0 -> state = if (c == '$'.code) 1 else 0
                1 -> state = if (c == 'M'.code) 2 else if (c == '$'.code) 1 else 0
                2 -> { error = c == '!'.code; state = if (c == '>'.code || error) 3 else 0 }
                3 -> if (c > 64) state = 0 else { size = c; checksum = c; offset = 0; state = 4 }
                4 -> { command = c; checksum = checksum xor c; state = if (size == 0) 6 else 5 }
                5 -> { buffer[offset++] = b; checksum = checksum xor c; if (offset == size) state = 6 }
                6 -> {
                    if (checksum == c) out += MspFrame(command, buffer.copyOf(size), error) else checksumErrors++
                    state = 0
                }
            }
        }
        return out
    }
}

/** MSP 199 reply. alt/altHold/vbat are raw firmware units; field 5 is AltHold, not heading. */
data class AirStatus(val flightState: Int, val firmwareId: Int, val estAlt: Int, val rollDeci: Int, val pitchDeci: Int,
                     val altHold: Int, val vbatRaw: Int, val trimPitch: Int, val trimRoll: Int) {
    val armed get() = flightState and 0x80 != 0
    val controlFirmware get() = firmwareId in Msp.CONTROL_FIRMWARE_ID..Msp.POSITION_FIRMWARE_ID
    /** From 232 the throttle byte is a target height while AUX2 is set; 231 would take it as throttle. */
    val holdFirmware get() = firmwareId == Msp.HOLD_FIRMWARE_ID || firmwareId == Msp.POSITION_FIRMWARE_ID
    val positionFirmware get() = firmwareId == Msp.POSITION_FIRMWARE_ID
    /**
     * Estimate only: firmware vbat = 16*ADC/VBATSCALE(64) = ADC/4 against a 5 V reference, which matches the kit's
     * own thresholds 179/184/189 = 3.5/3.6/3.7 V. Meaningless on USB power alone (pin floats near 35).
     */
    val batteryVolts get() = vbatRaw * 20f / 1023f

    companion object {
        fun decode(f: MspFrame): AirStatus? = if (f.command != Msp.AIR || f.error || f.payload.size != 14) null else
            AirStatus(f.byte(0), f.u16(1), f.s16(3), f.s16(5), f.s16(7), f.s16(9), f.byte(11), f.s8(12), f.s8(13))
    }
}

/**
 * MSP 198: status bits (1 range sensor found, 2 flow sensor found, 4 range valid), height mm, flow counts, SQUAL,
 * and from firmware 232 the height hold output in rcData us (0 when not holding), then odometry since ARM (cm,
 * body frame forward/right, from the flow sensor) and whether the position hold is acting.
 */
data class OpticalStatus(val status: Int, val heightMm: Int, val flowX: Int, val flowY: Int, val quality: Int, val holdUs: Int = 0,
                         val forwardCm: Int = 0, val rightCm: Int = 0, val positionActive: Boolean = false) {
    val rangeFound get() = status and 1 != 0
    val flowFound get() = status and 2 != 0
    val rangeValid get() = status and 4 != 0

    companion object {
        fun decode(f: MspFrame) = if (f.command != Msp.OPTICAL || f.error || f.payload.size !in setOf(8, 10, 15)) null else
            OpticalStatus(f.byte(0), f.u16(1), f.s16(3), f.s16(5), f.byte(7), if (f.payload.size >= 10) f.u16(8) else 0,
                if (f.payload.size == 15) f.s16(10) else 0, if (f.payload.size == 15) f.s16(12) else 0, f.payload.size == 15 && f.byte(14) != 0)
    }
}

data class RcTuning(val rcRate: Int, val rcExpo: Int, val rollPitchRate: Int, val yawRate: Int, val dynThrPid: Int, val thrMid: Int, val thrExpo: Int) {
    companion object {
        fun decode(f: MspFrame) = if (f.command != Msp.RC_TUNING || f.error || f.payload.size != 7) null else
            RcTuning(f.byte(0), f.byte(1), f.byte(2), f.byte(3), f.byte(4), f.byte(5), f.byte(6))
    }
}

/** MSP 113 activate bitmasks (uint16 per box), MSP 105 rcData (uint16 x8), MSP 101 status. */
fun decodeUint16List(f: MspFrame, command: Int): List<Int>? =
    if (f.command != command || f.error || f.payload.size % 2 != 0) null else List(f.payload.size / 2) { f.u16(it * 2) }

data class BoardStatus(val cycleTimeUs: Int, val i2cErrors: Int, val sensors: Int) {
    companion object {
        fun decode(f: MspFrame) = if (f.command != Msp.STATUS || f.error || f.payload.size < 6) null else
            BoardStatus(f.u16(0), f.s16(2), f.u16(4))
    }
}

package kr.ac.rb022

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Meters. Frame is carried by the owner (Q = Quest OpenXR space, W = drone operation space). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun horizontal() = sqrt(x * x + y * y)
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun of(value: Any?): Vec3 {
            val list = value as? List<*> ?: throw IllegalArgumentException("bad-vector")
            require(list.size == 3 && list.all { it is Double }) { "bad-vector" }
            val v = Vec3(list[0] as Double, list[1] as Double, list[2] as Double)
            require(v.finite() && abs(v.x) <= 100 && abs(v.y) <= 100 && abs(v.z) <= 100) { "vector-out-of-range" }
            return v
        }
    }
}

/**
 * p_W = Rz(yaw) * M * p_Q + t.
 * Q: OpenXR/WebXR stage space, right-handed, +Y up, -Z initial forward (Unity clients send (x, y, -z)).
 * W: right-handed, +Z up, origin on the floor at the alignment origin. Both are gravity aligned, so only
 * yaw and translation are estimated; M maps Q(x, y, z) to (x, -z, y).
 */
data class Alignment(val id: String, val yaw: Double, val t: Vec3, val rms: Double) {
    fun toW(q: Vec3): Vec3 {
        val mx = q.x; val my = -q.z
        return Vec3(cos(yaw) * mx - sin(yaw) * my + t.x, sin(yaw) * mx + cos(yaw) * my + t.y, q.y + t.z)
    }

    fun toQ(w: Vec3): Vec3 {
        val dx = w.x - t.x; val dy = w.y - t.y
        val mx = cos(yaw) * dx + sin(yaw) * dy
        val my = -sin(yaw) * dx + cos(yaw) * dy
        return Vec3(mx, w.z - t.z, -my)
    }

    companion object {
        const val MAX_RMS = 0.05
        const val MIN_SPREAD = 0.3

        /** Least-squares yaw/translation from point pairs measured in both frames (e.g. floor markers touched in Quest). */
        fun estimate(id: String, pairs: List<Pair<Vec3, Vec3>>): Alignment {
            require(pairs.size in 2..8) { "alignment-needs-2-to-8-pairs" }
            require(pairs.all { it.first.finite() && it.second.finite() }) { "non-finite" }
            val m = pairs.map { Vec3(it.first.x, -it.first.z, it.first.y) }
            val w = pairs.map { it.second }
            val spread = m.maxOf { a -> m.maxOf { b -> (a - b).horizontal() } }
            require(spread >= MIN_SPREAD) { "alignment-points-too-close" }
            val mc = m.reduce(Vec3::plus) * (1.0 / m.size)
            val wc = w.reduce(Vec3::plus) * (1.0 / w.size)
            var sCross = 0.0; var sDot = 0.0
            for (k in m.indices) {
                val a = m[k] - mc; val b = w[k] - wc
                sCross += a.x * b.y - a.y * b.x
                sDot += a.x * b.x + a.y * b.y
            }
            val yaw = atan2(sCross, sDot)
            val rotated = Vec3(cos(yaw) * mc.x - sin(yaw) * mc.y, sin(yaw) * mc.x + cos(yaw) * mc.y, mc.z)
            val draft = Alignment(id, yaw, wc - rotated, 0.0)
            val rms = sqrt(pairs.sumOf { (q, wp) -> (draft.toW(q) - wp).norm().let { it * it } } / pairs.size)
            require(rms <= MAX_RMS) { "alignment-residual-too-large" }
            return draft.copy(rms = rms)
        }
    }
}

/** Axis-aligned operating volume in W. Simulation defaults only; real limits need measured room data. */
data class Geofence(val min: Vec3 = Vec3(-2.0, -2.0, 0.0), val max: Vec3 = Vec3(2.0, 2.0, 1.5)) {
    fun contains(p: Vec3) = p.x in min.x..max.x && p.y in min.y..max.y && p.z in min.z..max.z
}

/**
 * Compact RC150 stick bytes. Firmware: rcData = 1000 + 4*byte; with EEPROM defaults rcRate8=90, expo=0,
 * ANGLE/HORIZON low-stick setpoint is 1.8*(rcData-1500) decidegrees, i.e. 0.72 deg per byte (EEPROM unverified).
 */
data class Rc150(val roll: Int, val pitch: Int, val yaw: Int, val throttle: Int, val aux: Int) {
    fun frame(): ByteArray {
        val payload = intArrayOf(roll, pitch, yaw, throttle, aux)
        var checksum = 5 xor 150
        payload.forEach { checksum = checksum xor it }
        return byteArrayOf('$'.code.toByte(), 'M'.code.toByte(), '<'.code.toByte(), 5, 150.toByte()) +
            ByteArray(5) { payload[it].toByte() } + checksum.toByte()
    }

    companion object {
        const val DEGREES_PER_BYTE = 0.72

        fun angleByte(degrees: Double): Int = (125 + degrees / DEGREES_PER_BYTE).roundToInt().coerceIn(0, 250)
        fun throttleByte(normalized: Double): Int = (normalized.coerceIn(0.0, 1.0) * 250).roundToInt()

        /** Forward/right accelerations (m/s^2, body axes) to level-referenced tilt bytes, clamped to maxTiltDeg. */
        fun fromAcceleration(forward: Double, right: Double, throttle: Double, maxTiltDeg: Double): Rc150 {
            val pitch = Math.toDegrees(atan(forward / 9.81)).coerceIn(-maxTiltDeg, maxTiltDeg)
            val roll = Math.toDegrees(atan(right / 9.81)).coerceIn(-maxTiltDeg, maxTiltDeg)
            return Rc150(angleByte(roll), angleByte(pitch), 125, throttleByte(throttle), AUX_DEFAULT)
        }

        /** AUX3/AUX4 mid (0x05); default EEPROM enables HORIZON for any AUX1 position. */
        const val AUX_DEFAULT = 0b0000_0101
        /** AUX2 mid on top: firmware 232 height hold, the throttle byte is then the target height in cm. */
        const val AUX_HOLD = AUX_DEFAULT or 0b0001_0000
        /** AUX3 high on top of the height hold: firmware position hold on the floor flow (PositionHold.hpp). */
        const val AUX_POSITION = (AUX_HOLD and 0b1111_0011) or 0b0000_1000
    }
}

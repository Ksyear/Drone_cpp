package kr.ac.rb022

data class ControlPreview(
    val throttle: Float = 0f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
) {
    fun right(x: Float, y: Float) = copy(
        roll = x.coerceIn(-1f, 1f),
        pitch = (-y).coerceIn(-1f, 1f),
    )

    /**
     * Compact RC150 bytes (firmware: rcData = 1000 + 4*byte). Throttle is clipped (not scaled) at [throttleLimit].
     * Axes stay within 26..224 so the legacy stick commands (arm, sensor calibration, EEPROM trims at stick
     * extremes) can never trigger from the app.
     */
    fun rc(throttleLimit: Float, axisLimit: Float): Rc150 {
        fun axis(v: Float) = 125 + Math.round(v.coerceIn(-1f, 1f) * axisLimit.coerceIn(0f, 1f) * 99)
        val thr = Math.round(minOf(throttle.coerceIn(0f, 1f), throttleLimit.coerceIn(0f, 1f)) * 250)
        return Rc150(axis(roll), axis(pitch), axis(yaw), thr, Rc150.AUX_DEFAULT)
    }

    companion object {
        /** Yaw while a rotate button is held, before the axis limit. */
        const val YAW_BUTTON = 0.5f
    }
}

/**
 * Height-style throttle for a craft with no altitude sensor, so it is open loop and only as good as [hover]
 * (the output that just holds the craft up). Released = hover. A press pushes hover ± [PULSE] for the pulse time
 * to start moving, then hover ± [DRAG] while held; the release pushes the opposite pulse for as long as the start
 * pulse ran, so the speed is cancelled instead of carried to the ceiling. Every press nudges hover 1% its way; the
 * first climb after ARM and any long descent also walk hover (finding lift-off, and making ▼ always win).
 */
class AltitudeThrottle(hover: Float) {
    var hover = hover.coerceIn(MIN_HOVER, MAX_HOVER); private set
    var airborne = false; private set
    private var dir = 0
    private var pressedAt = 0L
    private var pulseMs = 0L
    private var takeoff = false
    private var brakeDir = 0
    private var brakeUntil = 0L

    /** [armed] gates lift-off: before ARM the output must stay 0 (the app and MCU ARM checks need throttle 0). */
    fun press(direction: Int, now: Long, armed: Boolean, climbPulseMs: Long) {
        if (!airborne && (direction < 0 || !armed)) return
        if (!airborne) { airborne = true; takeoff = true }
        hover = (hover + direction * NUDGE).coerceIn(MIN_HOVER, MAX_HOVER)
        dir = direction; pressedAt = now; brakeDir = 0
        pulseMs = if (direction > 0) climbPulseMs else (climbPulseMs * DESCENT_RATIO).toLong()
    }

    fun release(now: Long) {
        if (dir == 0) return
        hover = walked(now)
        brakeDir = -dir; brakeUntil = now + minOf(now - pressedAt, pulseMs)
        dir = 0; takeoff = false
    }

    fun output(now: Long): Float {
        if (!airborne) return 0f
        val out = when {
            dir != 0 -> walked(now) + dir * (if (now - pressedAt < pulseMs) PULSE else DRAG)
            brakeDir != 0 && now < brakeUntil -> hover + brakeDir * PULSE
            else -> hover
        }
        return out.coerceIn(0f, 1f)
    }

    fun land() { airborne = false; takeoff = false; dir = 0; brakeDir = 0 }

    private fun walked(now: Long): Float {
        val rate = when { dir > 0 && takeoff -> TAKEOFF_RATE; dir < 0 -> DESCENT_WALK; else -> 0f }
        val heldS = (now - pressedAt - pulseMs).coerceAtLeast(0) / 1000f
        return (hover + dir * rate * heldS).coerceIn(MIN_HOVER, MAX_HOVER)
    }

    companion object {
        const val DEFAULT_HOVER = 0.5f
        const val MIN_HOVER = 0.2f
        const val MAX_HOVER = 0.9f
        const val PULSE = 0.10f
        const val DRAG = 0.02f
        const val NUDGE = 0.01f
        const val TAKEOFF_RATE = 0.04f   // per second while the first ▲ is held
        const val DESCENT_WALK = 0.05f   // per second while ▼ is held
        const val DESCENT_RATIO = 0.6f
        /** Speed slider 0..1 -> start/stop pulse 120..500 ms; a longer pulse gives a faster climb. */
        fun pulseMs(speed: Float) = 120L + Math.round(380 * speed.coerceIn(0f, 1f))
    }
}

package kr.ac.rb022

data class ControlPreview(
    val throttle: Float = 0f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
) {
    fun left(x: Float, y: Float) = copy(
        yaw = x.coerceIn(-1f, 1f),
        throttle = ((1f - y) / 2f).coerceIn(0f, 1f),
    )
    fun right(x: Float, y: Float) = copy(
        roll = x.coerceIn(-1f, 1f),
        pitch = (-y).coerceIn(-1f, 1f),
    )

    /**
     * Compact RC150 bytes (firmware: rcData = 1000 + 4*byte). Axes stay within 26..224 so the legacy stick
     * commands (arm, sensor calibration, EEPROM trims at stick extremes) can never trigger from the app.
     */
    fun rc(throttleLimit: Float, axisLimit: Float): Rc150 {
        fun axis(v: Float) = 125 + Math.round(v.coerceIn(-1f, 1f) * axisLimit.coerceIn(0f, 1f) * 99)
        val thr = Math.round(throttle.coerceIn(0f, 1f) * throttleLimit.coerceIn(0f, 1f) * 250)
        return Rc150(axis(roll), axis(pitch), axis(yaw), thr, Rc150.AUX_DEFAULT)
    }
}

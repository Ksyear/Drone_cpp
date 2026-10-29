package kr.ac.rb022

data class ControlPreview(
    val throttle: Float = 0f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
) {
    // shortcut: hardware and firmware safety are unverified; add a reviewed transport only after bench validation.
    val transmissionEnabled: Boolean get() = false

    fun left(x: Float, y: Float) = copy(
        yaw = x.coerceIn(-1f, 1f),
        throttle = ((1f - y) / 2f).coerceIn(0f, 1f),
    )
    fun right(x: Float, y: Float) = copy(
        roll = x.coerceIn(-1f, 1f),
        pitch = (-y).coerceIn(-1f, 1f),
    )
}

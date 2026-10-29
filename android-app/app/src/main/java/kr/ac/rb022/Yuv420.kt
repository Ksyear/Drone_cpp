package kr.ac.rb022

import java.nio.ByteBuffer

/** Write encoder planes with their actual strides, including interleaved U/V layouts. */
fun writeYuv420(pixels: IntArray, width: Int, height: Int,
                buffers: List<ByteBuffer>, rowStrides: List<Int>, pixelStrides: List<Int>) {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 && pixels.size == width * height)
    require(buffers.size == 3 && rowStrides.size == 3 && pixelStrides.size == 3)
    val starts = buffers.map { it.position() }
    for (y in 0 until height) for (x in 0 until width) {
        val pixel = pixels[y * width + x]
        val r = pixel shr 16 and 255
        val g = pixel shr 8 and 255
        val b = pixel and 255
        val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        buffers[0].put(starts[0] + y * rowStrides[0] + x * pixelStrides[0], luma.coerceIn(0, 255).toByte())
        if (y % 2 == 0 && x % 2 == 0) {
            var rr = 0; var gg = 0; var bb = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = pixels[(y + dy) * width + x + dx]
                rr += p shr 16 and 255; gg += p shr 8 and 255; bb += p and 255
            }
            rr /= 4; gg /= 4; bb /= 4
            val u = (((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128).coerceIn(0, 255)
            val v = (((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128).coerceIn(0, 255)
            buffers[1].put(starts[1] + y / 2 * rowStrides[1] + x / 2 * pixelStrides[1], u.toByte())
            buffers[2].put(starts[2] + y / 2 * rowStrides[2] + x / 2 * pixelStrides[2], v.toByte())
        }
    }
}

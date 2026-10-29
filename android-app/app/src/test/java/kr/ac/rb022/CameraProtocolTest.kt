package kr.ac.rb022

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.nio.ByteBuffer

class CameraProtocolTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
    private fun frame(length: String = jpeg.size.toString()) =
        "\r\n--test\r\nContent-Type: image/jpeg\r\nContent-Length: $length\r\n\r\n".toByteArray() + jpeg

    @Test fun fragmentedMultipartRecoversTwoFramesAndStopsAtBoundary() {
        val input = object : FilterInputStream(ByteArrayInputStream(frame() + frame() + "\r\n--test--\r\n".toByteArray())) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(1, length))
        }
        val reader = MjpegReader(input, "multipart/x-mixed-replace; boundary=\"test\"")
        assertArrayEquals(jpeg, reader.nextFrame())
        assertArrayEquals(jpeg, reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test fun rejectsHugeNegativeTruncatedAndNonJpegFrames() {
        for (data in listOf(frame("99999999"), frame("-1"), frame().dropLast(2).toByteArray(),
            frame().copyOf().apply { this[lastIndex] = 0 })) {
            assertThrows(Exception::class.java) { MjpegReader(ByteArrayInputStream(data), "multipart/x-mixed-replace;boundary=test").nextFrame() }
        }
        assertThrows(Exception::class.java) { MjpegReader(ByteArrayInputStream(byteArrayOf()), "text/html") }
    }

    @Test fun cameraAddressRejectsCredentialsAndNonNetworkSchemes() {
        assertEquals(81, cameraUrl(" http://192.168.1.5:81/stream ").port)
        for (url in listOf("file:///etc/passwd", "http://user:pass@camera/stream", "http:///stream", "http://camera:99999/stream")) {
            assertThrows(Exception::class.java) { cameraUrl(url) }
        }
    }

    @Test fun yuvWritesLimitedRangeWithOffsetsPaddingAndInterleavedChroma() {
        val y = ByteBuffer.allocate(20).apply { position(2) }
        val uv = ByteBuffer.allocate(8)
        val u = uv.duplicate().apply { position(1) }
        val v = uv.duplicate().apply { position(2) }
        writeYuv420(IntArray(4) { 0xff0000 }, 2, 2, listOf(y, u, v), listOf(4, 4, 4), listOf(1, 2, 2))
        assertEquals(82, y.get(2).toInt() and 255)
        assertEquals(82, y.get(6).toInt() and 255)
        assertEquals(90, uv.get(1).toInt() and 255)
        assertEquals(240, uv.get(2).toInt() and 255)
        assertEquals(0, y.get(4).toInt())
        val black = List(3) { ByteBuffer.allocate(4) }
        writeYuv420(IntArray(4), 2, 2, black, listOf(2, 1, 1), listOf(1, 1, 1))
        assertEquals(16, black[0].get(0).toInt())
        assertEquals(128, black[1].get(0).toInt() and 255)
    }
}

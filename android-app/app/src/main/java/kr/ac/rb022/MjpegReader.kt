package kr.ac.rb022

import java.io.EOFException
import java.io.InputStream
import java.net.URI

fun cameraUrl(text: String): URI {
    val uri = URI(text.trim())
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.fragment == null && uri.port in -1..65535) {
        "http://카메라IP:81/stream 형식의 주소를 입력하세요"
    }
    return uri
}

/** Espressif CameraWebServer multipart JPEG. Bounds apply before allocation. */
class MjpegReader(private val input: InputStream, contentType: String) {
    private val boundary: String

    init {
        require(contentType.substringBefore(';').trim().equals("multipart/x-mixed-replace", true)) {
            "MJPEG 스트림 주소가 아닙니다. /stream 주소를 확인하세요"
        }
        val value = contentType.split(';').drop(1).firstOrNull {
            it.substringBefore('=').trim().equals("boundary", true)
        }?.substringAfter('=')?.trim()?.trim('"')
        require(value != null && value.length in 1..200 && value.all { it.code in 33..126 }) {
            "MJPEG boundary가 올바르지 않습니다"
        }
        boundary = "--$value"
    }

    fun nextFrame(): ByteArray? {
        var found = false
        repeat(64) {
            if (!found) {
                val line = line()
                if (line == "$boundary--") return null
                found = line == boundary
            }
        }
        require(found) { "MJPEG 프레임 경계를 찾지 못했습니다" }
        var length: Int? = null
        var jpeg = false
        var ended = false
        repeat(16) {
            if (!ended) {
                val header = line()
                if (header.isEmpty()) ended = true
                else {
                    val name = header.substringBefore(':').trim()
                    val value = header.substringAfter(':', "").trim()
                    when {
                        name.equals("Content-Length", true) -> {
                            require(length == null) { "중복 Content-Length" }
                            length = value.toIntOrNull()
                            require(length != null && length!! in 4..2_097_152) { "JPEG 크기 제한 초과 또는 잘못된 길이" }
                        }
                        name.equals("Content-Type", true) -> jpeg = value.equals("image/jpeg", true)
                    }
                }
            }
        }
        require(ended && jpeg && length != null) { "MJPEG 헤더가 올바르지 않습니다" }
        val bytes = ByteArray(length!!)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw EOFException("JPEG 수신이 중단됐습니다")
            if (count == 0) throw EOFException("빈 JPEG 읽기")
            offset += count
        }
        require(bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "손상된 JPEG 프레임" }
        return bytes
    }

    private fun line(): String {
        val out = StringBuilder()
        repeat(1024) {
            val value = input.read()
            if (value < 0) throw EOFException("MJPEG 연결 종료")
            if (value == 10) return out.toString().removeSuffix("\r")
            out.append(value.toChar())
        }
        throw IllegalArgumentException("MJPEG 헤더 줄이 너무 깁니다")
    }
}

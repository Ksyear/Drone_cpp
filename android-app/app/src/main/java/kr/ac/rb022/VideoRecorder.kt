package kr.ac.rb022

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.IOException

/** Single camera worker owns the encoder and muxer; no audio or flight overlays. */
class VideoRecorder(private val resolver: ContentResolver, sourceWidth: Int, sourceHeight: Int) {
    private val scale = minOf(1.0, 640.0 / sourceWidth, 480.0 / sourceHeight)
    private val width = (sourceWidth * scale).toInt().let { it - it % 2 }.coerceAtLeast(2)
    private val height = (sourceHeight * scale).toInt().let { it - it % 2 }.coerceAtLeast(2)
    private val pixels = IntArray(width * height)
    private var uri: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxing = false
    private var track = -1
    private var frames = 0
    private val origin = System.nanoTime()
    private var lastPts = -1L
    private var lastFrameNanos = 0L
    private var closed = false

    init {
        try {
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "RB022_${System.currentTimeMillis()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/RB022")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw IOException("녹화 파일을 만들지 못했습니다")
            descriptor = resolver.openFileDescriptor(uri!!, "rw") ?: throw IOException("녹화 파일을 열지 못했습니다")
            muxer = MediaMuxer(descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec = MediaCodec.createEncoderByType("video/avc")
            codec!!.configure(MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec!!.start()
        } catch (e: Exception) {
            abort()
            throw e
        }
    }

    fun frame(bitmap: Bitmap, now: Long = System.nanoTime()) {
        check(!closed)
        if (lastFrameNanos != 0L && now - lastFrameNanos < 66_666_667) return
        drain(false)
        val encoder = codec!!
        val index = encoder.dequeueInputBuffer(10_000)
        if (index < 0) return
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            val image = encoder.getInputImage(index) ?: throw IOException("이 기기의 YUV 녹화 입력을 열 수 없습니다")
            image.use {
                writeYuv420(pixels, width, height, it.planes.map { p -> p.buffer },
                    it.planes.map { p -> p.rowStride }, it.planes.map { p -> p.pixelStride })
            }
            lastPts = maxOf(lastPts + 1, (now - origin) / 1000)
            encoder.queueInputBuffer(index, 0, width * height * 3 / 2, lastPts, 0)
            lastFrameNanos = now
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        drain(false)
    }

    fun finish(): Uri {
        check(!closed)
        try {
            val encoder = codec!!
            val deadline = System.nanoTime() + 3_000_000_000L
            var index = encoder.dequeueInputBuffer(10_000)
            while (index < 0 && System.nanoTime() < deadline) {
                drain(false)
                index = encoder.dequeueInputBuffer(10_000)
            }
            if (index < 0) throw IOException("녹화 종료 입력 시간 초과")
            encoder.queueInputBuffer(index, 0, 0, lastPts.coerceAtLeast(0) + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            if (!muxing || frames == 0) throw IOException("저장할 영상 프레임이 없습니다")
            muxer!!.stop()
            muxing = false
            release()
            val saved = uri!!
            check(resolver.update(saved, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            closed = true
            return saved
        } catch (e: Exception) {
            abort()
            throw e
        }
    }

    private fun drain(end: Boolean) {
        val encoder = codec!!
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    if (System.nanoTime() >= deadline) throw IOException("MP4 마무리 시간 초과")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxing)
                    track = muxer!!.addTrack(encoder.outputFormat)
                    muxer!!.start()
                    muxing = true
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxing)
                            val bytes = encoder.getOutputBuffer(index) ?: throw IOException("영상 출력 버퍼 없음")
                            bytes.position(info.offset)
                            bytes.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track, bytes, info)
                            frames++
                        }
                    } finally { encoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun abort() {
        if (closed) return
        release()
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        closed = true
    }

    private fun release() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        muxer?.let { runCatching { it.release() } }
        muxer = null
        descriptor?.let { runCatching { it.close() } }
        descriptor = null
    }
}

package kr.ac.rb022

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraClient(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val streamWorker = Executors.newSingleThreadExecutor()
    private val photoWorker = Executors.newSingleThreadExecutor()
    private val framePosted = AtomicBoolean(false)
    @Volatile private var generation = 0
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var latest: Frame? = null
    @Volatile private var wantRecording = false
    private data class Frame(val jpeg: ByteArray, val bitmap: Bitmap, val received: Long)
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var status by mutableStateOf("카메라 연결 대기")
        private set
    var active by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var recordRequested by mutableStateOf(false)
        private set
    var savingPhoto by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var savedMedia by mutableStateOf<Uri?>(null)
        private set
    var savedType by mutableStateOf("image/jpeg")
        private set
    var framesPerSecond by mutableStateOf(0)
        private set
    var recordStarted by mutableStateOf(0L)
        private set

    fun connect(address: String) {
        val uri = try { cameraUrl(address) } catch (e: Exception) {
            message = e.message ?: "카메라 주소 오류"; return
        }
        disconnect()
        val token = generation
        connecting = true
        status = "Wi-Fi 영상 연결 중"
        message = ""
        streamWorker.execute {
            var local: HttpURLConnection? = null
            var recorder: VideoRecorder? = null
            var recordingFailed = false
            try {
                if (token != generation) return@execute
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val wifi = manager.allNetworks.firstOrNull {
                    manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                local = (wifi?.openConnection(uri.toURL()) ?: uri.toURL().openConnection()) as HttpURLConnection
                local.connectTimeout = 5000
                local.readTimeout = 6000
                local.instanceFollowRedirects = false
                local.setRequestProperty("Accept", "multipart/x-mixed-replace")
                if (token != generation) return@execute
                connection = local
                if (local.responseCode != 200) throw IOException("카메라 HTTP ${local.responseCode}")
                BufferedInputStream(local.inputStream, 32 * 1024).use { input ->
                    val reader = MjpegReader(input, local.contentType ?: "")
                    var decodedAt = 0L
                    var fpsStart = System.nanoTime()
                    var count = 0
                    while (token == generation) {
                        val bytes = reader.nextFrame() ?: break
                        if (token != generation) break
                        val now = System.nanoTime()
                        if (now - decodedAt < 66_666_667) continue
                        decodedAt = now
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..1920 && bounds.outHeight in 1..1920 &&
                            bounds.outWidth.toLong() * bounds.outHeight <= 2_073_600) { "카메라 해상도 제한 초과" }
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: throw IOException("JPEG 해석 실패")
                        latest = Frame(bytes, decoded, now)
                        count++
                        if (now - fpsStart >= 1_000_000_000) {
                            val fps = (count * 1_000_000_000L / (now - fpsStart)).toInt()
                            post(token) { framesPerSecond = fps }
                            count = 0; fpsStart = now
                        }
                        if (framePosted.compareAndSet(false, true)) handler.post {
                            framePosted.set(false)
                            if (token == generation) {
                                bitmap = latest?.bitmap
                                connecting = false
                                active = true
                                status = "Wi-Fi 영상 연결됨"
                            }
                        }
                        if (!wantRecording && recorder != null) {
                            finishRecording(recorder!!)
                            recorder = null
                            post(token) { recording = false; recordRequested = false }
                        }
                        if (!wantRecording) recordingFailed = false
                        if (wantRecording && !recordingFailed) {
                            try {
                                if (recorder == null) {
                                    recorder = VideoRecorder(context.contentResolver, decoded.width, decoded.height)
                                    post(token) { recording = true; recordStarted = android.os.SystemClock.elapsedRealtime() }
                                }
                                recorder!!.frame(decoded, now)
                            } catch (e: Exception) {
                                recorder?.abort(); recorder = null
                                recordingFailed = true
                                wantRecording = false
                                post(token) {
                                    recording = false; recordRequested = false
                                    message = "녹화 실패: ${e.message}"
                                }
                            }
                        }
                    }
                }
                post(token) { status = "카메라 스트림 종료" }
            } catch (e: Exception) {
                post(token) { status = "카메라 연결 끊김"; message = e.message ?: "영상 수신 오류" }
            } finally {
                recorder?.let { finishRecording(it) }
                local?.disconnect()
                if (token == generation) {
                    connection = null
                    latest = null
                }
                post(token) {
                    bitmap = null; active = false; connecting = false
                    recording = false; recordRequested = false
                }
            }
        }
    }

    private fun post(token: Int, action: () -> Unit) {
        handler.post { if (token == generation) action() }
    }

    fun toggleRecording() {
        if (!active && !recording) return
        wantRecording = !wantRecording
        recordRequested = wantRecording
        if (!wantRecording && recording) message = "MP4 저장 중"
    }

    private fun finishRecording(recorder: VideoRecorder) {
        try {
            val uri = recorder.finish()
            handler.post {
                savedMedia = uri; savedType = "video/mp4"
                message = "MP4 저장 완료 · 갤러리 Movies/RB022"
            }
        } catch (e: Exception) {
            handler.post { message = "녹화 저장 실패: ${e.message}" }
        }
    }

    fun snapshot() {
        val frame = latest ?: return
        if (!active || savingPhoto || System.nanoTime() - frame.received > 2_000_000_000) return
        savingPhoto = true
        photoWorker.execute {
            var uri: Uri? = null
            try {
                val resolver = context.contentResolver
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "RB022_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/RB022")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: throw IOException("사진 파일 생성 실패")
                (resolver.openOutputStream(uri) ?: throw IOException("사진 파일 열기 실패")).use { it.write(frame.jpeg) }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                val saved = uri
                handler.post { savedMedia = saved; savedType = "image/jpeg"; message = "사진 저장 완료 · 갤러리 Pictures/RB022" }
            } catch (e: Exception) {
                uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                handler.post { message = "사진 저장 실패: ${e.message}" }
            } finally { handler.post { savingPhoto = false } }
        }
    }

    /** Latest decoded-valid JPEG for the Quest relay; time base is System.nanoTime in ms. */
    fun relayFrame(): RelayFrame? = latest?.let { RelayFrame(it.jpeg, it.received / 1_000_000) }

    fun disconnect() {
        generation++
        wantRecording = false
        recordRequested = false
        recording = false
        connecting = false
        active = false
        bitmap = null
        latest = null
        connection?.disconnect()
        connection = null
        framesPerSecond = 0
        status = "카메라 연결 대기"
    }

    fun close() {
        disconnect()
        streamWorker.shutdown()
        photoWorker.shutdown()
    }
}

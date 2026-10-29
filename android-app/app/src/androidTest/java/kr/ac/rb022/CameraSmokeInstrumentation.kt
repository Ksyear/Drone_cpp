package kr.ac.rb022

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Emulator-only integration test: synthetic MJPEG, real Android encoder and MediaStore. */
class CameraSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val output = Bundle()
        val server = ServerSocket(0)
        val workers = Executors.newCachedThreadPool()
        val ownedMedia = mutableListOf<Uri>()
        var client: CameraClient? = null
        try {
            val image = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.rgb(18, 57, 70))
            val paint = Paint().apply { color = Color.rgb(102, 227, 204); textSize = 20f; isAntiAlias = true }
            canvas.drawCircle(160f, 96f, 44f, paint)
            paint.color = Color.WHITE
            canvas.drawText("LOCAL CAMERA TEST", 45f, 180f, paint)
            paint.textSize = 12f
            canvas.drawText("Synthetic image - not drone footage", 50f, 204f, paint)
            val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            image.recycle()
            workers.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    workers.execute {
                        socket.use { s ->
                            runCatching {
                                val input = s.getInputStream().bufferedReader()
                                while (input.readLine()?.isNotEmpty() == true) { }
                                val stream = s.getOutputStream()
                                stream.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=test\r\nConnection: close\r\n\r\n".toByteArray())
                                repeat(800) {
                                    stream.write("\r\n--test\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                                    stream.write(bytes); stream.flush(); Thread.sleep(80)
                                }
                            }
                        }
                    }
                }
            }
            val url = "http://127.0.0.1:${server.localPort}/stream"
            lateinit var camera: CameraClient
            runOnMainSync { camera = CameraClient(targetContext); client = camera; camera.connect(url) }
            waitFor("MJPEG reception") { camera.active }
            check(camera.bitmap?.width == 320 && camera.bitmap?.height == 240)
            runOnMainSync { camera.snapshot() }
            waitFor("JPEG save") { camera.savedMedia != null && !camera.savingPhoto }
            val photo = camera.savedMedia!!; ownedMedia += photo
            targetContext.contentResolver.openInputStream(photo)!!.use {
                val decoded = android.graphics.BitmapFactory.decodeStream(it)!!
                check(decoded.width == 320 && decoded.height == 240); decoded.recycle()
            }
            runOnMainSync { camera.toggleRecording() }
            waitFor("recording start") { camera.recording }
            Thread.sleep(2500)
            runOnMainSync { camera.toggleRecording() }
            waitFor("MP4 finish") { camera.savedMedia != photo && camera.savedType == "video/mp4" }
            val movie = camera.savedMedia!!; ownedMedia += movie
            MediaMetadataRetriever().use {
                it.setDataSource(targetContext, movie)
                check((it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) >= 1500)
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(targetContext, movie, null)
                check(extractor.trackCount == 1)
                check(extractor.getTrackFormat(0).getString("mime") == "video/avc")
            } finally { extractor.release() }
            targetContext.contentResolver.openInputStream(movie)!!.use { input ->
                targetContext.openFileOutput("camera-smoke.mp4", 0).use { input.copyTo(it) }
            }
            runOnMainSync { camera.toggleRecording() }
            waitFor("second recording") { camera.recording }
            Thread.sleep(800)
            runOnMainSync { camera.disconnect() }
            waitFor("disconnect finalizes MP4") { camera.savedMedia != movie }
            ownedMedia += camera.savedMedia!!
            runOnMainSync { camera.close(); client = null }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitFor("portrait layout") { targetContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            waitForIdleSync(); Thread.sleep(800)
            screenshot("console-portrait.png")
            runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor("landscape layout") { targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            Thread.sleep(800); waitForIdleSync()
            screenshot("console-landscape.png")
            click("CAM")
            waitFor("camera settings") { find { it.className?.toString() == "android.widget.EditText" } != null }
            val field = find { it.className?.toString() == "android.widget.EditText" }!!
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, url)
            })
            waitFor("connect action") { find { it.text?.toString() == "영상 연결" } != null }
            click("영상 연결")
            waitFor("live UI") { find { it.text?.toString()?.startsWith("LIVE") == true } != null }
            Thread.sleep(1500)
            screenshot("console-live-test.png")
            runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; activity.finish() }
            output.putString("stream", "\nPASS: MJPEG receive, JPEG save/decode, H.264 MP4 duration/track, disconnect finalization, portrait/landscape/live UI screenshots. Synthetic stream only; no drone hardware.\n")
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            output.putString("stream", "\nFAIL: ${e.stackTraceToString()}\n")
            screenshot("console-failure.png")
            finish(Activity.RESULT_CANCELED, output)
        } finally {
            client?.let { runOnMainSync { it.close() } }
            server.close(); workers.shutdownNow()
            ownedMedia.forEach { runCatching { targetContext.contentResolver.delete(it, null, null) } }
        }
    }

    private fun waitFor(label: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) { if (check()) return; Thread.sleep(100) }
        error("Timed out: $label")
    }

    private fun screenshot(name: String) {
        uiAutomation.takeScreenshot()?.let { image ->
            if (name == "console-portrait.png") check(image.height > image.width)
            if (name == "console-landscape.png") check(image.width > image.height)
            targetContext.openFileOutput(name, 0).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> visit(child)?.let { return it } }
            return null
        }
        return uiAutomation.rootInActiveWindow?.let { visit(it) }
    }

    private fun click(text: String) {
        val node = find { it.text?.toString() == text } ?: error("Missing UI text $text")
        var clickable = node
        while (!clickable.isClickable) clickable = clickable.parent ?: error("Not clickable: $text")
        check(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}

package kr.ac.rb022

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Latest camera JPEG and the hub-clock time it was received. */
class RelayFrame(val jpeg: ByteArray, val receivedMs: Long)

/**
 * LAN-only HTTP/1.1 hub for the Quest client: page, single MJPEG relay of the phone's camera frames,
 * mission commands and state. One request per connection; no CORS headers, so cross-origin pages cannot call it.
 */
class MissionHub(
    private val session: MissionSession,
    private val controller: MissionController,
    private val page: () -> ByteArray,
    private val latestFrame: () -> RelayFrame?,
    private val clock: () -> Long,
    private val port: Int = 8080,
    private val drone: SimulatedDrone? = SimulatedDrone(),
) {
    companion object {
        const val BOUNDARY = "rb022relay"
        val LOCK_REASONS = listOf("no-validated-position-source", "mission-output-not-wired-to-ble",
            "battery-and-motor-current-unverified", "user-flight-approval-required")
    }

    private val running = AtomicBoolean(false)
    private val relayBusy = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val workers = ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS, SynchronousQueue())
    private var ticker: ScheduledExecutorService? = null

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port), 8)
        server = socket
        running.set(true)
        Thread({ acceptLoop(socket) }, "mission-hub-accept").start()
        if (drone != null) ticker = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ simulate() }, 0, 20, TimeUnit.MILLISECONDS)
        }
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        ticker?.shutdownNow()
        runCatching { server?.close() }
        workers.shutdownNow()
    }

    private fun simulate() = synchronized(session) {
        try {
            val now = clock()
            drone!!.step(controller.output, now, 20)
            controller.pose(drone.observe(now))
            controller.tick(now)
        } catch (_: Exception) { /* keep ticking; a bad step must not stop the loop */ }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try { socket.accept() } catch (_: IOException) { break }
            if (!localNetwork(client.localAddress)) { runCatching { client.close() }; continue }
            try { workers.execute { handle(client) } } catch (_: RejectedExecutionException) { runCatching { client.close() } }
        }
    }

    /** Wi-Fi/AP addresses (private IPv4) or loopback only; carrier and public interfaces are refused. */
    private fun localNetwork(address: InetAddress) = address.isLoopbackAddress || (address.address.size == 4 && address.isSiteLocalAddress)

    private fun handle(client: Socket) { client.use {
        try {
            it.soTimeout = 5000
            val input = BufferedInputStream(it.getInputStream(), 4096)
            val out = it.getOutputStream()
            val requestLine = line(input).split(' ')
            if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) return respond(out, 400, "text/plain", "bad request")
            val headers = HashMap<String, String>()
            while (true) {
                val header = line(input)
                if (header.isEmpty()) break
                if (headers.size >= 32 || ':' !in header) return respond(out, 400, "text/plain", "bad header")
                headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
            }
            val method = requestLine[0]
            val path = requestLine[1].substringBefore('?')
            val query = requestLine[1].substringAfter('?', "")
            when {
                method == "GET" && (path == "/" || path == "/index.html") -> respond(out, 200, "text/html; charset=utf-8", page())
                method == "GET" && path == "/api/state" -> {
                    if (!session.authorized(headers["x-rb022-session"])) return respond(out, 401, "application/json", "{\"ok\":false,\"reason\":\"unauthorized\"}")
                    session.touch()
                    val frame = latestFrame()
                    respond(out, 200, "application/json", session.stateJson(frame?.let { f -> clock() - f.receivedMs }, LOCK_REASONS))
                }
                method == "GET" && path == "/stream" -> relay(out, query.split('&').firstOrNull { q -> q.startsWith("s=") }?.substring(2))
                method == "POST" && (path == "/api/hello" || path == "/api/command") -> {
                    val type = headers["content-type"]?.substringBefore(';')?.trim()
                    val length = headers["content-length"]?.toIntOrNull()
                    if (type != "application/json" || length == null || length !in 1..MissionJson.MAX_BYTES)
                        return respond(out, 413, "application/json", "{\"ok\":false,\"reason\":\"bad-body\"}")
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n <= 0) return
                        read += n
                    }
                    val text = String(body, Charsets.UTF_8)
                    val (code, json) = if (path == "/api/hello") {
                        if (headers["x-rb022-client"].isNullOrBlank()) 400 to "{\"ok\":false,\"reason\":\"missing-client-header\"}"
                        else session.hello(text)
                    } else session.command(headers["x-rb022-session"], text)
                    respond(out, code, "application/json", json)
                }
                path in setOf("/", "/index.html", "/api/state", "/stream", "/api/hello", "/api/command") -> respond(out, 405, "text/plain", "method not allowed")
                else -> respond(out, 404, "text/plain", "not found")
            }
        } catch (_: IOException) {
        } catch (_: IllegalArgumentException) {
        }
    } }

    /** Re-serve the phone's single camera consumer so the camera keeps one stream client. */
    private fun relay(out: OutputStream, token: String?) {
        if (!session.authorized(token)) return respond(out, 401, "text/plain", "unauthorized")
        if (!relayBusy.compareAndSet(false, true)) return respond(out, 503, "text/plain", "relay busy")
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=$BOUNDARY\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            var sent = Long.MIN_VALUE
            while (running.get() && session.authorized(token)) {
                val frame = latestFrame()
                if (frame == null || frame.receivedMs == sent || clock() - frame.receivedMs > 2000) { Thread.sleep(30); continue }
                sent = frame.receivedMs
                out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.jpeg.size}\r\n\r\n".toByteArray())
                out.write(frame.jpeg)
                out.write("\r\n".toByteArray())
                out.flush()
                Thread.sleep(100)
            }
        } catch (_: InterruptedException) {
        } finally { relayBusy.set(false) }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) = respond(out, code, type, body.toByteArray())

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val text = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 409 to "Conflict", 413 to "Payload Too Large", 429 to "Too Many Requests",
            503 to "Service Unavailable")[code] ?: "Error"
        out.write(("HTTP/1.1 $code $text\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun line(input: InputStream): String {
        val out = StringBuilder()
        repeat(1024) {
            val value = input.read()
            if (value < 0) throw IOException("closed")
            if (value == 10) return out.toString().removeSuffix("\r")
            out.append(value.toChar())
        }
        throw IllegalArgumentException("line too long")
    }
}

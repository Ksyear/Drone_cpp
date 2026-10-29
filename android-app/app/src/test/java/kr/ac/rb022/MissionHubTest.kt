package kr.ac.rb022

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

class MissionHubTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 7, 7, 0xff.toByte(), 0xd9.toByte())
    private val clock = { System.nanoTime() / 1_000_000 }
    private val controller = MissionController()
    private val session = MissionSession(controller, clock)
    private var frame: RelayFrame? = null
    private val hub = MissionHub(session, controller, { "<html>ok</html>".toByteArray() }, { frame }, clock, port = 0)
    private val port = hub.start()

    @After fun stop() = hub.stop()

    private fun call(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach(c::setRequestProperty)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        return code to text
    }

    private fun pair(): String {
        val (code, text) = call("POST", "/api/hello", "{\"v\":1,\"type\":\"hello\",\"code\":\"${session.pairingCode}\"}", mapOf("X-RB022-Client" to "test"))
        assertEquals(text, 200, code)
        return Regex("\"session\":\"([0-9a-f]{32})\"").find(text)!!.groupValues[1]
    }

    private fun cmd(token: String, seq: Int, rest: String) =
        call("POST", "/api/command", "{\"v\":1,\"session\":\"$token\",\"seq\":$seq,$rest}", mapOf("X-RB022-Session" to token))

    @Test fun pageAndPairingRules() {
        assertEquals(200, call("GET", "/").first)
        assertEquals(404, call("GET", "/nope").first)
        assertEquals(405, call("POST", "/", "{}").first)
        assertEquals("cross-origin simple POST without custom header is refused", 400,
            call("POST", "/api/hello", "{\"v\":1,\"type\":\"hello\",\"code\":\"${session.pairingCode}\"}").first)
        val wrong = if (session.pairingCode == "000000") "000001" else "000000"
        repeat(5) { assertEquals(403, call("POST", "/api/hello", "{\"v\":1,\"type\":\"hello\",\"code\":\"$wrong\"}", mapOf("X-RB022-Client" to "t")).first) }
        assertEquals("brute force locks pairing", 429,
            call("POST", "/api/hello", "{\"v\":1,\"type\":\"hello\",\"code\":\"${session.pairingCode}\"}", mapOf("X-RB022-Client" to "t")).first)
        assertEquals(401, call("GET", "/api/state").first)
    }

    @Test fun goalAckExecuteAndReplaySemantics() {
        val token = pair()
        val (_, state) = call("GET", "/api/state", headers = mapOf("X-RB022-Session" to token))
        assertTrue(state, state.contains("\"mode\":\"simulation\"") && state.contains("no-validated-position-source"))
        Thread.sleep(300)  // let the simulator publish a pose
        val goal = "\"type\":\"goal\",\"goalId\":\"g1\",\"frame\":\"W\",\"position\":[0.5,0.2,0.8],\"arrival\":\"hover\""
        val ack = cmd(token, 1, goal).second
        assertTrue(ack, ack.contains("\"result\":\"accepted\"") && ack.contains("\"state\":\"PENDING\""))
        assertEquals("duplicate seq replays the cached ACK", ack, cmd(token, 1, "\"type\":\"cancel\",\"goalId\":\"g1\"").second)
        val q = cmd(token, 2, "\"type\":\"goal\",\"goalId\":\"g2\",\"frame\":\"Q\",\"alignmentId\":\"a\",\"position\":[0,0,0]").second
        assertTrue(q, q.contains("no-alignment"))
        assertEquals("older seq is refused", 409, cmd(token, 1, "\"type\":\"heartbeat\"").first)
        assertEquals(400, cmd(token, 0, "\"type\":\"heartbeat\"").first)
        val big = cmd(token, 3, "\"type\":\"goal\",\"goalId\":\"g3\",\"frame\":\"W\",\"position\":[0,0,1e999]")
        assertEquals(400, big.first)
        assertTrue(cmd(token, 4, "\"type\":\"execute\",\"goalId\":\"g1\"").second.contains("\"state\":\"EXECUTING\""))
        assertTrue(cmd(token, 5, "\"type\":\"align\",\"alignmentId\":\"a\",\"pairs\":[[0,0,0,0,0,0],[1,0,0,1,0,0]]").second.let { it.contains("busy") || it.contains("rejected") })
        assertEquals(401, cmd("f".repeat(32), 6, "\"type\":\"heartbeat\"").first)
        assertEquals(413, call("POST", "/api/command", "{\"pad\":\"${"x".repeat(3000)}\"}", mapOf("X-RB022-Session" to token)).first)
    }

    @Test fun questFrameGoalUsesActiveAlignment() {
        val token = pair()
        val pairs = "[[0,0,0,1,1,0],[1,0,0,2,1,0],[0,0,-1,1,2,0]]"
        assertTrue(cmd(token, 1, "\"type\":\"align\",\"alignmentId\":\"room\",\"pairs\":$pairs").second.contains("accepted"))
        assertTrue(cmd(token, 2, "\"type\":\"goal\",\"goalId\":\"g\",\"frame\":\"Q\",\"alignmentId\":\"old\",\"position\":[0,0.8,0]").second.contains("alignment-mismatch"))
        assertTrue(cmd(token, 3, "\"type\":\"goal\",\"goalId\":\"g\",\"frame\":\"Q\",\"alignmentId\":\"room\",\"position\":[0.5,0,-0.5],\"altitude\":0.9").second.contains("accepted"))
        val goal = controller.goal!!
        assertEquals(1.5, goal.x, 1e-9); assertEquals(1.5, goal.y, 1e-9); assertEquals(0.9, goal.z, 1e-9)
    }

    @Test fun relayServesExistingMjpegFormatToOneAuthorizedClient() {
        val token = pair()
        frame = RelayFrame(jpeg, clock())
        assertEquals(401, call("GET", "/stream").first)
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET /stream?s=$token HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val input = BufferedInputStream(s.getInputStream())
            val status = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
            assertTrue(status.first(), status.first().contains("200"))
            val type = status.first { it.startsWith("Content-Type") }.substringAfter(':').trim()
            assertEquals(503, call("GET", "/stream?s=$token").first)
            val reader = MjpegReader(input, type)
            assertArrayEquals(jpeg, reader.nextFrame())
            frame = RelayFrame(jpeg, clock() + 1)
            assertArrayEquals(jpeg, reader.nextFrame())
        }
    }

    private fun readLine(input: BufferedInputStream): String {
        val out = StringBuilder()
        while (true) { val c = input.read(); if (c < 0 || c == 10) return out.toString().removeSuffix("\r"); out.append(c.toChar()) }
    }
}

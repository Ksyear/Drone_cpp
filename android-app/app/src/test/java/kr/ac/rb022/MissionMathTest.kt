package kr.ac.rb022

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class MissionMathTest {
    private fun near(a: Vec3, b: Vec3, tol: Double = 1e-9) = assertTrue("$a != $b", (a - b).norm() < tol)

    @Test fun axisMappingKeepsMetersAndPutsQuestUpOnWorldZ() {
        val identity = Alignment("a0", 0.0, Vec3.ZERO, 0.0)
        near(Vec3(0.0, 0.0, 1.5), identity.toW(Vec3(0.0, 1.5, 0.0)))   // Q +Y up -> W +Z
        near(Vec3(0.0, 2.0, 0.0), identity.toW(Vec3(0.0, 0.0, -2.0)))  // Q forward (-Z) -> W +Y
        near(Vec3(1.0, 0.0, 0.0), identity.toW(Vec3(1.0, 0.0, 0.0)))   // Q +X right -> W +X
    }

    @Test fun roundTripAndYawSignAreConsistent() {
        val a = Alignment("a1", PI / 2, Vec3(1.0, -2.0, 0.1), 0.0)
        near(Vec3(1.0, -1.0, 0.1), a.toW(Vec3(1.0, 0.0, 0.0)))  // +X rotated CCW by 90 deg -> +Y
        for (q in listOf(Vec3(0.3, 1.2, -0.7), Vec3(-2.0, 0.0, 3.0), Vec3.ZERO)) near(q, a.toQ(a.toW(q)))
    }

    @Test fun estimatesYawAndTranslationFromMarkerPairs() {
        val truth = Alignment("t", 0.6, Vec3(0.4, -0.8, 0.02), 0.0)
        val qs = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, -0.2), Vec3(0.2, 0.0, -1.5))
        val est = Alignment.estimate("room-1", qs.map { it to truth.toW(it) })
        assertEquals(0.6, est.yaw, 1e-9)
        near(truth.t, est.t, 1e-9)
        assertTrue(est.rms < 1e-9)
    }

    @Test fun rejectsBadAlignments() {
        val good = listOf(Vec3.ZERO to Vec3.ZERO, Vec3(1.0, 0.0, 0.0) to Vec3(1.0, 0.0, 0.0))
        assertThrows(IllegalArgumentException::class.java) { Alignment.estimate("x", good.take(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            Alignment.estimate("x", listOf(Vec3.ZERO to Vec3.ZERO, Vec3(0.1, 0.0, 0.0) to Vec3(0.1, 0.0, 0.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Alignment.estimate("x", good + (Vec3(0.0, 0.0, -1.0) to Vec3(0.5, 1.0, 0.0)))  // inconsistent pair -> residual
        }
        assertThrows(IllegalArgumentException::class.java) {
            Alignment.estimate("x", good + (Vec3(Double.NaN, 0.0, 0.0) to Vec3.ZERO))
        }
        assertThrows(IllegalArgumentException::class.java) { Vec3.of(listOf(1.0, 2.0)) }
        assertThrows(IllegalArgumentException::class.java) { Vec3.of(listOf(1.0, 2.0, 1e6)) }
    }

    @Test fun rc150MatchesFirmwareFormula() {
        // firmware: rcData = 1000 + 4*byte, stick center 1500 = byte 125, AUX packed in byte 5
        assertEquals(125, Rc150.angleByte(0.0))
        assertEquals(125 + 7, Rc150.angleByte(5.0))
        assertEquals(0, Rc150.angleByte(-200.0))
        assertEquals(250, Rc150.angleByte(200.0))
        val clamped = Rc150.fromAcceleration(forward = 50.0, right = -50.0, throttle = 2.0, maxTiltDeg = 8.0)
        assertEquals(Rc150.angleByte(8.0), clamped.pitch)
        assertEquals(Rc150.angleByte(-8.0), clamped.roll)
        assertEquals(250, clamped.throttle)
        val frame = Rc150(125, 130, 125, 0, Rc150.AUX_DEFAULT).frame()
        assertEquals(11, frame.size)
        assertArrayEquals(byteArrayOf(36, 77, 60, 5, 150.toByte()), frame.copyOf(5))
        var xor = 0
        for (b in frame.copyOfRange(3, 10)) xor = xor xor (b.toInt() and 255)
        assertEquals(xor, frame[10].toInt() and 255)
    }

    @Test fun strictJsonRejectsAmbiguousInput() {
        assertEquals(1.5, MissionJson.parseObject("{\"a\":1.5}")["a"])
        for (bad in listOf("{\"a\":1,\"a\":2}", "{\"a\":NaN}", "{\"a\":1e999}", "{\"a\":[[[1]]]}", "{a:1}", "{\"a\":1} x",
            "{\"a\":01}", "{\"a\":\"" + "x".repeat(65) + "\"}", "[1]", "{\"a\":1,}", "{\"a\":\"\u0001\"}")) {
            assertThrows(bad, IllegalArgumentException::class.java) { MissionJson.parseObject(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { MissionJson.parseObject("{\"a\":\"${"y".repeat(3000)}\"}") }
        assertThrows(IllegalArgumentException::class.java) { MissionJson.number(Double.POSITIVE_INFINITY) }
        assertEquals("\"a\\\"b\"", MissionJson.quote("a\"b"))
    }
}

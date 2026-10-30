package kr.ac.rb022

import org.junit.Assert.*
import org.junit.Test

class ControlPreviewTest {
    @Test fun sticksClampAndCannotEnableTransmission() {
        val state = ControlPreview().right(-4f, 4f)
        assertEquals(-1f, state.pitch)
        assertEquals(-1f, state.roll)
        assertEquals(0f, ControlPreview().throttle)
    }

    @Test fun altitudeThrottleStaysZeroUntilArmedClimb() {
        val alt = AltitudeThrottle(0.5f)
        alt.press(+1, 0, armed = false, climbPulseMs = 300); alt.release(100)
        alt.press(-1, 200, armed = true, climbPulseMs = 300); alt.release(300)
        assertEquals(0f, alt.output(400))  // exact: canArm() compares with 0f
        assertFalse(alt.airborne)
    }

    @Test fun altitudeThrottlePulsesHoldsAndBrakes() {
        val alt = AltitudeThrottle(0.5f)
        alt.press(+1, 0, armed = true, climbPulseMs = 300)              // first climb: lift-off search
        assertEquals(0.61f, alt.output(100), 1e-5f)                      // hover+nudge + pulse
        assertEquals(0.51f + 0.04f * 2 + 0.02f, alt.output(2300), 1e-5f) // walks up 4%/s after the pulse, + drag
        alt.release(2300)
        val hover = 0.51f + 0.04f * 2
        assertEquals(hover - 0.10f, alt.output(2400), 1e-5f)             // opposite pulse cancels the climb speed
        assertEquals(hover, alt.output(2600), 1e-5f)                     // then holds
        alt.press(+1, 3000, armed = true, climbPulseMs = 300)            // later climbs do not walk
        assertEquals(hover + 0.01f + 0.02f, alt.output(5000), 1e-5f)
        alt.release(5000)
        alt.press(-1, 6000, armed = true, climbPulseMs = 300)            // tap shorter than the 180 ms descent pulse
        assertEquals(hover - 0.10f, alt.output(6050), 1e-5f)
        alt.release(6100)
        assertEquals(hover + 0.10f, alt.output(6150), 1e-5f)             // brake only as long as the tap
        assertEquals(hover, alt.output(6200), 1e-5f)
        alt.press(-1, 7000, armed = true, climbPulseMs = 300)            // long descent walks hover down 5%/s
        alt.release(7000 + 180 + 2000)
        assertEquals(hover - 0.01f - 0.10f, alt.hover, 1e-5f)
        alt.land()
        assertEquals(0f, alt.output(10_000))
    }

    @Test fun altitudeThrottleBounds() {
        val alt = AltitudeThrottle(5f)
        assertEquals(AltitudeThrottle.MAX_HOVER, alt.hover)
        alt.press(+1, 0, armed = true, climbPulseMs = 500)
        assertTrue(alt.output(60_000) <= 1f)
        assertEquals(120L, AltitudeThrottle.pulseMs(-1f)); assertEquals(500L, AltitudeThrottle.pulseMs(2f))
    }

    @Test fun rcBytesStayInsideLegacyStickCommandDeadZone() {
        val full = ControlPreview(throttle = 1f, yaw = 1f, pitch = -1f, roll = 1f).rc(throttleLimit = 1f, axisLimit = 1f)
        assertEquals(224, full.roll); assertEquals(26, full.pitch); assertEquals(224, full.yaw); assertEquals(250, full.throttle)
        for (b in listOf(full.roll, full.pitch, full.yaw)) assertTrue(1000 + 4 * b in 1101..1899)  // never LO (<=1100) or HI (>=1900)
        val idle = ControlPreview().rc(0.3f, 0.5f)
        assertEquals(listOf(125, 125, 125, 0), listOf(idle.roll, idle.pitch, idle.yaw, idle.throttle))
        assertTrue(1000 + 4 * idle.throttle < 1100)  // below MINCHECK: MOTOR_STOP keeps motors off even when armed
        assertEquals(75, ControlPreview(throttle = 1f).rc(0.3f, 1f).throttle)  // bench throttle limit
    }
}

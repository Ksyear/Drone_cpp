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

    @Test fun throttleButtonsStepWithinRangeAndReturnToExactZero() {
        var state = ControlPreview()
        repeat(ControlPreview.THROTTLE_STEPS + 3) { state = state.throttleStep(+1) }
        assertEquals(1f, state.throttle)
        repeat(7) { state = state.throttleStep(-1) }
        assertEquals(0.825f, state.throttle, 1e-6f)
        repeat(ControlPreview.THROTTLE_STEPS) { state = state.throttleStep(-1) }
        assertEquals(0f, state.throttle)  // exact: canArm() compares with 0f
        assertEquals(0.05f, ControlPreview(throttle = 0.049f).throttleStep(0).throttle)  // slider values snap to a step
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

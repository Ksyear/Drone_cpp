package kr.ac.rb022

import org.junit.Assert.*
import org.junit.Test

class ControlPreviewTest {
    @Test fun sticksClampAndCannotEnableTransmission() {
        val state = ControlPreview().left(4f, -4f).right(-4f, 4f)
        assertEquals(1f, state.throttle)
        assertEquals(1f, state.yaw)
        assertEquals(-1f, state.pitch)
        assertEquals(-1f, state.roll)
        assertFalse(state.transmissionEnabled)
        assertEquals(0f, ControlPreview().throttle)
    }
}

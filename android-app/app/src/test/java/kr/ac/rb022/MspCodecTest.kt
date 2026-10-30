package kr.ac.rb022

import org.junit.Assert.*
import org.junit.Test

class MspCodecTest {
    private fun reply(command: Int, payload: ByteArray, error: Boolean = false): ByteArray {
        val request = Msp.request(command, payload)
        request[2] = (if (error) '!' else '>').code.toByte()
        return request
    }

    @Test fun requestsMatchFirmwareFraming() {
        assertEquals("24 4D 3C 00 C7 C7", Msp.hex(Msp.request(Msp.AIR)))
        assertEquals("24 4D 3C 00 97 97", Msp.hex(Msp.request(Msp.ARM)))
        assertEquals("24 4D 3C 00 98 98", Msp.hex(Msp.request(Msp.DISARM)))
        assertEquals("24 4D 3C 00 6F 6F", Msp.hex(Msp.request(Msp.RC_TUNING)))
        assertArrayEquals(Rc150(125, 125, 125, 0, 5).frame(), Msp.request(150, byteArrayOf(125, 125, 125, 0, 5)))
    }

    @Test fun decodesAirStatusFromFragmentedNotifications() {
        // flightState armed|1, id 231, alt -3, roll -12.5 deg, pitch 3.0 deg, altHold 40, vbat 168, trims -2/+1
        val payload = byteArrayOf(0x81.toByte(), 231.toByte(), 0, 0xFD.toByte(), 0xFF.toByte(), 0x83.toByte(), 0xFF.toByte(),
            30, 0, 40, 0, 168.toByte(), 0xFE.toByte(), 1)
        val bytes = byteArrayOf(0x00, 0x24) + reply(Msp.AIR, payload)   // stray byte like the firmware's ARM tail
        val parser = MspParser()
        val frames = bytes.toList().chunked(7).flatMap { parser.feed(it.toByteArray()) }
        val status = AirStatus.decode(frames.single())!!
        assertTrue(status.armed && status.controlFirmware)
        assertEquals(listOf(-3, -125, 30, 40, 168, -2, 1),
            listOf(status.estAlt, status.rollDeci, status.pitchDeci, status.altHold, status.vbatRaw, status.trimPitch, status.trimRoll))
        assertFalse(AirStatus.decode(frames.single())!!.copy(firmwareId = 100).controlFirmware)  // original firmware id
        assertFalse(status.holdFirmware)                                    // 231: no height hold
        assertTrue(status.copy(firmwareId = 232).let { it.controlFirmware && it.holdFirmware })
        // the kit's config.h thresholds: 179/184/189 = 3.5/3.6/3.7 V
        for ((raw, volts) in listOf(179 to 3.5f, 184 to 3.6f, 189 to 3.7f)) assertEquals(volts, status.copy(vbatRaw = raw).batteryVolts, 0.01f)
    }

    @Test fun decodesOpticalStatusAndIgnoresOlderFirmware() {
        // range found + flow found + range valid, 523 mm, flow +300 / -2, SQUAL 87
        val payload = byteArrayOf(7, 0x0B, 0x02, 0x2C, 0x01, 0xFE.toByte(), 0xFF.toByte(), 87)
        val o = OpticalStatus.decode(MspParser().feed(reply(Msp.OPTICAL, payload)).single())!!
        assertTrue(o.rangeFound && o.flowFound && o.rangeValid)
        assertEquals(listOf(523, 300, -2, 87), listOf(o.heightMm, o.flowX, o.flowY, o.quality))
        val error = byteArrayOf('$'.code.toByte(), 'M'.code.toByte(), '!'.code.toByte(), 0, Msp.OPTICAL.toByte(), Msp.OPTICAL.toByte())
        assertNull(OpticalStatus.decode(MspParser().feed(error).single()))  // pre-0.9 firmware: unknown command
        assertEquals(0, o.holdUs)
        val withHold = OpticalStatus.decode(MspParser().feed(reply(Msp.OPTICAL, payload + byteArrayOf(0x40, 0x06))).single())!!
        assertEquals(1600, withHold.holdUs)                                 // firmware 232: hold output in us
    }

    @Test fun rejectsCorruptErrorAndWrongLengthFrames() {
        val parser = MspParser()
        val bad = reply(Msp.AIR, ByteArray(14)).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertTrue(parser.feed(bad).isEmpty())
        assertEquals(1, parser.checksumErrors)
        val error = parser.feed(reply(Msp.AIR, ByteArray(0), error = true)).single()
        assertTrue(error.error)
        assertNull(AirStatus.decode(error))
        assertNull(AirStatus.decode(parser.feed(reply(Msp.AIR, ByteArray(13))).single()))
        assertTrue("oversized length is dropped", parser.feed(byteArrayOf(0x24, 0x4D, 0x3E, 65, 1)).isEmpty())
    }

    @Test fun decodesConfigurationReplies() {
        val parser = MspParser()
        val tuning = RcTuning.decode(parser.feed(reply(Msp.RC_TUNING, byteArrayOf(90, 0, 0, 0, 0, 50, 0))).single())!!
        assertEquals(90, tuning.rcRate); assertEquals(50, tuning.thrMid)
        assertEquals(listOf(0, 7), decodeUint16List(parser.feed(reply(Msp.BOX, byteArrayOf(0, 0, 7, 0))).single(), Msp.BOX))
        assertEquals(listOf(1500, 1000), decodeUint16List(parser.feed(reply(Msp.RC, byteArrayOf(0xDC.toByte(), 5, 0xE8.toByte(), 3))).single(), Msp.RC))
        val status = BoardStatus.decode(parser.feed(reply(Msp.STATUS, byteArrayOf(0xB8.toByte(), 0x0B, 2, 0, 1, 0, 0, 0, 0, 0, 0))).single())!!
        assertEquals(3000, status.cycleTimeUs); assertEquals(2, status.i2cErrors)
    }
}

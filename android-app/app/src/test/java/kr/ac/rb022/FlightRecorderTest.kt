package kr.ac.rb022

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FlightRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val air = AirStatus(0x80, 232, 0, -12, 34, 0, 180, 0, 0)  // armed, roll -1.2, pitch 3.4, ~3.52 V
    private val optical = OpticalStatus(7, 412, 30, -5, 90, 1612)

    @Test fun rowHasOneValuePerHeaderColumn() {
        val row = FlightRecorder.row(1234, air, optical, Rc150(125, 125, 125, 40, Rc150.AUX_HOLD))
        assertEquals(FlightRecorder.HEADER.split(",").size, row.split(",").size)
        assertEquals("1234,1,-1.2,3.4,180,3.52,412,1,30,-5,90,1612,40,21", row)
        assertEquals(FlightRecorder.HEADER.split(",").size, FlightRecorder.row(0, null, optical, Rc150(125, 125, 125, 0, 5)).split(",").size)
    }

    @Test fun writesOneFilePerFlightAndKeepsTheNewest() {
        val dir = tmp.newFolder("flights")
        repeat(FlightRecorder.KEEP + 2) { i -> java.io.File(dir, "flight-2026010%02d-000000.csv".format(i)).writeText("old") }
        val rec = FlightRecorder(dir)
        rec.start(1000, 1_800_000_000_000)
        assertTrue(rec.recording)
        rec.record(1100, air, optical, Rc150(125, 125, 125, 40, Rc150.AUX_HOLD))
        rec.stop()
        assertFalse(rec.recording)
        val lines = rec.file!!.readLines()
        assertEquals(FlightRecorder.HEADER, lines[0])
        assertTrue(lines[1].startsWith("100,1,"))
        assertEquals(FlightRecorder.KEEP, dir.listFiles()!!.size)  // the oldest were removed to make room
    }

    @Test fun lowBatteryNeedsThreeArmedReadingsInARow() {
        val watch = LowBatteryWatch()
        val low = air.copy(vbatRaw = 160)                          // ~3.13 V
        assertFalse(watch.update(low)); assertFalse(watch.update(low))
        assertFalse(watch.update(air))                              // recovered: counter resets
        assertFalse(watch.update(low)); assertFalse(watch.update(low)); assertTrue(watch.update(low))
        assertFalse(watch.update(low.copy(flightState = 0)))        // disarmed (USB bench, landed) never warns
    }
}

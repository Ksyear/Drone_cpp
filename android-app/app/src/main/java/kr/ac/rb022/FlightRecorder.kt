package kr.ac.rb022

import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flight log for tuning the height hold: one CSV row per MSP 198 reply while ARMED (about 10 Hz) with the latest
 * 199 status and the RC frame being sent. Files live in the app's private files/flights
 * (`adb shell run-as kr.ac.rb022.diagnostic ls files/flights`); only the newest [KEEP] are kept.
 */
class FlightRecorder(private val dir: File) {
    private var out: Writer? = null
    private var startMs = 0L
    private var rows = 0
    var file: File? = null; private set
    val recording get() = out != null

    fun start(nowMs: Long, wallMs: Long) {
        stop()
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name }?.dropLast(KEEP - 1)?.forEach { it.delete() }
        val f = File(dir, "flight-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(wallMs))}.csv")
        out = f.bufferedWriter().also { it.write(HEADER + "\n") }
        file = f; startMs = nowMs; rows = 0
    }

    fun record(nowMs: Long, air: AirStatus?, optical: OpticalStatus, rc: Rc150) {
        val w = out ?: return
        w.write(row(nowMs - startMs, air, optical, rc) + "\n")
        if (++rows % 10 == 0) w.flush()  // about once a second, so a killed app loses little
    }

    fun stop() { out?.close(); out = null }

    companion object {
        const val KEEP = 30
        const val HEADER = "t_ms,armed,roll_deg,pitch_deg,vbat_raw,battery_v,height_mm,range_valid,flow_x,flow_y,squal,hold_us," +
            "forward_cm,right_cm,position_active,rc_roll,rc_pitch,rc_throttle,rc_aux"

        fun row(t: Long, air: AirStatus?, o: OpticalStatus, rc: Rc150): String = listOf(
            t.toString(),
            air?.let { if (it.armed) "1" else "0" } ?: "",
            air?.let { "%.1f".format(Locale.US, it.rollDeci / 10f) } ?: "",
            air?.let { "%.1f".format(Locale.US, it.pitchDeci / 10f) } ?: "",
            air?.vbatRaw?.toString() ?: "",
            air?.let { "%.2f".format(Locale.US, it.batteryVolts) } ?: "",
            o.heightMm.toString(), if (o.rangeValid) "1" else "0", o.flowX.toString(), o.flowY.toString(),
            o.quality.toString(), o.holdUs.toString(), o.forwardCm.toString(), o.rightCm.toString(), if (o.positionActive) "1" else "0",
            rc.roll.toString(), rc.pitch.toString(), rc.throttle.toString(), rc.aux.toString(),
        ).joinToString(",")
    }
}

/** Low battery while ARMED: three 199 readings in a row under [VOLTS] (one sag at a throttle punch is not enough). */
class LowBatteryWatch {
    private var below = 0
    fun update(air: AirStatus): Boolean {
        below = if (air.armed && air.batteryVolts < VOLTS) below + 1 else 0
        return below >= 3
    }

    companion object { const val VOLTS = 3.3f }
}

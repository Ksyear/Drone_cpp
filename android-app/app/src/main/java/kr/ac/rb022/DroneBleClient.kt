package kr.ac.rb022

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.util.UUID

private const val TAG = "RB022-MSP"
private val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
private val UART: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class BleDevice(val address: String, val name: String, val rssi: Int)
data class GattItem(val id: Int, val service: String, val uuid: String, val properties: String, val readable: Boolean)

@SuppressLint("MissingPermission")
class DroneBleClient(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    var status by mutableStateOf("연결 해제 · 제어 송신 잠금")
        private set
    var devices by mutableStateOf(emptyList<BleDevice>())
        private set
    var items by mutableStateOf(emptyList<GattItem>())
        private set
    var logs by mutableStateOf(emptyList<String>())
        private set
    var scanning by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    var reading by mutableStateOf(false)
        private set
    var uartReady by mutableStateOf(false)
        private set
    var telemetry by mutableStateOf<AirStatus?>(null)
        private set
    var telemetryAgeMs by mutableStateOf<Long?>(null)
        private set
    var optical by mutableStateOf<OpticalStatus?>(null)
        private set
    var config by mutableStateOf("")
        private set
    var controlEnabled by mutableStateOf(false)
        private set
    var framesSent by mutableStateOf(0)
        private set
    private val prefs = context.getSharedPreferences("drone", Context.MODE_PRIVATE)
    /** Stick source and output limits, read on every control frame. Limits survive restarts; 15% is the first-run bench value. */
    var sticks: () -> ControlPreview = { ControlPreview() }
    private var throttleLimitState by mutableFloatStateOf(prefs.getFloat("throttleLimit", 1f))
    var throttleLimit: Float
        get() = throttleLimitState
        set(value) { throttleLimitState = value; prefs.edit { putFloat("throttleLimit", value) } }
    private var axisLimitState by mutableFloatStateOf(prefs.getFloat("axisLimit", 0.5f))
    var axisLimit: Float
        get() = axisLimitState
        set(value) { axisLimitState = value; prefs.edit { putFloat("axisLimit", value) } }
    private var climbSpeedState by mutableFloatStateOf(prefs.getFloat("climbSpeed", 0.4f))
    var climbSpeed: Float
        get() = climbSpeedState
        set(value) { climbSpeedState = value; prefs.edit { putFloat("climbSpeed", value) } }
    /** Last learned hover output, the starting guess for the next flight. */
    var hoverThrottle: Float
        get() = prefs.getFloat("hover", AltitudeThrottle.DEFAULT_HOVER)
        set(value) = prefs.edit { putFloat("hover", value) }
    /** Address of the last drone whose UART link came up; reconnect() looks for it. */
    val lastDevice: String? get() = prefs.getString("lastDevice", null)
    private var autoConnect: String? = null
    private val parser = MspParser()
    private var uart: BluetoothGattCharacteristic? = null
    private class Op(val command: Int, val bytes: ByteArray?)  // bytes == null: enable notifications
    private val ops = ArrayDeque<Op>()
    private var opBusy = false
    private var opToken = 0
    private var telemetryAt = 0L
    private var framesSinceEnable = 0
    private var airCount = 0
    private val telemetryTick = object : Runnable {
        override fun run() {
            if (!uartReady) return
            if (ops.none { it.command == Msp.AIR }) enqueue(Op(Msp.AIR, Msp.request(Msp.AIR)))
            if (ops.none { it.command == Msp.OPTICAL }) enqueue(Op(Msp.OPTICAL, Msp.request(Msp.OPTICAL)))
            telemetryAgeMs = if (telemetryAt == 0L) null else SystemClock.elapsedRealtime() - telemetryAt
            handler.postDelayed(this, 200)
        }
    }
    private val controlTick = object : Runnable {
        override fun run() {
            if (!controlEnabled || !uartReady) return
            ops.removeAll { it.command == Msp.RC_COMPACT }
            enqueue(Op(Msp.RC_COMPACT, sticks().rc(throttleLimit, axisLimit).frame()))
            handler.postDelayed(this, 50)
        }
    }
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var generation = 0
    private var readGeneration = 0
    private var characteristics = emptyList<BluetoothGattCharacteristic>()
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                disconnect("Bluetooth 꺼짐 · 연결 상실")
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bluetoothState, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(bluetoothState, filter)
    }

    fun hasPermissions() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun log(message: String) {
        logs = (logs + "${java.time.LocalTime.now().withNano(0)} $message").takeLast(100)
    }

    private fun safely(action: () -> Unit) {
        try { action() }
        catch (e: SecurityException) { disconnect("Bluetooth 권한이 필요합니다") }
        catch (e: IllegalStateException) { disconnect("Bluetooth 오류: ${e.message}") }
    }

    fun scan() = safely {
        if (!hasPermissions()) { status = "근처 기기 권한이 필요합니다"; return@safely }
        if (adapter?.isEnabled != true) { status = "Bluetooth를 켜 주세요"; return@safely }
        disconnect("검색 중 · 제어 송신 잠금")
        devices = emptyList()
        val selectedScanner = adapter.bluetoothLeScanner
        if (selectedScanner == null) { status = "BLE 검색을 사용할 수 없습니다"; return@safely }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post {
                    if (scanCallback !== this || !scanning) return@post
                    safely {
                        val name = result.scanRecord?.deviceName ?: result.device.name ?: "이름 없음"
                        val entry = BleDevice(result.device.address, name, result.rssi)
                        // last drone, then AIR#### (kit manual), then named devices; RSSI updates keep the row in place so taps do not jump
                        val known = devices.indexOfFirst { it.address == entry.address }
                        val last = lastDevice
                        devices = (if (known >= 0) devices.toMutableList().also { it[known] = entry } else devices + entry).takeLast(128)
                            .sortedWith(compareByDescending<BleDevice> { it.address == last }.thenByDescending { it.name.startsWith("AIR", ignoreCase = true) }
                                .thenByDescending { it.name != "이름 없음" })
                        if (entry.address == autoConnect) connect(entry.address)
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (scanCallback !== this) return@post
                    stopScan()
                    status = "검색 실패: $errorCode"
                }
            }
        }
        scanner = selectedScanner
        scanCallback = callback
        scanning = true
        selectedScanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        handler.postDelayed({
            if (scanCallback === callback) {
                stopScan()
                status = if (autoConnect != null) "마지막 드론을 찾지 못함 · 전원 확인 후 다시 연결" else "검색 완료 · 기기를 직접 선택하세요"
                autoConnect = null
            }
        }, 10_000)
    }

    /** Scan and connect to [lastDevice] as soon as it advertises. False when no drone has been connected yet. */
    fun reconnect(): Boolean {
        val target = lastDevice ?: return false
        scan()
        if (scanning) { autoConnect = target; status = "마지막 드론 찾는 중" }
        return true
    }

    private fun stopScan() {
        val callback = scanCallback
        scanCallback = null
        scanning = false
        try { if (callback != null) scanner?.stopScan(callback) }
        catch (_: SecurityException) { }
        catch (_: IllegalStateException) { } // Bluetooth may turn off before stopScan reaches the adapter.
        scanner = null
    }

    fun connect(address: String) = safely {
        if (!hasPermissions()) { status = "근처 기기 권한이 필요합니다"; return@safely }
        if (adapter?.isEnabled != true) { status = "Bluetooth를 켜 주세요"; return@safely }
        if (devices.none { it.address == address }) return@safely
        disconnect("연결 중: $address")
        val token = generation
        val callback = object : BluetoothGattCallback() {
            private fun update(connection: BluetoothGatt, action: () -> Unit) {
                handler.post { if (token == generation && connection === gatt) safely(action) }
            }
            override fun onConnectionStateChange(connection: BluetoothGatt, result: Int, newState: Int) = update(connection) {
                if (result != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnect("연결 상실/해제: GATT $result")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status = "서비스 탐색 중 · 제어 잠금"
                    if (!connection.discoverServices()) disconnect("서비스 탐색 시작 실패")
                }
            }
            override fun onServicesDiscovered(connection: BluetoothGatt, result: Int) = update(connection) {
                if (result != BluetoothGatt.GATT_SUCCESS) { disconnect("서비스 탐색 실패: $result"); return@update }
                characteristics = connection.services.flatMap { it.characteristics }
                items = characteristics.mapIndexed { index, characteristic ->
                    val p = characteristic.properties
                    val names = listOf(
                        BluetoothGattCharacteristic.PROPERTY_READ to "READ",
                        BluetoothGattCharacteristic.PROPERTY_WRITE to "WRITE",
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to "WRITE_NR",
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY to "NOTIFY",
                        BluetoothGattCharacteristic.PROPERTY_INDICATE to "INDICATE",
                    ).filter { p and it.first != 0 }.joinToString { it.second }
                    GattItem(index, characteristic.service.uuid.toString(), characteristic.uuid.toString(), names, p and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                }
                connected = true
                uart = connection.getService(SERVICE)?.getCharacteristic(UART)
                if (uart == null) {
                    status = "연결됨 · FFE0/FFE1 UART 없음 · 제어 잠금"
                    log("GATT 탐색 완료. FFE1 특성이 없어 MSP 통신을 하지 않습니다.")
                } else {
                    status = "연결됨 · UART 알림 설정 중"
                    connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    enqueue(Op(-1, null))
                }
            }
            override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, result: Int) = update(connection) {
                opFinished()
                if (result != BluetoothGatt.GATT_SUCCESS) { status = "알림 설정 실패: GATT $result"; return@update }
                uartReady = true
                prefs.edit { putString("lastDevice", address) }
                status = "연결됨 · 상태 수신 대기 · 조종 송신 꺼짐"
                handler.removeCallbacks(telemetryTick)
                handler.post(telemetryTick)
            }
            override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, result: Int) = update(connection) {
                opFinished()
                if (result != BluetoothGatt.GATT_SUCCESS) log("쓰기 실패: GATT $result")
            }
            override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = update(connection) {
                received(value)
            }
            @Deprecated("API 31/32 callback")
            override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) { val copy = characteristic.value?.copyOf() ?: byteArrayOf(); update(connection) { received(copy) } }
            }
            override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, result: Int) = update(connection) {
                readResult(characteristic, value, result)
            }
            @Deprecated("API 31/32 callback")
            override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, result: Int) {
                if (Build.VERSION.SDK_INT < 33) update(connection) { readResult(characteristic, characteristic.value ?: byteArrayOf(), result) }
            }
        }
        gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) { disconnect("연결 시작 실패"); return@safely }
        handler.postDelayed({ if (token == generation && !connected) disconnect("연결/서비스 탐색 시간 초과") }, 15_000)
    }

    fun read(id: Int) = safely {
        if (!connected || reading || opBusy || !hasPermissions() || items.none { it.id == id && it.readable }) return@safely
        val characteristic = characteristics.getOrNull(id) ?: return@safely
        reading = true
        val token = ++readGeneration
        if (gatt?.readCharacteristic(characteristic) != true) { reading = false; log("읽기 시작 실패"); return@safely }
        handler.postDelayed({
            // A timed-out GATT operation may still complete: close the link before another read.
            if (token == readGeneration && reading) disconnect("GATT 읽기 시간 초과")
        }, 5_000)
    }

    private fun readResult(characteristic: BluetoothGattCharacteristic, value: ByteArray, result: Int) {
        reading = false
        readGeneration++
        handler.post { pump() }
        if (result == BluetoothGatt.GATT_SUCCESS) {
            val hex = value.take(128).joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            log("${characteristic.uuid}: $hex${if (value.size > 128) " … (${value.size} bytes)" else ""}")
        } else log("읽기 실패: GATT $result")
    }

    /** Read-only configuration requests: RC tuning, mode boxes, board status, received rcData. */
    fun readConfig() {
        if (!uartReady) return
        listOf(Msp.RC_TUNING, Msp.BOX, Msp.STATUS, Msp.RC).forEach { enqueue(Op(it, Msp.request(it))) }
    }

    /** Stream RC150 frames. Only for a link whose 199 reply identifies this project's guarded firmware. */
    fun setControl(enabled: Boolean) {
        if (!enabled) { controlEnabled = false; ops.removeAll { it.command == Msp.RC_COMPACT }; status = "조종 송신 꺼짐"; return }
        val t = telemetry
        when {
            !uartReady -> status = "UART 연결 필요"
            t == null || (telemetryAgeMs ?: Long.MAX_VALUE) > 1000 -> status = "드론 상태(199) 응답 없음 · 조종 잠금"
            !t.controlFirmware -> status = "펌웨어 ID ${t.firmwareId} · 보호 펌웨어(231/232) 아님 · 조종 잠금"
            else -> {
                controlEnabled = true; framesSinceEnable = 0
                status = "조종 송신 중 · DISARMED"
                handler.removeCallbacks(controlTick)
                handler.post(controlTick)
            }
        }
    }

    fun canArm(): Boolean {
        val t = telemetry ?: return false
        return controlEnabled && framesSinceEnable >= 6 && sticks().idle && t.controlFirmware && !t.armed &&
            (telemetryAgeMs ?: Long.MAX_VALUE) <= 600
    }

    fun arm() {
        if (!canArm()) { status = "시동 조건 미충족 · 스로틀 0·송신 0.3초·상태 수신 필요"; return }
        ops.addFirst(Op(Msp.ARM, Msp.request(Msp.ARM)))
        log("ARM 151 요청 (MCU가 최근 RC·최저 스로틀·I2C 오류 0을 다시 검사)")
        pump()
    }

    /** Long-press ARM: start the RC stream at throttle 0 if it is off, then ARM once enough frames went out. */
    fun armWhenReady() {
        if (!controlEnabled) setControl(true)
        if (!controlEnabled) return
        handler.postDelayed({ if (controlEnabled) arm() }, if (framesSinceEnable >= 6) 0L else 400L)
    }

    /** Trim step or ACC calibration. Both write EEPROM, so only on a fresh DISARMED status. */
    fun calibrate(command: Int) {
        val t = telemetry
        if (!uartReady || t == null || t.armed || (telemetryAgeMs ?: Long.MAX_VALUE) > 1000) {
            status = "보정은 연결된 시동 꺼짐 상태에서만 가능합니다"; return
        }
        enqueue(Op(command, Msp.request(command)))
        log("보정 명령 $command")
    }

    fun disarm() {
        if (!uartReady) return
        ops.removeAll { it.command == Msp.RC_COMPACT }
        ops.addFirst(Op(Msp.DISARM, Msp.request(Msp.DISARM)))
        log("DISARM 152 요청")
        pump()
    }

    /** DISARM twice, then stop the RC stream so the MCU link watchdog cuts outputs even if 152 is lost. */
    fun emergencyStop() {
        controlEnabled = false
        if (uartReady) {
            ops.clear()
            repeat(2) { ops.addLast(Op(Msp.DISARM, Msp.request(Msp.DISARM))) }
            pump()
        }
        status = "긴급 정지 · 송신 중단"
        log("긴급 정지: DISARM x2 + RC 송신 중단")
    }

    private fun enqueue(op: Op) { ops.addLast(op); pump() }

    private fun pump(): Unit = safely {
        val connection = gatt ?: return@safely
        val target = uart ?: return@safely
        if (opBusy || reading || ops.isEmpty()) return@safely
        val op = ops.removeFirst()
        val started = if (op.bytes == null) {
            connection.setCharacteristicNotification(target, true)
            val cccd = target.getDescriptor(CCCD)
            when {
                cccd == null -> false
                Build.VERSION.SDK_INT >= 33 -> connection.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                else -> @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; connection.writeDescriptor(cccd) }
            }
        } else {
            val type = if (target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= 33) connection.writeCharacteristic(target, op.bytes, type) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { target.writeType = type; target.value = op.bytes; connection.writeCharacteristic(target) }
        }
        if (!started) {
            if (op.bytes == null) { status = "알림 설정 시작 실패 · 제어 잠금"; return@safely }
            ops.addFirst(op)  // stack busy: retry on the next tick
            handler.postDelayed({ pump() }, 20)
            return@safely
        }
        opBusy = true
        if (op.command == Msp.RC_COMPACT) { framesSent++; framesSinceEnable++ }
        if (op.command != Msp.RC_COMPACT || framesSent % 20 == 0) Log.i(TAG, "tx ${op.command}: ${op.bytes?.let(Msp::hex) ?: "CCCD"}")
        val token = ++opToken
        handler.postDelayed({ if (token == opToken && opBusy) disconnect("GATT 쓰기 시간 초과 · 연결 종료") }, 1500)
    }

    private fun opFinished() { opBusy = false; opToken++; pump() }

    private fun received(value: ByteArray) {
        for (frame in parser.feed(value)) {
            val raw = Msp.hex(frame.payload)
            val air = AirStatus.decode(frame)
            if (air != null) {
                val previous = telemetry
                if (previous == null || previous.armed != air.armed || previous.firmwareId != air.firmwareId || ++airCount % 25 == 0)
                    Log.i(TAG, "rx 199: $raw → armed=${air.armed} fw=${air.firmwareId} roll=${air.rollDeci} pitch=${air.pitchDeci}")
                telemetry = air; telemetryAt = SystemClock.elapsedRealtime(); telemetryAgeMs = 0
                if (controlEnabled) status = "조종 송신 중 · ${if (air.armed) "ARMED" else "DISARMED"}"
                continue
            }
            if (frame.command == Msp.OPTICAL) { optical = OpticalStatus.decode(frame); continue }  // null on older firmware
            val text = RcTuning.decode(frame)?.toString() ?: BoardStatus.decode(frame)?.toString()
                ?: decodeUint16List(frame, Msp.BOX)?.let { "BOX ${it.joinToString { v -> "0x%03X".format(v) }}" }
                ?: decodeUint16List(frame, Msp.RC)?.let { "RC $it" }
                ?: "cmd ${frame.command}${if (frame.error) " 오류 응답" else ""}"
            config = (config.lines().filter { it.isNotBlank() && !it.startsWith("${frame.command}:") } + "${frame.command}: $text").joinToString("\n")
            log("rx ${frame.command}: $raw → $text")
            Log.i(TAG, "rx ${frame.command}${if (frame.error) "!" else ""}: $raw → $text")
        }
    }

    fun disconnect(reason: String = "연결 해제 · 제어 송신 잠금") {
        autoConnect = null
        generation++
        readGeneration++
        controlEnabled = false
        uartReady = false
        uart = null
        ops.clear()
        opBusy = false
        opToken++
        telemetry = null
        telemetryAt = 0L
        telemetryAgeMs = null
        optical = null
        handler.removeCallbacks(telemetryTick)
        handler.removeCallbacks(controlTick)
        stopScan()
        val old = gatt
        gatt = null
        try { old?.disconnect() } catch (_: SecurityException) { }
        try { old?.close() } catch (_: SecurityException) { }
        connected = false
        reading = false
        items = emptyList()
        characteristics = emptyList()
        status = reason
    }

    fun close() {
        disconnect()
        handler.removeCallbacksAndMessages(null)
        context.unregisterReceiver(bluetoothState)
    }
}

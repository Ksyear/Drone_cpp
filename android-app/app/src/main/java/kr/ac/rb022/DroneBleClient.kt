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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
                        val entry = BleDevice(result.device.address, result.scanRecord?.deviceName ?: "이름 없음", result.rssi)
                        devices = (devices.filterNot { it.address == entry.address } + entry).takeLast(128)
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
        handler.postDelayed({ if (scanCallback === callback) { stopScan(); status = "검색 완료 · 기기를 직접 선택하세요" } }, 10_000)
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
                        BluetoothGattCharacteristic.PROPERTY_WRITE to "WRITE(잠금)",
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to "WRITE_NR(잠금)",
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY to "NOTIFY",
                        BluetoothGattCharacteristic.PROPERTY_INDICATE to "INDICATE",
                    ).filter { p and it.first != 0 }.joinToString { it.second }
                    GattItem(index, characteristic.service.uuid.toString(), characteristic.uuid.toString(), names, p and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                }
                connected = true
                status = "진단 연결 · ${connection.services.size}개 서비스 · 제어 잠금"
                log("GATT 탐색 완료. 센서 값은 아직 해석하지 않습니다.")
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
        if (!connected || reading || !hasPermissions() || items.none { it.id == id && it.readable }) return@safely
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
        if (result == BluetoothGatt.GATT_SUCCESS) {
            val hex = value.take(128).joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            log("${characteristic.uuid}: $hex${if (value.size > 128) " … (${value.size} bytes)" else ""}")
        } else log("읽기 실패: GATT $result")
    }

    fun disconnect(reason: String = "연결 해제 · 제어 송신 잠금") {
        generation++
        readGeneration++
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

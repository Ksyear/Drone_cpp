package kr.ac.rb022

import android.Manifest
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.net.Inet4Address

private val Background = Color(0xff090E14)
private val Panel = Color(0xff121B25)
private val Outline = Color(0xff263543)
private val Muted = Color(0xff8D9EAF)
private val Accent = Color(0xff66E3CC)
private val Coral = Color(0xffFF646E)
private val Amber = Color(0xffFFC46B)
private val White = Color(0xffEDF4FA)

class MainActivity : ComponentActivity() {
    private lateinit var ble: DroneBleClient
    private lateinit var camera: CameraClient
    private var preview by mutableStateOf(ControlPreview())
    private var hub: MissionHub? = null
    private var mission by mutableStateOf<MissionLink?>(null)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it } && ble.hasPermissions()) ble.scan()
        else ble.disconnect("근처 기기 권한을 허용하세요")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(0xff090E14.toInt()), SystemBarStyle.dark(0xff090E14.toInt()))
        ble = DroneBleClient(applicationContext)
        ble.sticks = { preview }
        camera = CameraClient(applicationContext)
        val preferences = getSharedPreferences("camera", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Background,
                background = Background, surface = Panel, onSurface = White, error = Coral, outline = Outline)) {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var address by rememberSaveable { mutableStateOf(preferences.getString("stream", "") ?: "") }
                LaunchedEffect(ble.connected) { if (!ble.connected) preview = ControlPreview() }
                val scan = {
                    preview = ControlPreview()
                    if (ble.hasPermissions()) ble.scan()
                    else permissionRequest.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                }
                // last drone first; before the first connection, open the list instead
                val connectDrone = { if (!ble.hasPermissions() || !ble.reconnect()) { showSettings = true; scan() } }
                Surface(Modifier.fillMaxSize(), color = Background) {
                    Row(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StickPad("THROTTLE", "YAW", preview.yaw, 1f - 2f * preview.throttle,
                            "${(preview.throttle * 100).toInt()}% · 상한 ${(ble.throttleLimit * 100).toInt()}%",
                            Modifier.weight(0.3f).fillMaxHeight(), { x, y -> preview = preview.left(x, y) },
                            { preview = preview.copy(yaw = 0f) })  // throttle holds its position; yaw springs back
                        Column(Modifier.weight(0.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusBar(ble, camera, mission != null) { showSettings = true }
                            CameraViewport(camera, ble, 1000 + 4 * preview.rc(ble.throttleLimit, ble.axisLimit).throttle,
                                Modifier.weight(1f).fillMaxWidth(), connectDrone) {
                                camera.savedMedia?.let { uri ->
                                    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, camera.savedType)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                }
                            }
                            ActionBar(ble, onDisarm = { preview = preview.copy(throttle = 0f); ble.disarm() },
                                onStop = { preview = ControlPreview(); ble.emergencyStop() })
                        }
                        StickPad("PITCH", "ROLL", preview.roll, -preview.pitch, "${(preview.pitch * 100).toInt()} / ${(preview.roll * 100).toInt()}",
                            Modifier.weight(0.3f).fillMaxHeight(), { x, y -> preview = preview.right(x, y) },
                            { preview = preview.copy(pitch = 0f, roll = 0f) })
                    }
                }
                if (showSettings) SettingsSheet(ble, camera, preview, mission, address, { address = it }, { showSettings = false },
                    onScan = scan, onPreview = { preview = it }, onCameraConnect = {
                        preferences.edit().putString("stream", address.trim()).apply()
                        camera.connect(address)
                    }, onWifi = { startActivity(Intent(Settings.Panel.ACTION_WIFI)) },
                    onMission = { if (mission == null) startMission() else stopMission() })
            }
        }
    }

    private fun startMission() {
        val clock = { System.nanoTime() / 1_000_000 }
        val controller = MissionController()
        val session = MissionSession(controller, clock)
        val page = assets.open("quest/index.html").use { it.readBytes() }
        val server = MissionHub(session, controller, { page }, camera::relayFrame, clock)
        try {
            val port = server.start()
            hub = server
            mission = MissionLink(session, controller, clock, wifiAddresses().map { "http://$it:$port/" })
        } catch (e: Exception) {
            server.stop()
            mission = null
        }
    }

    private fun stopMission() {
        hub?.stop()
        hub = null
        mission = null
    }

    private fun wifiAddresses(): List<String> {
        val manager = getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.filter { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { network -> manager.getLinkProperties(network)?.linkAddresses.orEmpty() }
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
    }

    override fun onStop() {
        stopMission()
        preview = ControlPreview()
        ble.disconnect("앱 비활성 · 연결 해제")  // RC stream stops: the MCU link watchdog cuts armed outputs
        camera.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        camera.close()
        ble.close()
        super.onDestroy()
    }
}

/** Running hub handles shown on the phone; the controller is read under the session lock. */
class MissionLink(val session: MissionSession, val controller: MissionController, val clock: () -> Long, val urls: List<String>)

@Composable
private fun StatusBar(ble: DroneBleClient, camera: CameraClient, quest: Boolean, onSettings: () -> Unit) {
    val t = ble.telemetry
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(if (ble.uartReady) "BLE" else if (ble.connected) "BLE · UART 없음" else "BLE 끊김", if (ble.uartReady) Accent else Muted)
        Chip(when { t == null -> "FW —"; t.controlFirmware -> "FW ${t.firmwareId} 보호"; else -> "FW ${t.firmwareId} 잠금" },
            if (t?.controlFirmware == true) Accent else Muted)
        Chip(when { t == null -> "상태 —"; t.armed -> "ARMED"; else -> "DISARMED" }, if (t?.armed == true) Coral else Muted)
        Chip(if (ble.controlEnabled) "송신 ${ble.framesSent}" else "송신 꺼짐", if (ble.controlEnabled) Amber else Muted)
        if (camera.active || camera.connecting) Chip(if (camera.active) "CAM ${camera.framesPerSecond}fps" else "CAM 연결 중", Accent)
        if (quest) Chip("QUEST", Accent)
      }
        Surface(onClick = onSettings, shape = RoundedCornerShape(12.dp), color = Panel, border = BorderStroke(1.dp, Outline),
            modifier = Modifier.semantics { contentDescription = "설정" }) {
            Text("설정", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
        modifier = Modifier.border(1.dp, Outline, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
private fun CameraViewport(camera: CameraClient, ble: DroneBleClient, throttleUs: Int, modifier: Modifier, onConnect: () -> Unit, onGallery: () -> Unit) {
    var frameAge by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(camera.active) {
        while (camera.active) { frameAge = camera.relayFrame()?.let { System.nanoTime() / 1_000_000 - it.receivedMs }; delay(300) }
        frameAge = null
    }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xff0C141D)).border(1.dp, Outline, RoundedCornerShape(18.dp))) {
        val image = camera.bitmap
        val t = ble.telemetry
        // The camera is optional: without video the center shows the flight state instead.
        if (image == null) FlightView(ble, throttleUs, onConnect)
        else {
            Image(image.asImageBitmap(), "ESP32-CAM 실시간 영상", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                HudTag(t?.let { "ROLL %.1f°  PITCH %.1f°".format(it.rollDeci / 10f, it.pitchDeci / 10f) } ?: "자세 —", Muted)
                HudTag(t?.let { "VBAT raw ${it.vbatRaw} · ${ble.telemetryAgeMs ?: 0} ms" } ?: "상태 수신 없음", Muted)
            }
        }
        if (camera.active || camera.connecting || camera.recording) {
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                HudTag(when {
                    !camera.active -> "영상 연결 중"
                    (frameAge ?: 0) > 1000 -> "영상 끊김 · ${frameAge} ms 전 수신"
                    else -> "LIVE ${camera.framesPerSecond} FPS · 수신 후 ${frameAge ?: 0} ms"
                }, if (camera.active && (frameAge ?: 0) <= 1000) Accent else Muted)
                if (camera.recording) HudTag("REC", Coral)
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(onClick = { camera.snapshot() }, enabled = camera.active && !camera.savingPhoto, modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = White, contentColor = Background)) {
                    Icon(painterResource(R.drawable.ic_camera), "사진 촬영", Modifier.size(20.dp))
                }
                OutlinedIconButton(onClick = { camera.toggleRecording() }, enabled = camera.active || camera.recording, modifier = Modifier.size(44.dp),
                    border = BorderStroke(2.dp, if (camera.active) Coral else Outline)) {
                    Box(Modifier.size(if (camera.recordRequested) 14.dp else 18.dp)
                        .background(if (camera.active) Coral else Muted, if (camera.recordRequested) RoundedCornerShape(4.dp) else CircleShape)
                        .semantics { contentDescription = if (camera.recordRequested) "녹화 정지 및 저장" else "영상 녹화 시작" })
                }
                IconButton(onClick = onGallery, enabled = camera.savedMedia != null, modifier = Modifier.size(44.dp).background(Panel, RoundedCornerShape(12.dp))) {
                    Icon(painterResource(R.drawable.ic_gallery), "마지막 저장 사진 또는 영상 열기", tint = if (camera.savedMedia != null) White else Muted)
                }
            }
        }
    }
}

/** Camera-free center: link and ARM state, what the throttle stick sends, attitude and link age. */
@Composable
private fun FlightView(ble: DroneBleClient, throttleUs: Int, onConnect: () -> Unit) {
    val t = ble.telemetry
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(when { !ble.connected -> "드론 연결 안 됨"; t == null -> "상태 수신 대기"; t.armed -> "ARMED"; else -> "DISARMED" },
            color = if (t?.armed == true) Coral else White, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(ble.status, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
        if (!ble.connected) Button(onClick = onConnect, enabled = !ble.scanning) { Text(if (ble.scanning) "드론 찾는 중" else "드론 연결") }
        else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Readout("THR µs", "$throttleUs", Modifier.weight(1f))
                Readout("ROLL", t?.let { "%.1f°".format(it.rollDeci / 10f) } ?: "—", Modifier.weight(1f))
                Readout("PITCH", t?.let { "%.1f°".format(it.pitchDeci / 10f) } ?: "—", Modifier.weight(1f))
                Readout("수신", ble.telemetryAgeMs?.let { "$it ms" } ?: "—", Modifier.weight(1f))
            }
            Text(t?.let { "VBAT raw ${it.vbatRaw} · 트림 ${it.trimPitch}/${it.trimRoll} · FW ${it.firmwareId}" } ?: "",
                color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun Readout(label: String, value: String, modifier: Modifier) {
    Column(modifier.border(1.dp, Outline, RoundedCornerShape(10.dp)).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun HudTag(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.background(Background.copy(alpha = .84f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
}

@Composable
private fun ActionBar(ble: DroneBleClient, onDisarm: () -> Unit, onStop: () -> Unit) {
    val armed = ble.telemetry?.armed == true
    // long press turns the RC stream on by itself, so a fresh guarded DISARMED link at throttle 0 is enough
    val canArm = ble.framesSent >= 0 && (ble.canArm() || (!ble.controlEnabled && ble.uartReady && ble.telemetry?.controlFirmware == true &&
        !armed && ble.sticks().throttle == 0f))
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp))
            .background(if (canArm) Accent.copy(alpha = .22f) else Panel).border(1.dp, if (canArm) Accent else Outline, RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures(onLongPress = { ble.armWhenReady() }) }
            .semantics { contentDescription = "길게 눌러 시동" }, contentAlignment = Alignment.Center) {
            Text(if (armed) "ARMED" else "길게 눌러\n시동", color = if (canArm || armed) White else Muted, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = onDisarm, enabled = ble.uartReady, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(4.dp)) {
            Text("DISARM", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Button(onClick = onStop, modifier = Modifier.weight(1.2f).fillMaxHeight(), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Background)) {
            Text("STOP", fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
    }
}

@Composable
private fun StickPad(primary: String, secondary: String, x: Float, y: Float, value: String,
                     modifier: Modifier, onMove: (Float, Float) -> Unit, onRelease: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Panel).border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(secondary, color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
        }
        val move by rememberUpdatedState(onMove)
        val release by rememberUpdatedState(onRelease)
        Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)
            .semantics { contentDescription = "$primary / $secondary 조이스틱, 설정의 드론 진단에 접근성 슬라이더 제공" }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { release() }, onDragCancel = { release() }, onDrag = { change, _ ->
                    change.consume()
                    val r = size.width.coerceAtMost(size.height) / 2f
                    move(((change.position.x - size.width / 2f) / r).coerceIn(-1f, 1f), ((change.position.y - size.height / 2f) / r).coerceIn(-1f, 1f))
                })
            }) {
            val c = center
            val radius = size.minDimension / 2 - 4.dp.toPx()
            drawCircle(Color(0xff0B121A), radius)
            drawCircle(Outline, radius, style = Stroke(1.dp.toPx()))
            drawCircle(Outline, radius * .6f, style = Stroke(1.dp.toPx()))
            drawLine(Outline, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y))
            drawLine(Outline, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius))
            val knob = c + Offset(x * (radius - 24.dp.toPx()), y * (radius - 24.dp.toPx()))
            drawLine(Accent.copy(alpha = .55f), c, knob, 2.dp.toPx())
            drawCircle(Accent.copy(alpha = .12f), 34.dp.toPx(), knob)
            drawCircle(Color(0xff263F46), 22.dp.toPx(), knob)
            drawCircle(Accent, 22.dp.toPx(), knob, style = Stroke(2.dp.toPx()))
        }
        Text(value, color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(ble: DroneBleClient, camera: CameraClient, preview: ControlPreview, mission: MissionLink?, address: String,
                          onAddress: (String) -> Unit, onDismiss: () -> Unit, onScan: () -> Unit, onPreview: (ControlPreview) -> Unit,
                          onCameraConnect: () -> Unit, onWifi: () -> Unit, onMission: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            TabRow(selectedTabIndex = tab, containerColor = Panel) {
                listOf("드론", "카메라", "Quest").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
            }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> DroneSettings(ble, preview, onScan, onPreview)
                1 -> CameraSettings(camera, address, onAddress, onCameraConnect, onWifi)
                else -> QuestSettings(mission, onMission)
            }
        }
    }
}

@Composable
private fun DroneSettings(ble: DroneBleClient, preview: ControlPreview, onScan: () -> Unit, onPreview: (ControlPreview) -> Unit) {
    var diagnostics by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(ble.status, color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onScan, enabled = !ble.scanning) { Text(if (ble.scanning) "검색 중" else "BLE 검색") }
                OutlinedButton(onClick = { ble.disconnect() }) { Text("연결 해제") }
                OutlinedButton(onClick = { ble.readConfig() }, enabled = ble.uartReady) { Text("설정 읽기") }
            }
        }
        items(ble.devices, key = { it.address }) { device ->
            Surface(onClick = { ble.connect(device.address) }, shape = RoundedCornerShape(12.dp), color = Background) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text(device.name); Text(device.address, color = Muted, fontSize = 10.sp) }
                    Text("${device.rssi} dBm", color = Accent, fontSize = 11.sp)
                }
            }
        }
        item {
            val t = ble.telemetry
            Text("상태(199)", fontWeight = FontWeight.Bold)
            Text(t?.let { "flightState 0x%02X · ${if (it.armed) "ARMED" else "DISARMED"} · FW ${it.firmwareId}\nroll ${it.rollDeci / 10f}° pitch ${it.pitchDeci / 10f}° · alt ${it.estAlt} · altHold ${it.altHold} (방향값 아님)\nvbat raw ${it.vbatRaw} · trim ${it.trimPitch}/${it.trimRoll} · ${ble.telemetryAgeMs} ms 전".format(it.flightState) }
                ?: "응답 없음", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (ble.config.isNotEmpty()) Text(ble.config, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("조종 송신", fontWeight = FontWeight.Bold)
                    Text("보호 펌웨어(ID ${Msp.CONTROL_FIRMWARE_ID})가 응답할 때만 켜집니다. 시동 버튼을 길게 누르면 자동으로 켜집니다. 송신이 끊기면 MCU가 0.5초 안에 출력을 끊습니다.", color = Muted, fontSize = 11.sp)
                }
                Switch(ble.controlEnabled, { ble.setControl(it) })
            }
            Text("스로틀 상한 ${(ble.throttleLimit * 100).toInt()}% · 처음 값 15%(모터 시험용), 비행하려면 올리세요. 바꾼 값은 유지됩니다", color = Muted, fontSize = 12.sp)
            Slider(ble.throttleLimit, { ble.throttleLimit = it }, valueRange = 0.1f..1f)
            Text("조종 감도 ${(ble.axisLimit * 100).toInt()}%", color = Muted, fontSize = 12.sp)
            Slider(ble.axisLimit, { ble.axisLimit = it }, valueRange = 0.2f..1f)
        }
        item { LevelSettings(ble) }
        item {
            TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "진단 접기" else "GATT 진단 / 접근성 입력") }
        }
        if (diagnostics) {
            items(ble.items, key = { it.id }) { item ->
                Column {
                    Text(item.service, fontSize = 10.sp, color = Muted)
                    Text(item.uuid, fontSize = 11.sp)
                    Text(item.properties, fontSize = 10.sp, color = Accent)
                    if (item.readable) TextButton(onClick = { ble.read(item.id) }, enabled = !ble.reading) { Text("HEX 읽기") }
                }
            }
            item {
                Text(ble.logs.takeLast(8).joinToString("\n").ifEmpty { "수신 기록 없음" }, fontSize = 10.sp, color = Muted)
                Text("아래 슬라이더는 스틱과 같으며 조종 송신이 켜져 있으면 전송됩니다.", color = Amber, fontSize = 11.sp)
                PreviewSlider("스로틀", preview.throttle, 0f..1f) { onPreview(preview.copy(throttle = it)) }
                PreviewSlider("Yaw", preview.yaw, -1f..1f) { onPreview(preview.copy(yaw = it)) }
                PreviewSlider("Roll", preview.roll, -1f..1f) { onPreview(preview.copy(roll = it)) }
                PreviewSlider("Pitch", preview.pitch, -1f..1f) { onPreview(preview.copy(pitch = it)) }
            }
        }
    }
}

/** App-side replacement for the legacy stick-combo calibration and trims, which the app's axis limits block. */
@Composable
private fun LevelSettings(ble: DroneBleClient) {
    val t = ble.telemetry
    val ready = ble.uartReady && t != null && !t.armed
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(5000); confirm = false } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("수평 보정", fontWeight = FontWeight.Bold)
        Text("시동 꺼짐에서만 동작하며 드론 EEPROM에 저장됩니다. 현재 트림 ${t?.let { "${it.trimPitch}/${it.trimRoll}" } ?: "—"} (피치/롤)",
            color = Muted, fontSize = 11.sp)
        Button(onClick = { if (confirm) { ble.calibrate(Msp.ACC_CALIBRATION); confirm = false } else confirm = true }, enabled = ready,
            colors = if (confirm) ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Background) else ButtonDefaults.buttonColors()) {
            Text(if (confirm) "평평한 곳에 두고 움직이지 않으면 다시 누르기" else "가속도 센서 수평 보정")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("트림", color = Muted, fontSize = 12.sp)
            listOf("▲" to Msp.TRIM_UP, "▼" to Msp.TRIM_DOWN, "◀" to Msp.TRIM_LEFT, "▶" to Msp.TRIM_RIGHT).forEach { (label, command) ->
                OutlinedButton(onClick = { ble.calibrate(command) }, enabled = ready, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(label) }
            }
        }
        Text("트림 방향의 실제 효과(앞/뒤·좌/우)는 비행으로 확인하세요. 흐른 방향을 보고 착륙해 시동을 끈 뒤 반대쪽으로 한 칸씩 누릅니다.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun CameraSettings(camera: CameraClient, address: String, onAddress: (String) -> Unit, onConnect: () -> Unit, onWifi: () -> Unit) {
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("휴대폰을 카메라 Wi-Fi(카메라가 만드는 AP)에 연결한 뒤 주소를 입력하세요. 영상(Wi-Fi)과 조종(BLE)은 별개입니다.", color = Muted, fontSize = 12.sp)
        TextButton(onClick = { onAddress("http://192.168.4.1:81/stream"); error = "" }) { Text("카메라 AP 주소 사용") }
        OutlinedTextField(address, { onAddress(it); error = "" }, label = { Text("MJPEG 스트림 주소") },
            placeholder = { Text("http://192.168.4.1:81/stream", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = error.isNotEmpty())
        if (error.isNotEmpty()) Text(error, color = Coral, fontSize = 12.sp)
        Text(camera.status + if (camera.message.isNotEmpty()) " · ${camera.message}" else "", color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { try { cameraUrl(address); onConnect() } catch (e: Exception) { error = e.message ?: "주소 오류" } }) { Text("영상 연결") }
            OutlinedButton(onClick = { camera.disconnect() }) { Text("연결 해제") }
            TextButton(onClick = onWifi) { Text("Wi-Fi") }
        }
        Text("촬영은 JPEG, 녹화는 무음 MP4로 갤러리에 저장됩니다. 앱을 나가면 연결과 녹화를 종료합니다.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun QuestSettings(link: MissionLink?, onToggle: () -> Unit) {
    var snapshot by remember { mutableStateOf<MissionSnapshot?>(null) }
    LaunchedEffect(link) {
        while (link != null) { snapshot = synchronized(link.session) { link.controller.snapshot(link.clock()) }; delay(300) }
        snapshot = null
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Quest에서 아래 주소와 코드로 접속합니다. 목표 이동은 가상 드론만 움직이며 기체로 보내지 않습니다.", color = Muted, fontSize = 12.sp)
        Button(onClick = onToggle) { Text(if (link == null) "Quest 중계 시작" else "중계 중지") }
        if (link != null) {
            Text(link.urls.ifEmpty { listOf("Wi-Fi 주소 없음 — 같은 Wi-Fi에 연결하세요") }.joinToString("\n"), color = Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Text("연결 코드  ${link.session.pairingCode}", fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            snapshot?.let { s ->
                Text("${s.state} · ${s.reason.ifEmpty { "-" }}", color = White, fontSize = 13.sp)
                Text(s.pose?.let { p -> "SIM W %.2f, %.2f, %.2f m · %d ms".format(p.w.x, p.w.y, p.w.z, s.poseAgeMs ?: 0) } ?: "위치 없음",
                    color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Text("실기 자동이동 잠금: " + MissionHub.LOCK_REASONS.joinToString(", "), color = Coral, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PreviewSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, color = Muted, fontSize = 11.sp)
    Slider(value, onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
}

package kr.ac.rb022

import android.Manifest
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.style.TextOverflow
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
        camera = CameraClient(applicationContext)
        val preferences = getSharedPreferences("camera", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Background,
                background = Background, surface = Panel, onSurface = White, error = Coral, outline = Outline)) {
                var showLink by rememberSaveable { mutableStateOf(false) }
                var showCamera by rememberSaveable { mutableStateOf(false) }
                var showMission by rememberSaveable { mutableStateOf(false) }
                var address by rememberSaveable { mutableStateOf(preferences.getString("stream", "") ?: "") }
                LaunchedEffect(ble.connected) { if (!ble.connected) preview = ControlPreview() }
                Surface(Modifier.fillMaxSize(), color = Background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                        ConsoleHeader(ble.connected, camera.active, mission != null, { showLink = true }, { showCamera = true }, { showMission = true })
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val wide = maxWidth > maxHeight
                            if (wide) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ControlPod("01", "THROTTLE", "YAW", preview.yaw, 1f - 2f * preview.throttle,
                                        "${(preview.throttle * 100).toInt()}%", Modifier.width(152.dp),
                                        { x, y -> preview = preview.left(x, y) }, { preview = preview.copy(throttle = 0f, yaw = 0f) })
                                    CameraViewport(camera, Modifier.weight(1f).fillMaxHeight(), { showCamera = true })
                                    ControlPod("02", "PITCH", "ROLL", preview.roll, -preview.pitch,
                                        "${(preview.pitch * 100).toInt()} / ${(preview.roll * 100).toInt()}", Modifier.width(152.dp),
                                        { x, y -> preview = preview.right(x, y) }, { preview = preview.copy(pitch = 0f, roll = 0f) })
                                }
                            } else {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CameraViewport(camera, Modifier.weight(1f).fillMaxWidth(), { showCamera = true })
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        ControlPod("01", "THROTTLE", "YAW", preview.yaw, 1f - 2f * preview.throttle,
                                            "${(preview.throttle * 100).toInt()}%", Modifier.weight(1f),
                                            { x, y -> preview = preview.left(x, y) }, { preview = preview.copy(throttle = 0f, yaw = 0f) })
                                        ControlPod("02", "PITCH", "ROLL", preview.roll, -preview.pitch,
                                            "${(preview.pitch * 100).toInt()} / ${(preview.roll * 100).toInt()}", Modifier.weight(1f),
                                            { x, y -> preview = preview.right(x, y) }, { preview = preview.copy(pitch = 0f, roll = 0f) })
                                    }
                                }
                            }
                        }
                        MediaBar(camera, {
                            camera.savedMedia?.let { uri ->
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, camera.savedType)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            }
                        }, { preview = ControlPreview() })
                    }
                }
                if (showLink) LinkSheet(ble, preview, { showLink = false }, {
                    preview = ControlPreview()
                    if (ble.hasPermissions()) ble.scan()
                    else permissionRequest.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                }, { preview = it })
                if (showCamera) CameraSheet(camera, address, { address = it }, { showCamera = false }, {
                    preferences.edit().putString("stream", address.trim()).apply()
                    camera.connect(address)
                    showCamera = false
                }, { startActivity(Intent(Settings.Panel.ACTION_WIFI)) })
                if (showMission) MissionSheet(mission, { showMission = false }, { if (mission == null) startMission() else stopMission() })
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
        ble.disconnect("앱 비활성 · 연결 해제")
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
private fun ConsoleHeader(ble: Boolean, camera: Boolean, mission: Boolean, onBle: () -> Unit, onCamera: () -> Unit, onMission: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_drone), "AIR COPTER", Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("AIR COPTER", fontSize = 17.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text("RB022  /  FLIGHT CONSOLE", fontSize = 9.sp, letterSpacing = 1.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusChip("BLE", ble, onBle)
        Spacer(Modifier.width(8.dp))
        StatusChip("CAM", camera, onCamera)
        Spacer(Modifier.width(8.dp))
        StatusChip("QUEST", mission, onMission)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionSheet(link: MissionLink?, onDismiss: () -> Unit, onToggle: () -> Unit) {
    var snapshot by remember { mutableStateOf<MissionSnapshot?>(null) }
    LaunchedEffect(link) {
        while (link != null) { snapshot = synchronized(link.session) { link.controller.snapshot(link.clock()) }; delay(300) }
        snapshot = null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Quest 목표 이동", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Quest 브라우저에서 아래 주소를 열고 코드를 입력하세요. 목표는 가상 드론 시뮬레이션만 움직이며 BLE 조종 송신은 잠겨 있습니다.", color = Muted, fontSize = 12.sp)
            Button(onClick = onToggle) { Text(if (link == null) "Quest 중계 시작" else "중계 중지") }
            if (link != null) {
                Text(link.urls.ifEmpty { listOf("Wi-Fi 주소 없음 — RB022-CAM 또는 같은 Wi-Fi에 연결하세요") }.joinToString("\n"), color = Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text("연결 코드  ${link.session.pairingCode}", fontSize = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                snapshot?.let { s ->
                    Text("${s.state} · ${s.reason.ifEmpty { "-" }}", color = White, fontSize = 13.sp)
                    Text(s.pose?.let { p -> "SIM W %.2f, %.2f, %.2f m · %d ms".format(p.w.x, p.w.y, p.w.z, s.poseAgeMs ?: 0) } ?: "위치 없음", color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Text("실기 잠금: " + MissionHub.LOCK_REASONS.joinToString(", "), color = Coral, fontSize = 11.sp)
            }
            Text("카메라 영상은 이 휴대폰이 받은 프레임을 Quest로 다시 보냅니다. 카메라에 Quest를 직접 연결하지 마세요(단일 스트림).", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StatusChip(label: String, online: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Panel, border = BorderStroke(1.dp, Outline)) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(6.dp).background(if (online) Accent else Muted, CircleShape))
            Text(label, color = if (online) Accent else White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CameraViewport(camera: CameraClient, modifier: Modifier, onSettings: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xff0C141D)).border(1.dp, Outline, RoundedCornerShape(22.dp))) {
        val image = camera.bitmap
        if (image != null) Image(image.asImageBitmap(), "ESP32-CAM 실시간 영상", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else {
            Canvas(Modifier.fillMaxSize()) {
                val grid = Color(0xff192733)
                for (x in 0..12) drawLine(grid, Offset(size.width * x / 12, 0f), Offset(size.width * x / 12, size.height))
                for (y in 0..10) drawLine(grid, Offset(0f, size.height * y / 10), Offset(size.width, size.height * y / 10))
                drawCircle(Color(0xff203A45), size.minDimension * .24f, style = Stroke(1.dp.toPx()))
                drawCircle(Color(0xff294B54), size.minDimension * .16f, style = Stroke(1.dp.toPx()))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Background.copy(.1f), Background.copy(.8f)))))
            Column(Modifier.align(Alignment.Center).padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(34.dp), tint = Accent)
                Spacer(Modifier.height(10.dp))
                Text(if (camera.connecting) "영상 연결 중" else "카메라를 연결하세요", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("ESP32-CAM · Wi-Fi MJPEG", color = Muted, fontSize = 11.sp)
                if (camera.connecting) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                else TextButton(onClick = onSettings) { Text("영상 연결 설정", fontSize = 12.sp) }
            }
        }
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            HudTag(if (camera.active) "LIVE  /  ${camera.framesPerSecond} FPS" else "CAMERA  /  OFFLINE", if (camera.active) Accent else Muted)
            HudTag("제어 송신 잠금", Muted)
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            HudTag(if (image != null) "${image.width} × ${image.height}" else "영상 신호 대기", Muted)
            if (camera.recording) HudTag("REC", Coral) else HudTag("IMU · BAT  —", Muted)
        }
    }
}

@Composable
private fun HudTag(text: String, color: Color) {
    Text(text, color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.background(Background.copy(alpha = .84f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
private fun ControlPod(index: String, primary: String, secondary: String, x: Float, y: Float, value: String,
                       modifier: Modifier, onMove: (Float, Float) -> Unit, onRelease: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Panel).border(1.dp, Outline, RoundedCornerShape(20.dp))
        .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(index, color = Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        val move by rememberUpdatedState(onMove)
        val release by rememberUpdatedState(onRelease)
        Canvas(Modifier.padding(vertical = 5.dp).size(124.dp)
            .semantics { contentDescription = "$primary / $secondary 조이스틱 미리보기, BLE 패널에 접근성 슬라이더 제공" }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { release() }, onDragCancel = { release() }, onDrag = { change, _ ->
                    change.consume()
                    move((change.position.x / size.width * 2 - 1).coerceIn(-1f, 1f),
                        (change.position.y / size.height * 2 - 1).coerceIn(-1f, 1f))
                })
            }) {
            val c = center
            val radius = size.minDimension / 2 - 5.dp.toPx()
            drawCircle(Color(0xff0B121A), radius)
            drawCircle(Outline, radius, style = Stroke(1.dp.toPx()))
            drawCircle(Outline, radius * .65f, style = Stroke(1.dp.toPx()))
            drawLine(Outline, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y))
            drawLine(Outline, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius))
            val knob = c + Offset(x * (radius - 14.dp.toPx()), y * (radius - 14.dp.toPx()))
            drawLine(Accent.copy(alpha = .55f), c, knob, 2.dp.toPx())
            drawCircle(Accent.copy(alpha = .1f), 23.dp.toPx(), knob)
            drawCircle(Color(0xff263F46), 15.dp.toPx(), knob)
            drawCircle(Accent, 15.dp.toPx(), knob, style = Stroke(1.5.dp.toPx()))
            drawCircle(Accent, 3.dp.toPx(), knob)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(secondary, color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
            Text(value, color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun MediaBar(camera: CameraClient, onGallery: () -> Unit, onReset: () -> Unit) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(camera.recording, camera.recordStarted) {
        while (camera.recording) { elapsed = (SystemClock.elapsedRealtime() - camera.recordStarted) / 1000; delay(500) }
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.width(70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onGallery, enabled = camera.savedMedia != null,
                    modifier = Modifier.size(48.dp).background(Panel, RoundedCornerShape(14.dp))) {
                    Icon(painterResource(R.drawable.ic_gallery), "마지막 저장 사진 또는 영상 열기", tint = if (camera.savedMedia != null) White else Muted)
                }
                Text("갤러리", color = Muted, fontSize = 9.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = { camera.snapshot() }, enabled = camera.active && !camera.savingPhoto,
                        modifier = Modifier.size(54.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = White, contentColor = Background)) {
                        Icon(painterResource(R.drawable.ic_camera), "사진 촬영", Modifier.size(24.dp))
                    }
                    Text(if (camera.savingPhoto) "저장 중" else "촬영", color = Muted, fontSize = 9.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedIconButton(onClick = { camera.toggleRecording() }, enabled = camera.active || camera.recording,
                        modifier = Modifier.size(54.dp), border = BorderStroke(2.dp, if (camera.active) Coral else Outline)) {
                        Box(Modifier.size(if (camera.recordRequested) 18.dp else 22.dp)
                            .background(if (camera.active) Coral else Muted, if (camera.recordRequested) RoundedCornerShape(4.dp) else CircleShape)
                            .semantics { contentDescription = if (camera.recordRequested) "녹화 정지 및 저장" else "영상 녹화 시작" })
                    }
                    Text(if (camera.recording) "%02d:%02d".format(elapsed / 60, elapsed % 60) else if (camera.recordRequested) "준비 중" else "녹화",
                        color = if (camera.recording) Coral else Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
            TextButton(onClick = onReset, modifier = Modifier.width(70.dp)) { Text("입력\n초기화", fontSize = 10.sp, color = Muted) }
        }
        if (camera.message.isNotEmpty()) Text(camera.message, Modifier.fillMaxWidth().padding(top = 5.dp), fontSize = 10.sp, color = Accent, maxLines = 2)
        else Text("조종은 미리보기입니다 · 모터 정지 명령을 보내지 않습니다", Modifier.padding(top = 5.dp), fontSize = 9.sp, color = Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraSheet(camera: CameraClient, address: String, onAddress: (String) -> Unit, onDismiss: () -> Unit,
                        onConnect: () -> Unit, onWifi: () -> Unit) {
    var error by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("카메라 연결", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("ESP32-CAM과 같은 Wi-Fi에 연결하세요.\n카메라 영상과 BLE 조종 연결은 별개입니다.", color = Muted, fontSize = 13.sp)
            Text("RB022-CAM 펌웨어 사용 시 휴대폰 Wi-Fi에서 RB022-CAM에 연결한 뒤 아래 주소 버튼을 누르세요. USB 연결만으로는 영상이 나오지 않습니다.", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { onAddress("http://192.168.4.1:81/stream"); error = "" }) { Text("RB022-CAM 주소 사용") }
            OutlinedTextField(address, { onAddress(it); error = "" }, label = { Text("MJPEG 스트림 주소") },
                placeholder = { Text("http://192.168.1.50:81/stream", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = error.isNotEmpty())
            if (error.isNotEmpty()) Text(error, color = Coral, fontSize = 12.sp)
            Text(camera.status, color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { try { cameraUrl(address); onConnect() } catch (e: Exception) { error = e.message ?: "주소 오류" } }) { Text("영상 연결") }
                OutlinedButton(onClick = { camera.disconnect(); onDismiss() }) { Text("연결 해제") }
                TextButton(onClick = onWifi) { Text("Wi-Fi") }
            }
            Text("촬영은 JPEG, 녹화는 무음 MP4로 휴대폰 갤러리에 저장됩니다. 앱을 나가거나 화면을 회전하면 연결과 녹화를 종료합니다.", color = Muted, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSheet(ble: DroneBleClient, preview: ControlPreview, onDismiss: () -> Unit, onScan: () -> Unit,
                      onPreview: (ControlPreview) -> Unit) {
    var diagnostics by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("드론 연결", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("휴대폰 Bluetooth 설정에서 페어링하는 대신 이 화면의 BLE 검색으로 기기를 선택하세요.", color = Muted, fontSize = 12.sp)
                Text(ble.status, color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onScan, enabled = !ble.scanning) { Text(if (ble.scanning) "검색 중" else "BLE 검색") }
                    OutlinedButton(onClick = { ble.disconnect() }) { Text("연결 해제") }
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
                Text("실제 제어는 잠금 상태", color = Accent, fontSize = 13.sp)
                Text("센서·전원·안전 제어가 검증되기 전까지 조종 입력은 전송되지 않습니다. 앱은 실제 기체의 긴급 정지 기능을 제공하지 않습니다.", color = Muted, fontSize = 12.sp)
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
                    Text(ble.logs.takeLast(6).joinToString("\n").ifEmpty { "진단 수신 기록 없음" }, fontSize = 10.sp, color = Muted)
                    PreviewSlider("스로틀 미리보기", preview.throttle, 0f..1f) { onPreview(preview.copy(throttle = it)) }
                    PreviewSlider("Yaw 미리보기", preview.yaw, -1f..1f) { onPreview(preview.copy(yaw = it)) }
                    PreviewSlider("Roll 미리보기", preview.roll, -1f..1f) { onPreview(preview.copy(roll = it)) }
                    PreviewSlider("Pitch 미리보기", preview.pitch, -1f..1f) { onPreview(preview.copy(pitch = it)) }
                }
            }
        }
    }
}

@Composable
private fun PreviewSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, color = Muted, fontSize = 11.sp)
    Slider(value, onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
}

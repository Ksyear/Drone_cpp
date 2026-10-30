# Quest 클라이언트 개발 가이드 (Unity)

기준: Mission Protocol v1(`docs/MISSION_PROTOCOL.md`), 휴대폰 허브 앱 0.12, 2026-10-30. 이 문서는 요약이다. 내용이 다르면 **규격 문서와 코드가 우선**이다.

## 1. 한 줄 요약

Quest 앱은 같은 Wi-Fi에 있는 휴대폰 허브 앱에 **HTTP(JSON 폴링 + MJPEG 영상)** 로 붙는다. 목표 위치를 보내고 상태를 받는다. 지금은 허브 안의 **가상 드론**만 움직인다.

저장소: https://github.com/Ksyear/Drone_cpp (main 브랜치)

| 파일 | 왜 보나 |
|---|---|
| `docs/MISSION_PROTOCOL.md` | 통신 규격 v1 원문. 구현 기준 |
| `android-app/app/src/main/assets/quest/index.html` | 허브가 `/`로 내주는 2D 참조 클라이언트. 페어링·폴링·명령 흐름이 짧은 JS로 다 들어 있다 |
| `android-app/app/src/main/java/kr/ac/rb022/MissionHub.kt` | HTTP 엔드포인트, 요청 크기 제한, 영상 중계 |
| `…/rb022/MissionProtocol.kt` | 페어링·세션·seq·명령 처리, 상태 JSON 생성 |
| `…/rb022/MissionController.kt`, `…/rb022/MissionJson.kt` | 상태기계·거절 사유·시뮬레이션 한계값·가상 드론 / 엄격 JSON 파서(무엇이 400이 되는지) |
| `…/rb022/MissionMath.kt` | Q↔W 변환과 정렬 추정(`Alignment.estimate`, `toW`, `toQ`) |
| `android-app/app/src/test/java/kr/ac/rb022/Mission*Test.kt` | 단위 테스트. 시험 벡터와 기대 응답 |
| `README.md`, `android-app/README.md` | 전체 현황, 휴대폰 앱 사용법 |

## 2. 전체 구조

```text
Meta Quest (Unity 앱)
   │  Wi-Fi · HTTP/1.1 평문, 요청마다 연결 1개 (WebSocket 없음)
   │    POST /api/hello      페어링 → session
   │    POST /api/command    goal / execute / cancel / land / align / pose / heartbeat
   │    GET  /api/state      상태 (5 Hz 이상 폴링, heartbeat 겸용)
   │    GET  /stream?s=…     카메라 영상 중계 (MJPEG)
   ▼
휴대폰 허브 앱 (Android, 포트 8080) ── 내부 가상 드론 시뮬레이션 50 Hz
   │  BLE (MSP)   ※ mission 명령은 여기로 보내지 않는다 (잠금)
   ▼
드론 (비행 컨트롤러)            ESP32-CAM ──Wi-Fi MJPEG──▶ 휴대폰
```

- Quest는 카메라에 직접 붙지 않는다. 카메라는 한 번에 한 스트림만 내보내므로 영상은 허브의 `/stream`으로 받는다.
- 휴대폰 화면의 수동 조종(BLE)은 실제 기체로 나간다. **Quest에서 온 mission 명령만** 기체로 가지 않는다.

## 3. 시작 방법

1. 휴대폰과 Quest(또는 PC)를 **같은 Wi-Fi**에 연결한다.
2. 휴대폰 허브 앱 → 상단 **설정** → **Quest** 탭 → **Quest 중계 시작**.
3. 화면에 `http://<휴대폰 IP>:8080/` 주소와 `연결 코드 NNNNNN`(6자리)이 뜬다. Wi-Fi 주소를 못 찾으면 "Wi-Fi 주소 없음"이 뜬다.
4. 먼저 PC나 Quest 브라우저로 그 주소를 연다 → 코드 입력 → 평면도 클릭 → **목표 전송** → **실행** → 도착 뒤 **착륙**. 여기까지 되면 네트워크와 허브는 정상이다. 그다음 Unity 앱을 같은 주소로 붙인다.

주의할 점:
- 허브 앱이 화면에서 사라지면(백그라운드, 화면 꺼짐) 허브도 멈춘다. 다시 시작하면 **코드와 세션이 모두 바뀐다**.
- 세션은 한 번에 하나다. 브라우저, Unity, curl이 번갈아 페어링하면 먼저 붙은 쪽은 401을 받는다.
- 휴대폰 IP는 Wi-Fi 재접속 때 바뀔 수 있다. Unity 앱에 IP 입력 칸을 둔다.

PC에서 curl로 확인하기:
```sh
curl -s -X POST http://<휴대폰 IP>:8080/api/hello -H 'X-RB022-Client: curl' \
  -H 'Content-Type: application/json' -d '{"v":1,"type":"hello","code":"123456"}'
curl -s http://<휴대폰 IP>:8080/api/state -H 'X-RB022-Session: <session>'
```

## 4. 프로토콜 핵심

### 4.1 HTTP·JSON 규칙

- 요청 1개 = 연결 1개(`Connection: close`). 허브는 사설 IPv4·루프백으로 들어온 연결만 받는다. CORS 헤더는 없다(Unity와는 무관).
- POST는 `Content-Type: application/json`과 `Content-Length`가 필수다. 본문은 1~2048바이트다. 어기면 413 `bad-body`. 요청줄·헤더 한 줄 ≤1024바이트, 헤더 ≤32개.
- API 응답은 4xx여도 `{"ok":false,"reason":"…"}` JSON이다. 본문을 읽어 사유를 보여 준다. 요청줄이 깨진 400, 404, 405, `/stream` 오류만 text/plain이다.
- JSON은 엄격하다. 객체 하나, 중첩 ≤3, 필드 ≤16, 배열 원소 ≤32, 문자열 ≤64자. 중복 키, NaN·Infinity, 선행 0(`01`), 주석, 후행 쉼표는 400이다.
- ID(`goalId`, `alignmentId`)는 `[A-Za-z0-9._-]{1,40}`다. 벡터는 `[x,y,z]` 숫자 3개이고 각 |값| ≤100이며 단위는 m다.

### 4.2 페어링

```http
POST /api/hello
X-RB022-Client: quest-unity
Content-Type: application/json

{"v":1,"type":"hello","code":"123456"}
```

성공하면 `200 {"ok":true,"v":1,"session":"<32자리 hex>"}`가 온다. `code`는 **문자열**로 보낸다. 숫자로 보내면 403이다.
실패: 400 `missing-client-header`·`bad-hello`, 403 `bad-code`, 409 `busy-mission-active`(실행·착륙 중). 연속 5회 틀리면 30초 동안 429 `pairing-locked`다.
이후 모든 요청에 헤더 `X-RB022-Session: <session>`을 붙인다. 명령은 본문 `session`에도 같은 값을 넣는다. 401이 오면 다시 페어링한다.

### 4.3 명령 봉투와 seq

```json
{"v":1,"session":"<session>","seq":12,"type":"goal", ...}
```

- `seq`는 페어링 후 1부터 **엄격히 증가**하는 정수다. 건너뛰기는 허용한다.
- 같은 seq를 다시 보내면 이전 응답이 그대로 온다(중복 실행 없음). 타임아웃 재시도는 **같은 본문, 같은 seq**로 한다. 다른 명령에 같은 seq를 쓰면 새 명령은 실행되지 않고 옛 응답만 온다.
- 더 작은 seq는 409 `stale-seq`다. 형식 오류(400: `bad-seq`, `unsupported-version`, JSON 오류)는 seq를 소비하지 않는다. 업무상 거절(200 + `"ok":false`)은 소비한다.
- **ACK(수락)는 도착이 아니다.** 진행과 도착은 `/api/state`로만 판단한다.

```json
{"ok":true,"type":"goal","result":"accepted","goalId":"g1","state":"PENDING"}
{"ok":false,"result":"rejected","goalId":"g1","reason":"outside-geofence","state":"IDLE"}
```

### 4.4 명령 목록

| type | 필드 (단위) | 받는 상태 | 주요 거절 `reason` |
|---|---|---|---|
| `heartbeat` | 없음 | 항상 | (상태 폴링이 heartbeat를 겸하므로 보통 불필요) |
| `goal` | `goalId`, `frame` `"W"`·`"Q"`, `alignmentId`(Q일 때 필수), `position`[3] m, `altitude`(선택, W 높이 m로 덮어씀), `arrival`(선택, `"hover"`만) | IDLE, PENDING(교체), ARRIVED, CANCELLED | `busy-<상태>`, `outside-geofence`, `altitude-out-of-range`, `no-alignment`, `alignment-mismatch`, `bad-frame`, `bad-vector`, `vector-out-of-range`, `unsupported-arrival`, `bad-id` |
| `execute` | `goalId` | PENDING(같은 목표) | `no-pending-goal`, `goal-mismatch`, `link-stale`, `pose-invalid` |
| `cancel` | `goalId` | PENDING → 대기 해제, EXECUTING → 그 자리 호버링. 반복해도 성공 | `goal-mismatch`, `not-cancellable` |
| `land` | 없음 | ARRIVED, CANCELLED, FAULT | `not-hovering` |
| `align` | `alignmentId`, `pairs` (4.6) | PENDING·EXECUTING 이외 | `alignment-needs-2-to-8-pairs`, `alignment-points-too-close`, `alignment-residual-too-large`, `bad-pair`, `busy-mission-active` |
| `pose` | `frame`, `alignmentId`(Q), `position`[3] m, `yaw`(선택, rad), `sigma` 0~1 m, `ageMs` 0~1000 ms | 항상 | `bad-sigma`, `bad-age`. **기록·표시만 하고 제어에는 쓰지 않는다** |

```json
{"v":1,"session":"…","seq":5,"type":"goal","goalId":"g1","frame":"Q","alignmentId":"room-a",
 "position":[0.5,0,-0.5],"altitude":0.9,"arrival":"hover"}
{"v":1,"session":"…","seq":6,"type":"execute","goalId":"g1"}
```

바닥을 찍은 점은 W 높이가 0 근처다. `altitude`를 빼면 `altitude-out-of-range`로 거절된다. 권장 UX는 **조준 → 확정(goal) → ACK 확인 → 명시적 실행(execute)** 이다. 손·머리의 평상 움직임을 명령으로 계속 보내지 않는다.

### 4.5 상태 `GET /api/state`

```json
{"v":1,"state":"EXECUTING","reason":"moving","mode":"simulation","goalId":"g1","goal":[1,0.8,0.8],
 "pose":{"w":[0.4,0.3,0.79],"source":"simulation","ageMs":77,"sigma":0.012,"valid":true},
 "speed":0.24,"remaining":0.71,"path":[[0.4,0.3,0.79],[1,0.8,0.8]],"rcPreview":[125,128,125,138,5],
 "alignment":null,"external":null,"fence":{"min":[-2,-2,0],"max":[2,2,1.5]},"altitude":[0.3,1.5],
 "videoAgeMs":80,"realOutputLocked":["no-validated-position-source","…"],"lastSeq":4}
```

| 필드 | 뜻 (좌표는 모두 W, m) |
|---|---|
| `state`, `reason` | 상태기계 값과 사유 (아래 표). `mode`는 v1에서 항상 `"simulation"` |
| `goalId`, `goal` | 현재 목표. 없으면 `null` |
| `pose` | `w` 위치, `source` 위치원, `ageMs`, `sigma`(m), `valid`. `ageMs` ≤250이고 `sigma` ≤0.05여야 유효 |
| `speed`, `remaining`, `path` | 추정 속력(m/s), 목표까지 남은 거리(m, 목표 없으면 `null`), 표시용 경로 점 목록 |
| `rcPreview` | 계산만 한 조종 바이트 [롤,피치,요,스로틀,AUX]. **송신하지 않는다** |
| `alignment`, `external` | 현재 정렬 `{id, rms}`, 마지막 `pose` 명령 `{w, ageMs, sigma}`. 없으면 `null` |
| `fence`, `altitude` | 허용영역 min/max와 고도 범위. **시뮬레이션 기본값이다. 하드코딩하지 말고 읽어서 쓴다** |
| `videoAgeMs` | 휴대폰이 마지막 프레임을 받은 뒤 경과(ms). 종단 지연이 아니다 |
| `realOutputLocked`, `lastSeq` | 실기 출력 잠금 사유(4.8), 허브가 마지막으로 처리한 seq |

| 상태 | 뜻 | 다음 |
|---|---|---|
| `IDLE` | 지상 대기 | goal |
| `PENDING` | 목표 수락, 실행 대기. 30초 뒤 만료(`goal-expired`) | execute / cancel / 새 goal |
| `EXECUTING` | 이동 중 | 도착 → ARRIVED, cancel → CANCELLED, 오류 → FAULT |
| `ARRIVED` | 반경 0.15 m, 속도 0.1 m/s 이내를 1초 유지한 뒤 호버링 | land / 새 goal |
| `CANCELLED` | 그 자리 호버링 | land / 새 goal |
| `LANDING` | 하강 중 | 착지 → IDLE(`landed`) |
| `FAULT` | 위치 상실, 링크 상실, 허용영역 이탈, 시간 초과 | land |

`reason` 값: `awaiting-execute`, `moving`, `hovering-await-user-landing`, `cancelled`, `goal-expired`, `link-lost`, `pose-stale`, `pose-uncertain`, `geofence`, `timeout`, `user-landing`, `link-lost-auto-landing`, `landed`.

**주기와 링크.** 200 ms 간격(5 Hz 이상)으로 폴링한다. 인증된 요청이 1.5초 동안 없으면 링크 상실이다. 그러면 EXECUTING은 `FAULT`(`link-lost`)가 되고, 호버링 중이면 자동 착륙(`link-lost-auto-landing`)한다. 이 동작은 시뮬레이션 정책이다. 참조 클라이언트는 폴링이 3회 연속 실패하면 "LINK LOST"를 표시한다.

버튼 활성화는 참조 클라이언트를 따르면 된다. 목표 전송은 IDLE·PENDING·ARRIVED·CANCELLED, 실행은 PENDING이면서 `goalId`가 내 목표이고 `pose.valid`일 때, 취소는 PENDING·EXECUTING, 착륙은 ARRIVED·CANCELLED·FAULT에서 켠다.

### 4.6 좌표계와 정렬

| 이름 | 정의 |
|---|---|
| **Q** (Quest) | OpenXR 기준 공간. 오른손 좌표계, +X 오른쪽, +Y 위, **−Z 앞**, m, 중력 정렬 |
| **W** (드론 운용) | 오른손 좌표계, **+Z 위**, 원점은 정렬 기준점의 바닥, m. 허용영역·목표·드론 위치·`path`는 모두 W |
| 변환 | `p_W = Rz(yaw) · M · p_Q + t`, `M(x,y,z) = (x, −z, y)`. yaw와 이동만 추정한다(4자유도) |

**Unity 변환.** Unity 월드는 왼손 좌표계(+Z 앞)다. Unity 위치 `u`는 `p_Q = (u.x, u.y, −u.z)`로 보낸다. 정렬이 항등이면 결과는 `p_W = (u.x, u.z, u.y)`다. `align.pairs`의 Q 열에도 같은 z 반전을 적용한다. XR Origin 스케일은 1(1 unit = 1 m)로 둔다. 코드 주석상 Q는 stage(바닥 기준) 공간이므로 Tracking Origin은 Floor를 권장한다.

**정렬 절차.** 바닥 기준점 2~8개의 W 좌표를 미리 실측해 정한다(예: 바닥 테이프 표시). Quest에서 손이나 컨트롤러로 각 점을 찍어 Q 좌표를 얻고 짝지어 보낸다.

```json
{"v":1,"session":"…","seq":3,"type":"align","alignmentId":"room-2026-10-29a",
 "pairs":[[0,0,0, 1,1,0],[1,0,0, 2,1,0],[0,0,-1, 1,2,0]]}
```

각 행은 `[qx,qy,qz, wx,wy,wz]`다. 가장 먼 두 점의 수평 거리가 0.3 m 이상이어야 하고, 잔차 RMS는 0.05 m 이하여야 한다. 성공하면 `/api/state.alignment`에 `{id, rms}`가 나타난다. 재로컬라이즈, 가디언 재설정, 헤드셋 재부팅 뒤에는 Q 원점이 바뀔 수 있다. 그때는 **새 `alignmentId`로 다시 정렬한다**.

| 시험 입력 (단위 테스트와 동일) | 기대 W |
|---|---|
| 항등 정렬, Q `(0, 1.5, 0)` | `(0, 0, 1.5)` |
| 항등 정렬, Q `(0, 0, −2)` | `(0, 2, 0)` |
| yaw=π/2, t=(1, −2, 0.1), Q `(1, 0, 0)` | `(1, −1, 0.1)` |
| 위 `align` 예시 → goal Q `(0.5, 0, −0.5)`, `altitude` 0.9 | `(1.5, 1.5, 0.9)` |

**알아 둘 공백.** 상태 JSON은 정렬의 `id`와 `rms`만 주고 `yaw`·`t`는 주지 않는다. 가상 드론 위치, 허용영역, 경로(모두 W)를 Quest 공간에 그리려면 클라이언트가 같은 정렬을 직접 계산해야 한다. `MissionMath.kt`의 `Alignment.estimate`와 `toQ`(합쳐 30줄 정도)를 C#으로 옮긴다. 결과 rms를 허브 값과 비교하면 검산이 된다. `toQ` 결과는 OpenXR Q이므로 Unity 위치는 `(q.x, q.y, −q.z)`다.

### 4.7 영상 `GET /stream?s=<session>`

`Content-Type: multipart/x-mixed-replace;boundary=rb022relay`이고, 최대 약 10 fps다. 2초보다 오래된 프레임은 보내지 않는다. 각 부분의 형식은 다음과 같다.
```text
--rb022relay\r\nContent-Type: image/jpeg\r\nContent-Length: N\r\n\r\n<JPEG N bytes>\r\n
```

시청자는 동시에 한 명이다(두 번째는 503). 휴대폰 앱이 카메라에 연결돼 있어야 프레임이 온다. 세션 토큰이 URL에 들어가므로 이 URL을 로그나 화면에 남기지 않는다.

### 4.8 실기 출력 잠금 `realOutputLocked`

`no-validated-position-source`(검증된 위치원 없음), `mission-output-not-wired-to-ble`(mission 출력은 BLE에 연결하지 않음), `battery-and-motor-current-unverified`, `user-flight-approval-required`. 모든 항목이 풀리고 드론 담당이 따로 승인하기 전에는 실제 기체로 출력하지 않는다. 허용영역 ±2 m, 고도 0.3~1.5 m, 수평 속도 0.3 m/s, 기울기 8°, 도착 반경 0.15 m는 **시뮬레이션 기본값**이다. 실측 근거가 있는 안전값이 아니다.

## 5. 현재 드론 상태 (실제 vs 시뮬레이션)

| 항목 | 상태 |
|---|---|
| Quest 목표 이동 | 허브 안 **가상 드론**만 움직인다. 자동 이동 명령은 실제 기체로 보내지 않는다. 이것은 안전 잠금이며 **위치 추정이 검증될 때까지 유지된다**. 상태 JSON의 `mode`·`pose.source`가 `"simulation"`이다 |
| 휴대폰 → 드론 수동 조종 | BLE로 실제 기체에 시동·정지·조종값을 보낸다. 시동·모터 회전·정지는 확인했고, 프로펠러를 단 이륙은 배터리 전압 강하로 실패해 새 배터리를 기다린다 |
| 높이 측정 | 레이저 거리 센서 **VL53L0X**, 약 0~1.2 m. 휴대폰 앱에 높이를 표시한다 |
| 바닥 흐름 센서 | **PMW3901**. 손으로 움직이면 값이 따라오는 것까지 확인, cm 환산 보정은 재측정 예정 |
| 높이 유지 | 펌웨어에 있다. 모터 전원 없이 동작만 확인했고 **비행 튜닝 전**이다. 새 배터리를 주문했다 |
| 위치 유지 | 펌웨어 코드 작성(보정 전 임시 상수, 드론에는 아직 안 올림). ARM 이후 앞/오른쪽 이동 거리(cm)를 드론이 계산해 휴대폰까지 보낸다 → 나중에 `pose`의 실제 위치원 후보. 아직 Quest 규격(`/api/state`)에는 연결하지 않았다 |
| Quest ↔ 허브 접속 | 기록된 종단 시험은 에뮬레이터 + 헤드리스 브라우저(가상 드론)뿐이다. 실제 Quest 접속과 실카메라 영상 중계는 **아직 확인되지 않았다**. 처음 붙여 본 결과를 꼭 공유해 달라 |

**지금 만들 수 있는 것** (모두 가상 드론으로 끝까지 시험할 수 있다)
- 프로토콜 클라이언트: 페어링, 상태 폴링, 명령과 seq 관리, 끊김·401·FAULT 처리
- 목표 지정 UI: 손 레이로 바닥 찍기, 고도 선택, 2단계 확정(goal → execute), 취소·착륙 버튼
- 좌표 정렬: 기준점 찍기, `align` 전송, W→Q 역변환으로 가상 드론·경로·허용영역 표시
- 영상 패널(카메라를 연결했을 때)

**나중에 오는 것:** 드론의 실제 위치(`pose.source`가 시뮬레이션이 아닌 값이 된다)와 실제 목표 비행. 그때 한계값이 바뀔 수 있다. 예를 들어 시뮬레이션 고도 상한 1.5 m는 레이저 측정 범위(약 1.2 m)보다 높다. 그래서 `fence`·`altitude`는 항상 상태에서 읽는다.

## 6. Unity 개발 팁

- **통신 방식.** WebSocket이 아니라 HTTP 폴링이다. NativeWebSocket 같은 플러그인은 필요 없다. 엔진 내장 `UnityWebRequest`로 충분하다.
- **HTTP 허용.** Project Settings → Player → Other Settings → Configuration → **Allow downloads over HTTP**를 `Always allowed`로 둔다(개발 빌드에서만 쓰려면 `Development builds only`). 기본값 `Not allowed`는 `http://` 요청을 막는다.
- **요청 본문.** `JsonUtility`는 `pairs` 같은 배열의 배열을 못 다룬다. 문자열로 직접 조립하고 숫자는 InvariantCulture로 쓴다.

```csharp
static string F(float v) => v.ToString("R", CultureInfo.InvariantCulture); // 쉼표 소수점 방지, NaN 금지
IEnumerator Post(string json) {
    var req = new UnityWebRequest(hubUrl + "api/command", "POST");   // hubUrl = "http://<휴대폰 IP>:8080/"
    req.uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(json));
    req.downloadHandler = new DownloadHandlerBuffer();
    req.SetRequestHeader("Content-Type", "application/json");
    req.SetRequestHeader("X-RB022-Session", session);
    req.timeout = 2;
    yield return req.SendWebRequest();
    // 4xx여도 req.downloadHandler.text에 {"ok":false,"reason":"…"}가 있다. 타임아웃이면 같은 seq로 재전송한다.
}
```

- **응답 파싱.** `path`(배열의 배열)와 `null` 필드가 있어 `JsonUtility`로는 불편하다. Unity 공식 무료 패키지 `com.unity.nuget.newtonsoft-json`(Package Manager → Add package by name)의 `JObject`를 쓰면 편하다. 모르는 필드는 무시하게 짠다.
- **폴링 루프.** 코루틴 하나에서 이전 요청이 끝난 뒤 200 ms 간격으로 다음 요청을 보낸다. 요청이 쌓이지 않게 한다. 헤드셋을 벗어 앱이 멈추면 1.5초 뒤 허브가 링크 상실로 처리한다. `OnApplicationPause`에서 "끊김"을 표시하고, 복귀하면 상태부터 다시 읽는다.
- **시간.** `pose.ageMs`는 `Time.realtimeSinceStartupAsDouble`처럼 Quest 자신의 단조 시계로 잰다. 두 기기의 절대 시각은 비교하지 않는다.
- **영상.** `DownloadHandlerScript`를 상속해 받은 바이트에서 `--rb022relay` 경계와 `Content-Length`를 파싱하고 `Texture2D.LoadImage`로 띄운다. 콜백은 메인 스레드에서 돌므로 무거운 일은 피한다. 시청자가 한 명이므로 브라우저 영상은 끄고 붙는다.
- **무료 도구만.** Unity OpenXR Plugin, XR Interaction Toolkit, XR Hands, Meta XR SDK처럼 무료 공식 패키지만 쓴다. 유료 에셋은 쓰지 않는다. Quest 기종은 아직 정하지 않았다. 패스스루 카메라로 드론을 추적하는 연구(`pose`)는 Quest 3/3S와 Horizon OS v74 이상이 필요하고, 방법과 정확도는 미검증이다.
- **네트워크.** Quest와 휴대폰은 같은 Wi-Fi에 두고, 주소는 휴대폰 Quest 탭에 표시된 IP를 쓴다. 학교·공용 Wi-Fi는 단말 간 통신을 막아 둘 수 있다(미확인). 막히면 개인 공유기로 시험한다. 카메라를 쓰면 휴대폰이 ESP32-CAM AP에 붙으므로 Quest도 그 AP에 붙어야 하고 최대 2대다. 이 경로의 단말 간 통신과 처리량은 아직 측정하지 않았다. 휴대폰 핫스팟 구성은 시험하지 않았다(앱은 Wi-Fi 클라이언트 주소만 표시한다).

## 7. 연락과 분담

- **드론 담당(저장소 관리자):** 드론 펌웨어, 휴대폰 허브 앱, 통신 규격(`docs/MISSION_PROTOCOL.md`)과 그 변경.
- **Quest 담당:** Quest Unity 앱.

**규격 변경 절차 (제안)**
1. GitHub Issue(또는 평소 연락 채널)에 `[protocol]` 제목으로 올린다. 이유, 바꿀 엔드포인트·필드, 예시 JSON을 적는다.
2. 드론 담당이 `docs/MISSION_PROTOCOL.md`를 먼저 고치고 허브 코드와 테스트를 맞춘다. 규격 문서에 없는 동작에는 기대지 않는다.
3. 기존 클라이언트를 깨는 변경은 `v`를 올린다. 지금 허브는 `v`가 1이 아닌 명령을 400 `unsupported-version`으로 거절한다. 응답에 필드만 추가하는 변경은 v1을 유지하고 문서에 변경 기록을 남긴다.

**지금 보이는 요청 후보:** 상태에 정렬 `yaw`·`t` 노출(4.6의 공백 해소). 반대 방향으로는 Quest 실측 결과(접속 성공 여부, 폴링 왕복 시간, 영상 fps, 사용한 Quest 기종과 Unity 버전)를 공유해 달라.

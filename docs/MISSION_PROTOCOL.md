# RB022 Mission Protocol v1 — Quest 클라이언트 ↔ 휴대폰 허브

2026-10-29 · 구현: `android-app/.../MissionProtocol.kt`, `MissionHub.kt`, `MissionController.kt`, `MissionMath.kt`. 이 문서는 **Quest(Unity 등) 클라이언트 개발자가 그대로 구현할 수 있는 인터페이스 규격**이다. 드론 쪽(휴대폰 허브·펌웨어)은 이 규격을 기준으로 동작한다.

> 현재 빌드는 **시뮬레이션 전용**이다. 목표를 보내면 휴대폰 안의 가상 드론만 움직인다. 실제 기체로 가는 BLE 조종 송신은 구현·활성화되어 있지 않다(§9).

## 1. 구성

```text
ESP32-CAM ──Wi-Fi MJPEG(:81, 단일 소비자)──> 휴대폰 앱 = 허브(:8080)
                                              │  ├─ GET  /stream      영상 중계 (MJPEG)
Quest 클라이언트 ──Wi-Fi HTTP/1.1──────────────┤  ├─ POST /api/hello   페어링
                                              │  ├─ POST /api/command 명령 (goal/execute/cancel/land/align/pose/heartbeat)
                                              │  └─ GET  /api/state   상태 (= heartbeat)
                                              └─ BLE(FFE0/FFE1, MSP) ──> 드론 [현재 송신 잠금]
```

- 허브 주소: 휴대폰 앱 상단 **QUEST → Quest 중계 시작**에 표시되는 `http://<휴대폰 Wi-Fi IPv4>:8080/`와 **6자리 연결 코드**.
- 같은 Wi-Fi에 있어야 한다. RB022-CAM AP의 최대 접속은 2대(휴대폰+Quest)다. **AP를 통한 휴대폰↔Quest 단말 간 통신과 처리량은 아직 실측하지 않았다.**
- Quest가 카메라 `:81/stream`에 직접 붙지 않는다. 카메라는 한 번에 한 스트림만 서비스한다. 영상은 반드시 허브의 `/stream` 중계를 쓴다.
- 허브는 루프백·사설 IPv4로 들어온 연결만 받는다. HTTP 평문이므로 신뢰할 수 있는 로컬 네트워크에서만 쓴다.

## 2. HTTP 규칙

| 항목 | 규칙 |
|---|---|
| 연결 | 요청 1개당 연결 1개(`Connection: close`). 응답 후 서버가 닫는다 |
| 요청 크기 | 요청줄·헤더 줄 ≤1024바이트, 헤더 ≤32개, 본문 ≤2048바이트 |
| POST 본문 | `Content-Type: application/json`, `Content-Length` 필수. 위반 시 413 `bad-body` |
| 인증 헤더 | 명령·상태: `X-RB022-Session: <session>`. 페어링: `X-RB022-Client: <아무 비어있지 않은 값>` |
| CORS | 응답하지 않는다. 다른 출처 웹페이지는 사용자 지정 헤더 때문에 호출할 수 없다 |
| 상태 코드 | 200 처리됨(수락/거절은 본문 `ok`), 400 형식 오류, 401 세션 없음/불일치, 403 코드 틀림, 404, 405, 409 순서/점유, 413 크기, 429 페어링 잠금, 503 중계 사용 중 |

## 3. JSON 규칙 (엄격 파서)

UTF-8 객체 하나. 중첩 깊이 ≤3, 객체 필드 ≤16, 배열 원소 ≤32, 문자열 ≤64자. **중복 키·NaN·Infinity·`1e999`처럼 유한하지 않은 수·선행 0·주석·후행 쉼표·따옴표 없는 키는 모두 거절**(400). 숫자는 모두 실수로 해석한다. ID 문자열은 `[A-Za-z0-9._-]{1,40}`. 벡터는 `[x, y, z]` 숫자 3개, 각 성분 |값| ≤100(m).

> Unity `JsonUtility`는 배열의 배열(`align.pairs`)을 다루지 못한다. 명령 본문은 문자열로 직접 조립하고, 숫자는 `ToString("R", CultureInfo.InvariantCulture)`로 쓴다(쉼표 소수점 방지).

## 4. 페어링과 세션

```http
POST /api/hello
X-RB022-Client: quest-unity
Content-Type: application/json

{"v":1,"type":"hello","code":"123456"}
```

성공: `200 {"ok":true,"v":1,"session":"<32자리 hex>"}`. 실패: 403 `bad-code`, 연속 5회 실패 시 30초간 429 `pairing-locked`, 실행·착륙 중이면 409 `busy-mission-active`.

- 활성 세션은 하나다. 새 hello가 성공하면 이전 토큰은 즉시 401이 된다.
- 허브를 다시 시작하면 코드와 세션이 모두 바뀐다.

## 5. 명령 공통 봉투

```json
{"v":1,"session":"<session>","seq":12,"type":"goal", ...}
```

| 규칙 | 동작 |
|---|---|
| `seq` | 세션마다 1부터 시작하는 정수. **엄격히 증가**해야 한다. 건너뛰기는 허용 |
| 같은 `seq` 재전송 | 이전 응답을 그대로 다시 돌려준다(재시도 안전, 중복 실행 없음) |
| 더 작은 `seq` | 409 `stale-seq` |
| 형식 오류(JSON·seq) | 400. 이 경우 seq를 소비하지 않는다 |
| 업무상 거절 | 200 + `"ok":false`. seq는 소비된다 |

수락 응답: `{"ok":true,"type":"goal","result":"accepted","goalId":"g1","state":"PENDING"}`
거절 응답: `{"ok":false,"result":"rejected","goalId":"g1","reason":"outside-geofence","state":"IDLE"}`

**ACK(수락)는 도착이 아니다.** 진행·도착은 `/api/state`로만 판단한다.

## 6. 좌표계

| 이름 | 정의 |
|---|---|
| **Q** (Quest) | OpenXR/WebXR 기준 공간. 오른손 좌표계, **+X 오른쪽, +Y 위, −Z 앞**, 단위 m. 중력 정렬 |
| **W** (드론 운용) | 오른손 좌표계, **+Z 위**, 원점은 정렬 기준점의 바닥, 단위 m. 평면도·허용영역·목표·드론 위치가 모두 W |
| **T_W_from_Q** | `p_W = Rz(yaw) · M · p_Q + t`, `M(x, y, z) = (x, −z, y)`. 두 좌표계 모두 중력 정렬이라 yaw와 이동만 추정한다(4자유도) |

**Unity 변환.** Unity 월드는 왼손 좌표계(+Z 앞)다. Unity OpenXR 패키지도 Unity↔OpenXR 변환에서 z만 부호를 바꾼다. 따라서 Unity 위치 `u`는 `p_Q = (u.x, u.y, −u.z)`로 보낸다. 정렬이 항등일 때 결과는 `p_W = (u.x, u.z, u.y)`다. XR Origin 스케일은 1(1 unit = 1 m)로 둔다. 재로컬라이즈·가디언 재설정·헤드셋 재부팅 뒤에는 Q 원점이 바뀔 수 있으므로 **정렬을 다시 수행하고 새 `alignmentId`를 써야 한다.**

### 정렬 `align`

바닥 기준점 2~8개를 Quest에서 손/컨트롤러로 찍어 Q 좌표를 얻는다. 각 점의 W 좌표(미리 정한 실측 배치)와 짝지어 보낸다.

```json
{"v":1,"session":"…","seq":3,"type":"align","alignmentId":"room-2026-10-29a",
 "pairs":[[0,0,0, 1,1,0],[1,0,0, 2,1,0],[0,0,-1, 1,2,0]]}
```

각 행은 `[qx,qy,qz, wx,wy,wz]`다. 점들의 수평 간격은 최소 0.3 m 이상이어야 하고 잔차 RMS가 0.05 m를 넘으면 안 된다. 위반하면 거절된다(`alignment-points-too-close`, `alignment-residual-too-large`). 목표가 대기·실행 중일 때 보낸 정렬은 `busy-mission-active`로 거절된다. 성공하면 `/api/state.alignment`에 `{id, rms}`가 나타난다.

### 시험 벡터 (단위 테스트와 동일)

| 입력 | 기대값 |
|---|---|
| 항등 정렬, Q `(0, 1.5, 0)` | W `(0, 0, 1.5)` |
| 항등 정렬, Q `(0, 0, −2)` | W `(0, 2, 0)` |
| yaw=π/2, t=(1, −2, 0.1), Q `(1, 0, 0)` | W `(1, −1, 0.1)` |
| 위 `align` 예시 → yaw 0, t (1,1,0); goal Q `(0.5, 0, −0.5)`, `altitude` 0.9 | W `(1.5, 1.5, 0.9)` |

## 7. 명령

| type | 필드 | 의미·조건 |
|---|---|---|
| `heartbeat` | — | 링크 유지만 갱신 |
| `goal` | `goalId`, `frame` `"W"`\|`"Q"`, `alignmentId`(Q일 때 필수·현재 정렬과 같아야 함), `position`[3], `altitude`(선택, W 높이 m로 덮어씀), `arrival`(선택, 현재 `"hover"`만) | 목표 등록 → `PENDING`. **움직이지 않는다.** IDLE·PENDING(교체)·ARRIVED·CANCELLED에서만 받는다 |
| `execute` | `goalId` | 대기 중인 같은 목표만 실행. 링크가 신선하고 위치가 유효해야 한다 |
| `cancel` | `goalId` | PENDING → 대기 해제. EXECUTING → 그 자리 호버링(`CANCELLED`). 같은 취소 반복은 성공(멱등) |
| `land` | — | ARRIVED·CANCELLED·FAULT에서 사용자 착륙 시작 |
| `align` | §6 | 정렬 설정 |
| `pose` | `frame`, `alignmentId`(Q), `position`[3], `yaw`(선택, rad), `sigma`(m, 0~1), `ageMs`(0~1000) | 외부 위치 관측. **현재는 기록·표시만 하고 제어에 쓰지 않는다**(§8) |

손·머리·컨트롤러의 평상 움직임을 명령으로 보내지 마라. 권장 UX는 **조준 → 확정(goal) → ACK 확인 → 명시적 실행(execute)** 두 단계다.

## 8. 상태 `GET /api/state`

5 Hz(200 ms) 이상으로 폴링한다. **인증된 상태 폴링이 heartbeat를 겸한다.** 1.5초 동안 아무 요청이 없으면 링크 상실로 처리한다.

```json
{"v":1,"state":"EXECUTING","reason":"moving","mode":"simulation","goalId":"g1","goal":[1,0.8,0.8],
 "pose":{"w":[0.4,0.3,0.79],"source":"simulation","ageMs":77,"sigma":0.012,"valid":true},
 "speed":0.24,"remaining":0.71,"path":[[0.4,0.3,0.79],[1,0.8,0.8]],"rcPreview":[125,128,125,138,5],
 "alignment":null,"external":null,"fence":{"min":[-2,-2,0],"max":[2,2,1.5]},"altitude":[0.3,1.5],
 "videoAgeMs":80,"realOutputLocked":["no-validated-position-source","…"],"lastSeq":4}
```

| 상태 | 뜻 | 다음 |
|---|---|---|
| `IDLE` | 지상 대기 | goal |
| `PENDING` | 목표 수락, 실행 대기(30초 후 만료) | execute / cancel / 새 goal |
| `EXECUTING` | 이동 중 | 도착 → ARRIVED, cancel → CANCELLED, 오류 → FAULT |
| `ARRIVED` | 반경 0.15 m·속도 0.1 m/s 이내 1초 유지 후 호버링 | land / 새 goal |
| `CANCELLED` | 그 자리 호버링 | land / 새 goal |
| `LANDING` | 하강 중 | 착지 → IDLE(`landed`) |
| `FAULT` | 위치 상실·링크 상실·허용영역 이탈·시간 초과 | land |

`reason` 값: `awaiting-execute`, `moving`, `hovering-await-user-landing`, `cancelled`, `goal-expired`, `link-lost`, `pose-stale`, `pose-uncertain`, `geofence`, `timeout`, `user-landing`, `link-lost-auto-landing`, `landed`.

**시간 규칙.** 허브는 자기 단조 시계만 쓴다. `pose.ageMs = (허브 수신 후 경과) + (보낸 쪽이 잰 촬영→송신 시간)`이다. 두 기기의 절대 시각은 비교하지 않는다. Quest 쪽 `ageMs`는 같은 기기의 단조 시계(예: `Time.realtimeSinceStartupAsDouble`)로 잰다. `videoAgeMs`는 휴대폰이 마지막 프레임을 받은 뒤의 경과일 뿐 종단 지연이 아니다.

**위치 유효성.** `ageMs` ≤250 ms이고 `sigma` ≤0.05 m여야 유효하다. 실행 중 무효가 되면 `FAULT`다. 현재 위치원은 시뮬레이터뿐이다.

## 9. 영상 중계 `GET /stream?s=<session>`

`Content-Type: multipart/x-mixed-replace;boundary=rb022relay`. 각 부분은 아래 형식이며 최대 약 10 fps다. 2초보다 오래된 프레임은 보내지 않는다.

```text
--rb022relay\r\nContent-Type: image/jpeg\r\nContent-Length: N\r\n\r\n<JPEG N bytes>\r\n
```

동시에 한 시청자만 받는다(두 번째는 503). 휴대폰 앱이 카메라에 연결돼 있어야 프레임이 있다. Unity에서는 소켓/`DownloadHandlerScript`로 바이트를 받아 위 경계를 파싱하고 `Texture2D.LoadImage`로 띄울 수 있다(엔진 기본 기능, 추가 구매 없음). 세션 토큰이 URL 쿼리에 들어가므로 이 URL을 로그·화면에 남기지 않는다.

## 10. 드론 쪽 연결 (허브 → 기체, 현재 잠금)

| 항목 | 규격 | 상태 |
|---|---|---|
| 링크 | BLE 서비스 FFE0 / 특성 FFE1 (쓰기·알림), 투명 UART 115200 | 현 모듈 GATT 실측 로그 미확보 |
| 프레임 | MSP `$M<` + 길이 + 명령 + payload + XOR(길이·명령·payload) | 펌웨어 소스 기준 |
| 이동 출력 | **새 GOTO 명령을 만들지 않는다.** 외부 위치 루프가 MSP **150**(롤·피치·요·스로틀·AUX 각 1바이트)을 보낸다. `rcData = 1000 + 4×byte`, 중립 125 | 수동 조종은 앱 0.4-control에서 실기 송신. mission 허브는 `rcPreview` 계산만 하고 **송신 안 함** |
| 자세 환산 | rcRate 90, expo 0, HORIZON(AUX1 전 구간)에서 저타각 1바이트 ≈ 0.72°. 시뮬레이션 최대 기울기 8° | 실기 111/113 읽기로 확인(2026-10-29) |
| AUX | `0x05` | |
| ARM 151 | 파생 펌웨어가 **최근 250 ms 안에 150 수신 + 스로틀 ≤1100 + I2C 오류 0**일 때만 허용. ARMED 중 150이 500 ms 끊기면 출력 차단 | 실기 업로드·무출력 시험 완료 |
| DISARM 152 | 즉시 해제(실측 왕복 23 ms, USB) | |
| 상태 199 | 응답 14바이트: state8, version16, alt16, angle×2(0.1°), altHold16, vbat8, trim×2 | 단위·실측 미확인 |
| 150 대체 | 200(표준 RC)은 AIR 50 Hz 루프가 덮어써 대체 경로가 아니다 | |

## 11. 실기 잠금 사유 (`realOutputLocked`)

`no-validated-position-source`(검증된 위치원 없음), `mission-output-not-wired-to-ble`(수동 조종 송신과 달리 mission 출력은 BLE에 연결하지 않음), `battery-and-motor-current-unverified`, `user-flight-approval-required`. (펌웨어 보호 기능은 2026-10-29 실기에 업로드되어 사유에서 뺐다.) 모든 항목이 풀리고 사용자가 별도로 승인하기 전에는 실제 기체로 출력하지 않는다. 허용영역 ±2 m·고도 0.3~1.5 m·속도 0.3 m/s·기울기 8°·도착 반경 0.15 m는 **시뮬레이션 기본값**이다. 실측 근거가 없는 실기 안전값이 아니다.

## 12. Quest 쪽 구현 체크리스트

1. 페어링 화면(코드 6자리) → 세션 저장. 401이면 재페어링.
2. 200 ms 상태 폴링 루프(heartbeat 겸용). 3회 연속 실패하면 "링크 끊김"을 표시한다.
3. 목표 지정: 손 레이로 바닥을 맞힌 점 `p_Q`와 고도를 정한다 → `goal`(frame `Q`, 현재 `alignmentId`, `altitude`) → ACK 표시 → 사용자가 명시적으로 `execute`.
4. 취소·착륙 버튼은 상태 표에 따라 활성화한다. `FAULT`이면 사유를 크게 표시한다.
5. 정렬 절차(§6)와 재로컬라이즈 감지 시 재정렬.
6. (연구 과제) 드론 위치를 Quest가 관측하면 `pose`(frame `Q`, `sigma`, `ageMs`)로 보낸다. Meta Passthrough Camera API는 Quest 3/3S, Horizon OS v74 이상, `horizonos.permission.HEADSET_CAMERA`(또는 `android.permission.CAMERA`)가 필요하다. 문서상 캡처 지연은 20~40 ms다. **이것으로 드론을 추적하는 방법·정확도는 아직 검증되지 않았다.** 유료 에셋은 쓰지 않는다.
7. 참조 구현: 허브가 `/`로 제공하는 웹 페이지(`android-app/app/src/main/assets/quest/index.html`)가 같은 규격의 2D 평면도 클라이언트다.

참고: [OpenXR 기준 공간(Khronos)](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XR_REFERENCE_SPACE_TYPE_VIEW.html), [Unity OpenXR XrVector3f 변환](https://docs.unity3d.com/Packages/com.unity.xr.openxr@1.16/api/UnityEngine.XR.OpenXR.NativeTypes.XrVector3f.html), [Meta Passthrough Camera API](https://developers.meta.com/horizon/documentation/unity/unity-pca-overview/).

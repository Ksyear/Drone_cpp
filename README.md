# Drone_cpp

소형 교육용 쿼드콥터(ATmega32U4 Pro Micro · MPU6050 · BLE UART 모듈 · ESP32-CAM)를 C++ 중심으로 다시 구성하는 학생 프로젝트다. 최종 목표는 **Meta Quest에서 지정한 위치로 드론이 이동하는 시스템**이다. 이 저장소는 드론 쪽(펌웨어·휴대폰 허브)과 Quest 클라이언트와의 **통신 규격**을 담는다. Quest(Unity) 앱은 별도로 개발한다.

> **현재 상태:** Quest 목표 → 휴대폰 허브 → **가상 드론** 이동·도착·취소·통신 끊김 처리까지 동작하고 시험했다. 실제 기체의 자동 비행은 잠겨 있다. 검증된 위치원 없음, BLE 조종 송신 미구현, 펌웨어 보호 미업로드, 전원·전류 미측정 상태다. 이 코드로 실제 기체를 날리지 마라.

## 구성

```text
docs/MISSION_PROTOCOL.md      Quest 클라이언트 ↔ 휴대폰 허브 통신 규격 v1 (좌표계·명령·상태·영상 중계)
android-app/                  Kotlin/Compose 앱: BLE 진단, ESP32-CAM 영상·촬영·녹화, Quest mission 허브
  app/src/main/assets/quest/  Quest 브라우저용 2D 참조 클라이언트(허브가 제공)
firmware/
  build.py                    원본 AirCopter 소스 → C++ 파생 비행 펌웨어 생성·컴파일(업로드 기능 없음)
  FlightController.hpp        Arduino setup/loop → 기존 비행 루프 Facade
  ControlPayload.hpp          조종 명령 payload 길이 검사
  ArmGuard.hpp                시리얼 ARM 보호(최근 RC + 최저 스로틀)
  BoardDiagnostics/           모터 LOW 고정 MPU6050·UART 진단 스케치
  AirCameraCpp/               ESP32-CAM Soft-AP + MJPEG Facade
  tests/                      호스트 C++(ASan/UBSan)·Python 테스트와 스텁
```

## 빌드와 테스트

**Android** (JDK 17+, Android SDK 35, `android-app/local.properties`에 `sdk.dir`)

```sh
cd android-app && ./gradlew assembleDebug testDebugUnitTest lintDebug
```

앱 상단 **QUEST → Quest 중계 시작**을 누르면 `http://<휴대폰 Wi-Fi IP>:8080/`와 6자리 코드가 표시된다. 같은 Wi-Fi의 브라우저나 Unity 클라이언트가 [통신 규격](docs/MISSION_PROTOCOL.md)대로 접속한다. 목표는 가상 드론만 움직인다.

**호스트 테스트** (clang++)

```sh
clang++ -std=c++11 -Wall -Wextra -Werror -fsanitize=address,undefined -Ifirmware firmware/tests/control_test.cpp -o /tmp/control_test && /tmp/control_test
python3 firmware/diagnostics/check.py --test-only
```

**비행 펌웨어 파생본**: `firmware/build.py`는 키트 제공사가 배포한 MultiWii 2.2 기반 AirCopter 원본 소스를 입력으로 쓴다. 그 원본과 생성 결과는 **이 저장소에 포함하지 않는다.** 자신이 가진 원본을 `RB022_AIRCOPTER_GEAR_TYPE/<폴더>/AirCopter/`에 두고 Arduino CLI·AVR 도구 경로(`.tools/arduino`, `RB022_AVR_BIN`, `RB022_CTAGS_BIN`)를 맞춘 뒤 실행한다. `firmware/tests/test_conversion.py`도 같은 원본이 필요하다.

**ESP32-CAM**: `firmware/AirCameraCpp/CameraSecrets.example.hpp`를 참고해 로컬 비밀번호 헤더를 만든다. 비밀번호 파일은 Git에서 제외된다. `esp32:esp32@3.3.10`, FQBN `esp32:esp32:esp32cam`.

## 안전

- 앱에는 실제 조종 송신 경로가 없다. 입력 초기화나 연결 해제는 모터 정지 장치가 아니다.
- 허용영역·고도·속도·기울기·도착 반경 값은 시뮬레이션 기본값이며 실측 근거가 있는 비행 안전값이 아니다.
- 실기 시험은 프로펠러 제거, 전원·전류 확인, 위치 추정 검증, 보호 기능 업로드 후 단계적으로만 한다.

## 라이선스

[GPL-3.0-or-later](LICENSE). 비행 펌웨어는 GPLv3 MultiWii 계열 원본 소스에서 로컬로 생성되므로 같은 라이선스를 쓴다. 원본·생성 소스와 키트 배포 자료는 포함하지 않는다.

- ESP32 카메라 코드는 Espressif Arduino-ESP32 3.3.10의 라이브러리 API(LGPL-2.1)를 사용한다. 라이브러리는 포함하지 않는다. AI Thinker 핀 번호는 Espressif CameraWebServer 예제의 핀 표를 따랐다.
- `android-app/gradle/wrapper/`는 Gradle Wrapper(Apache-2.0)다.

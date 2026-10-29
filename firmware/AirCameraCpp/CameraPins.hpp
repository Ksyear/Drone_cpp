#pragma once
// AI Thinker layout from Espressif Arduino-ESP32 3.3.10 CameraWebServer/camera_pins.h.
// This build profile does not establish the connected board's physical identity.
static constexpr int CameraDataPins[] = {5, 18, 19, 21, 36, 39, 34, 35};
static constexpr int CameraPwdn = 32, CameraReset = -1, CameraXclk = 0;
static constexpr int CameraSda = 26, CameraScl = 27;
static constexpr int CameraVsync = 25, CameraHref = 23, CameraPclk = 22;

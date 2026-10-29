#include <Arduino.h>
#include <WiFi.h>
#include "CameraPins.hpp"
#include "CameraSecrets.hpp"
#include "CameraStream.hpp"

static_assert(sizeof(CameraPassword) >= 13 && sizeof(CameraPassword) <= 64,
              "Use a 12..63 character Wi-Fi passphrase");

// Facade: one initialization path for the camera, private Wi-Fi AP and JPEG server.
class CameraFacade final {
public:
    static bool begin() {
        camera_config_t config = {};
        config.ledc_channel = LEDC_CHANNEL_0;
        config.ledc_timer = LEDC_TIMER_0;
        config.pin_d0 = CameraDataPins[0]; config.pin_d1 = CameraDataPins[1];
        config.pin_d2 = CameraDataPins[2]; config.pin_d3 = CameraDataPins[3];
        config.pin_d4 = CameraDataPins[4]; config.pin_d5 = CameraDataPins[5];
        config.pin_d6 = CameraDataPins[6]; config.pin_d7 = CameraDataPins[7];
        config.pin_xclk = CameraXclk; config.pin_pclk = CameraPclk;
        config.pin_vsync = CameraVsync; config.pin_href = CameraHref;
        config.pin_sccb_sda = CameraSda; config.pin_sccb_scl = CameraScl;
        config.pin_pwdn = CameraPwdn; config.pin_reset = CameraReset;
        config.xclk_freq_hz = 20000000;
        config.pixel_format = PIXFORMAT_JPEG;
        const bool psram = psramFound();
        config.frame_size = psram ? FRAMESIZE_VGA : FRAMESIZE_QVGA;
        config.jpeg_quality = 12;
        config.fb_count = psram ? 2 : 1;
        config.fb_location = psram ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
        config.grab_mode = CAMERA_GRAB_LATEST;
        const esp_err_t cameraResult = esp_camera_init(&config);
        if (cameraResult != ESP_OK) {
            Serial.printf("CAMERA INIT FAILED: 0x%X; check board profile, ribbon and power\n", cameraResult);
            return false;
        }
        Serial.printf("Camera sensor PID: 0x%X; PSRAM: %s\n",
                      esp_camera_sensor_get()->id.PID, psram ? "yes" : "no");
        WiFi.mode(WIFI_AP);
        const IPAddress local(192, 168, 4, 1), mask(255, 255, 255, 0);
        if (!WiFi.softAPConfig(local, local, mask) ||
            !WiFi.softAP("RB022-CAM", CameraPassword, 1, false, 2)) {
            WiFi.softAPdisconnect(true);
            esp_camera_deinit();
            Serial.println("CAMERA WI-FI FAILED");
            return false;
        }
        httpd_config_t serverConfig = HTTPD_DEFAULT_CONFIG();
        serverConfig.server_port = 81;
        serverConfig.max_open_sockets = 2;
        serverConfig.lru_purge_enable = true;
        serverConfig.send_wait_timeout = 5;
        httpd_handle_t server = nullptr;
        esp_err_t result = httpd_start(&server, &serverConfig);
        if (result == ESP_OK) {
            httpd_uri_t route = {};
            route.uri = "/stream";
            route.method = HTTP_GET;
            route.handler = camera::stream;
            result = httpd_register_uri_handler(server, &route);
        }
        if (result != ESP_OK) {
            if (server) httpd_stop(server);
            WiFi.softAPdisconnect(true);
            esp_camera_deinit();
            Serial.printf("CAMERA SERVER FAILED: 0x%X\n", result);
            return false;
        }
        Serial.println("Wi-Fi: RB022-CAM (passphrase in local connection card)");
        Serial.println("Stream: http://192.168.4.1:81/stream");
        return true;
    }
};

void setup() {
    Serial.begin(115200);
    CameraFacade::begin();
}

void loop() { delay(1000); }

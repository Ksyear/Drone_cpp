#include <cassert>
#include <iostream>
#include <string>
#include <vector>
#include "../AirCameraCpp/CameraStream.hpp"

static uint8_t jpeg[] = {0xff, 0xd8, 0x12, 0xff, 0xd9};
static camera_fb_t frame{jpeg, sizeof(jpeg), PIXFORMAT_JPEG};
static int available, acquired, returned, sends, failSend, delays;
static bool failType;
static std::string contentType, body;
camera_fb_t* esp_camera_fb_get() { if (available-- <= 0) return nullptr; ++acquired; return &frame; }
void esp_camera_fb_return(camera_fb_t* value) { assert(value == &frame); ++returned; }
void vTaskDelay(unsigned ticks) { assert(ticks == 67); ++delays; }
esp_err_t httpd_resp_set_type(httpd_req_t*, const char* value) { contentType = value; return failType ? ESP_FAIL : ESP_OK; }
esp_err_t httpd_resp_set_hdr(httpd_req_t*, const char*, const char*) { return ESP_OK; }
esp_err_t httpd_resp_send_chunk(httpd_req_t*, const char* value, size_t size) {
    assert(value && size);
    if (++sends == failSend) return ESP_FAIL;
    body.append(value, size); return ESP_OK;
}
static void reset(int count) {
    available = count; acquired = returned = sends = delays = 0;
    failSend = -1; failType = false; contentType.clear(); body.clear();
    frame = camera_fb_t{jpeg, sizeof(jpeg), PIXFORMAT_JPEG};
}
int main() {
    httpd_req_t request;
    reset(2);
    assert(camera::stream(&request) == ESP_FAIL); // Camera exhaustion ends the stream.
    assert(contentType == "multipart/x-mixed-replace;boundary=rb022frame");
    const std::string one = "\r\n--rb022frame\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\n\r\n" + std::string(reinterpret_cast<char*>(jpeg), sizeof(jpeg));
    assert(body == one + one && acquired == 2 && returned == 2 && delays == 2);
    for (int failure = 1; failure <= 4; ++failure) {
        reset(2); failSend = failure;
        assert(camera::stream(&request) == ESP_FAIL && acquired == returned);
        assert(sends == failure && delays == (failure - 1) / 2);
    }
    for (int invalid = 0; invalid < 3; ++invalid) {
        reset(1);
        if (invalid == 0) frame.format = PIXFORMAT_RGB565;
        if (invalid == 1) frame.len = 0;
        if (invalid == 2) frame.buf = nullptr;
        assert(camera::stream(&request) == ESP_FAIL && returned == 1 && sends == 0);
    }
    reset(0); assert(camera::stream(&request) == ESP_FAIL && acquired == 0 && returned == 0);
    reset(1); failType = true;
    assert(camera::stream(&request) == ESP_FAIL && acquired == 0);
    std::cout << "PASS camera stream: multipart bytes, repeated frames, frame release on header/JPEG disconnect, null/empty/non-JPEG frame, startup response failure\n";
}

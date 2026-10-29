#pragma once
#include <stdio.h>
#include "esp_camera.h"
#include "esp_http_server.h"

namespace camera {

// Same multipart JPEG contract as Espressif CameraWebServer, without control endpoints.
inline esp_err_t stream(httpd_req_t* request) {
    esp_err_t result = httpd_resp_set_type(request, "multipart/x-mixed-replace;boundary=rb022frame");
    if (result != ESP_OK) return result;
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    while (result == ESP_OK) {
        camera_fb_t* frame = esp_camera_fb_get();
        if (!frame) return ESP_FAIL;
        if (frame->format != PIXFORMAT_JPEG || !frame->buf || !frame->len) {
            esp_camera_fb_return(frame);
            return ESP_FAIL;
        }
        char header[112];
        const int length = snprintf(header, sizeof(header),
            "\r\n--rb022frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n",
            static_cast<unsigned>(frame->len));
        if (length < 0 || length >= static_cast<int>(sizeof(header))) result = ESP_FAIL;
        else result = httpd_resp_send_chunk(request, header, length);
        if (result == ESP_OK)
            result = httpd_resp_send_chunk(request, reinterpret_cast<const char*>(frame->buf), frame->len);
        esp_camera_fb_return(frame);
        if (result == ESP_OK) vTaskDelay(pdMS_TO_TICKS(67));
    }
    return result;
}

} // namespace camera

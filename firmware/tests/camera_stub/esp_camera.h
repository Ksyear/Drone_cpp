#pragma once
#include <stddef.h>
#include <stdint.h>
enum pixformat_t { PIXFORMAT_JPEG, PIXFORMAT_RGB565 };
struct camera_fb_t { uint8_t* buf; size_t len; pixformat_t format; };
camera_fb_t* esp_camera_fb_get();
void esp_camera_fb_return(camera_fb_t*);
void vTaskDelay(unsigned ticks);
#define pdMS_TO_TICKS(value) (value)

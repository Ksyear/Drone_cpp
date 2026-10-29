#pragma once
#include <stddef.h>
typedef int esp_err_t;
constexpr esp_err_t ESP_OK = 0, ESP_FAIL = -1;
struct httpd_req_t {};
esp_err_t httpd_resp_set_type(httpd_req_t*, const char*);
esp_err_t httpd_resp_set_hdr(httpd_req_t*, const char*, const char*);
esp_err_t httpd_resp_send_chunk(httpd_req_t*, const char*, size_t);

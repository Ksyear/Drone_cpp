#include <Arduino.h>
#include <string.h>
#if __has_include("BleName.local.hpp")
#include "BleName.local.hpp"  // git-excluded: static constexpr const char* BleLocalName = "...";
#else
static constexpr const char* BleLocalName = "RB022-AIR";
#endif

// Facade: one-shot HM-10 rename over Serial1 with motors held LOW. Query first, rename only an
// HM-10 style reply, never AT+RENEW. Works only while no phone is connected (module LED blinking).
class BleNameSetter final {
public:
    static constexpr const char* kName = BleLocalName;  // HM-10 limit: 12 characters

    void begin() {
        for (uint8_t pin : motorPins_) { digitalWrite(pin, LOW); pinMode(pin, OUTPUT); }
        Serial.begin(115200);
        const uint32_t bauds[] = {115200, 9600};
        for (uint32_t baud : bauds) {
            Serial1.begin(baud);
            delay(300);
            if (strstr(ask("AT"), "OK")) { baud_ = baud; break; }
        }
        if (!baud_) { strcpy(result_, "no HM-10 reply at 115200/9600 (connected phone or other module)"); return; }
        char before[40];
        strncpy(before, ask("AT+NAME?"), sizeof(before) - 1);
        before[sizeof(before) - 1] = 0;
        if (!strstr(before, "OK+NAME")) { snprintf(result_, sizeof(result_), "baud %lu, unexpected name reply [%s]", baud_, before); return; }
        char command[24];
        snprintf(command, sizeof(command), "AT+NAME%s", kName);
        char set[40];
        strncpy(set, ask(command), sizeof(set) - 1);
        set[sizeof(set) - 1] = 0;
        ask("AT+RESET");
        delay(1500);  // module restarts; name applies after reset
        snprintf(result_, sizeof(result_), "baud %lu | before [%s] | set [%s] | after [%s]", baud_, before, set, ask("AT+NAME?"));
    }

    void update() {
        for (uint8_t pin : motorPins_) digitalWrite(pin, LOW);
        if (Serial && uint32_t(millis() - lastPrint_) >= 2000U) {
            lastPrint_ = millis();
            Serial.print("BLE-NAME ");
            Serial.println(result_);
        }
    }

private:
    const char* ask(const char* command) {
        while (Serial1.available()) Serial1.read();
        Serial1.write(command);  // HM-10 commands have no line ending
        size_t n = 0;
        const uint32_t start = millis();
        while (uint32_t(millis() - start) < 800U && n < sizeof(reply_) - 1) {
            if (Serial1.available()) {
                const int c = Serial1.read();
                if (c >= 32 && c < 127) reply_[n++] = char(c);
            }
        }
        reply_[n] = 0;
        return reply_;
    }

    const uint8_t motorPins_[4] = {5, 6, 9, 10};
    uint32_t baud_ = 0;
    uint32_t lastPrint_ = 0;
    char reply_[40] = {};
    char result_[160] = "pending";
};

static BleNameSetter setter;
void setup() { setter.begin(); }
void loop() { setter.update(); }

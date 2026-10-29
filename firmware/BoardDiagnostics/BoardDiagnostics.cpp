#include <Arduino.h>
#include <stdio.h>
#include <string.h>
#include "Mpu6050.hpp"

// Facade for motor-inhibited sensor sampling and passive UART observation.
class BoardDiagnostics final {
public:
    void begin() {
        holdMotorsLow();
        for (uint8_t pin : motorPins_) pinMode(pin, OUTPUT);
        Serial.begin(115200);
        Serial1.begin(115200);
        imu_.begin();
        lastAttempt_ = lastRead_ = lastReport_ = uint32_t(millis());
    }

    void update() {
        holdMotorsLow();
        const uint32_t now = uint32_t(millis());
        // RX bytes are data only: no command parser, Serial1 TX or AT bridge.
        for (uint8_t i = 0; i < 32 && Serial1.available(); ++i) {
            uartHistory_[uartNext_] = uint8_t(Serial1.read());
            uartNext_ = (uartNext_ + 1) % sizeof(uartHistory_);
            if (uartCount_ < sizeof(uartHistory_)) ++uartCount_;
            ++uartBytes_;
        }
        if (!imu_.ready() && uint32_t(now - lastAttempt_) >= 1000U) {
            lastAttempt_ = now;
            imu_.begin();
            lastRead_ = uint32_t(millis());
        } else if (imu_.ready() && uint32_t(now - lastRead_) >= 20U) {
            lastRead_ = now;
            if (imu_.read()) lastSample_ = now;
        }

        if (!Serial) {
            pendingSize_ = pendingOffset_ = 0;
            return;
        }
        if (!pendingSize_ && uint32_t(now - lastReport_) >= 250U) {
            lastReport_ = now;
            makeReport(now);
        }
        // A closed/stalled USB monitor must not block sensor or UART polling.
        const int space = Serial.availableForWrite();
        if (space > 0 && pendingSize_) {
            const uint16_t remaining = pendingSize_ - pendingOffset_;
            const size_t count = remaining < uint16_t(space) ? remaining : uint16_t(space);
            pendingOffset_ += Serial.write(
                reinterpret_cast<const uint8_t*>(report_ + pendingOffset_), count);
            if (pendingOffset_ == pendingSize_) pendingSize_ = pendingOffset_ = 0;
        }
    }

private:
    void holdMotorsLow() {
        // Latch LOW before OUTPUT, then keep it LOW throughout every loop.
        for (uint8_t pin : motorPins_) digitalWrite(pin, LOW);
    }

    void makeReport(uint32_t now) {
        const auto& health = imu_.health();
        const auto& sample = imu_.sample();
        int length = 0;
        switch (reportPhase_++ % 4) {
        case 0:
            if (sample.valid) {
                length = snprintf(report_, sizeof(report_),
                    "S t=%lu addr=%02X valid=1 age=%lu n=%lu A=%d,%d,%d T=%d G=%d,%d,%d\r\n",
                    (unsigned long)now, unsigned(imu_.address()),
                    (unsigned long)(now - lastSample_), (unsigned long)health.samples,
                    sample.accel[0], sample.accel[1], sample.accel[2], sample.temperature,
                    sample.gyro[0], sample.gyro[1], sample.gyro[2]);
            } else {
                length = snprintf(report_, sizeof(report_),
                    "S t=%lu addr=%02X valid=0 ready=%u n=%lu error=%u\r\n",
                    (unsigned long)now, unsigned(imu_.address()), unsigned(imu_.ready()),
                    (unsigned long)health.samples, unsigned(health.last));
            }
            break;
        case 1: {
            int16_t accel[3] = {}, gyro[3] = {};
            if (sample.valid) diagnostic::legacyAxes(sample, accel, gyro);
            length = snprintf(report_, sizeof(report_),
                "L t=%lu valid=%u precal_A=%d,%d,%d precal_G=%d,%d,%d\r\n",
                (unsigned long)now, unsigned(sample.valid), accel[0], accel[1], accel[2], gyro[0], gyro[1], gyro[2]);
            break;
        }
        case 2: {
            length = snprintf(report_, sizeof(report_), "U bytes=%lu last16=", (unsigned long)uartBytes_);
            for (uint8_t i = 0; i < uartCount_; ++i) {
                const uint8_t value = uartHistory_[(uartNext_ + 16U - uartCount_ + i) % 16U];
                length += snprintf(report_ + length, sizeof(report_) - length, "%02X", unsigned(value));
            }
            length += snprintf(report_ + length, sizeof(report_) - length, "\r\n");
            break;
        }
        case 3:
            length = snprintf(report_, sizeof(report_),
                "E total=%lu no_device=%lu who=%lu ambiguous=%lu nack=%lu timeout=%lu short=%lu cfg=%lu not_ready=%lu\r\n",
                (unsigned long)health.failures,
                (unsigned long)health.errors[1], (unsigned long)health.errors[2],
                (unsigned long)health.errors[3], (unsigned long)health.errors[4],
                (unsigned long)health.errors[5], (unsigned long)health.errors[6],
                (unsigned long)health.errors[7], (unsigned long)health.errors[8]);
            break;
        }
        pendingOffset_ = 0;
        pendingSize_ = length > 0 ? (length < int(sizeof(report_)) ? length : sizeof(report_) - 1) : 0;
    }

    const uint8_t motorPins_[4] = {5, 6, 9, 10};
    diagnostic::Mpu6050 imu_;
    uint32_t lastAttempt_ = 0, lastRead_ = 0, lastReport_ = 0, lastSample_ = 0;
    uint32_t uartBytes_ = 0;
    uint8_t uartHistory_[16] = {};
    uint8_t uartNext_ = 0, uartCount_ = 0;
    uint8_t reportPhase_ = 0;
    char report_[192] = {};
    uint16_t pendingSize_ = 0, pendingOffset_ = 0;
};

static BoardDiagnostics diagnostics;
void setup() { diagnostics.begin(); }
void loop() { diagnostics.update(); }

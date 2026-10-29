#pragma once
#include <stdint.h>

// Serial ARM (MSP 151) only right after a compact RC frame with the throttle stick at minimum and a
// clean IMU bus; legacy go_arm() checks none of these. Once armed, outputs stop when RC frames stop.
class SerialArmGuard final {
public:
    static const uint16_t kMinCheck = 1100;      // AirCopter.ino MINCHECK
    static const uint32_t kMaxRcAgeMs = 250;     // several periods of a 50-71 ms RC sender
    static const uint32_t kLinkTimeoutMs = 500;  // bench value, not flight-validated
    static const uint16_t kFirmwareId = 231;     // MSP 199 "version" field: lets the app recognise this build

    void rcReceived(uint32_t nowMs) { lastRcMs_ = nowMs; seen_ = true; }

    bool allows(uint16_t throttle, uint32_t nowMs, int16_t i2cErrors) const {
        return i2cErrors == 0 && seen_ && uint32_t(nowMs - lastRcMs_) <= kMaxRcAgeMs && throttle <= kMinCheck;
    }

    bool mustCut(uint32_t nowMs, int16_t i2cErrors) const {
        return i2cErrors != 0 || !seen_ || uint32_t(nowMs - lastRcMs_) > kLinkTimeoutMs;
    }

private:
    uint32_t lastRcMs_ = 0;
    bool seen_ = false;
};

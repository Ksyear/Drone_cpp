#pragma once
#include <stdint.h>

// Serial ARM (MSP 151) only right after a compact RC frame with the throttle stick at minimum;
// legacy go_arm() checks neither condition.
class SerialArmGuard final {
public:
    static const uint16_t kMinCheck = 1100;   // AirCopter.ino MINCHECK
    static const uint32_t kMaxRcAgeMs = 250;  // several periods of a 50-71 ms RC sender

    void rcReceived(uint32_t nowMs) { lastRcMs_ = nowMs; seen_ = true; }

    bool allows(uint16_t throttle, uint32_t nowMs) const {
        return seen_ && uint32_t(nowMs - lastRcMs_) <= kMaxRcAgeMs && throttle <= kMinCheck;
    }

private:
    uint32_t lastRcMs_ = 0;
    bool seen_ = false;
};

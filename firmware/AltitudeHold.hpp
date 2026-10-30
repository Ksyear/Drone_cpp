#pragma once
#include <stdint.h>

// Height hold on the Optical Plus range finder. Requested by AUX2 in the compact RC frame (150); in that mode the
// throttle byte is the target height in cm, and this class replaces the throttle channel with its own output.
// Gains start from the kit's PLUS sketch (aircopter_plus_xy_h_2m: P 1.0, D 0.3, I 0.4 in PWM counts per cm), scaled
// to rcData microseconds through the legacy throttle curve (about 4 us per count x 1.4). Not flight-tuned yet.
class AltitudeHold final {
public:
    static const uint16_t kIdleUs = 1000;        // below MINCHECK: MOTOR_STOP keeps outputs off
    static const uint16_t kMinUs = 1150, kMaxUs = 1850;  // stays under MAXCHECK 1900 (legacy stick commands)
    static const uint16_t kHoverGuessUs = 1500;  // learned by the integral term
    static const uint8_t kMaxTargetCm = 120;     // VL53L0X default mode, white target ~1.2 m
    static const uint8_t kGroundCm = 8;          // at or below this with a zero target the craft counts as landed
    static const uint16_t kLostMs = 300;         // no valid range this long: slow open-loop descent
    static const uint8_t kAuxHold = 0x10;        // AUX2 bits 5..4 = 01 (rcData 1500)

    // From each RC frame: the AUX byte and the throttle byte (the target in cm while holding).
    void command(uint8_t auxChannels, uint8_t throttleByte) {
        requested_ = (auxChannels & 0x30) == kAuxHold;
        target_ = throttleByte > kMaxTargetCm ? kMaxTargetCm : throttleByte;
    }
    bool requested() const { return requested_; }

    // Once per loop. heightMm 0 = no valid range; sample changes whenever the range finder delivered a new reading.
    void update(bool armed, bool sensorFound, uint16_t heightMm, uint8_t sample, uint32_t nowMs) {
        if (!requested_ || !armed || !sensorFound) { stop(); return; }
        bool fresh = sample != sample_;
        bool lost = engaged_ && uint32_t(nowMs - validMs_) > kLostMs;
        if (!fresh && !lost) return;             // between readings: keep the output and the step clock
        sample_ = sample;
        float dt = (nowMs - stepMs_) / 1000.0f;
        stepMs_ = nowMs;
        if (dt > 0.2f) dt = 0.2f;
        if (!fresh || heightMm == 0) {
            if (lost) slewTo(kHoverGuessUs + integral_ - kDescendUs, dt);  // hover estimate minus a margin
            return;
        }
        if (dt < 0.005f) dt = 0.005f;
        validMs_ = nowMs;
        float h = heightMm / 10.0f;
        if (target_ == 0 && h <= kGroundCm) { stop(); return; }
        if (!engaged_) {                         // take-off: ramp up from the low clamp
            engaged_ = true; prevH_ = h; speed_ = 0; integral_ = 0; out_ = kMinUs;
        }
        float e = (target_ == 0 ? 0.0f : target_) - h;
        speed_ += 0.3f * ((h - prevH_) / dt - speed_);  // cm/s, low-passed
        prevH_ = h;
        integral_ += kKi * e * dt;
        if (integral_ > kIntegralUs) integral_ = kIntegralUs; else if (integral_ < -kIntegralUs) integral_ = -kIntegralUs;
        slewTo(kHoverGuessUs + integral_ + kKp * e - kKd * speed_, dt);
    }

    // Throttle channel value while requested; the raw channel otherwise.
    uint16_t throttle(uint16_t raw) const { return requested_ ? out_ : raw; }
    bool engaged() const { return engaged_; }

private:
    static constexpr float kKp = 5.6f, kKd = 1.7f, kKi = 2.2f;  // us per cm, per cm/s, per cm*s
    static constexpr float kIntegralUs = 300.0f, kSlewUsPerS = 600.0f, kDescendUs = 60.0f;

    void stop() { engaged_ = false; out_ = kIdleUs; }
    void slewTo(float u, float dt) {
        float step = kSlewUsPerS * dt;
        if (u > out_ + step) u = out_ + step; else if (u < out_ - step) u = out_ - step;
        if (u < kMinUs) u = kMinUs; else if (u > kMaxUs) u = kMaxUs;
        out_ = uint16_t(u + 0.5f);
    }

    bool requested_ = false, engaged_ = false;
    uint8_t target_ = 0, sample_ = 0;
    uint16_t out_ = kIdleUs;
    uint32_t stepMs_ = 0, validMs_ = 0;
    float prevH_ = 0, speed_ = 0, integral_ = 0;
};

#pragma once
#include <stdint.h>

// Position hold on the PMW3901 floor flow, requested with AUX3 = 2000 (bits 3..2 = 10) next to the height hold.
// It acts only while the height hold is engaged above the flow sensor's range limit, the surface quality is good
// and the pilot's roll/pitch sticks are centred: then roll/pitch carry a tilt (max kMaxTiltDeg) back to where
// the sticks were released. A stick outside the deadband always wins and re-anchors on release.
// It also keeps odometry since ARM (body frame, no yaw correction) for telemetry.
// Flow axes/scales: hand calibration in evidence/2026-10-30/firmware (flow-calib-*). Gains: first guesses,
// not flight-tuned.
class PositionHold final {
public:
    static const uint8_t kAuxMask = 0x0C, kAuxRequest = 0x08;  // AUX3 bits 3..2 = 10 (rcData 2000)
    static const uint16_t kMidUs = 1500, kStickDeadbandUs = 16;
    static const uint8_t kMinSqual = 30;
    static const uint16_t kMinHeightMm = 100, kMaxHeightMm = 1500;
    static constexpr float kMaxTiltDeg = 6.0f;

    // Calibration: sensor counts -> body axes. A count is an angle seen by the sensor, so the floor distance is
    // counts x height; attitude changes also move the image and are removed first.
    static constexpr float kCmPerCountPerM = 0.244f;   // PLACEHOLDER until flow-calib (Crazyflie model 30 px / 4.2 deg)
    static constexpr float kCountsPerRad = 409.0f;     // PLACEHOLDER, same model
    static const bool kForwardIsSensorX = true;            // PLACEHOLDER: which sensor axis is the craft's forward
    static const int8_t kForwardSign = 1, kRightSign = 1;      // PLACEHOLDER signs

    // From each RC frame: AUX byte and the pilot's roll/pitch channels.
    void command(uint8_t aux, uint16_t rollUs, uint16_t pitchUs) {
        requested_ = (aux & kAuxMask) == kAuxRequest;
        pilotRoll_ = rollUs; pilotPitch_ = pitchUs;
        bool centred = absDiff(rollUs, kMidUs) <= kStickDeadbandUs && absDiff(pitchUs, kMidUs) <= kStickDeadbandUs;
        if (!centred) anchored_ = false;                       // pilot flies: re-anchor on release
    }
    bool requested() const { return requested_; }
    bool active() const { return requested_ && anchored_; }

    // Once per loop with the latest sensor values; flowSample changes with every new flow burst.
    void update(bool heightHeld, bool flowFound, int16_t flowX, int16_t flowY, uint8_t squal, uint8_t flowSample,
                uint16_t heightMm, int16_t rollDeci, int16_t pitchDeci, uint32_t nowMs) {
        if (flowSample == sample_) return;
        sample_ = flowSample;
        float dt = (nowMs - stepMs_) / 1000.0f;
        stepMs_ = nowMs;
        int16_t dx = int16_t(flowX - lastX_), dy = int16_t(flowY - lastY_);  // wrap-safe deltas
        lastX_ = flowX; lastY_ = flowY;
        float dRoll = (rollDeci - lastRoll_) * kDeciToRad, dPitch = (pitchDeci - lastPitch_) * kDeciToRad;
        lastRoll_ = rollDeci; lastPitch_ = pitchDeci;
        bool usable = flowFound && squal >= kMinSqual && heightMm >= kMinHeightMm && heightMm <= kMaxHeightMm && primed_;
        primed_ = true;
        if (!usable || dt <= 0 || dt > 0.2f) { anchored_ = false; return; }

        float a = kForwardIsSensorX ? dx : dy, b = kForwardIsSensorX ? dy : dx;
        float scale = heightMm / 1000.0f * kCmPerCountPerM;
        float df = (kForwardSign * a - dPitch * kCountsPerRad) * scale;  // cm this step, rotation removed
        float dr = (kRightSign * b - dRoll * kCountsPerRad) * scale;
        forwardCm_ += df; rightCm_ += dr;
        vf_ += 0.3f * (df / dt - vf_); vr_ += 0.3f * (dr / dt - vr_);

        bool centred = absDiff(pilotRoll_, kMidUs) <= kStickDeadbandUs && absDiff(pilotPitch_, kMidUs) <= kStickDeadbandUs;
        if (!requested_ || !heightHeld || !centred) { anchored_ = false; return; }
        if (!anchored_) { anchored_ = true; anchorF_ = forwardCm_; anchorR_ = rightCm_; iF_ = iR_ = 0; }
        float ef = forwardCm_ - anchorF_, er = rightCm_ - anchorR_;
        iF_ = clamp(iF_ + kKi * ef * dt, kIntegralDeg); iR_ = clamp(iR_ + kKi * er * dt, kIntegralDeg);
        tiltF_ = clamp(-(kKp * ef + kKd * vf_ + iF_), kMaxTiltDeg);  // back toward the anchor
        tiltR_ = clamp(-(kKp * er + kKd * vr_ + iR_), kMaxTiltDeg);
    }

    // Channel values while requested: the hold tilt when active, the pilot's sticks otherwise.
    uint16_t roll() const { return active() ? toUs(tiltR_) : pilotRoll_; }
    uint16_t pitch() const { return active() ? toUs(tiltF_) : pilotPitch_; }

    void resetOdometry() { forwardCm_ = rightCm_ = 0; anchored_ = false; }
    int16_t forwardCm() const { return int16_t(forwardCm_); }
    int16_t rightCm() const { return int16_t(rightCm_); }

    // ANGLE/HORIZON target = 2 x rcCommand decidegrees, rcCommand = 0.9 x stick offset (rcRate 90, expo 0)
    static uint16_t toUs(float tiltDeg) { return uint16_t(kMidUs + tiltDeg * 10.0f / 1.8f + 0.5f); }  // always > 0: round half up

private:
    static constexpr float kKp = 0.15f, kKd = 0.10f, kKi = 0.05f;  // deg per cm, per cm/s, per cm*s
    static constexpr float kIntegralDeg = 2.0f, kDeciToRad = 0.0017453293f;

    static uint16_t absDiff(uint16_t a, uint16_t b) { return a > b ? a - b : b - a; }
    static float clamp(float v, float m) { return v > m ? m : (v < -m ? -m : v); }

    bool requested_ = false, anchored_ = false, primed_ = false;
    uint8_t sample_ = 0;
    uint16_t pilotRoll_ = kMidUs, pilotPitch_ = kMidUs;
    int16_t lastX_ = 0, lastY_ = 0, lastRoll_ = 0, lastPitch_ = 0;
    uint32_t stepMs_ = 0;
    float forwardCm_ = 0, rightCm_ = 0, vf_ = 0, vr_ = 0, anchorF_ = 0, anchorR_ = 0, iF_ = 0, iR_ = 0;
    float tiltF_ = 0, tiltR_ = 0;
};

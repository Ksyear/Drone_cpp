#include "ControlPayload.hpp"
#include "FlightController.hpp"
#include "ArmGuard.hpp"
#include "AltitudeHold.hpp"
#include <cassert>

static int starts = 0;
static int updates = 0;
static int opticalBegins = 0;
static int opticalPolls = 0;
static int holdSteps = 0;
void legacySetup() { ++starts; }
void legacyLoop() { ++updates; }
void altitudeHoldStep() { assert(opticalPolls == holdSteps + 1); ++holdSteps; }  // after the sensor poll
namespace Optical {
void begin() { assert(starts == 1); ++opticalBegins; }  // after legacySetup has started I2C
void poll() { assert(updates == opticalPolls + 1); ++opticalPolls; }
}

int main() {
    for (int size = 0; size <= 255; ++size) {
        assert(validControlPayload(150, size) == (size == 5));
        assert(validControlPayload(200, size) == (size == 16));
        assert(validControlPayload(151, size) == (size == 0));
        assert(validControlPayload(152, size) == (size == 0));
    }
    assert(validControlPayload(108, 0));
    for (int command = 0; command <= 255; ++command)
        assert(writesEeprom(command) == ((command >= 153 && command <= 160) || command == 250));
    FlightController::begin();
    FlightController::update();
    FlightController::update();
    assert(starts == 1 && updates == 2 && opticalBegins == 1 && opticalPolls == 2 && holdSteps == 2);

    // VL53L0X result block: device status in bits 6..3 of byte 0, range big-endian at bytes 10..11
    uint8_t result[12] = {11 << 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x04, 0xD2};
    assert(Optical::rangeFromResult(result) == 1234);
    result[0] = 4 << 3;                                          // signal fail
    assert(Optical::rangeFromResult(result) == 0);
    result[0] = 11 << 3; result[10] = 0x1F; result[11] = 0xFE;   // 8190: nothing in range
    assert(Optical::rangeFromResult(result) == 0);
    // PMW3901 motion burst: little-endian deltas, SQUAL at byte 6, motion flag bit 7
    const uint8_t burst[12] = {0x80, 0, 0x10, 0x00, 0xF0, 0xFF, 100, 0, 0, 0, 0, 0};
    Optical::Flow flow = Optical::flowFromBurst(burst);
    assert(flow.dx == 16 && flow.dy == -16 && flow.squal == 100 && flow.moved);
    // VL53L0X timeouts: exact round trip while the value fits the 8-bit mantissa, never longer above it
    for (uint32_t mclks = 1; mclks <= 256; ++mclks)
        assert(uint32_t(Optical::decodeTimeout(Optical::encodeTimeout(mclks))) == mclks);
    for (uint32_t mclks = 257; mclks < 60000; mclks += 997) {
        uint32_t back = Optical::decodeTimeout(Optical::encodeTimeout(mclks));
        assert(back <= mclks && back * 2 > mclks);
    }
    assert(Optical::encodeTimeout(0) == 0 && Optical::decodeTimeout(0x0102) == 5);
    assert(Optical::macroPeriodNs(14) == 53384);                 // (2304 * 14 * 1655 + 500) / 1000
    assert(Optical::usToMclks(1000, 14) == 19 && Optical::mclksToUs(19, 14) == 1014);

    // height hold: off unless AUX2 asks for it; idle whenever it cannot run
    AltitudeHold hold;
    assert(!hold.requested() && hold.throttle(1234) == 1234);
    hold.command(0x05, 30);                                     // app default AUX byte: no hold bit
    assert(!hold.requested() && hold.throttle(1120) == 1120);
    hold.command(0x15, 200);                                    // AUX2 = 1500: hold, target clipped to 120 cm
    assert(hold.requested());
    hold.update(false, true, 24, 1, 0);                         // disarmed
    assert(hold.throttle(0) == AltitudeHold::kIdleUs);
    hold.update(true, false, 24, 2, 10);                        // no range finder
    assert(hold.throttle(0) == AltitudeHold::kIdleUs);
    hold.command(0x15, 0);
    hold.update(true, true, 24, 3, 20);                         // armed on the ground, target 0
    assert(hold.throttle(0) == AltitudeHold::kIdleUs && !hold.engaged());
    // take-off to 40 cm from 2.4 cm: from the low clamp, rising, never faster than the slew limit
    hold.command(0x15, 40);
    uint32_t t = 20;
    uint8_t sample = 3;
    uint16_t last = 0;
    for (int i = 0; i < 40; ++i) {
        t += 33;
        hold.update(true, true, 24, ++sample, t);
        uint16_t out = hold.throttle(0);
        assert(out >= AltitudeHold::kMinUs && out <= AltitudeHold::kMaxUs);
        assert(last == 0 || (out >= last && out - last <= 21));  // 600 us/s over 33 ms
        last = out;
    }
    assert(hold.engaged() && last > 1700);
    // at the target and still: settles near the hover guess
    AltitudeHold level;
    level.command(0x15, 40);
    t = 0; sample = 0;
    for (int i = 0; i < 60; ++i) { t += 33; level.update(true, true, 400, ++sample, t); }
    uint16_t settled = level.throttle(0);
    assert(settled > 1480 && settled < 1520);
    // climbing through the target: the speed term pulls the output below the still value
    AltitudeHold rising = level;
    for (int i = 0; i < 5; ++i) { t += 33; rising.update(true, true, 400 + 15 * (i + 1), ++sample, t); }
    assert(rising.throttle(0) < settled);
    // range lost for longer than 300 ms: slow descent from the hover estimate, never a jump
    AltitudeHold lost = level;
    uint32_t tl = t;
    uint8_t sl = sample;
    uint16_t before = lost.throttle(0);
    for (int i = 0; i < 30; ++i) { tl += 33; lost.update(true, true, 0, ++sl, tl); }
    uint16_t after = lost.throttle(0);
    assert(after < before && before - after <= 70 && after >= AltitudeHold::kMinUs);
    // landing: zero target at ground height idles the motors
    level.command(0x15, 0);
    t += 33; level.update(true, true, 60, ++sample, t);
    assert(level.throttle(0) == AltitudeHold::kIdleUs && !level.engaged());

    SerialArmGuard guard;
    assert(!guard.allows(1000, 0, 0) && guard.mustCut(0, 0));   // no RC frame yet
    guard.rcReceived(1000);
    assert(guard.allows(1000, 1250, 0) && guard.allows(1100, 1000, 0));
    assert(!guard.allows(1101, 1000, 0));                       // throttle above MINCHECK
    assert(!guard.allows(1000, 1251, 0));                       // RC older than 250 ms
    assert(!guard.allows(1000, 1000, 1));                       // IMU bus error
    // armed failsafe, separate from the ARM entry checks
    assert(!guard.mustCut(1500, 0) && guard.mustCut(1501, 0));  // 500 ms without RC
    assert(guard.mustCut(1000, 3));                             // I2C error while armed
    guard.rcReceived(0xFFFFFFF0u);                              // millis() wrap-around
    assert(guard.allows(1000, 0x00000010u, 0) && !guard.allows(1000, 0x00000100u, 0));
    assert(!guard.mustCut(0x000001E4u, 0) && guard.mustCut(0x000001E5u, 0));
    assert(SerialArmGuard::kFirmwareId != 100);                 // distinguishable from the original 199 version
}

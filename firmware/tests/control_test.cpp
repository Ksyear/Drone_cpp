#include "ControlPayload.hpp"
#include "FlightController.hpp"
#include "ArmGuard.hpp"
#include <cassert>

static int starts = 0;
static int updates = 0;
static int opticalBegins = 0;
static int opticalPolls = 0;
void legacySetup() { ++starts; }
void legacyLoop() { ++updates; }
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
    assert(starts == 1 && updates == 2 && opticalBegins == 1 && opticalPolls == 2);

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

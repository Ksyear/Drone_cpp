#include "ControlPayload.hpp"
#include "FlightController.hpp"
#include "ArmGuard.hpp"
#include <cassert>

static int starts = 0;
static int updates = 0;
void legacySetup() { ++starts; }
void legacyLoop() { ++updates; }

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
    assert(starts == 1 && updates == 2);

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

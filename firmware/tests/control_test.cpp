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
    FlightController::begin();
    FlightController::update();
    FlightController::update();
    assert(starts == 1 && updates == 2);

    SerialArmGuard guard;
    assert(!guard.allows(1000, 0));                    // no RC frame yet
    guard.rcReceived(1000);
    assert(guard.allows(1000, 1250) && guard.allows(1100, 1000));
    assert(!guard.allows(1101, 1000));                 // throttle above MINCHECK
    assert(!guard.allows(1000, 1251));                 // RC older than 250 ms
    guard.rcReceived(0xFFFFFFF0u);                     // millis() wrap-around
    assert(guard.allows(1000, 0x00000010u) && !guard.allows(1000, 0x00000100u));
}

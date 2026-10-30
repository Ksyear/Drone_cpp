#pragma once
#include "OpticalSensors.hpp"

void legacySetup();
void legacyLoop();
void holdStep();  // generated next to the legacy loop (AltitudeHold.hpp, PositionHold.hpp)

// Facade: keep Arduino entry points separate from the unchanged legacy flight loop. The Optical Plus sensors
// are read between loops, after the legacy setup has started the I2C bus; the height and position holds then
// refresh the throttle and roll/pitch channels the next loop will read.
class FlightController final {
public:
    static void begin() { legacySetup(); Optical::begin(); }
    static void update() { legacyLoop(); Optical::poll(); holdStep(); }
};

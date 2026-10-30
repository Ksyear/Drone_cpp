#pragma once
#include "OpticalSensors.hpp"

void legacySetup();
void legacyLoop();
void altitudeHoldStep();  // generated next to the legacy loop (AltitudeHold.hpp)

// Facade: keep Arduino entry points separate from the unchanged legacy flight loop. The Optical Plus sensors
// are read between loops, after the legacy setup has started the I2C bus; the height hold then refreshes the
// throttle channel the next loop will read.
class FlightController final {
public:
    static void begin() { legacySetup(); Optical::begin(); }
    static void update() { legacyLoop(); Optical::poll(); altitudeHoldStep(); }
};

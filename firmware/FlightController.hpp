#pragma once
#include "OpticalSensors.hpp"

void legacySetup();
void legacyLoop();

// Facade: keep Arduino entry points separate from the unchanged legacy flight loop. The Optical Plus sensors
// are read between loops, after the legacy setup has started the I2C bus.
class FlightController final {
public:
    static void begin() { legacySetup(); Optical::begin(); }
    static void update() { legacyLoop(); Optical::poll(); }
};

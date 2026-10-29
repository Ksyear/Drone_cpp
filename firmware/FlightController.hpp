#pragma once

void legacySetup();
void legacyLoop();

// Facade: keep Arduino entry points separate from the unchanged legacy flight loop.
class FlightController final {
public:
    static void begin() { legacySetup(); }
    static void update() { legacyLoop(); }
};

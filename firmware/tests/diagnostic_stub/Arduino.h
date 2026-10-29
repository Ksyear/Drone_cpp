#pragma once
#include <cstddef>
#include <cstdint>
#include <deque>
#include <string>
#include <vector>

constexpr uint8_t LOW = 0, OUTPUT = 1, SDA = 2, SCL = 3;
struct PinEvent { uint8_t pin, value; bool mode; };
extern std::vector<PinEvent> pinEvents;
extern uint32_t testMillis;
inline void digitalWrite(uint8_t pin, uint8_t value) { pinEvents.push_back({pin, value, false}); }
inline void pinMode(uint8_t pin, uint8_t mode) { pinEvents.push_back({pin, mode, true}); }
inline unsigned long millis() { return testMillis; }
inline void delay(unsigned long ms) { testMillis += uint32_t(ms); }

struct FakeSerial {
    bool connected = true;
    int space = 64;
    uint32_t baud = 0;
    size_t txCalls = 0;
    std::string output;
    std::deque<uint8_t> input;
    void begin(uint32_t value) { baud = value; }
    explicit operator bool() const { return connected; }
    int available() const { return int(input.size()); }
    int availableForWrite() const { return space; }
    int read() {
        if (input.empty()) return -1;
        const uint8_t byte = input.front(); input.pop_front(); return byte;
    }
    size_t write(const uint8_t* bytes, size_t count) {
        ++txCalls;
        if (!connected || space <= 0) return 0;
        if (count > size_t(space)) count = size_t(space);
        output.append(reinterpret_cast<const char*>(bytes), count);
        return count;
    }
};
extern FakeSerial Serial, Serial1;

#pragma once
#include <Arduino.h>
#include <array>

struct TwoWire {
    bool present[2] = {true, false};
    std::array<std::array<uint8_t, 256>, 2> registers{};
    bool timeoutFlag = false, fresh = true, wrongConfig = false;
    int timeoutReg = -1, shortReg = -1, nackReg = -1;
    uint8_t address = 0, reg = 0;
    uint32_t clock = 0, timeoutUs = 0;
    bool resetOnTimeout = false;
    std::vector<uint8_t> tx;
    std::deque<uint8_t> rx;
    TwoWire() { registers[0][0x75] = registers[1][0x75] = 0x68; }
    void begin() {}
    void setClock(uint32_t value) { clock = value; }
    void setWireTimeout(uint32_t value, bool reset) { timeoutUs = value; resetOnTimeout = reset; }
    bool getWireTimeoutFlag() const { return timeoutFlag; }
    void clearWireTimeoutFlag() { timeoutFlag = false; }
    void beginTransmission(uint8_t value) { address = value; tx.clear(); }
    size_t write(uint8_t byte) { tx.push_back(byte); return 1; }
    uint8_t endTransmission(uint8_t = true) {
        if (address < 0x68 || address > 0x69 || !present[address - 0x68]) return 2;
        if (tx.empty()) return 4;
        reg = tx[0];
        if (int(reg) == nackReg) return 3;
        if (tx.size() == 2) registers[address - 0x68][reg] = tx[1];
        return 0;
    }
    uint8_t requestFrom(uint8_t value, uint8_t count, uint8_t) {
        rx.clear();
        if (int(reg) == timeoutReg) { timeoutFlag = true; return 0; }
        if (value < 0x68 || value > 0x69 || !present[value - 0x68]) return 0;
        const uint8_t received = int(reg) == shortReg ? count - 1 : count;
        for (uint8_t i = 0; i < received; ++i) {
            uint8_t byte = registers[value - 0x68][uint8_t(reg + i)];
            if (reg == 0x3A) byte = fresh ? 1 : 0;
            if (wrongConfig && reg == 0x1A) byte ^= 1;
            rx.push_back(byte);
        }
        return received;
    }
    int available() const { return int(rx.size()); }
    int read() { if (rx.empty()) return -1; const uint8_t b = rx.front(); rx.pop_front(); return b; }
};
extern TwoWire Wire;

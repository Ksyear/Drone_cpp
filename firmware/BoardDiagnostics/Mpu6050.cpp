#include <Arduino.h>
#include <Wire.h>
#include "Mpu6050.hpp"

namespace diagnostic {

Error Mpu6050::readRegisters(uint8_t address, uint8_t reg, uint8_t* bytes, uint8_t count) {
    Wire.clearWireTimeoutFlag();
    Wire.beginTransmission(address);
    Wire.write(reg);
    const uint8_t result = Wire.endTransmission(false);
    if (Wire.getWireTimeoutFlag()) return Error::Timeout;
    if (result != 0) return Error::Nack;
    const uint8_t received = Wire.requestFrom(address, count, uint8_t(true));
    if (Wire.getWireTimeoutFlag()) {
        while (Wire.available()) Wire.read();
        return Error::Timeout;
    }
    if (received != count || Wire.available() != count) {
        while (Wire.available()) Wire.read();
        return Error::ShortRead;
    }
    for (uint8_t i = 0; i < count; ++i) bytes[i] = uint8_t(Wire.read());
    return Error::None;
}

Error Mpu6050::writeRegister(uint8_t reg, uint8_t value) {
    Wire.clearWireTimeoutFlag();
    Wire.beginTransmission(address_);
    Wire.write(reg);
    Wire.write(value);
    const uint8_t result = Wire.endTransmission(true);
    if (Wire.getWireTimeoutFlag()) return Error::Timeout;
    return result == 0 ? Error::None : Error::Nack;
}

bool Mpu6050::fail(Error error, bool offline) {
    sample_ = Sample{};
    if (offline) ready_ = false;
    ++health_.failures;
    ++health_.errors[uint8_t(error)];
    health_.last = error;
    return false;
}

bool Mpu6050::begin() {
    ready_ = false;
    address_ = 0;
    sample_ = Sample{};
    Wire.begin();
    Wire.setClock(400000UL);
    Wire.setWireTimeout(3000UL, true);
    // The original AIR configuration disables internal I2C pullups.
    digitalWrite(SDA, LOW);
    digitalWrite(SCL, LOW);

    for (uint8_t address = 0x68; address <= 0x69; ++address) {
        uint8_t identity = 0;
        const Error result = readRegisters(address, 0x75, &identity, 1);
        if (result == Error::Nack) continue; // One unpopulated address is expected.
        if (result != Error::None) return fail(result);
        if (identity != 0x68) return fail(Error::WrongIdentity);
        if (address_ != 0) return fail(Error::AmbiguousAddress);
        address_ = address;
    }
    if (address_ == 0) return fail(Error::NoDevice);

    Error result = writeRegister(0x6B, 0x80);
    if (result != Error::None) return fail(result);
    delay(100); // Allow reset to finish before applying and verifying settings.
    const uint8_t registers[] = {0x6B, 0x1A, 0x1B, 0x1C, 0x19, 0x38};
    const uint8_t values[] = {0x03, 0x02, 0x18, 0x10, 0x09, 0x01};
    for (uint8_t i = 0; i < sizeof(registers); ++i) {
        result = writeRegister(registers[i], values[i]);
        if (result != Error::None) return fail(result);
        uint8_t actual = 0;
        result = readRegisters(address_, registers[i], &actual, 1);
        if (result != Error::None) return fail(result);
        if (actual != values[i]) return fail(Error::ConfigMismatch);
    }
    delay(100); // Gyroscope startup; no calibration or offset writes.
    ready_ = true;
    health_.last = Error::None;
    return true;
}

bool Mpu6050::read() {
    sample_ = Sample{}; // Never present the preceding sample as a successful read.
    if (!ready_) return false;
    uint8_t status = 0;
    Error result = readRegisters(address_, 0x3A, &status, 1);
    if (result != Error::None) return fail(result);
    if (!(status & 1)) return fail(Error::NotReady, false);
    uint8_t bytes[14] = {};
    result = readRegisters(address_, 0x3B, bytes, sizeof(bytes));
    if (result != Error::None) return fail(result);
    decode(bytes, sizeof(bytes), sample_);
    ++health_.samples;
    health_.last = Error::None;
    return true;
}

} // namespace diagnostic

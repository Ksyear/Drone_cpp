#pragma once

#include <stdint.h>

namespace diagnostic {

struct Sample {
    int16_t accel[3];
    int16_t temperature;
    int16_t gyro[3];
    bool valid;
};

// Big-endian sensor counts, before board-axis mapping or calibration.
inline int16_t signedWord(const uint8_t* bytes) {
    const uint16_t word = (uint16_t(bytes[0]) << 8) | bytes[1];
    return word < 0x8000U ? int16_t(word) : int16_t(int32_t(word) - 65536L);
}

inline bool decode(const uint8_t* bytes, uint8_t length, Sample& sample) {
    sample = Sample{};
    if (!bytes || length != 14) return false;
    for (uint8_t axis = 0; axis < 3; ++axis) {
        sample.accel[axis] = signedWord(bytes + axis * 2);
        sample.gyro[axis] = signedWord(bytes + 8 + axis * 2);
    }
    sample.temperature = signedWord(bytes + 6);
    sample.valid = true;
    return true;
}

// Matches AVR signed >> without relying on the host's negative right shift.
inline int16_t divideLikeAvr(int16_t value, uint8_t bits) {
    const int32_t divisor = int32_t(1) << bits;
    return value >= 0 ? value / divisor : -((-int32_t(value) + divisor - 1) / divisor);
}

inline void legacyAxes(const Sample& raw, int16_t accel[3], int16_t gyro[3]) {
    accel[0] = -divideLikeAvr(raw.accel[0], 3);
    accel[1] = -divideLikeAvr(raw.accel[1], 3);
    accel[2] = divideLikeAvr(raw.accel[2], 3);
    gyro[0] = divideLikeAvr(raw.gyro[1], 2);
    gyro[1] = -divideLikeAvr(raw.gyro[0], 2);
    gyro[2] = -divideLikeAvr(raw.gyro[2], 2);
}

enum class Error : uint8_t {
    None, NoDevice, WrongIdentity, AmbiguousAddress, Nack, Timeout,
    ShortRead, ConfigMismatch, NotReady, Count
};

struct Health {
    uint32_t samples = 0;
    uint32_t failures = 0;
    uint32_t errors[uint8_t(Error::Count)] = {};
    Error last = Error::None;
};

class Mpu6050 final {
public:
    bool begin();
    bool read();
    bool ready() const { return ready_; }
    uint8_t address() const { return address_; }
    const Sample& sample() const { return sample_; }
    const Health& health() const { return health_; }

private:
    Error readRegisters(uint8_t address, uint8_t reg, uint8_t* bytes, uint8_t count);
    Error writeRegister(uint8_t reg, uint8_t value);
    bool fail(Error error, bool offline = true);
    uint8_t address_ = 0;
    bool ready_ = false;
    Sample sample_ = {};
    Health health_;
};

} // namespace diagnostic

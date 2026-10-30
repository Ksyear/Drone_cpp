#pragma once
#include <stdint.h>

// Optical Plus board: VL53L0X range finder on the MPU6050 I2C bus (0x29) and PMW3901 optical flow on
// hardware SPI with chip select D7. Read-only: nothing here touches the motors or the flight loop, and a
// missing board only leaves the status bits clear. Reported over MSP 198 (see Serial.ino replacement).
namespace Optical {

const uint8_t kMspOptical = 198;
enum : uint8_t { kRangeFound = 1, kFlowFound = 2, kRangeValid = 4 };

void begin();
void poll();  // between flight loops; rate-limited inside
uint8_t status();
uint16_t heightMm();  // last valid range, 0 when none
int16_t flowX();      // flow counts accumulated since boot, wrapping
int16_t flowY();
uint8_t quality();    // PMW3901 SQUAL of the last burst
uint8_t rangeSamples();  // counts range finder readings (valid or not), wrapping
uint8_t flowSamples();   // counts flow bursts, wrapping

// VL53L0X RESULT_RANGE_STATUS block (12 bytes from 0x14): the ST API treats only device status 11 as a valid
// range (vl53l0x_api_core VL53L0X_get_pal_range_status); 8190/8191 mean nothing in range.
inline uint16_t rangeFromResult(const uint8_t r[12]) {
    uint16_t mm = uint16_t(r[10]) << 8 | r[11];
    return ((r[0] >> 3) & 0x0F) == 11 && mm < 8190 ? mm : 0;
}

// PMW3901 motion burst (12 bytes from 0x16): motion, observation, dx L/H, dy L/H, SQUAL, ...
struct Flow { int16_t dx, dy; uint8_t squal; bool moved; };
inline Flow flowFromBurst(const uint8_t b[12]) {
    return Flow{int16_t(uint16_t(b[3]) << 8 | b[2]), int16_t(uint16_t(b[5]) << 8 | b[4]), b[6], (b[0] & 0x80) != 0};
}

// VL53L0X sequence step timeouts, "(LSByte * 2^MSByte) + 1" (Pololu vl53l0x-arduino, from ST VL53L0X_decode_timeout)
inline uint16_t decodeTimeout(uint16_t reg) { return uint16_t((reg & 0xFF) << (reg >> 8)) + 1; }
inline uint16_t encodeTimeout(uint32_t mclks) {
    if (mclks == 0) return 0;
    uint32_t ls = mclks - 1;
    uint16_t ms = 0;
    while (ls & 0xFFFFFF00UL) { ls >>= 1; ms++; }
    return uint16_t(ms << 8 | (ls & 0xFF));
}
inline uint32_t macroPeriodNs(uint8_t vcselPclks) { return (2304UL * vcselPclks * 1655 + 500) / 1000; }
inline uint32_t mclksToUs(uint16_t mclks, uint8_t vcselPclks) { return (mclks * macroPeriodNs(vcselPclks) + 500) / 1000; }
inline uint32_t usToMclks(uint32_t us, uint8_t vcselPclks) {
    uint32_t ns = macroPeriodNs(vcselPclks);
    return (us * 1000 + ns / 2) / ns;
}

}  // namespace Optical

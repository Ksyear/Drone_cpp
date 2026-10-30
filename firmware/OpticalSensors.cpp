// Optical Plus drivers for the ATmega32U4 flight firmware (see OpticalSensors.hpp).
//
// VL53L0X sequence adapted from Pololu vl53l0x-arduino 1.3.1, Copyright (c) 2017-2022 Pololu Corporation (MIT),
// itself derived from the ST VL53L0X API (STSW-IMG005), Copyright (c) 2016 STMicroelectronics International N.V.
// (BSD-3-Clause). PMW3901 sequence adapted from the Bitcraze PMW3901 Arduino driver 1.0.0, Copyright (c) 2017
// Bitcraze AB (MIT). Both MIT notices: permission is hereby granted, free of charge, to deal in the Software
// without restriction, provided the copyright and permission notices are included; THE SOFTWARE IS PROVIDED
// "AS IS", WITHOUT WARRANTY OF ANY KIND. ST notice: redistributions must retain the copyright notice, this list of
// conditions and the disclaimer; neither the name of STMicroelectronics nor its contributors may be used to
// endorse derived products; provided "AS IS" without any express or implied warranty.
//
// I2C goes through the legacy MultiWii TWI helpers (same bus as the MPU6050, so no second driver fights over TWI).
#include "OpticalSensors.hpp"

#if defined(__AVR__)
#include <Arduino.h>
#include <avr/pgmspace.h>

void i2c_rep_start(uint8_t address);
void i2c_stop(void);
void i2c_write(uint8_t data);
void i2c_writeReg(uint8_t add, uint8_t reg, uint8_t val);
uint8_t i2c_readReg(uint8_t add, uint8_t reg);
size_t i2c_read_reg_to_buf(uint8_t add, uint8_t reg, void *buf, size_t size);

namespace Optical {
namespace {

const uint8_t kTof = 0x29;
const uint8_t kDelayMark = 0xFF;  // PMW3901 table: pause 100 ms (register 0xFF is never written)

uint8_t state, stopVariable, squal, phase, samples, bursts;
uint16_t range, lastPollMs;
int16_t sumX, sumY;

// VL53L0X DefaultTuningSettings (vl53l0x_tuning.h via Pololu init), register/value pairs
const uint8_t kTuning[] PROGMEM = {
    0xFF, 0x01, 0x00, 0x00, 0xFF, 0x00, 0x09, 0x00, 0x10, 0x00, 0x11, 0x00, 0x24, 0x01, 0x25, 0xFF, 0x75, 0x00,
    0xFF, 0x01, 0x4E, 0x2C, 0x48, 0x00, 0x30, 0x20, 0xFF, 0x00, 0x30, 0x09, 0x54, 0x00, 0x31, 0x04, 0x32, 0x03,
    0x40, 0x83, 0x46, 0x25, 0x60, 0x00, 0x27, 0x00, 0x50, 0x06, 0x51, 0x00, 0x52, 0x96, 0x56, 0x08, 0x57, 0x30,
    0x61, 0x00, 0x62, 0x00, 0x64, 0x00, 0x65, 0x00, 0x66, 0xA0, 0xFF, 0x01, 0x22, 0x32, 0x47, 0x14, 0x49, 0xFF,
    0x4A, 0x00, 0xFF, 0x00, 0x7A, 0x0A, 0x7B, 0x00, 0x78, 0x21, 0xFF, 0x01, 0x23, 0x34, 0x42, 0x00, 0x44, 0xFF,
    0x45, 0x26, 0x46, 0x05, 0x40, 0x40, 0x0E, 0x06, 0x20, 0x1A, 0x43, 0x40, 0xFF, 0x00, 0x34, 0x03, 0x35, 0x44,
    0xFF, 0x01, 0x31, 0x04, 0x4B, 0x09, 0x4C, 0x05, 0x4D, 0x04, 0xFF, 0x00, 0x44, 0x00, 0x45, 0x20, 0x47, 0x08,
    0x48, 0x28, 0x67, 0x00, 0x70, 0x04, 0x71, 0x01, 0x72, 0xFE, 0x76, 0x00, 0x77, 0x00, 0xFF, 0x01, 0x0D, 0x01,
    0xFF, 0x00, 0x80, 0x01, 0x01, 0xF8, 0xFF, 0x01, 0x8E, 0x01, 0x00, 0x01, 0xFF, 0x00, 0x80, 0x00,
};

// PMW3901 performance optimisation registers (Bitcraze initRegisters), register/value pairs
const uint8_t kFlowInit[] PROGMEM = {
    0x7F, 0x00, 0x61, 0xAD, 0x7F, 0x03, 0x40, 0x00, 0x7F, 0x05, 0x41, 0xB3, 0x43, 0xF1, 0x45, 0x14, 0x5B, 0x32,
    0x5F, 0x34, 0x7B, 0x08, 0x7F, 0x06, 0x44, 0x1B, 0x40, 0xBF, 0x4E, 0x3F, 0x7F, 0x08, 0x65, 0x20, 0x6A, 0x18,
    0x7F, 0x09, 0x4F, 0xAF, 0x5F, 0x40, 0x48, 0x80, 0x49, 0x80, 0x57, 0x77, 0x60, 0x78, 0x61, 0x78, 0x62, 0x08,
    0x63, 0x50, 0x7F, 0x0A, 0x45, 0x60, 0x7F, 0x00, 0x4D, 0x11, 0x55, 0x80, 0x74, 0x1F, 0x75, 0x1F, 0x4A, 0x78,
    0x4B, 0x78, 0x44, 0x08, 0x45, 0x50, 0x64, 0xFF, 0x65, 0x1F, 0x7F, 0x14, 0x65, 0x60, 0x66, 0x08, 0x63, 0x78,
    0x7F, 0x15, 0x48, 0x58, 0x7F, 0x07, 0x41, 0x0D, 0x43, 0x14, 0x4B, 0x0E, 0x45, 0x0F, 0x44, 0x42, 0x4C, 0x80,
    0x7F, 0x10, 0x5B, 0x02, 0x7F, 0x07, 0x40, 0x41, 0x70, 0x00, kDelayMark, 0x00,
    0x32, 0x44, 0x7F, 0x07, 0x40, 0x40, 0x7F, 0x06, 0x62, 0xF0, 0x63, 0x00, 0x7F, 0x0D, 0x48, 0xC0, 0x6F, 0xD5,
    0x7F, 0x00, 0x5B, 0xA0, 0x4E, 0xA8, 0x5A, 0x50, 0x40, 0x80,
};

void w(uint8_t reg, uint8_t v) { i2c_writeReg(kTof, reg, v); }
uint8_t r(uint8_t reg) { return i2c_readReg(kTof, reg); }
void w16(uint8_t reg, uint16_t v) {
    i2c_rep_start(kTof << 1); i2c_write(reg); i2c_write(v >> 8); i2c_write(v & 0xFF); i2c_stop();
}
uint16_t r16(uint8_t reg) {
    uint8_t b[2];
    i2c_read_reg_to_buf(kTof, reg, b, 2);
    return uint16_t(b[0]) << 8 | b[1];
}
// up to 100 ms for (reg & mask) != 0, as the Pololu startTimeout/checkTimeoutExpired loops
bool waitSet(uint8_t reg, uint8_t mask) {
    uint16_t start = millis();
    while (!(r(reg) & mask)) if (uint16_t(millis() - start) > 100) return false;
    return true;
}

struct Sequence { uint8_t cfg, preVcsel, finalVcsel; uint16_t preMclks; uint32_t msrcUs, preUs, finalUs; };
void readSequence(Sequence &s) {
    s.cfg = r(0x01);                                  // SYSTEM_SEQUENCE_CONFIG
    s.preVcsel = (r(0x50) + 1) << 1;                  // PRE_RANGE_CONFIG_VCSEL_PERIOD
    s.msrcUs = mclksToUs(r(0x46) + 1, s.preVcsel);    // MSRC_CONFIG_TIMEOUT_MACROP
    s.preMclks = decodeTimeout(r16(0x51));            // PRE_RANGE_CONFIG_TIMEOUT_MACROP_HI
    s.preUs = mclksToUs(s.preMclks, s.preVcsel);
    s.finalVcsel = (r(0x70) + 1) << 1;                // FINAL_RANGE_CONFIG_VCSEL_PERIOD
    uint16_t finalMclks = decodeTimeout(r16(0x71));   // FINAL_RANGE_CONFIG_TIMEOUT_MACROP_HI
    if (s.cfg & 0x40) finalMclks -= s.preMclks;
    s.finalUs = mclksToUs(finalMclks, s.finalVcsel);
}
uint32_t overheadUs(const Sequence &s) {              // everything but the final range step
    uint32_t us = 1910 + 960;
    if (s.cfg & 0x10) us += s.msrcUs + 590;           // TCC
    if (s.cfg & 0x08) us += 2 * (s.msrcUs + 690);     // DSS
    else if (s.cfg & 0x04) us += s.msrcUs + 660;      // MSRC
    if (s.cfg & 0x40) us += s.preUs + 660;            // pre-range
    return us;
}

bool singleRefCalibration(uint8_t vhvInit) {
    w(0x00, 0x01 | vhvInit);                          // SYSRANGE_START
    if (!waitSet(0x13, 0x07)) return false;           // RESULT_INTERRUPT_STATUS
    w(0x0B, 0x01);                                    // SYSTEM_INTERRUPT_CLEAR
    w(0x00, 0x00);
    return true;
}

bool rangeBegin() {
    if (r(0xC0) != 0xEE) return false;                // IDENTIFICATION_MODEL_ID
    w(0x89, r(0x89) | 0x01);                          // 2V8 I/O, as the kit's ST API (USE_I2C_2V8)
    w(0x88, 0x00);
    w(0x80, 0x01); w(0xFF, 0x01); w(0x00, 0x00);
    stopVariable = r(0x91);
    w(0x00, 0x01); w(0xFF, 0x00); w(0x80, 0x00);
    w(0x60, r(0x60) | 0x12);                          // no MSRC / pre-range signal rate limit checks
    w16(0x44, 32);                                    // final range signal rate limit 0.25 MCPS (Q9.7)
    w(0x01, 0xFF);

    // reference SPAD count and type (getSpadInfo)
    w(0x80, 0x01); w(0xFF, 0x01); w(0x00, 0x00);
    w(0xFF, 0x06); w(0x83, r(0x83) | 0x04); w(0xFF, 0x07); w(0x81, 0x01);
    w(0x80, 0x01); w(0x94, 0x6B); w(0x83, 0x00);
    if (!waitSet(0x83, 0xFF)) return false;
    w(0x83, 0x01);
    uint8_t info = r(0x92);
    w(0x81, 0x00); w(0xFF, 0x06); w(0x83, r(0x83) & ~0x04); w(0xFF, 0x01); w(0x00, 0x01);
    w(0xFF, 0x00); w(0x80, 0x00);
    uint8_t count = info & 0x7F, first = (info & 0x80) ? 12 : 0, enabled = 0;

    uint8_t map[6];
    i2c_read_reg_to_buf(kTof, 0xB0, map, 6);          // GLOBAL_CONFIG_SPAD_ENABLES_REF_0..5
    w(0xFF, 0x01); w(0x4F, 0x00); w(0x4E, 0x2C); w(0xFF, 0x00); w(0xB6, 0xB4);
    for (uint8_t i = 0; i < 48; i++) {
        if (i < first || enabled == count) map[i / 8] &= ~(1 << (i % 8));
        else if ((map[i / 8] >> (i % 8)) & 1) enabled++;
    }
    i2c_rep_start(kTof << 1); i2c_write(0xB0);
    for (uint8_t i = 0; i < 6; i++) i2c_write(map[i]);
    i2c_stop();

    for (uint8_t i = 0; i < sizeof(kTuning); i += 2) w(pgm_read_byte(kTuning + i), pgm_read_byte(kTuning + i + 1));

    w(0x0A, 0x04); w(0x84, r(0x84) & ~0x10); w(0x0B, 0x01);  // interrupt on new sample, active low

    Sequence s;
    readSequence(s);
    uint32_t budget = overheadUs(s) + ((s.cfg & 0x80) ? s.finalUs + 550 : 0);
    w(0x01, 0xE8);                                    // MSRC and TCC off
    readSequence(s);                                  // recalculate the final range timeout for the same budget
    uint32_t used = overheadUs(s) + 550;
    if (used <= budget) {
        uint32_t mclks = usToMclks(budget - used, s.finalVcsel) + ((s.cfg & 0x40) ? s.preMclks : 0);
        w16(0x71, encodeTimeout(mclks));
    }

    w(0x01, 0x01);
    if (!singleRefCalibration(0x40)) return false;    // VHV
    w(0x01, 0x02);
    if (!singleRefCalibration(0x00)) return false;    // phase
    w(0x01, 0xE8);

    w(0x80, 0x01); w(0xFF, 0x01); w(0x00, 0x00); w(0x91, stopVariable); w(0x00, 0x01); w(0xFF, 0x00); w(0x80, 0x00);
    w(0x00, 0x02);                                    // continuous back-to-back ranging
    return true;
}

// D7 (PE6) is also pulsed by legacy go_arm()/go_disarm(), so every transaction starts from a fresh falling edge.
void select() { PORTE |= 1 << 6; delayMicroseconds(2); PORTE &= ~(1 << 6); delayMicroseconds(50); }
void release() { PORTE |= 1 << 6; }
uint8_t xfer(uint8_t v) { SPDR = v; while (!(SPSR & (1 << SPIF))) {} return SPDR; }
void flowWrite(uint8_t reg, uint8_t v) {
    select(); xfer(reg | 0x80); xfer(v); delayMicroseconds(50); release(); delayMicroseconds(200);
}
uint8_t flowRead(uint8_t reg) {
    select(); xfer(reg & 0x7F); delayMicroseconds(50);
    uint8_t v = xfer(0);
    release(); delayMicroseconds(200);
    return v;
}

bool flowBegin() {
    DDRB |= (1 << 0) | (1 << 1) | (1 << 2);           // SS (RX LED, keeps master mode), SCK D15, MOSI D16
    DDRE |= 1 << 6;                                   // CS D7
    SPCR = (1 << SPE) | (1 << MSTR) | (1 << CPOL) | (1 << CPHA) | (1 << SPR0);  // mode 3
    SPSR = 1 << SPI2X;                                // 16 MHz / 8 = 2 MHz
    release(); delay(1); PORTE &= ~(1 << 6); delay(1); release(); delay(1);
    flowWrite(0x3A, 0x5A);                            // power-on reset
    delay(5);
    if (flowRead(0x00) != 0x49 || flowRead(0x5F) != 0xB6) return false;  // product ID and its inverse (datasheet)
    for (uint8_t reg = 0x02; reg <= 0x06; reg++) flowRead(reg);
    delay(1);
    for (uint8_t i = 0; i < sizeof(kFlowInit); i += 2) {
        uint8_t reg = pgm_read_byte(kFlowInit + i);
        if (reg == kDelayMark) delay(100);
        else flowWrite(reg, pgm_read_byte(kFlowInit + i + 1));
    }
    return true;
}

void pollRange() {
    if (!(r(0x13) & 0x07)) return;                    // no new sample yet
    uint8_t b[12];
    i2c_read_reg_to_buf(kTof, 0x14, b, 12);           // RESULT_RANGE_STATUS block
    w(0x0B, 0x01);
    range = rangeFromResult(b);
    samples++;
    state = range ? state | kRangeValid : state & ~kRangeValid;
}

void pollFlow() {
    uint8_t b[12];
    select(); xfer(0x16); delayMicroseconds(50);      // motion burst
    for (uint8_t i = 0; i < 12; i++) b[i] = xfer(0);
    release();
    Flow f = flowFromBurst(b);
    squal = f.squal;
    bursts++;
    if (f.moved) { sumX += f.dx; sumY += f.dy; }
}

}  // namespace

void begin() {
    if (rangeBegin()) state |= kRangeFound;
    if (flowBegin()) state |= kFlowFound;
}

void poll() {
    uint16_t now = millis();
    if (uint16_t(now - lastPollMs) < 10) return;
    lastPollMs = now;
    phase ^= 1;                                       // each sensor every 20 ms, one per gap between loops
    if (phase && (state & kRangeFound)) pollRange();
    else if (!phase && (state & kFlowFound)) pollFlow();
}

uint8_t status() { return state; }
uint16_t heightMm() { return range; }
int16_t flowX() { return sumX; }
int16_t flowY() { return sumY; }
uint8_t quality() { return squal; }
uint8_t rangeSamples() { return samples; }
uint8_t flowSamples() { return bursts; }

}  // namespace Optical
#endif

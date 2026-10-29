#include <Arduino.h>
#include <Wire.h>
#include <cassert>
#include <cstring>
#include <iostream>
#include <limits>

std::vector<PinEvent> pinEvents;
uint32_t testMillis = 0;
FakeSerial Serial, Serial1;
TwoWire Wire;

#include "../BoardDiagnostics/Mpu6050.cpp"
#include "../BoardDiagnostics/BoardDiagnostics.cpp"

static void reset() {
    pinEvents.clear(); testMillis = 0;
    Serial = FakeSerial{}; Serial1 = FakeSerial{}; Wire = TwoWire{};
}

static void sensorBytes(uint8_t address = 0x68) {
    const uint8_t values[] = {0x10,0x00, 0xF0,0x00, 0x7F,0xFF, 0xFE,0xAC,
                              0x00,0x04, 0xFF,0xFB, 0x80,0x00};
    std::memcpy(Wire.registers[address - 0x68].data() + 0x3B, values, sizeof(values));
}

static void decoding() {
    diagnostic::Sample sample{};
    uint8_t bytes[14] = {0x80,0x00, 0x7F,0xFF, 0xFF,0xFF, 0x00,0x01,
                         0x80,0x00, 0x7F,0xFF, 0xFF,0xFB};
    assert(diagnostic::decode(bytes, sizeof(bytes), sample));
    assert(sample.valid && sample.accel[0] == -32768 && sample.accel[1] == 32767);
    assert(sample.accel[2] == -1 && sample.temperature == 1 && sample.gyro[2] == -5);
    int16_t accel[3], gyro[3];
    diagnostic::legacyAxes(sample, accel, gyro);
    assert(accel[0] == 4096 && accel[1] == -4095 && accel[2] == -1);
    assert(gyro[0] == 8191 && gyro[1] == 8192 && gyro[2] == 2);
    for (uint8_t length = 0; length < 14; ++length) {
        assert(!diagnostic::decode(bytes, length, sample));
        assert(!sample.valid && sample.accel[0] == 0 && sample.gyro[2] == 0);
    }
    assert(!diagnostic::decode(nullptr, 14, sample));
    assert(!diagnostic::decode(bytes, 15, sample));
}

static void identityAndConfiguration() {
    reset();
    diagnostic::Mpu6050 imu;
    assert(imu.begin() && imu.ready() && imu.address() == 0x68);
    assert(Wire.clock == 400000 && Wire.timeoutUs == 3000 && Wire.resetOnTimeout);
    assert(Wire.registers[0][0x1A] == 2 && Wire.registers[0][0x1B] == 0x18);
    assert(Wire.registers[0][0x1C] == 0x10 && Wire.registers[0][0x19] == 9);
    sensorBytes(); assert(imu.read());
    assert(imu.sample().accel[0] == 4096 && imu.sample().gyro[2] == -32768);
    assert(imu.sample().temperature == -340 && imu.health().samples == 1);

    reset(); Wire.present[0] = false; Wire.present[1] = true;
    diagnostic::Mpu6050 alternate;
    assert(alternate.begin() && alternate.address() == 0x69); // WHO stays 0x68.
    reset(); Wire.registers[0][0x75] = 0x69;
    diagnostic::Mpu6050 wrong;
    assert(!wrong.begin() && wrong.health().last == diagnostic::Error::WrongIdentity);
    reset(); Wire.present[0] = false;
    diagnostic::Mpu6050 missing;
    assert(!missing.begin() && missing.health().last == diagnostic::Error::NoDevice);
    reset(); Wire.present[1] = true;
    diagnostic::Mpu6050 ambiguous;
    assert(!ambiguous.begin() && ambiguous.health().last == diagnostic::Error::AmbiguousAddress);
    reset(); Wire.wrongConfig = true;
    diagnostic::Mpu6050 config;
    assert(!config.begin() && config.health().last == diagnostic::Error::ConfigMismatch);
    reset(); Wire.nackReg = 0x1B;
    diagnostic::Mpu6050 writeNack;
    assert(!writeNack.begin() && writeNack.health().last == diagnostic::Error::Nack);
}

static void failuresDiscardSamples() {
    for (int failure = 0; failure < 4; ++failure) {
        reset(); sensorBytes(); diagnostic::Mpu6050 imu;
        assert(imu.begin() && imu.read() && imu.sample().valid);
        diagnostic::Error expected = diagnostic::Error::None;
        switch (failure) {
        case 0: Wire.shortReg = 0x3B; expected = diagnostic::Error::ShortRead; break;
        case 1: Wire.timeoutReg = 0x3B; expected = diagnostic::Error::Timeout; break;
        case 2: Wire.nackReg = 0x3A; expected = diagnostic::Error::Nack; break;
        case 3: Wire.fresh = false; expected = diagnostic::Error::NotReady; break;
        }
        assert(!imu.read() && !imu.sample().valid);
        assert(imu.sample().accel[0] == 0 && imu.sample().gyro[2] == 0);
        assert(imu.health().samples == 1 && imu.health().last == expected);
        assert(imu.health().failures == 1 && imu.health().errors[uint8_t(expected)] == 1);
        assert(imu.ready() == (failure == 3));
    }
    reset(); Wire.shortReg = 0x75;
    diagnostic::Mpu6050 truncatedWho;
    assert(!truncatedWho.begin() && truncatedWho.health().last == diagnostic::Error::ShortRead);
    reset(); Wire.timeoutReg = 0x75;
    diagnostic::Mpu6050 timedWho;
    assert(!timedWho.begin() && timedWho.health().last == diagnostic::Error::Timeout);
}

static void assertNoMotorDrive() {
    for (const auto& event : pinEvents) {
        assert(event.pin == 2 || event.pin == 3 || event.pin == 5 ||
               event.pin == 6 || event.pin == 9 || event.pin == 10);
        assert(event.mode ? event.value == OUTPUT : event.value == LOW);
    }
    assert(Serial1.txCalls == 0 && Serial1.output.empty());
}

static void passiveBoard() {
    reset(); sensorBytes(); BoardDiagnostics board; board.begin();
    assert(Serial.baud == 115200 && Serial1.baud == 115200);
    const uint8_t pins[] = {5, 6, 9, 10};
    for (uint8_t i = 0; i < 4; ++i) {
        assert(pinEvents[i].pin == pins[i] && !pinEvents[i].mode && pinEvents[i].value == LOW);
        assert(pinEvents[i + 4].pin == pins[i] && pinEvents[i + 4].mode);
    }
    // Real command bytes, malformed headers, and all byte values remain inert RX data.
    const uint8_t arm[] = {'$', 'M', '<', 0, 151, 151, '$', 'M', '<', 0, 205, 205};
    for (auto byte : arm) Serial1.input.push_back(byte);
    for (int byte = 0; byte < 256; ++byte) Serial1.input.push_back(uint8_t(byte));
    for (int i = 0; i < 80; ++i) { testMillis += 20; board.update(); }
    assert(Serial1.input.empty());
    assert(Serial.output.find("valid=1") != std::string::npos);
    assert(Serial.output.find("precal_A=-512,512,4095") != std::string::npos);
    assert(Serial.output.find("U bytes=268 last16=F0F1F2F3F4F5F6F7F8F9FAFBFCFDFEFF") != std::string::npos);
    assertNoMotorDrive();

    Serial.space = 0; Serial.output.clear();
    for (int i = 0; i < 100; ++i) { testMillis += 20; board.update(); }
    assert(Serial.output.empty()); // No blocking write when USB has no room.
    Serial.connected = false; board.update();
    Serial.connected = true; Serial.space = 7;
    Wire.shortReg = 0x3B;
    for (int i = 0; i < 150; ++i) { testMillis += 20; board.update(); }
    assert(Serial.output.find("valid=0") != std::string::npos);
    assert(Serial.output.find("A=4096") == std::string::npos); // Failed reads cannot replay raw data.
    assertNoMotorDrive();

    reset(); Wire.present[0] = false; BoardDiagnostics absent; absent.begin();
    testMillis = std::numeric_limits<uint32_t>::max() - 300U;
    absent.update();
    for (int i = 0; i < 100; ++i) { testMillis += 20; absent.update(); }
    assert(Serial.output.find("valid=0") != std::string::npos);
    assertNoMotorDrive(); // Missing sensor, retry, millis wrap and USB loss are inert.
}

int main() {
    decoding(); identityAndConfiguration(); failuresDiscardSamples(); passiveBoard();
    std::cout << "PASS diagnostic: decode, axes, WHO 68/69, configuration, NACK, timeout, short read, freshness, passive UART, motor LOW, USB backpressure, millis wrap\n";
}

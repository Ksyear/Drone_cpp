import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("firmware_build", Path(__file__).parents[1] / "build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class ConversionTest(unittest.TestCase):
    def test_guard_and_facade(self):
        source = ('#line 1 "/original/AirCopter.ino"\nvoid evaluateCommand();\n'
                  'evaluateCommand();  // we got a valid packet\nvoid evaluateCommand() {\n'
                  '   case MSP_SET_RAW_RC_SERIAL:\n   case MSP_ARM:\n     go_arm();\n   case MSP_DISARM:\n     go_disarm();\n'
                  'void serialCom() {\n}\n     serialize16(100); //version\n   case MSP_AIR:\n'
                  '       serialRcValue[7] = 2000;\n     }\n}\n')
        result = build.convert(source)
        self.assertIn('evaluateCommand(dataSize[CURRENTPORT])', result)
        self.assertIn('if (!validControlPayload(cmdMSP[CURRENTPORT], payloadSize)) return;', result)
        self.assertIn('if (f.ARMED && writesEeprom(cmdMSP[CURRENTPORT])) return;', result)
        self.assertIn('   case Optical::kMspOptical:\n     headSerialReply(15);', result)
        self.assertIn('#include "OpticalSensors.hpp"', result)
        self.assertIn('serialRcValue[THROTTLE] = altitudeHold.throttle(serialRcValue[THROTTLE]);', result)
        self.assertIn('void holdStep() {', result)
        self.assertIn('static PositionHold positionHold;', result)
        self.assertIn('positionHold.command(auxChannels, serialRcValue[ROLL], serialRcValue[PITCH]);', result)
        self.assertIn('static AltitudeHold altitudeHold;', result)
        self.assertIn('void setup() { FlightController::begin(); }', result)
        self.assertIn('#line 1 "AirCopter.ino"', result)
        self.assertIn('serialArmGuard.rcReceived(millis());', result)
        self.assertIn('if (serialArmGuard.allows(serialRcValue[THROTTLE], millis(), i2c_errors_count)) go_arm();', result)
        self.assertIn('case MSP_DISARM:\n     f.ARMED = 0; flightState &= 0x7f;', result)
        self.assertIn('serialArmGuard.mustCut(millis(), i2c_errors_count)) { f.ARMED = 0;', result)
        self.assertIn('serialize16(SerialArmGuard::kFirmwareId); //version', result)
        self.assertNotIn('go_disarm();', result)
        self.assertIn('static SerialArmGuard serialArmGuard;', result)

    def test_unexpected_source_is_rejected(self):
        with self.assertRaises(ValueError):
            build.convert('void evaluateCommand() {}')

    def test_original_flight_tabs_remain_intact(self):
        tabs = sorted(build.ORIGINAL.glob("*.ino"))
        source = 'void evaluateCommand();\n' + '\n'.join(tab.read_text() for tab in tabs)
        result = build.convert(source)
        for tab in tabs:
            if tab.name != "Serial.ino":
                with self.subTest(tab=tab.name):
                    self.assertIn(tab.read_text(), result)


if __name__ == "__main__":
    unittest.main()

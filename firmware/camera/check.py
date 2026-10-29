#!/usr/bin/env python3
"""Test and compile the AI Thinker camera profile. Never opens or uploads to a port."""
from pathlib import Path
import hashlib
import json
import os
import secrets
import string
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SKETCH = ROOT / "firmware/AirCameraCpp"
LOGS = ROOT / "evidence/2026-10-29/camera-setup"


def run(command, log):
    result = subprocess.run([str(value) for value in command], cwd=ROOT, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    log.write_text("COMMAND: " + repr(command) + "\n" + result.stdout + f"EXIT: {result.returncode}\n")
    print(result.stdout, end="")
    result.check_returncode()


def main():
    LOGS.mkdir(parents=True, exist_ok=True)
    secret = SKETCH / "CameraSecrets.hpp"
    if not secret.exists():
        card = SKETCH / "connection.local.txt"
        if card.exists():
            raise SystemExit("Existing connection card without its secret header; preserve it before generating new credentials")
        password = "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(18))
        with secret.open("x") as output:
            output.write('#pragma once\nstatic constexpr char CameraPassword[] = "' + password + '";\n'
                         'static constexpr char CameraSsid[] = "RB022-CAM";\n')
        secret.chmod(0o600)
        with card.open("x") as output:
            output.write("SSID: RB022-CAM\nPassword: " + password +
                         "\nStream: http://192.168.4.1:81/stream\nNot active until firmware is uploaded and starts successfully.\n")
        card.chmod(0o600)
    with tempfile.TemporaryDirectory(prefix="rb022-camera-test-") as directory:
        executable = Path(directory) / "camera_test"
        run(["clang++", "-std=c++11", "-Wall", "-Wextra", "-Werror",
             "-fsanitize=address,undefined", "-Ifirmware/tests/camera_stub",
             "firmware/tests/camera_stream_test.cpp", "-o", executable], LOGS / "host-compile.log")
        run([executable], LOGS / "host-test.log")
    cli = ROOT / ".tools/arduino/bin/arduino-cli"
    installed = json.loads(subprocess.check_output([str(cli), "--config-file",
        str(ROOT / ".tools/arduino/arduino-cli.yaml"), "core", "list", "--format", "json"], text=True))
    versions = {p["id"]: p.get("installed_version") for p in installed["platforms"]}
    if versions.get("esp32:esp32") != "3.3.10":
        raise SystemExit("This camera profile is pinned to esp32:esp32@3.3.10; review core changes before rebuilding")
    ctags = Path(os.environ.get("RB022_CTAGS_BIN", "/tmp/rb022-ctags/bin"))
    run([cli, "--config-file", ROOT / ".tools/arduino/arduino-cli.yaml", "compile",
         "--fqbn", "esp32:esp32:esp32cam", "--warnings", "all", "--build-property",
         f"runtime.tools.ctags.path={ctags}", "--build-path",
         ROOT / "firmware/build/camera", SKETCH], LOGS / "camera-compile.log")
    artifacts = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in (ROOT / "firmware/build/camera").glob("*.bin")}
    (LOGS / "artifacts.json").write_text(json.dumps({"profile": "AI Thinker ESP32-CAM",
        "core": "esp32:esp32@3.3.10", "physical_board_verified": False,
        "uploaded": False, "files": artifacts}, indent=2))


if __name__ == "__main__":
    main()

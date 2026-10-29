#!/usr/bin/env python3
"""Test and compile BoardDiagnostics only. Never opens a port or uploads."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
LOGS = ROOT / "evidence/2026-10-29/next-stage"


def run(command, log, env=None):
    result = subprocess.run([str(value) for value in command], cwd=ROOT, env=env,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    with log.open("a") as output:
        output.write("COMMAND: " + repr(command) + "\n" + result.stdout + f"EXIT: {result.returncode}\n")
    print(result.stdout, end="")
    result.check_returncode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-only", action="store_true", help="Run host tests without Arduino tools")
    args = parser.parse_args()
    LOGS.mkdir(parents=True, exist_ok=True)
    host_log = LOGS / "board-diagnostics-host-test.log"
    host_log.write_text("")
    compiler = shutil.which("clang++")
    if not compiler:
        raise SystemExit("clang++ is required for the existing native host-test workflow")
    with tempfile.TemporaryDirectory(prefix="rb022-diagnostic-test-") as temporary:
        executable = Path(temporary) / "diagnostic_test"
        run([compiler, "-std=c++11", "-Wall", "-Wextra", "-Werror", "-fsanitize=address,undefined",
             "-fno-omit-frame-pointer", "-Ifirmware/tests/diagnostic_stub",
             "firmware/tests/diagnostic_test.cpp", "-o", executable], host_log)
        run([executable], host_log)
    if args.test_only:
        return

    native = Path(os.environ.get("RB022_AVR_BIN", "/tmp/rb022-native-avr/avr-gcc@8/8.5.0_5/bin"))
    ctags = Path(os.environ.get("RB022_CTAGS_BIN", "/tmp/rb022-ctags/bin"))
    cli = ROOT / ".tools/arduino/bin/arduino-cli"
    for tool in (cli, native / "avr-g++", ctags / "ctags"):
        if not tool.is_file():
            raise SystemExit(f"Existing tool missing: {tool}; preserve tool configuration and restore it first")
    command = [cli, "--config-file", ROOT / ".tools/arduino/arduino-cli.yaml", "compile",
               "--fqbn", "arduino:avr:leonardo", "--warnings", "all", "--build-property",
               f"compiler.path={native}/", "--build-property", f"runtime.tools.ctags.path={ctags}"]
    for kind in ("cpp", "c", "S", "c.elf"):
        command += ["--build-property", f"compiler.{kind}.extra_flags=-B{native}/"]
    command += ["--build-path", ROOT / "firmware/build/board-diagnostics", ROOT / "firmware/BoardDiagnostics"]
    env = os.environ.copy()
    env["PATH"] = str(native) + os.pathsep + env["PATH"]
    build_log = LOGS / "board-diagnostics-compile.log"
    build_log.write_text("")
    run(command, build_log, env)


if __name__ == "__main__":
    main()

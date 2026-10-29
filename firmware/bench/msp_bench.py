#!/usr/bin/env python3
"""Stage-A bench check over the drone's USB MSP port (stdlib only, no upload, no reset).
Battery must be disconnected and propellers removed: throttle bytes stay 0, so even when the MCU
reports ARMED, MOTOR_STOP keeps outputs at MINCOMMAND. Prints raw bytes and decoded results as JSON."""
import fcntl, json, os, select, struct, sys, termios, time

FIRMWARE_ID = 231


def frame(cmd, payload=b""):
    c = len(payload) ^ cmd
    for b in payload:
        c ^= b
    return b"$M<" + bytes([len(payload), cmd]) + payload + bytes([c])


class Port:
    def __init__(self, path):
        self.fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        a = termios.tcgetattr(self.fd)
        a[0] = a[1] = a[3] = 0
        a[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
        a[4] = a[5] = termios.B115200  # never 1200: that would reset into the bootloader
        termios.tcsetattr(self.fd, termios.TCSANOW, a)
        # the 32U4 CDC replies only reach the host with DTR asserted
        fcntl.ioctl(self.fd, termios.TIOCMBIS, struct.pack("I", termios.TIOCM_DTR | termios.TIOCM_RTS))
        self.buf = b""
        self.replies(1.0)  # drain replies queued before this session
        self.buf = b""

    def send(self, data):
        os.write(self.fd, data)

    def replies(self, timeout, want=None):
        end, out = time.time() + timeout, []
        while time.time() < end and not (want is not None and any(c == want for c, _, _ in out)):
            r, _, _ = select.select([self.fd], [], [], 0.01)
            if r:
                try:
                    self.buf += os.read(self.fd, 512)
                except BlockingIOError:
                    pass
            while True:
                i = self.buf.find(b"$M")
                if i < 0 or len(self.buf) < i + 5:
                    break
                size = self.buf[i + 3]
                if len(self.buf) < i + 6 + size:
                    break
                kind, cmd, payload, chk = self.buf[i + 2], self.buf[i + 4], self.buf[i + 5:i + 5 + size], self.buf[i + 5 + size]
                c = size ^ cmd
                for b in payload:
                    c ^= b
                self.buf = self.buf[i + 6 + size:]
                if c == chk and kind in (ord(">"), ord("!")):
                    out.append((cmd, payload, kind == ord("!")))
        return out

    def ask(self, cmd, timeout=0.3):
        # USB CDC replies leave the MCU only when the next request arrives: push with MSP_IDENT (read-only)
        self.send(frame(cmd)); time.sleep(0.02); self.send(frame(100))
        for c, p, err in self.replies(timeout, want=cmd):
            if c == cmd:
                return p, err
        return None, None


def s16(p, i):
    v = p[i] | p[i + 1] << 8
    return v - 65536 if v & 0x8000 else v


def air(port):
    p, _ = port.ask(199)
    if not p or len(p) != 14:
        return None
    return {"armed": bool(p[0] & 0x80), "flightState": p[0], "firmwareId": p[1] | p[2] << 8,
            "rollDeci": s16(p, 5), "pitchDeci": s16(p, 7), "altHold": s16(p, 9), "vbatRaw": p[11], "raw": p.hex(" ")}


def rc(roll=125, pitch=125, yaw=125, throttle=0, aux=5):
    return frame(150, bytes([roll, pitch, yaw, throttle, aux]))


def stream(port, seconds, **kw):
    end = time.time() + seconds
    while time.time() < end:
        port.send(rc(**kw))
        port.replies(0.05)


def main():
    port = Port(sys.argv[1])
    log = {"note": "USB MSP bench, battery disconnected, throttle byte 0 except the rejected-ARM probe"}
    air(port)  # first reply may be one queued before this session
    log["air_boot"] = air(port)
    for cmd, name in ((111, "rc_tuning"), (113, "box"), (101, "status")):
        p, err = port.ask(cmd)
        log[name] = {"raw": p.hex(" ") if p is not None else None, "error": err}
    st = log["status"]["raw"]
    log["i2c_errors"] = None if st is None else s16(bytes.fromhex(st), 2)
    # ARM without any RC frame since boot must be refused
    port.send(frame(151)); port.replies(0.3)
    log["arm_without_rc"] = air(port)
    # fresh RC but throttle above MINCHECK (byte 30 -> rcData 1120): refused
    stream(port, 0.6, throttle=30)
    port.send(frame(151)); port.replies(0.1)
    port.send(rc(throttle=30)); port.replies(0.1)
    log["arm_throttle_high"] = air(port)
    # fresh RC at minimum throttle: allowed if the IMU is calibrated, level and error-free
    stream(port, 0.6)
    port.send(frame(151)); port.replies(0.1)
    port.send(rc()); port.replies(0.05)
    log["arm_ok"] = air(port)
    # axis mapping as received by the MCU (MSP 105 rcData) while still at throttle 0
    stream(port, 0.3, roll=150, pitch=100, yaw=140)
    port.send(rc(roll=150, pitch=100, yaw=140)); p, _ = port.ask(105)
    log["rc_received"] = None if p is None else [p[i] | p[i + 1] << 8 for i in range(0, len(p), 2)]
    # link watchdog: stop RC and time the armed bit clearing
    if log["arm_ok"] and log["arm_ok"]["armed"]:
        port.send(rc()); last = time.time()
        while time.time() - last < 2.0:
            asked = time.time()
            a = air(port)
            if a and not a["armed"]:
                # disarmed state was sampled between the previous request and this one
                log["watchdog_disarm_ms"] = {"between": [round((prev - last) * 1000), round((asked - last) * 1000)]}
                break
            prev = asked
        # re-ARM without fresh RC is refused
        time.sleep(0.4); port.send(frame(151)); port.replies(0.2)
        log["rearm_stale_rc"] = air(port)
        # DISARM 152 response time
        stream(port, 0.6); port.send(frame(151)); port.replies(0.1)
        stream(port, 0.3)
        armed = air(port)
        port.send(rc()); port.send(frame(152)); t = time.time()
        a = air(port)  # first request after 152: state is sampled right after DISARM is evaluated
        log["disarm_cmd"] = {"armed_before": armed and armed["armed"], "after": a, "ms": round((time.time() - t) * 1000)}
    log["air_end"] = air(port)
    print(json.dumps(log, indent=1))


if __name__ == "__main__":
    main()

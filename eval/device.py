"""Drives Habits on the LightPhone3 emulator over adb.

Everything the scenarios need: putting the device clock on a chosen date, writing a
HabitState straight into the tool's DataStore, launching it, reading the screen through
`uiautomator dump`, tapping, typing and taking screenshots. Needs a userdebug image, for
`adb root`. Standard library only.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path

PACKAGE = "com.christianlacerda.habits"
ACTIVITY = "com.thelightphone.sdk.LightActivity"
STORE = f"/data/data/{PACKAGE}/files/datastore/DEFAULT_DATASTORE.preferences_pb"
STATE_KEY = "habit_state_json"


def _adb_path() -> str:
    found = shutil.which("adb")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Developer/Environment/android-sdk")
    return os.path.join(sdk, "platform-tools", "adb")


ADB = _adb_path()


class DeviceError(RuntimeError):
    pass


# --- DataStore's preferences file --------------------------------------------------------------
#
# A protobuf PreferenceMap: repeated field 1 of {key: string = 1, value: Value = 2}, where
# Value is a oneof and a string sits in field 5. Only the string case is decoded; every other
# entry is carried through as raw bytes so seeding never loses someone else's key.


def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _read_varint(buf: bytes, i: int) -> tuple[int, int]:
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def _fields(buf: bytes):
    i = 0
    while i < len(buf):
        tag, i = _read_varint(buf, i)
        if tag & 7 != 2:
            raise DeviceError(f"unexpected wire type {tag & 7} in preferences file")
        n, i = _read_varint(buf, i)
        yield tag >> 3, buf[i : i + n]
        i += n


def _ld(field: int, payload: bytes) -> bytes:
    return _varint(field << 3 | 2) + _varint(len(payload)) + payload


def decode_preferences(buf: bytes) -> dict[str, bytes]:
    """Key -> the raw Value message."""
    entries = {}
    for _, entry in _fields(buf):
        parts = dict(_fields(entry))
        entries[parts[1].decode()] = parts.get(2, b"")
    return entries


def encode_preferences(entries: dict[str, bytes]) -> bytes:
    return b"".join(_ld(1, _ld(1, k.encode()) + _ld(2, v)) for k, v in entries.items())


def string_value(s: str) -> bytes:
    return _ld(5, s.encode())


def read_string_value(value: bytes) -> str | None:
    fields = dict(_fields(value))
    return fields[5].decode() if 5 in fields else None


# --- the screen ----------------------------------------------------------------------------------


@dataclass
class Node:
    text: str
    desc: str
    clickable: bool
    bounds: tuple[int, int, int, int]

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    @property
    def label(self) -> str:
        return self.text or self.desc


class Screen:
    """One `uiautomator dump`, searchable by visible text or content description."""

    def __init__(self, xml: str):
        self.xml = xml
        self.nodes = []
        for n in ET.fromstring(xml).iter("node"):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
            self.nodes.append(
                Node(n.get("text", ""), n.get("content-desc", ""), n.get("clickable") == "true", (x1, y1, x2, y2))
            )

    def labels(self) -> list[str]:
        return [n.label for n in self.nodes if n.label]

    def find(self, label: str | None = None, *, startswith: str | None = None, contains: str | None = None):
        for n in self.nodes:
            if label is not None and label in (n.text, n.desc):
                return n
            if startswith is not None and (n.text.startswith(startswith) or n.desc.startswith(startswith)):
                return n
            if contains is not None and (contains in n.text or contains in n.desc):
                return n
        return None

    def has(self, label: str | None = None, **kw) -> bool:
        return self.find(label, **kw) is not None

    def cell(self, habit: str, day: date) -> str | None:
        """State of a day cell: 'done', 'not done', 'future', 'before habit', or None if absent."""
        n = self.find(startswith=f"{habit}, {day.isoformat()}, ")
        return n.desc.rsplit(", ", 1)[1] if n else None

    def bar(self, habit: str, month: str) -> str | None:
        """A report column's description after the month, e.g. '50%, closed' or 'not tracked'."""
        n = self.find(startswith=f"{habit}, {month}, ")
        return n.desc.split(", ", 2)[2] if n else None


# --- the device ----------------------------------------------------------------------------------


class Device:
    def __init__(self, serial: str | None = None):
        self.serial = serial

    def adb(self, *args: str, check: bool = True, binary: bool = False, timeout: float = 60):
        cmd = [ADB] + (["-s", self.serial] if self.serial else []) + list(args)
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
        if check and r.returncode != 0:
            raise DeviceError(f"{' '.join(args)}: {r.stderr.decode(errors='replace').strip()}")
        return r.stdout if binary else r.stdout.decode(errors="replace").strip()

    def shell(self, cmd: str, **kw) -> str:
        return self.adb("shell", cmd, **kw)

    # preflight

    def preflight(self) -> None:
        devices = self.adb("devices")
        if not re.search(r"^emulator-\d+\s+device$", devices, re.M):
            raise DeviceError("no emulator is running; start one with ./scripts/run-emulator.sh")
        if self.shell("getprop sys.boot_completed") != "1":
            raise DeviceError("the emulator has not finished booting")
        if f"package:{PACKAGE}" not in self.shell(f"pm list packages {PACKAGE}"):
            raise DeviceError(f"{PACKAGE} is not installed; run ./scripts/run-emulator.sh")
        if self.shell("id -u") != "0":
            self.adb("root")
            time.sleep(2)
            self.adb("wait-for-device")
            if self.shell("id -u") != "0":
                raise DeviceError("adb root is unavailable; the eval needs a userdebug emulator image")

    # state

    def stop(self) -> None:
        self.shell(f"am force-stop {PACKAGE}")

    def read_store(self) -> bytes | None:
        if self.shell(f"[ -f {STORE} ] && echo yes", check=False) != "yes":
            return None
        return self.adb("exec-out", f"cat {STORE}", binary=True)

    def write_store(self, data: bytes | None) -> None:
        """Replaces the DataStore file with the app stopped. None deletes it."""
        self.stop()
        if data is None:
            self.shell(f"rm -f {STORE}")
            return
        owner = self.shell(f"stat -c %u:%g /data/data/{PACKAGE}")
        tmp = Path(os.environ.get("TMPDIR", "/tmp")) / f"habits-eval-{os.getpid()}.pb"
        tmp.write_bytes(data)
        try:
            self.adb("push", str(tmp), "/data/local/tmp/habits-eval.pb")
        finally:
            tmp.unlink(missing_ok=True)
        dirname = os.path.dirname(STORE)
        self.shell(
            f"mkdir -p {dirname} && chown {owner} /data/data/{PACKAGE}/files {dirname} && "
            f"mv /data/local/tmp/habits-eval.pb {STORE} && chown {owner} {STORE} && chmod 600 {STORE} && "
            f"restorecon -R /data/data/{PACKAGE}/files"
        )

    def seed(self, state: dict | None) -> None:
        """Writes a HabitState (as the app's JSON) over whatever the tool had stored."""
        if state is None:
            self.write_store(None)
            return
        current = self.read_store()
        entries = decode_preferences(current) if current else {}
        entries[STATE_KEY] = string_value(json.dumps(state, separators=(",", ":")))
        self.write_store(encode_preferences(entries))

    def stored_state(self) -> dict | None:
        data = self.read_store()
        if not data:
            return None
        raw = decode_preferences(data).get(STATE_KEY)
        return json.loads(read_string_value(raw)) if raw else None

    # clock

    def auto_time(self) -> str:
        return self.shell("settings get global auto_time")

    def set_clock(self, when: datetime) -> None:
        self.shell("settings put global auto_time 0")
        self.shell(f"date {when.strftime('%m%d%H%M%Y.%S')}")

    def restore_clock(self, auto_time: str) -> None:
        self.set_clock(datetime.now())
        self.shell(f"settings put global auto_time {auto_time}")

    # interaction

    def launch(self) -> Screen:
        self.stop()
        self.shell(f"am start -W -n {PACKAGE}/{ACTIVITY}")
        return self.wait_for(lambda s: s.has("Settings") or s.has("DONE"), what="the home screen")

    def screen(self) -> Screen:
        self.shell("uiautomator dump /sdcard/habits-eval.xml")
        return Screen(self.shell("cat /sdcard/habits-eval.xml"))

    def wait_for(self, predicate, timeout: float = 8, what: str = "the screen to settle") -> Screen:
        deadline = time.monotonic() + timeout
        while True:
            s = self.screen()
            if predicate(s):
                return s
            if time.monotonic() > deadline:
                raise DeviceError(f"timed out waiting for {what}; on screen: {s.labels()}")
            time.sleep(0.3)

    def tap_xy(self, x: int, y: int) -> None:
        self.shell(f"input tap {x} {y}")
        time.sleep(0.4)

    def tap(self, label: str | None = None, **kw) -> Screen:
        """Taps the node with this text or description, then returns the screen after it."""
        s = self.screen()
        n = s.find(label, **kw)
        if n is None:
            raise DeviceError(f"nothing labelled {label or kw} to tap; on screen: {s.labels()}")
        self.tap_xy(*n.center)
        return self.screen()

    def tap_cell(self, habit: str, day: date) -> Screen:
        return self.tap(startswith=f"{habit}, {day.isoformat()}, ")

    def type_text(self, text: str) -> None:
        # `input text` takes %s for a space and needs shell metacharacters escaped.
        escaped = re.sub(r"([\\'\"`$&|;<>()!*?#~])", r"\\\1", text).replace(" ", "%s")
        self.shell(f"input text {escaped}")
        time.sleep(0.3)

    def clear_text(self, length: int = 40) -> None:
        self.shell("input keyevent KEYCODE_MOVE_END")
        self.shell("input keyevent " + " ".join(["KEYCODE_DEL"] * length))

    def key(self, code: str) -> None:
        self.shell(f"input keyevent {code}")
        time.sleep(0.4)

    def resume(self) -> Screen:
        """Brings the tool back to the front without killing it, as reopening it would."""
        self.shell(f"am start -W -n {PACKAGE}/{ACTIVITY}")
        return self.wait_for(lambda s: s.has("Settings") or s.has("DONE"), what="the tool")

    def screen_off_on(self) -> Screen:
        """Display off, on, past LightOS's lock screen (its 'circle' button), back to the tool.
        Unlocking lands on the LightOS home, so the tool is then reopened, not relaunched."""
        self.key("KEYCODE_SLEEP")
        time.sleep(1.5)
        self.key("KEYCODE_WAKEUP")
        s = self.wait_for(lambda s: s.has("circle") or s.has("Settings"), what="the lock screen")
        if s.has("circle"):
            self.tap("circle")
            time.sleep(1)
        return self.resume()

    def screenshot(self, path: Path) -> Path:
        path.write_bytes(self.adb("exec-out", "screencap -p", binary=True))
        return path

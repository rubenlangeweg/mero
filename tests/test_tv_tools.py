#!/usr/bin/env python3
"""Offline checks for explicit TV selection and device-scoped Home restoration."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import tv
import home


class FakeDevice:
    def __init__(self):
        self.serial = "TEST-SERIAL-A"
        self.current_home = "com.vendor.launcher/.Home"
        self.services = "com.reader/.Reader:studio.invrse.mero/.UnrelatedService"
        self.commands = []

    def shell(self, command):
        self.commands.append(command)
        parts = shlex.split(command)
        if command == "getprop ro.serialno":
            return self.serial
        if command == "settings get secure enabled_accessibility_services":
            return self.services
        if parts[:4] == ["settings", "put", "secure", "enabled_accessibility_services"]:
            self.services = parts[4]
            return ""
        if parts[:4] == ["settings", "delete", "secure", "enabled_accessibility_services"]:
            self.services = ""
            return ""
        if command.startswith("cmd package resolve-activity"):
            return self.current_home
        if parts[:3] == ["cmd", "package", "set-home-activity"]:
            self.current_home = parts[3]
            return "Success"
        if command.startswith("pm path "):
            return "package:/system/app/example.apk"
        return ""

    def close(self):
        pass

    def mutations(self):
        return [x for x in self.commands if x.startswith(("settings put", "settings delete", "cmd package set-home", "am start", "input keyevent"))]


class TvToolsTest(unittest.TestCase):
    def test_help_needs_no_adb_dependency(self):
        for name in ["tv.py", "home.py", "smoke.py"]:
            result = subprocess.run([sys.executable, "-S", "-B", str(ROOT / "tools" / name), "--help"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("--host", result.stdout)
            self.assertIn("--port", result.stdout)

    def test_missing_host_and_invalid_port_fail_before_connect(self):
        env = dict(os.environ)
        for name in ["TV_HOST", "TV_PORT", "TV_ADB_KEY"]:
            env.pop(name, None)
        for args in [["shell", "true"], ["--host", "tv.local", "--port", "0", "shell", "true"], ["--host", "http://tv.local", "shell", "true"]]:
            result = subprocess.run([sys.executable, "-S", "-B", str(ROOT / "tools/tv.py")] + args, env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertNotIn("Install the TV tools", result.stderr)

    def test_identity_scopes_host_port_and_device(self):
        root = Path("example")
        reference = home.state_path(root, "tv.local", 5555, "identity-a")
        self.assertNotEqual(reference, home.state_path(root, "other.local", 5555, "identity-a"))
        self.assertNotEqual(reference, home.state_path(root, "tv.local", 5556, "identity-a"))
        self.assertNotEqual(reference, home.state_path(root, "tv.local", 5555, "identity-b"))
        self.assertEqual(reference, home.state_path(root, "TV.LOCAL", 5555, "identity-a"))

    def test_helper_recognizes_normalized_component(self):
        self.assertTrue(home.helper_enabled(home.SERVICE))
        self.assertTrue(home.helper_enabled(home.PACKAGE + "/" + home.PACKAGE + ".HomeRedirectService"))
        self.assertFalse(home.helper_enabled(home.PACKAGE + "/.UnrelatedService"))

    def test_component_rejects_shell_input(self):
        for value in ["com.vendor/.Home; reboot", "com.vendor/.Home\n", "../x", None]:
            with self.assertRaises(ValueError):
                home.component(value)

    def run_home(self, root, device, *arguments):
        with mock.patch.object(home, "ROOT", root), mock.patch.object(home, "LEGACY_STATE", root / ".local/mero-home-original.json"), mock.patch.object(home, "connect", return_value=device), contextlib.redirect_stdout(io.StringIO()):
            home.main(["--host", "tv.local"] + list(arguments))

    def test_enable_restore_preserves_current_other_services(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            device = FakeDevice()
            self.run_home(root, device, "enable")
            files = list((root / ".local/mero-home-state").glob("*.json"))
            self.assertEqual(len(files), 1)
            self.assertEqual(files[0].stat().st_mode & 0o777, 0o600)
            self.assertNotIn(device.serial, files[0].read_text())
            device.services += ":com.new/.Reader"
            self.run_home(root, device, "restore")
            self.assertEqual(device.current_home, "com.vendor.launcher/.Home")
            self.assertEqual(device.services, "com.reader/.Reader:studio.invrse.mero/.UnrelatedService:com.new/.Reader")

    def test_copied_state_from_other_device_cannot_mutate_tv(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            device = FakeDevice()
            identity = hashlib.sha256(device.serial.encode()).hexdigest()
            path = home.state_path(root, "tv.local", 5555, identity)
            home.save_state(path, {"host": "tv.local", "port": 5555, "deviceHash": "different-device", "home": "com.vendor.launcher/.Home"})
            with self.assertRaises(SystemExit):
                self.run_home(root, device, "restore")
            self.assertEqual(device.mutations(), [])

    def test_legacy_backup_needs_explicit_selection_and_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            device = FakeDevice()
            legacy = root / ".local/mero-home-original.json"
            legacy.parent.mkdir()
            legacy.write_text(json.dumps({"home": "com.vendor.launcher/.Home", "accessibility_services": "old/.DoNotRestore"}))
            original = legacy.read_bytes()
            with self.assertRaises(SystemExit):
                self.run_home(root, device, "restore")
            self.assertEqual(device.mutations(), [])
            self.run_home(root, device, "--import-legacy-state", "restore")
            self.assertEqual(legacy.read_bytes(), original)
            self.assertNotIn("old/.DoNotRestore", device.services)
            self.assertTrue(list((root / ".local/mero-home-state").glob("*.json")))

    def test_unidentifiable_tv_is_not_changed(self):
        with self.assertRaises(ValueError):
            home.device_identity(lambda _: "Error: cannot read value")


if __name__ == "__main__":
    unittest.main()

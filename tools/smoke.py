#!/usr/bin/env python3
"""Exercise Mero on an explicitly selected TV. This opens apps and changes the foreground screen."""
import argparse
import json
from pathlib import Path
import re
import shlex
import time

from tv import add_connection_arguments, validate_connection_arguments, connect

ROOT = Path(__file__).resolve().parent.parent
PUBLIC_PACKAGE = "studio.invrse.mero"
CLASS = "studio.invrse.mero.HomeActivity"


def package_name(value):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", value):
        raise argparse.ArgumentTypeError("Use an Android package name")
    return value


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    add_connection_arguments(parser)
    parser.add_argument("--package", type=package_name, default=PUBLIC_PACKAGE,
                        help="Mero application package; Preview and Lab use their own suffixes")
    parser.add_argument("--shortcut-packages", nargs=2, type=package_name, metavar=("FIRST", "SECOND"),
                        help="also launch the two configured shortcuts and verify these package names")
    parser.add_argument("--test-home-button", action="store_true",
                        help="also verify the physical Home-key route; only for the public Home build")
    return parser


def main(argv=None):
    parser = build_parser()
    args = validate_connection_arguments(parser, parser.parse_args(argv))
    if args.test_home_button and args.package != PUBLIC_PACKAGE:
        parser.error("Lab and Preview do not register as Home; omit --test-home-button")
    device = connect(args)
    results = {}

    def key(name):
        device.shell("input keyevent KEYCODE_" + name)
        time.sleep(0.35)

    def wait_for(package):
        for _ in range(32):
            active = device.shell("dumpsys activity activities | grep mResumedActivity")
            if package + "/" in active:
                return
            time.sleep(0.25)
        raise AssertionError("The expected app did not become active: " + package)

    def home():
        device.shell("am start -W -n " + shlex.quote(args.package + "/" + CLASS))
        wait_for(args.package)
        time.sleep(0.5)

    def focused_window():
        focus = device.shell("dumpsys window | grep mCurrentFocus")
        match = re.search(r"mCurrentFocus=Window\{([^ ]+)", focus)
        if not match:
            raise AssertionError("Cannot read the focused TV window")
        return match.group(1)

    def wait_for_window(previous, changed):
        for _ in range(24):
            if (focused_window() != previous) == changed:
                return
            time.sleep(0.25)
        raise AssertionError("The TV dialog did not open or close as expected")

    def dock_dialog(index, name):
        home()
        before = focused_window()
        for _ in range(index):
            key("DPAD_RIGHT")
        key("DPAD_CENTER")
        wait_for_window(before, True)
        results[name + " opens with D-pad"] = True
        key("BACK")
        wait_for_window(before, False)
        wait_for(args.package)
        results["Back closes " + name] = True

    try:
        home()
        if args.shortcut_packages:
            for index, package in enumerate(args.shortcut_packages):
                home()
                for _ in range(index):
                    key("DPAD_RIGHT")
                key("DPAD_CENTER")
                wait_for(package)
                results["Configured shortcut " + str(index + 1) + " opens its app"] = True
        dock_dialog(2, "All apps")
        dock_dialog(3, "Artwork")
        dock_dialog(4, "Settings")
        home()
        key("BACK")
        wait_for(args.package)
        results["Back from Mero stays in Mero"] = True
        if args.test_home_button:
            key("HOME")
            wait_for(PUBLIC_PACKAGE)
            results["Home key returns to Mero"] = True
        disabled = device.shell("pm list packages -d")
        for package in ["com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith"]:
            assert "package:" + package + "\n" not in disabled
        results["Google launcher and recovery remain enabled"] = True
    finally:
        device.close()
        destination = ROOT / "output/device" / ("smoke-" + args.package + ".json")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(results, indent=2) + "\n")
        print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()

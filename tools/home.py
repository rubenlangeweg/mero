#!/usr/bin/env python3
"""Enable the optional Sony Home helper, or restore this TV's saved Home activity."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import tempfile

from tv import add_connection_arguments, validate_connection_arguments, connect

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "studio.invrse.mero"
SERVICE = PACKAGE + "/.HomeRedirectService"
ACTIVITY = PACKAGE + "/.HomeActivity"
LEGACY_STATE = ROOT / ".local/mero-home-original.json"
COMPONENT = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*/\.?[A-Za-z_$][A-Za-z0-9_.$]*")


def component(value):
    if not isinstance(value, str) or not COMPONENT.fullmatch(value):
        raise ValueError("The saved Home component is invalid. No settings changed.")
    return value


def canonical(value):
    package, name = component(value).split("/", 1)
    return package + "/" + (package + name if name.startswith(".") else name)


def helper_enabled(current):
    own = {SERVICE, PACKAGE + "/" + PACKAGE + ".HomeRedirectService"}
    return any(entry in own for entry in current.split(":"))


def services_without_helper(current):
    services = [] if current in ["null", ""] else current.split(":")
    own = {SERVICE, PACKAGE + "/" + PACKAGE + ".HomeRedirectService"}
    return [entry for entry in services if entry and entry not in own]


def device_identity(shell):
    for query in ["getprop ro.serialno", "getprop ro.boot.serialno", "settings get secure android_id"]:
        value = shell(query).strip()
        if re.fullmatch(r"[A-Za-z0-9._:-]{4,128}", value) and value.lower() not in {"null", "unknown", "0000000000000000"}:
            return hashlib.sha256(value.encode()).hexdigest()
    raise ValueError("Cannot identify this TV safely. No settings changed.")


def state_path(root, host, port, identity):
    target = json.dumps([host.lower(), int(port), identity], separators=(",", ":"))
    return root / ".local/mero-home-state" / (hashlib.sha256(target.encode()).hexdigest() + ".json")


def save_state(path, state):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".home-", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(state, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        path.chmod(0o600)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load_state(path, host, port, identity):
    state = json.loads(path.read_text())
    if not isinstance(state, dict):
        raise ValueError("The Home backup format is invalid. No settings changed.")
    if state.get("host") != host or state.get("port") != port or state.get("deviceHash") != identity:
        raise ValueError("This Home backup belongs to a different TV or address. No settings changed.")
    component(state.get("home"))
    return state


def home_component(shell):
    output = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    for line in output.splitlines():
        if COMPONENT.fullmatch(line.strip()):
            return line.strip()
    raise ValueError("Cannot determine the current Home activity. No settings changed.")


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    add_connection_arguments(parser)
    parser.add_argument("--import-legacy-state", action="store_true",
                        help="confirm that .local/mero-home-original.json belongs to this connected TV, and copy it to scoped storage")
    parser.add_argument("action", choices=["enable", "restore"])
    return parser


def main(argv=None):
    parser = build_parser()
    args = validate_connection_arguments(parser, parser.parse_args(argv))
    device = connect(args)
    try:
        shell = lambda command: device.shell(command).strip()
        identity = device_identity(shell)
        path = state_path(ROOT, args.host, args.port, identity)
        current = shell("settings get secure enabled_accessibility_services")
        others = services_without_helper(current)
        if path.exists():
            original = load_state(path, args.host, args.port, identity)
        elif args.import_legacy_state:
            if not LEGACY_STATE.is_file():
                raise ValueError("No legacy Home backup exists. No settings changed.")
            legacy = json.loads(LEGACY_STATE.read_text())
            if not isinstance(legacy, dict):
                raise ValueError("The legacy Home backup format is invalid. No settings changed.")
            original = {"host": args.host, "port": args.port, "deviceHash": identity,
                        "home": component(legacy.get("home")), "importedLegacy": True}
            if original["home"].split("/", 1)[0] == PACKAGE:
                raise ValueError("The legacy backup does not contain an original system Home. No settings changed.")
            save_state(path, original)
            print("Copied the explicitly selected legacy backup to this TV's scoped storage.")
        elif args.action == "restore":
            suffix = " A legacy backup exists; use --import-legacy-state only if it belongs to this TV." if LEGACY_STATE.is_file() else ""
            raise ValueError("No Home backup exists for this TV and address. No settings changed." + suffix)
        else:
            original_home = home_component(shell)
            if original_home.split("/", 1)[0] == PACKAGE:
                raise ValueError("Mero is already Home, but this TV has no scoped backup. Use --import-legacy-state only for its matching legacy backup.")
            original = {"host": args.host, "port": args.port, "deviceHash": identity,
                        "home": original_home, "accessibilityServicesAtBackup": current}
            save_state(path, original)

        if args.action == "enable":
            # Only the public Home build registers this service. Lab/Preview do not.
            if not shell("pm path " + shlex.quote(PACKAGE)).startswith("package:"):
                raise ValueError("Install the Mero build first. No settings changed.")
            updated = ":".join(others + [SERVICE])
            shell("settings put secure enabled_accessibility_services " + shlex.quote(updated))
            shell("settings put secure accessibility_enabled 1")
            if not helper_enabled(shell("settings get secure enabled_accessibility_services")):
                raise ValueError("The Home helper could not be enabled. The original Home backup is saved.")
            print(shell("cmd package set-home-activity " + shlex.quote(ACTIVITY)))
            if canonical(home_component(shell)) != canonical(ACTIVITY):
                raise ValueError("The TV did not select Mero as Home. Run restore to remove the helper.")
            print(shell("am start -W -n " + shlex.quote(ACTIVITY)))
            print("Mero helper enabled. Other accessibility services remain enabled.")
        else:
            original_package = original["home"].split("/", 1)[0]
            if not shell("pm path " + shlex.quote(original_package)).startswith("package:"):
                raise ValueError("The saved Home app is no longer installed. No settings changed.")
            print(shell("cmd package set-home-activity " + shlex.quote(original["home"])))
            if canonical(home_component(shell)) != canonical(original["home"]):
                raise ValueError("The TV did not restore the saved Home. The helper remains unchanged.")
            if others:
                shell("settings put secure enabled_accessibility_services " + shlex.quote(":".join(others)))
            else:
                shell("settings delete secure enabled_accessibility_services")
                shell("settings put secure accessibility_enabled 0")
            print(shell("input keyevent KEYCODE_HOME"))
            print("This TV's previous Home was restored. Other accessibility services remain enabled.")
    except (ValueError, OSError, json.JSONDecodeError) as error:
        raise SystemExit(str(error))
    finally:
        device.close()


if __name__ == "__main__":
    main()

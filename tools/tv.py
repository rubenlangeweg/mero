#!/usr/bin/env python3
"""Use an explicitly selected Android TV over legacy TCP ADB (adb-shell[rsa]==0.4.4)."""
import argparse
import base64
import datetime
import hashlib
import ipaddress
import os
from pathlib import Path
import re
import shlex
import uuid

ROOT = Path(__file__).resolve().parent.parent


def port_number(value):
    try:
        port = int(value)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError("ADB port must be an integer from 1 to 65535")
    if not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError("ADB port must be from 1 to 65535")
    return port


def add_connection_arguments(parser):
    parser.add_argument("--host", default=os.environ.get("TV_HOST"),
                        help="TV IP address or hostname; alternatively set TV_HOST")
    parser.add_argument("--port", type=port_number, default=os.environ.get("TV_PORT", "5555"),
                        help="TCP ADB port (default: TV_PORT or 5555)")
    parser.add_argument("--key", default=os.environ.get("TV_ADB_KEY", str(Path.home() / ".android/adbkey")),
                        help="existing ADB private key (default: TV_ADB_KEY or ~/.android/adbkey)")


def validate_connection_arguments(parser, args):
    if not args.host or not args.host.strip():
        parser.error("Select a TV with --host TV_IP or set TV_HOST. No default TV is selected.")
    args.host = args.host.strip().lower()
    try:
        ipaddress.ip_address(args.host)
    except ValueError:
        if re.fullmatch(r"[0-9.]+", args.host) or len(args.host) > 253 or not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", args.host):
            parser.error("Use an IP address or hostname, without a URL or port; select the port with --port.")
        if any(not part or len(part) > 63 or part.startswith("-") or part.endswith("-") for part in args.host.split(".")):
            parser.error("Invalid TV hostname.")
    args.key = str(Path(args.key).expanduser())
    return args


def connect(args):
    """Import the optional ADB dependency only after parsing --help and validating the target."""
    if not Path(args.key).is_file():
        raise SystemExit("ADB key not found. Run adb start-server once, or select an existing key with --key.")
    try:
        from adb_shell.adb_device import AdbDeviceTcp
        from adb_shell.auth.sign_pythonrsa import PythonRSASigner
    except ModuleNotFoundError:
        raise SystemExit("Install the TV tools first: python3 -m pip install -r requirements-dev.txt")
    device = AdbDeviceTcp(args.host, args.port, default_transport_timeout_s=20)
    try:
        device.connect(rsa_keys=[PythonRSASigner.FromRSAKeyPath(args.key)], auth_timeout_s=30, read_timeout_s=35)
    except Exception:
        device.close()
        raise
    return device


def remove_remote(device, path):
    try:
        device.shell("rm -f " + shlex.quote(path))
    except Exception:
        pass  # Cleanup must not hide an interrupted transfer's original error.


def push_file(device, source, destination):
    """Chunk and checksum uploads for TVs that stall the usual ADB sync transfer."""
    encoded = destination + ".b64"
    device.shell(": > " + shlex.quote(encoded))
    digest = hashlib.sha256()
    try:
        with open(source, "rb") as stream:
            while chunk := stream.read(32766):
                digest.update(chunk)
                data = base64.b64encode(chunk).decode("ascii")
                device.shell("printf %s '" + data + "' >> " + shlex.quote(encoded))
        device.shell("base64 -d " + shlex.quote(encoded) + " > " + shlex.quote(destination))
        actual = device.shell("sha256sum " + shlex.quote(destination)).split()[0]
        if actual != digest.hexdigest():
            raise RuntimeError("TV upload checksum mismatch")
    finally:
        remove_remote(device, encoded)


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    add_connection_arguments(parser)
    commands = parser.add_subparsers(dest="action", required=True)
    shell = commands.add_parser("shell")
    shell.add_argument("command")
    for name in ["push", "pull"]:
        transfer = commands.add_parser(name)
        transfer.add_argument("source")
        transfer.add_argument("destination")
    screenshot = commands.add_parser("screenshot")
    screenshot.add_argument("destination")
    install = commands.add_parser("install")
    install.add_argument("apk")
    commands.add_parser("backup")
    return parser


def main(argv=None):
    parser = build_parser()
    args = validate_connection_arguments(parser, parser.parse_args(argv))
    if args.action in {"push", "install"}:
        source = Path(args.source if args.action == "push" else args.apk)
        if not source.is_file():
            parser.error("Source file does not exist")
    device = connect(args)
    try:
        if args.action == "shell":
            print(device.shell(args.command), end="")
        elif args.action == "push":
            push_file(device, args.source, args.destination)
        elif args.action == "pull":
            device.pull(args.source, args.destination)
        elif args.action == "screenshot":
            remote = "/data/local/tmp/mero-screen-" + uuid.uuid4().hex + ".png"
            try:
                device.shell("screencap -p " + shlex.quote(remote))
                Path(args.destination).parent.mkdir(parents=True, exist_ok=True)
                device.pull(remote, args.destination)
            finally:
                remove_remote(device, remote)
            print(args.destination)
        elif args.action == "install":
            remote = "/data/local/tmp/mero-update-" + uuid.uuid4().hex + ".apk"
            try:
                push_file(device, args.apk, remote)
                result = device.shell("pm install -r " + shlex.quote(remote), read_timeout_s=60)
                print(result, end="")
                if "Success" not in result:
                    raise SystemExit(1)
            finally:
                remove_remote(device, remote)
        elif args.action == "backup":
            folder = ROOT / "output/device" / datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")
            folder.mkdir(parents=True)
            reads = {
                "packages.txt": "pm list packages -f",
                "disabled-packages.txt": "pm list packages -d",
                "home.txt": "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                "home-candidates.txt": "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
                "build.txt": "getprop ro.build.fingerprint",
            }
            for name, command in reads.items():
                (folder / name).write_text(device.shell(command))
            print(folder)
    finally:
        device.close()


if __name__ == "__main__":
    main()

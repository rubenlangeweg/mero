#!/usr/bin/env python3
"""Check the files Git would publish, without reading ignored device data."""
import argparse
import ipaddress
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ROOT_FILES = {
    '.gitignore', 'README.md', 'LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md',
    'SECURITY.md', 'CONTRIBUTING.md', 'CHANGELOG.md', 'requirements-dev.txt',
    'requirements.txt',
}
DIRECTORIES = ('launcher/app/src/', 'tools/', 'tests/', 'docs/', 'examples/', '.github/')
FORBIDDEN_SUFFIXES = {'.apk', '.apks', '.aab', '.zip', '.jks', '.keystore', '.pem', '.key', '.pyc', '.p12'}
TEXT_SUFFIXES = {'.md', '.txt', '.py', '.sh', '.java', '.json', '.html', '.xml', '.yml', '.yaml', '.toml', '.properties'}
DOCUMENTATION_NETWORKS = tuple(ipaddress.ip_network(n) for n in ('192.0.2.0/24', '198.51.100.0/24', '203.0.113.0/24'))
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
IPV4 = re.compile(r'(?<![\w.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![\w.])')
JWT = re.compile(r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}')
PRIVATE_KEY = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----')
PERSONAL_HOST = re.compile(r'(?i)(?:https?://)?[a-z0-9.-]*ruben\.(?:computer|local)\b|/Users/[^/\s]+/')


def candidate_files(root=ROOT):
    result = subprocess.run(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
        cwd=root, capture_output=True, check=True,
    )
    return sorted(set(Path(name.decode('utf-8')) for name in result.stdout.split(b'\0') if name))


def allowed(path):
    value = path.as_posix()
    return value in ROOT_FILES or any(value.startswith(prefix) for prefix in DIRECTORIES)


def inspect(root=ROOT):
    files = candidate_files(root)
    issues = []
    for path in files:
        full = root / path
        name = path.as_posix()
        if not allowed(path):
            issues.append((name, 'outside the public source allowlist'))
            continue
        if full.is_symlink() or not full.is_file():
            issues.append((name, 'not a regular source file'))
            continue
        if path.suffix.lower() in FORBIDDEN_SUFFIXES or '.DS_Store' in path.parts or '__pycache__' in path.parts:
            issues.append((name, 'private/build/archive file type'))
            continue
        if full.stat().st_size > 12 * 1024 * 1024:
            issues.append((name, 'larger than the curated public-file limit (12 MiB)'))
        if path.suffix.lower() not in TEXT_SUFFIXES and path.name not in ROOT_FILES:
            continue
        try:
            text = full.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            issues.append((name, 'expected UTF-8 text'))
            continue
        if PRIVATE_KEY.search(text):
            issues.append((name, 'private key material'))
        if JWT.search(text):
            issues.append((name, 'possible access token'))
        # The checker contains its own pattern, never any private endpoint value.
        if path.name != 'check-public.py' and PERSONAL_HOST.search(text):
            issues.append((name, 'personal endpoint or machine path'))
        # Peer-address tests deliberately cover RFC1918; docs/examples should use TEST-NET.
        if not name.startswith('tests/') and path.name != 'check-public.py':
            for match in IPV4.finditer(text):
                try:
                    ip = ipaddress.ip_address(match.group())
                except ValueError:
                    continue
                if any(ip in network for network in PRIVATE_NETWORKS) and not any(ip in network for network in DOCUMENTATION_NETWORKS):
                    issues.append((name, 'private LAN address; use an explicit runtime host or TEST-NET example'))
                    break
    for required in ('README.md', 'LICENSE', '.gitignore'):
        if Path(required) not in files:
            issues.append((required, 'missing public repository file'))
    return files, issues


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try:
        files, issues = inspect()
    except (OSError, subprocess.CalledProcessError) as error:
        print('Cannot inspect the Git source set: ' + str(error), file=sys.stderr)
        return 1
    if issues:
        for path, reason in issues:
            print(path + ': ' + reason, file=sys.stderr)
        print('Public-source check failed. No export was created.', file=sys.stderr)
        return 1
    print('Public-source check passed: ' + str(len(files)) + ' candidate files; ignored local/device data excluded.')
    print('Review screenshots and recordings separately for visible personal information.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

#!/usr/bin/env python3
"""Check public export boundaries with real temporary Git working trees."""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


checker = load('public_checker_test', ROOT / 'tools/check-public.py')
exporter = load('public_exporter_test', ROOT / 'tools/export-public.py')


class PublicSourceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        subprocess.run(['git', 'init', '--quiet', str(self.root)], check=True, capture_output=True)
        self.write('README.md', '# Example\n')
        self.write('LICENSE', 'Example test license\n')
        self.write('.gitignore', '/output/\n/.local/\n')

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
        return path

    def test_ignored_configuration_and_artifacts_are_not_read(self):
        self.write('.local/private.key', '-----BEGIN ' + 'PRIVATE KEY-----\n')
        self.write('output/private.apk', 'private artifact')
        files, issues = checker.inspect(self.root)
        self.assertEqual(issues, [])
        self.assertEqual({p.as_posix() for p in files}, {'README.md', 'LICENSE', '.gitignore'})

    def test_unlisted_archive_cannot_enter_public_source(self):
        self.write('old-release.zip', 'archive')
        _, issues = checker.inspect(self.root)
        self.assertTrue(any(path == 'old-release.zip' for path, _ in issues))

    def test_private_endpoint_or_machine_path_is_rejected(self):
        self.write('README.md', 'Server: https://10.77.2.30\nLocal: /Users/' + 'someone/project\n')
        _, issues = checker.inspect(self.root)
        self.assertTrue(any('private LAN' in reason for _, reason in issues))
        self.assertTrue(any('machine path' in reason for _, reason in issues))

    def test_documentation_address_and_generic_peer_tests_are_allowed(self):
        self.write('README.md', 'adb connect 192.0.2.10:5555\n')
        self.write('tests/peer.py', 'peer = "192.168.50.2"\n')
        self.assertEqual(checker.inspect(self.root)[1], [])

    def test_key_or_token_content_is_rejected_without_printing_it(self):
        value = '-----BEGIN ' + 'PRIVATE KEY-----\n' + ''.join(['ey', 'J']) + 'A' * 30 + '.' + 'B' * 30 + '.' + 'C' * 30
        self.write('docs/config.txt', value)
        _, issues = checker.inspect(self.root)
        self.assertTrue(any(reason == 'private key material' for _, reason in issues))
        self.assertTrue(any(reason == 'possible access token' for _, reason in issues))
        self.assertTrue(all(value not in reason for _, reason in issues))

    def test_source_symlink_is_rejected(self):
        target = self.write('.local/private.txt', 'private')
        link = self.root / 'docs/linked.txt'
        link.parent.mkdir()
        link.symlink_to(target)
        _, issues = checker.inspect(self.root)
        self.assertTrue(any(path == 'docs/linked.txt' and reason == 'not a regular source file' for path, reason in issues))

    def test_export_contains_only_checked_files_and_refuses_overwrite(self):
        self.write('.local/private.txt', 'private')
        self.write('output/device/capture.png', 'private')
        self.write('tools/example.py', 'print("example")\n')
        archive = self.root / 'output/public/example.zip'
        with mock.patch.object(exporter, 'ROOT', self.root), mock.patch.object(sys, 'argv', ['export-public.py', '--output', str(archive)]), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(exporter.main(), 0)
            original = archive.read_bytes()
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                exporter.main()
        self.assertEqual(archive.read_bytes(), original)
        with zipfile.ZipFile(archive) as contents:
            self.assertEqual(set(contents.namelist()), {'mero/.gitignore', 'mero/LICENSE', 'mero/README.md', 'mero/tools/example.py'})
        self.assertTrue(archive.with_name(archive.name + '.sha256').is_file())


if __name__ == '__main__':
    unittest.main()

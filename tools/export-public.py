#!/usr/bin/env python3
"""Export the checked public source set, without Git history or ignored data."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('mero_public_check', ROOT / 'tools/check-public.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'output/public/mero-source.zip')
    args = parser.parse_args()
    files, issues = checker.inspect(ROOT)
    if issues:
        for path, reason in issues:
            print(path + ': ' + reason, file=sys.stderr)
        print('Fix the public-source check before exporting.', file=sys.stderr)
        return 1
    destination = args.output.resolve()
    if destination in ((ROOT / name).resolve() for name in files):
        parser.error('The export cannot overwrite a public source file.')
    if destination.exists():
        parser.error('The export already exists. Choose a new --output path or remove the old export explicitly.')
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + '.tmp')
    if temporary.exists():
        parser.error('An unfinished export exists beside the destination. Remove it explicitly before retrying.')
    try:
        with zipfile.ZipFile(temporary, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in files:
                source = ROOT / name
                # Recheck path type at use time; no symlink can enter the source archive.
                if source.is_symlink() or not source.is_file():
                    raise RuntimeError('A source file changed during export: ' + name.as_posix())
                archive.write(source, 'mero/' + name.as_posix())
        os.replace(temporary, destination)
    finally:
        if temporary.exists():
            temporary.unlink()
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    destination.with_name(destination.name + '.sha256').write_text(digest + '  ' + destination.name + '\n')
    print(str(destination))
    print('Exported ' + str(len(files)) + ' checked files. SHA-256: ' + digest)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

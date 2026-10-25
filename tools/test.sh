#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 or newer}"
mkdir -p launcher/build/test-classes
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -d launcher/build/test-classes \
    launcher/app/src/main/java/studio/invrse/mero/ArtworkRotation.java tests/ArtworkRotationTest.java
"$JAVA_HOME/bin/java" -cp launcher/build/test-classes studio.invrse.mero.ArtworkRotationTest
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -d launcher/build/test-classes \
    launcher/app/src/main/java/studio/invrse/mero/ArtworkBackupArchive.java tests/ArtworkBackupArchiveTest.java
"$JAVA_HOME/bin/java" -cp launcher/build/test-classes studio.invrse.mero.ArtworkBackupArchiveTest
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -d launcher/build/test-classes \
    launcher/app/src/main/java/studio/invrse/mero/HomeAssistantPolicy.java tests/HomeAssistantPolicyTest.java
"$JAVA_HOME/bin/java" -cp launcher/build/test-classes studio.invrse.mero.HomeAssistantPolicyTest
python3 -m unittest discover -s tests -p 'test_*.py'
python3 - <<'PY'
import json
from pathlib import Path

resources = Path('launcher/app/src/main/res')
catalog = json.loads((resources / 'raw/artworks.json').read_text())
keys = set()
for artist in catalog['artists']:
    assert artist['name'] and artist['works']
    for work in artist['works']:
        key = artist['id'] + '/' + work['id']
        assert key not in keys, 'Duplicate artwork key: ' + key
        keys.add(key)
        assert list((resources / 'drawable-nodpi').glob(work['image'] + '.*')), 'Missing image: ' + key
assert keys, 'Empty catalog'
print('Artwork catalog: unique keys and all image resources verified')
PY

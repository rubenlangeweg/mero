#!/usr/bin/env bash
# Small Android SDK build. No Gradle or network access is needed after SDK setup.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
Usage: bash tools/build.sh [--variant home|lab|preview] [--unsigned|--release]

home      Full launcher and optional Sony Home-button helper (default).
lab       Separate Mero Lab package, without Home registration or helper.
preview   Separate Mero Preview package, without Home registration or helper.

Default builds use an ignored, local DEVELOPMENT signing key for every variant.
They are not maintainer release builds.

--unsigned  Produce an aligned, unsigned APK without reading or creating keys.
--release   Sign using MERO_KEYSTORE, MERO_KEY_ALIAS, MERO_STORE_PASSWORD_FILE and
            MERO_KEY_PASSWORD_FILE. Password files must each contain one line.

Required: JAVA_HOME (JDK 17+), ANDROID_HOME (or ANDROID_SDK_ROOT), Python 3,
zip, Android SDK platform 35 and build-tools 35.0.0.
EOF
}

variant=home
signing=development
while [[ $# -gt 0 ]]; do
    case "$1" in
        --variant)
            [[ $# -ge 2 ]] || { usage >&2; exit 2; }
            variant="$2"; shift 2 ;;
        --unsigned|--release)
            [[ "$signing" == development ]] || { echo "Choose one signing mode." >&2; exit 2; }
            signing="${1#--}"; shift ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done
case "$variant" in
    home) build="launcher/build"; basename="mero" ;;
    lab) build="launcher/build-experiment"; basename="mero-experiment" ;;
    preview) build="launcher/build-preview"; basename="mero-preview" ;;
    *) echo "Choose home, lab or preview." >&2; exit 2 ;;
esac
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 or newer}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
export JAVA_HOME ANDROID_HOME
bt="$ANDROID_HOME/build-tools/35.0.0"
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
for program in "$JAVA_HOME/bin/javac" "$JAVA_HOME/bin/jar" "$bt/aapt2" "$bt/d8" "$bt/zipalign" "$bt/apksigner"; do
    [[ -x "$program" ]] || { echo "Required SDK/JDK tool is missing: $program" >&2; exit 1; }
done
[[ -f "$android_jar" ]] || { echo "Install platforms;android-35 in the Android SDK." >&2; exit 1; }
command -v python3 >/dev/null || { echo "Install Python 3." >&2; exit 1; }
command -v zip >/dev/null || { echo "Install zip." >&2; exit 1; }

src="launcher/app/src/main"
if [[ "$signing" == unsigned ]]; then
    apk="output/apk/$basename-unsigned.apk"
else
    apk="output/apk/$basename.apk"
fi
mkdir -p "$build/generated" "$build/classes" "$build/dex" output/apk
find "$build/classes" "$build/generated" "$build/dex" -type f -delete
rm -f "$build/resources.zip" "$build/resources.apk" "$build/unsigned.apk" "$apk" "$apk.idsig"

python3 - "$variant" "$build/AndroidManifest.xml" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

variant, destination = sys.argv[1:]
android = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', android[1:-1])
tree = ET.parse('launcher/app/src/main/AndroidManifest.xml')
manifest = tree.getroot()
namespace = manifest.attrib['package']
application = manifest.find('application')
if variant != 'home':
    suffix = 'experiment' if variant == 'lab' else 'preview'
    manifest.set('package', namespace + '.' + suffix)
    manifest.set(android + 'versionName', manifest.get(android + 'versionName') + '-' + suffix)
    application.set(android + 'label', 'Mero Lab' if variant == 'lab' else 'Mero Preview')
    for service in list(application.findall('service')):
        application.remove(service)
    for activity in application.findall('activity'):
        name = activity.get(android + 'name')
        if name.startswith('.'):
            activity.set(android + 'name', namespace + name)
        elif '.' not in name:
            activity.set(android + 'name', namespace + '.' + name)
        for intent in list(activity.findall('intent-filter')):
            if any(category.get(android + 'name') == 'android.intent.category.HOME'
                   for category in intent.findall('category')):
                activity.remove(intent)
tree.write(Path(destination), encoding='utf-8', xml_declaration=True)
PY

"$bt/aapt2" compile --dir "$src/res" -o "$build/resources.zip"
"$bt/aapt2" link -o "$build/resources.apk" --manifest "$build/AndroidManifest.xml" \
    --custom-package studio.invrse.mero -I "$android_jar" \
    --java "$build/generated" "$build/resources.zip"
find "$src/java" "$build/generated" -name '*.java' > "$build/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:deprecation -cp "$android_jar" \
    -d "$build/classes" @"$build/sources.txt"
"$JAVA_HOME/bin/jar" cf "$build/classes.jar" -C "$build/classes" .
"$bt/d8" --lib "$android_jar" --min-api 26 --output "$build/dex" "$build/classes.jar"
cp "$build/resources.apk" "$build/unsigned.apk"
zip -q -j "$build/unsigned.apk" "$build/dex/classes.dex"
"$bt/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"

if [[ "$signing" == unsigned ]]; then
    cp "$build/aligned.apk" "$apk"
    echo "Unsigned APK: $apk"
else
    if [[ "$signing" == release ]]; then
        : "${MERO_KEYSTORE:?Set MERO_KEYSTORE to the release keystore file}"
        : "${MERO_KEY_ALIAS:?Set MERO_KEY_ALIAS to its signing alias}"
        : "${MERO_STORE_PASSWORD_FILE:?Set MERO_STORE_PASSWORD_FILE to a private password file}"
        : "${MERO_KEY_PASSWORD_FILE:?Set MERO_KEY_PASSWORD_FILE to a private password file}"
        [[ -f "$MERO_KEYSTORE" && -r "$MERO_STORE_PASSWORD_FILE" && -r "$MERO_KEY_PASSWORD_FILE" ]] || {
            echo "The release keystore or password files are unavailable." >&2; exit 1;
        }
        keystore="$MERO_KEYSTORE"
        alias="$MERO_KEY_ALIAS"
        store_password="file:$MERO_STORE_PASSWORD_FILE"
        key_password="file:$MERO_KEY_PASSWORD_FILE"
    else
        mkdir -p .local
        keystore=.local/mero-development.jks
        alias=mero-development
        export MERO_DEVELOPMENT_PASSWORD=android
        if [[ ! -f "$keystore" ]]; then
            "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$keystore" \
                -alias "$alias" -storepass:env MERO_DEVELOPMENT_PASSWORD -keypass:env MERO_DEVELOPMENT_PASSWORD \
                -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Mero Development" >/dev/null
            chmod 600 "$keystore"
        fi
        store_password=env:MERO_DEVELOPMENT_PASSWORD
        key_password=env:MERO_DEVELOPMENT_PASSWORD
        echo "DEVELOPMENT signature. Keep the ignored local key to update your own builds."
    fi
    "$bt/apksigner" sign --ks "$keystore" --ks-key-alias "$alias" \
        --ks-pass "$store_password" --key-pass "$key_password" \
        --out "$apk" "$build/aligned.apk"
    "$bt/apksigner" verify --verbose "$apk"
    echo "Signed APK: $apk"
fi
"$bt/aapt2" dump badging "$apk" | sed -n '1,12p'

# Build, test and release

## Requirements

- JDK 17 or newer. Java source targets Java 8 bytecode.
- Android SDK Platform 35, Build Tools 35.0.0, and Platform Tools for device access.
- Python 3 and standard shell tools, including `zip`.
- Optional Python ADB tools: install `requirements-dev.txt` in a virtual environment.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
bash tools/build.sh
bash tools/test.sh
python3 tools/check-public.py
```

No Gradle wrapper or third-party Android runtime dependency is downloaded by the build. The QR library is vendored with its license.

## Build variants

| Command | Package | Purpose |
|---|---|---|
| `bash tools/build.sh` | `studio.invrse.mero` | Launcher with Home registration and optional helper. |
| `bash tools/build.sh --variant preview` | `studio.invrse.mero.preview` | Separate app for testing and recording. No Home registration or helper. |
| `bash tools/build-experiment.sh` | `studio.invrse.mero.experiment` | Separate Lab package for experiments. |

All variants include the temporary LAN artwork manager. Home Assistant stays off until configured. Package storage and Keystore credentials are separate between variants.

A first development build creates `.local/mero-development.jks`. All development variants use that key. It is not a maintainer release key. Keep it private and retain it for your own updates.

## Release signing

Use a dedicated signing key and keep it outside Git. Updates need the same package and signing identity. Supply passwords through private files, not command-line literals:

```sh
export MERO_KEYSTORE=/private/path/mero-release.jks
export MERO_KEY_ALIAS=mero
export MERO_STORE_PASSWORD_FILE=/private/path/store-password.txt
export MERO_KEY_PASSWORD_FILE=/private/path/key-password.txt
bash tools/build.sh --release
```

`--unsigned` creates an unsigned APK for a separate signing process. Do not publish CI's temporary development-signed APK as a stable update channel.

## Checks

The host checks cover artwork rotation, bounded ZIP restore, configuration validation and the HTTP request boundary. Build against the Android SDK before protocol checks. CI builds the APK and runs the same checks without a TV or Home Assistant account.

Device checks still matter: D-pad navigation, returning from apps, pairing on a phone, rotation, backup/restore and the one-minute Art mode wake behaviour. Never run destructive library tests against someone's only collection. Export a backup first or use the preview package with disposable data.

## Prepare the public source bundle

```sh
python3 tools/check-public.py
python3 tools/export-public.py
```

The export includes public source, docs, examples, tests and curated media. It excludes `.local/`, generated `output/`, device captures, signing keys, personal artwork and old release archives. Review its printed file list before creating a public repository.

Before publishing a release:

1. Run the checks from a clean checkout and build with the maintainer signing key.
2. Verify the APK package/version/signature and keep a recovery copy of the previous release.
3. Inspect screenshots and recordings for tokens, pairing codes, private server names and artwork permissions.
4. Publish the signed APK and its SHA-256 checksum with the release notes.

## Existing private installations

Mero uses a new package identity. Early private installations remain separate and are not updated in place. To migrate, export their artwork library, install Mero, then restore the backup in Mero. Set up Home Assistant separately. Before switching the default Home app or enabling Mero’s Home-button helper, turn off the previous launcher’s Home-button accessibility helper so the two cannot compete. Use the preview package to review the interface without changing the default Home app.

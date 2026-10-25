# Install and restore Home

Mero targets Android TV and Google TV. It has been tested on a Sony XR-55X94J running Android 12. The APK declares Android 8 as its minimum; other models and older versions remain unverified.

## Sideload

Build using the README, or use an APK from a maintainer release. Enable ADB debugging on the TV and connect with Android Platform Tools. Newer Wireless debugging may require `adb pair` before `adb connect`; use the two ports displayed by the TV.

```sh
adb connect TV_IP:ADB_PORT
adb install -r output/apk/mero.apk
```

Open Mero from Apps. Select the two favorite apps under **Settings → App shortcuts**. A slot with no installed selection offers **Choose app**.

If the TV offers a default Home-app setting, select Mero there. Do not disable or uninstall Google TV or Sony's system apps.

## Sony Home-button helper

Some Sony firmware prioritizes Google TV even after a different Home app is selected. Mero includes an optional accessibility helper for two specific Google Home/recovery activities. It is not a universal launcher replacement for every brand.

The helper receives window-state changes for those two packages. It cannot retrieve screen content or filter remote keys. It does not disable the original launcher.

To enable it using the Python tools on a legacy ADB connection:

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python tools/home.py --host TV_IP enable
```

The tool saves the previous Home and enabled-service state locally for that TV. Keep the saved state until rollback is no longer needed. Other accessibility services stay enabled.

If your TV uses TLS Wireless debugging and the Python client cannot connect, use the TV's Accessibility settings to enable **Mero Home button**. Keep a note of the original Home app before changing defaults.

## Restore the original Home app

For a setup enabled through the helper tool:

```sh
.venv/bin/python tools/home.py --host TV_IP restore
```

Use the same TV and saved state. If configured manually, turn off **Mero Home button** in Accessibility settings and choose the original Home app in the TV's default-app settings.

Back up artwork before uninstalling Mero. Removing app data deletes the artwork library and Home Assistant credential.

## Optional Python ADB fallback

Some Sony TVs stall standard ADB file transfers. The included client sends APKs in checked chunks and verifies the upload checksum:

```sh
.venv/bin/python tools/tv.py --host TV_IP install output/apk/mero.apk
.venv/bin/python tools/tv.py --host TV_IP screenshot output/home.png
```

These tools require a legacy TCP ADB connection and an existing RSA key. Set `TV_HOST`, `TV_PORT` and `TV_ADB_KEY` or pass the documented flags. They do not implement Android's TLS pairing flow.

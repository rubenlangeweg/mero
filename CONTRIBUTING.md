# Contributing

Keep Mero small, remote-friendly and usable without a cloud account. Home Assistant and device-specific behaviour must stay optional.

Before opening a change:

- Build the APK and run `bash tools/test.sh` and `python3 tools/check-public.py`.
- Describe the user-visible change and how you checked it.
- Include a screenshot or short recording for UI changes. Use redistributable artwork and hide credentials, private addresses and pairing codes.
- State which TV and Android version you tested. Do not imply broader hardware support from one device.

Use the preview package for device experiments. Do not commit signing keys, provisioning files, device dumps, personal artwork or generated releases. Keep the vendored QR library's attribution intact.

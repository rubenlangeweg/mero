# Demo captures

The screenshots show the isolated `studio.invrse.mero.preview` app on a Sony Android 12 TV. The navigation sequence was captured from the preceding public preview, before the Mero rename. Its dock interface is unchanged. They use the included CC0 museum collection. Home Assistant was not configured, and no sensor data was invented for the demo.

- `media/home.png`: native Home screen.
- `media/navigation.gif`: cropped navigation detail, assembled from sampled TV screenshots.
- `media/navigation.mp4`: the same sequence with the full TV frame.
- `media/library.png` and `media/framing.png`: the real paired browser manager using the demo library.
- `media/home-assistant-optional.png`: native optional setup before configuration.

The tested TV's hardware video encoder produced artifacts during animation. The published navigation sequence therefore uses directly captured screen frames. Frames are held for 600 ms to make a short loop. It demonstrates the controls; its sampling cadence is not a frame-rate benchmark for the app.

See [artwork credits](artwork-credits.md) for the museum image sources. The captures contain no pairing codes, private server addresses or credentials.

## Capture another device

Build and install the preview package, leaving the main launcher and personal library separate:

```sh
bash tools/build.sh --variant preview
adb install -r output/apk/mero-preview.apk
adb shell am start -W -n \
  studio.invrse.mero.preview/studio.invrse.mero.HomeActivity
adb shell screencap -p /data/local/tmp/mero.png
adb pull /data/local/tmp/mero.png output/home.png
```

Use a clean preview library with redistributable images. Keep Home Assistant off or use clearly identified demo readings without real credentials. Do not capture the pairing screen for public documentation.

A short `adb shell screenrecord` can work on devices with a reliable encoder. Decode and visually inspect the complete clip before publishing. Use sampled screenshots if the video encoder corrupts frames. Keep raw captures under ignored `output/` and copy only inspected media into `docs/media/`.

# Android PSTN audio diagnostics

This repository contains the read-only diagnostics and the controlled ADB call
runner for the connected I2403 device.

## Run one call

The device serial and phone number are supplied at runtime and are never stored
in the project:

```bash
ADB_DEVICE_SERIAL="YOUR_DEVICE_SERIAL" ./run-marento-call.sh "PHONE_NUMBER"
```

The audio source can be selected with `MARRENTO_AUDIO_FILE` when it is not at
the default local path.

The runner builds the probe, decodes `marento.mp3` to 44.1 kHz mono PCM16,
places one call, waits for the PSTN uplink route, injects the audio, lets the
OEM InCallUI call recorder capture the call, and hangs up after playback. On
success it writes two files under `recordings/`:

- `*-uplink-source.wav`: the exact PCM source sent upstream
- `*-oem-call.m4a`: the OEM call recording containing the call audio

The upstream file is a source copy, not a modem loopback capture. This firmware
rejects the generic privileged downlink `AudioRecord`, so the runner uses the
OEM recorder exposed by `com.android.incallui` instead.

## Layout

```text
config/       Device and path defaults
probe/src/    Java ADB-shell probe source
probe/build/  Generated classes and DEX output
probe/fixtures/  Decoded audio fixtures
scripts/      Probe builder and call runner
diagnostics/  Captured investigation records
recordings/   Local WAV output, created at runtime
```

The probe is deliberately source-only and runs under the ADB shell identity; no
APK is installed and no system files are modified.

# ivr_calling_using_android

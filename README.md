# IVR Calling Using Android

This project tests whether an Android phone can play a prepared audio file into a normal phone call.

The phone is controlled from a computer with ADB. The script:

1. Builds a small Java diagnostic probe.
2. Converts an audio file to 44.1 kHz, mono, 16-bit PCM.
3. Calls the phone number supplied on the command line.
4. Waits for the call to become active.
5. Sends the PCM audio through the phone's call-uplink audio path.
6. Ends the call after the audio finishes.
7. Saves the input audio and the phone's OEM call recording locally.

This is a controlled proof-of-concept for Android call audio injection. It is not yet a complete IVR product: it does not provide a web server, call queue, keypad/DTMF menu, database, or automatic multi-call service.

## Important status

The project uses Android's hidden call-audio APIs through a temporary ADB-shell probe. These APIs are device- and firmware-specific and may stop working after an Android update.

The tested phone is an I2403. The probe is source-only; it does not install an APK and does not modify system files. The recommended production direction is a real Android application or service with a valid application context. See [recommended-approach.md](recommended-approach.md).

Use this only with a phone, SIM, number, and audio that you are authorized to use. Never use it for emergency or service numbers. The runner blocks several common emergency/service numbers automatically, but this is not a substitute for responsible use.

## Requirements

### Computer

Install and make sure these commands are available in `PATH`:

- `adb` — Android Debug Bridge
- `ffmpeg` — audio conversion
- `javac` — Java compiler

The Android SDK must also contain:

- `platforms/android-36/android.jar`
- `build-tools/36.0.0/d8`

By default, the build script looks for the SDK at:

```text
$HOME/Android/Sdk
```

Set `ANDROID_SDK_ROOT` if the SDK is installed somewhere else.

### Android phone

The phone must:

1. Have USB debugging enabled.
2. Be connected to the computer.
3. Show as `device` in `adb devices`.
4. Have permission for the computer's USB debugging key.
5. Be able to place a normal cellular/PSTN call.
6. Have the OEM call recorder enabled if you want the recorded output file.

Check the connection with:

```bash
adb devices
```

Example output:

```text
List of devices attached
YOUR_DEVICE_SERIAL    device
```

## Get the project

```bash
git clone https://github.com/Ogpavan/ivr_calling_using_android.git
cd ivr_calling_using_android
```

## Prepare an audio file

The default command expects an audio file named `marento.mp3` in the project root:

```text
ivr_calling_using_android/marento.mp3
```

That audio file is not included in this repository. Supply your own audio file, or point the script to another file with `MARRENTO_AUDIO_FILE`.

The script converts the source automatically to:

- mono audio
- 44,100 Hz sample rate
- signed 16-bit little-endian PCM

For reliable testing, use a short voice recording or announcement and avoid very large files.

## Run one call

First set the Android device serial:

```bash
export ADB_DEVICE_SERIAL="YOUR_DEVICE_SERIAL"
```

Then run the call:

```bash
./run-marento-call.sh "+PHONE_NUMBER"
```

Example:

```bash
./run-marento-call.sh "+15551234567"
```

The phone number must contain only digits and may begin with `+`. The script accepts numbers between 3 and 20 digits. It rejects several emergency and service numbers.

### Use a different audio file

```bash
export MARRENTO_AUDIO_FILE="/absolute/path/to/announcement.mp3"
./run-marento-call.sh "+15551234567"
```

The phone number is passed at runtime and is not stored in the repository.

## What happens during a run

The runner performs these checks and actions:

1. Verifies the phone number format.
2. Checks that `adb` and `ffmpeg` are installed.
3. Checks that the audio file exists.
4. Builds `probe/build/dex/classes.dex` from the Java source.
5. Converts the source audio to a cached PCM fixture.
6. Pushes the probe and PCM file to temporary paths on the phone.
7. Starts a cellular call with Android's call intent.
8. Waits up to 30 seconds for the call to become off-hook.
9. Waits for the phone's PSTN uplink path to become available.
10. Sends the audio into the call.
11. Ends the call after the final PCM bytes are written.
12. Pulls the newest OEM call recording, when available.
13. Removes the temporary probe and PCM files from the phone.

Pressing `Ctrl+C` attempts to end an active call and clean up temporary files.

## Output files

Successful runs write files under `recordings/`:

```text
recordings/call-YYYYMMDD-HHMMSS-uplink-source.wav
recordings/call-YYYYMMDD-HHMMSS-oem-call.m4a
```

The files mean different things:

- `uplink-source.wav` is a WAV copy of the exact PCM bytes sent to the call uplink. It is not a modem loopback recording.
- `oem-call.m4a` is the recording produced by the phone's OEM InCallUI call recorder. It is the useful file for checking what the call recorder captured.

The converted PCM cache is stored at:

```text
probe/fixtures/audio/marento-44k1-mono-s16le.pcm
```

The cache is regenerated when the source audio changes.

## Project structure

```text
.
├── run-marento-call.sh          Main command-line entry point
├── config/defaults.sh           Device, audio, and temporary-path defaults
├── scripts/build-probe.sh       Compiles Java and creates the DEX file
├── scripts/run-call.sh          Validates, calls, injects audio, and collects output
├── probe/src/                   Java probe source
├── probe/build/                 Generated Java classes and DEX output
├── probe/fixtures/audio/        Generated PCM audio fixtures
├── diagnostics/                 Captured device investigation records
├── recordings/                  Local call output files
├── compatibility-report.md      Device compatibility findings
├── audio-policy.txt             Relevant audio-policy notes
└── recommended-approach.md      Suggested path toward a production app
```

Generated build files and audio recordings are local test artifacts. The repository ignores generated PCM, WAV, and M4A files where appropriate.

## Build the probe only

To compile the Java probe without making a call:

```bash
./scripts/build-probe.sh
```

To use a non-default Android SDK location:

```bash
export ANDROID_SDK_ROOT="/path/to/Android/Sdk"
./scripts/build-probe.sh
```

## Common problems

### `adb device is not available`

Run:

```bash
adb devices
```

Unlock the phone, accept the USB debugging prompt, and use the exact serial shown in the output:

```bash
export ADB_DEVICE_SERIAL="SERIAL_FROM_ADB_DEVICES"
```

### `adb is not installed or not in PATH`

Install Android Platform Tools and add the directory containing `adb` to `PATH`.

### `ffmpeg is required`

Install FFmpeg and confirm it works:

```bash
ffmpeg -version
```

### `Audio file not found`

Use the default filename `marento.mp3` in the project root, or provide an explicit path:

```bash
MARRENTO_AUDIO_FILE="/path/to/file.mp3" ./run-marento-call.sh "+15551234567"
```

### Missing Android API jar or `d8`

Install Android SDK Platform 36 and Build Tools 36.0.0, or set `ANDROID_SDK_ROOT` to the correct SDK directory.

### The call does not become active

Check the SIM, network signal, call permissions, phone lock state, and the target number. The runner waits 30 seconds and then ends the call without injecting audio.

### The call works but no M4A is saved

The script depends on the OEM InCallUI recorder writing to:

```text
/storage/emulated/0/Recordings/Record/Call
```

Enable the phone's call recording feature and check that the OEM uses this location. The uplink source WAV can still be useful, but it does not prove that the remote party heard the audio.

### The probe fails on another phone

That is expected. The hidden APIs and permission behavior vary by Android version, manufacturer, and firmware. Review the files in `diagnostics/`, then use [recommended-approach.md](recommended-approach.md) before treating the device as compatible.

## Limitations

- This project places one controlled call at a time.
- It does not implement an IVR menu or DTMF input handling.
- It does not run as a background Android application.
- It does not provide SIP, VoIP, a web API, a database, or call scheduling.
- It relies on hidden Android APIs and OEM behavior.
- A saved source WAV is not proof of successful remote playback.
- The phone's screen, call state, carrier, and recording settings can affect results.

## Moving toward a real IVR system

A production design should first validate the audio path inside a real Android application or service with a valid application context. After that, add:

1. A call-state controller.
2. Audio playback and prompt management.
3. DTMF detection and menu logic.
4. Retry, timeout, and hang-up handling.
5. A secure control API.
6. Authentication, logging, and consent controls.
7. Tests for each supported phone model and Android version.

The current shell probe is useful for device investigation, but it should not be treated as production-ready IVR infrastructure.

## License and authorization

No license is currently declared. Before distributing or deploying this project, add an appropriate license and confirm that your use complies with local law, carrier rules, call-recording requirements, and the consent of everyone involved in the calls.

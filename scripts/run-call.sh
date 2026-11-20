#!/usr/bin/env bash
set -Eeuo pipefail

# Supply the number at runtime: ./run-marento-call.sh "PHONE_NUMBER"
PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$PROJECT_ROOT/config/defaults.sh"

if [[ "$#" -ne 1 ]]; then
    echo "Usage: $0 PHONE_NUMBER"
    echo "Example: $0 \"PHONE_NUMBER\""
    exit 2
fi
TARGET_NUMBER="$1"

if [[ -z "$DEVICE_SERIAL" ]]; then
    echo "Set ADB_DEVICE_SERIAL to the connected Android device serial."
    exit 2
fi

DEX_FILE="$PROJECT_ROOT/probe/build/dex/classes.dex"
BUILD_SCRIPT="$PROJECT_ROOT/scripts/build-probe.sh"

ADB=(adb -s "$DEVICE_SERIAL")
call_started=0

cleanup() {
    "${ADB[@]}" shell rm -f "$REMOTE_DEX" "$REMOTE_PCM" "$REMOTE_RECORD" >/dev/null 2>&1 || true
}

abort_call() {
    if [[ "$call_started" == 1 ]]; then
        "${ADB[@]}" shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1 || true
    fi
    exit 130
}

trap cleanup EXIT
trap abort_call INT TERM

if [[ ! "$TARGET_NUMBER" =~ ^\+?[0-9]{3,20}$ ]]; then
    echo "Invalid phone number format: use digits, optionally starting with +."
    exit 2
fi

case "$TARGET_NUMBER" in
    112|911|999|100|101|102|108)
        echo "Refusing to dial an emergency/service number."
        exit 2
        ;;
esac

command -v adb >/dev/null || { echo "adb is not installed or not in PATH."; exit 1; }
command -v ffmpeg >/dev/null || { echo "ffmpeg is required to decode marento.mp3."; exit 1; }
[[ -f "$AUDIO_FILE" ]] || { echo "Audio file not found: $AUDIO_FILE"; exit 1; }
echo "Building probe..."
"$BUILD_SCRIPT" >/dev/null
mkdir -p "$RECORD_DIR"
mkdir -p "$(dirname -- "$PCM_FILE")"
record_stamp="$(date +%Y%m%d-%H%M%S)"
UPLINK_WAV="$RECORD_DIR/call-$record_stamp-uplink-source.wav"
OEM_RECORD="$RECORD_DIR/call-$record_stamp-oem-call.m4a"

oem_before_line="$("${ADB[@]}" shell "find '$OEM_CALL_RECORD_DIR' -maxdepth 1 -type f -printf '%T@ %p\\n' 2>/dev/null | sort -nr | head -1" | tr -d '\r' || true)"
call_started_at="$(date +%s)"

[[ "$("${ADB[@]}" get-state 2>/dev/null || true)" == "device" ]] || {
    echo "ADB device is not available: $DEVICE_SERIAL"
    exit 1
}

if [[ ! -f "$PCM_FILE" || "$AUDIO_FILE" -nt "$PCM_FILE" ]]; then
    echo "Decoding marento.mp3 to 44.1 kHz mono PCM16..."
    ffmpeg -y -v error -i "$AUDIO_FILE" -ac 1 -ar 44100 -f s16le "$PCM_FILE"
fi

echo "Staging audio and probe on $DEVICE_SERIAL..."
"${ADB[@]}" push "$DEX_FILE" "$REMOTE_DEX" >/dev/null
"${ADB[@]}" push "$PCM_FILE" "$REMOTE_PCM" >/dev/null

echo "Calling $TARGET_NUMBER..."
"${ADB[@]}" shell am start -a android.intent.action.CALL -d "tel:$TARGET_NUMBER" >/dev/null
call_started=1

for attempt in {1..30}; do
    if "${ADB[@]}" shell dumpsys telephony.registry 2>/dev/null | grep -q 'mCallState=2'; then
        break
    fi
    sleep 1
done

if ! "${ADB[@]}" shell dumpsys telephony.registry 2>/dev/null | grep -q 'mCallState=2'; then
    echo "The call did not reach off-hook within 30 seconds; no audio was injected."
    "${ADB[@]}" shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1 || true
    call_started=0
    exit 1
fi

echo "Call is off-hook. Waiting inside the audio probe for the answered PSTN uplink..."
probe_status=0
"${ADB[@]}" shell app_process \
        -Djava.class.path="$REMOTE_DEX" \
        /system/bin ProbeMain inject "$REMOTE_PCM" || probe_status=$?

# ProbeMain invokes KEYCODE_ENDCALL immediately after the final PCM write.
for attempt in {1..10}; do
    if ! "${ADB[@]}" shell dumpsys telephony.registry 2>/dev/null | grep -q 'mCallState=2'; then
        call_started=0
        break
    fi
    sleep 1
done

if [[ "$call_started" == 1 ]]; then
    echo "Playback finished, but the call state did not become idle."
    "${ADB[@]}" shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1 || true
    call_started=0
fi

sleep 2
oem_after_line="$("${ADB[@]}" shell "find '$OEM_CALL_RECORD_DIR' -maxdepth 1 -type f -printf '%T@ %p\\n' 2>/dev/null | sort -nr | head -1" | tr -d '\r' || true)"
oem_after_mtime="${oem_after_line%% *}"
oem_after_path="${oem_after_line#* }"
oem_record_saved=0
if [[ -n "$oem_after_path" && "$oem_after_path" != "$oem_before_line" && \
      -n "$oem_after_mtime" ]] && \
   awk -v m="$oem_after_mtime" -v s="$call_started_at" \
       'BEGIN { exit !(m >= s - 5) }'; then
    if "${ADB[@]}" pull "$oem_after_path" "$OEM_RECORD" >/dev/null 2>&1; then
        oem_record_saved=1
        echo "Saved OEM call recording to: $OEM_RECORD"
    fi
fi

if [[ "$probe_status" -eq 0 && "$oem_record_saved" -eq 1 ]]; then
    ffmpeg -y -v error -f s16le -ar 44100 -ac 1 -i "$PCM_FILE" \
        -c:a pcm_s16le "$UPLINK_WAV"
    echo "Saved injected upstream source to: $UPLINK_WAV"
    echo "Playback completed and the call was automatically disconnected."
    exit 0
fi

echo "Call completed, but the OEM call recording was not found."
echo "Probe exit status: $probe_status"
exit 1

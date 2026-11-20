# Device and audio settings used by scripts/run-call.sh.
# The phone number is intentionally never stored here; pass it as an argument.

DEVICE_SERIAL="${ADB_DEVICE_SERIAL:-}"
AUDIO_FILE="${MARRENTO_AUDIO_FILE:-$PROJECT_ROOT/marento.mp3}"
PCM_FILE="$PROJECT_ROOT/probe/fixtures/audio/marento-44k1-mono-s16le.pcm"
RECORD_DIR="$PROJECT_ROOT/recordings"

REMOTE_DEX="/data/local/tmp/android-call-probe.dex"
REMOTE_PCM="/data/local/tmp/marento-44k1-mono-s16le.pcm"
REMOTE_RECORD="/data/local/tmp/pstn-downlink-recording.pcm"
OEM_CALL_RECORD_DIR="/storage/emulated/0/Recordings/Record/Call"

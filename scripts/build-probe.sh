#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
ANDROID_JAR="$SDK_ROOT/platforms/android-36/android.jar"
D8="$SDK_ROOT/build-tools/36.0.0/d8"
SOURCE_DIR="$PROJECT_ROOT/probe/src"
CLASS_DIR="$PROJECT_ROOT/probe/build/classes"
DEX_DIR="$PROJECT_ROOT/probe/build/dex"

[[ -f "$ANDROID_JAR" ]] || { echo "Missing Android API jar: $ANDROID_JAR" >&2; exit 1; }
[[ -x "$D8" ]] || { echo "Missing d8: $D8" >&2; exit 1; }

mkdir -p "$CLASS_DIR" "$DEX_DIR"
javac -source 8 -target 8 \
    -classpath "$ANDROID_JAR" \
    -d "$CLASS_DIR" \
    "$SOURCE_DIR/ProbeMain.java"

mapfile -d '' CLASS_FILES < <(find "$CLASS_DIR" -type f -name '*.class' -print0)
[[ "${#CLASS_FILES[@]}" -gt 0 ]] || { echo "No compiled class files found." >&2; exit 1; }

DEX_TMP="$(mktemp -d "$DEX_DIR/dex-tmp.XXXXXX")"
trap 'find "$DEX_TMP" -type f -delete 2>/dev/null || true; rmdir "$DEX_TMP" 2>/dev/null || true' EXIT

"$D8" --min-api 36 --output "$DEX_TMP" "${CLASS_FILES[@]}"
cp "$DEX_TMP/classes.dex" "$DEX_DIR/classes.dex"

echo "Built $DEX_DIR/classes.dex"
printf 'Included classes:\n'
find "$CLASS_DIR" -type f -name 'ProbeMain*.class' -printf '  %f\n' | sort

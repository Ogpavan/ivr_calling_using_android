# Recommended Approach

## Current conclusion

This I2403 firmware has favorable static routing evidence, but the tested shell-only
injection path is **blocked**. The hidden API can create an initialized track, yet the
shell `app_process` lacks the application attribution context needed for playback, and
the context-aware direct builder fails on this firmware.

## Next safe step

Use the source-only probe in `probe/src/AudioInjectionProbe.java` as the basis for a real
Android service with a valid package/application context. The service must be installed
and granted only the permissions the firmware permits, or run as a Shizuku UserService
with a valid attribution context. The shell-only `app_process` approach should not be
treated as production-ready.

Shizuku is not currently installed. It may provide a shell-identity helper, but it does
not automatically grant every signature permission; this phone's shell permission
state must be verified again inside the actual helper process.

## Production architecture if verified

Use a native Kotlin app/service for Telecom call control and call-state callbacks, PCM
decoding, and the hidden call-uplink AudioTrack only after the app-context path is
validated. A Node.js control API can send commands after the on-device path is proven.
Keep a conservative fallback state machine for
`SecurityException`, `UnsupportedOperationException`, `IllegalStateException`, and
AudioTrack initialization/write failures.

Do not use speaker/microphone acoustic coupling; it would not satisfy the requirement.

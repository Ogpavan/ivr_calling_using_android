# Diagnostic probe

`AudioInjectionProbe.java` is the source-only diagnostic helper. `ProbeMain.java` was
compiled and run temporarily under ADB shell for the authorized call. It confirmed
`isPstnCallAudioInterceptable=true`, initialized the hidden uplink track, started it
through the native path, and accepted all 2,552,736 PCM bytes from `black.mp3`. The
temporary dex and PCM file were removed from the phone after playback.

The runtime probe now waits for `isPstnCallAudioInterceptable()` to become true and
for `getCallUplinkInjectionAudioTrack()` to return an initialized track before it
writes any PCM data. This avoids treating the dialing/off-hook state as an answered
call. It invokes `KEYCODE_ENDCALL` after the final write.

When a recording path is supplied, the probe also waits for and starts
`getCallDownlinkExtractionAudioRecord()` before playback. The shell script pulls
that PCM capture and converts it to a local 44.1 kHz mono WAV file. If either
redirection endpoint cannot be initialized, the probe exits unsuccessfully and
does not claim that a recording was saved. Downlink initialization runs in a
separate thread, so it never delays upstream playback. A successful script run saves the
downlink capture and a separate WAV copy of the exact upstream PCM source. The
upstream WAV is not a modem loopback capture; it is the bytes sent to the uplink.

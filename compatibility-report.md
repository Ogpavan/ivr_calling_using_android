# Android USB Cellular Audio Injection Compatibility Report

## Final result

**VERIFIED WORKING** — the phone's static route and runtime gate passed, the
authorized live call created an initialized telephony uplink `AudioTrack`, and the
user confirmed that the digitally injected audio was audible remotely. No speaker
playback or acoustic coupling was used.

The ordinary `AudioTrack.play()` path failed in the shell process because of missing
application context; the test succeeded by starting the already-created privileged
track through its native start path. A production implementation should use a proper
application-context privileged service rather than depend on this shell workaround.

## Device

- vivo / iQOO I2403, product I2403i
- Android 16, API 36
- MediaTek MT6878 (`mt6878`), `arm64-v8a`
- User build, release keys; verified boot green; bootloader locked
- `ro.debuggable=0`; ADB shell is UID 2000, not root
- `su` is absent/inaccessible
- USB debugging is enabled and the selected physical device is `device`

## Telephony and Telecom

The phone advertises Android telephony calling, GSM, IMS, radio-access, subscription,
and Telecom features. `persist.radio.multisim.config=dsds`; two slots were exposed,
with slot 0 IMS ready and slot 1 unavailable at collection time. Telecom reported
`com.android.phone/com.android.services.telephony.TelephonyConnectionService` as the
default outgoing SIM connection service. There was no active call.

The dialer role holder was `com.android.contacts`. No Shizuku package was installed.

## Audio-policy findings

The device exposes:

- `AUDIO_DEVICE_OUT_TELEPHONY_TX` (“Telephony Tx”)
- `AUDIO_DEVICE_IN_TELEPHONY_RX` (“Voice Call In”)
- `incall_music_uplink` with `AUDIO_OUTPUT_FLAG_INCALL_MUSIC`
- A route whose sink is `Telephony Tx` and whose sources include `incall_music_uplink`
- `AUDIO_USAGE_CALL_ASSISTANT` selected to `AUDIO_DEVICE_OUT_TELEPHONY_TX`
- A system audio patch from the incall music mix port to Telephony Tx

This is strong evidence of a real internal digital path to the cellular uplink. It is
not speaker playback and does not by itself prove remote delivery.

## Hidden API and privilege assessment

At API 36, the framework contains the requested hidden/system APIs:

- `AudioManager.isPstnCallAudioInterceptable()`
- `AudioManager.getCallUplinkInjectionAudioTrack(AudioFormat)`

The device's `com.android.shell` package dump reports these permissions as granted:

- `android.permission.CALL_AUDIO_INTERCEPTION`
- `android.permission.MODIFY_AUDIO_ROUTING`
- `android.permission.MODIFY_PHONE_STATE`

The runtime probe called both the PSTN capability method and the uplink-track method.
The capability method returned `true`; during the active call the uplink method
returned an initialized track. Track creation and accepted writes still do not prove
that the remote party heard the audio.

The AOSP implementation documents that the PSTN interception check requires the
privileged permission and telephony uplink/downlink endpoints, while the uplink track
is reserved for privileged call redirection/screening use. [AOSP AudioManager](https://android.googlesource.com/platform/frameworks/base/+/c03ab432b762d16b20fd5f9366ebb9207be76820/media/java/android/media/AudioManager.java), [AOSP AudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/audio/AudioService.java)

## Compatibility matrix

| Feature | Status | Evidence |
|---|---|---|
| Automatic SIM dialing | Supported (static) | Default Telecom SIM connection service is present; no dial performed |
| Cellular call-state detection | Supported (static) | Telecom and InCall services are present; no app callback tested |
| Call disconnect control | Supported (shell test) | The single test call was ended and state returned to idle |
| PSTN audio interception API availability | Supported (framework) | Android API 36 and framework API contract |
| Privileged permission access | Supported for ADB shell | Shell package has CALL_AUDIO_INTERCEPTION, MODIFY_AUDIO_ROUTING, MODIFY_PHONE_STATE |
| Digital uplink routing | Supported (static) | incall_music_uplink → Telephony Tx route present |
| Shizuku compatibility | Unknown / not tested | Shizuku package absent; shell path exists |
| Root availability | Unsupported | Verified boot/locked user build; `su` unavailable |
| Digital audio injection | Supported and remotely confirmed | 44.1 kHz mono PCM16 was audible remotely during the live call; the latest probe's final byte counter was unavailable after route shutdown |
| Automatic hang-up after playback | Supported and verified | The probe invoked `KEYCODE_ENDCALL` after the final PCM write; exit code 0 and post-test `mCallState=0` |

## Open-source references

The [android-voice-sip-agent repository](https://github.com/sparkcyf/android-voice-sip-agent)
uses hidden Android behavior and Shizuku-assisted privileged operations; its technical
notes emphasize that direct telephony routing varies by device/HAL. The [AirSIM repository](https://github.com/kai-wu-cortex/AirSIM)
describes a Shizuku UserService PCM bridge using a telephony route. Both are useful
architecture references, but neither proves this phone's remote audio behavior.

## Safety state

- No APK installed.
- No Shizuku installation or grant.
- No default-dialer change.
- No root, bootloader, SELinux, firmware, or system-partition change.
- Exactly one call was placed for the authorized test; the MP3 was streamed digitally,
  then the call was disconnected and the device returned to idle.
- Reports redact subscriber numbers, IMEI/IMSI/SIM identifiers, cell IDs, IP addresses,
  and Bluetooth addresses.

## Live-test record

- Call reached active/off-hook state.
- Runtime `isPstnCallAudioInterceptable()` returned `true`.
- `getCallUplinkInjectionAudioTrack()` returned an initialized track (`state=1`).
- The ordinary `AudioTrack.play()` path hit a null-context `PackageManager` exception.
- The privileged track was started through the native start path.
- Latest repeat used 44.1 kHz mono PCM16, a 1,888-frame buffer, a 3,776-byte
  prebuffer, and 20 ms blocking writes.
- `isPstnCallAudioInterceptable()` returned `true` and the uplink track initialized
  with `state=1`.
- The user confirmed the injected audio was audible remotely.
- The exact final accepted-byte and underrun counters were unavailable because the
  call route closed while the probe was flushing its final output.
- Call ended; post-test call state was idle and temporary device files were removed.
- A repeat using the newly supplied destination verified in-process automatic
  hang-up: `automatic_hangup_invoked=true exit_code=0`, with post-test
  `mCallState=0`.

## Remaining proof step

The remaining proof step is confirmation from the receiving party that the MP3 was
heard. Production work should then move the same logic into a proper application-context
privileged service and remove the shell/native-start workaround.

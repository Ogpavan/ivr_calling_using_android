import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.lang.reflect.Method;

/**
 * Source-only diagnostic helper for an approved Android test harness.
 *
 * Default behavior is read-only. Do not pass allowTrackCreation=true unless a user has
 * explicitly authorized one controlled live call; creating the track changes call audio
 * state and is not a proof that the remote caller hears the PCM.
 */
public final class AudioInjectionProbe {
    private AudioInjectionProbe() {}

    public static String inspect(Context context, boolean allowTrackCreation) {
        StringBuilder out = new StringBuilder();
        out.append("sdk=").append(Build.VERSION.SDK_INT).append('\n');

        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return out.append("audio_manager=absent\n").toString();
        }

        out.append("permission.CALL_AUDIO_INTERCEPTION=")
                .append(check(context, "android.permission.CALL_AUDIO_INTERCEPTION"))
                .append('\n');
        out.append("permission.MODIFY_AUDIO_ROUTING=")
                .append(check(context, "android.permission.MODIFY_AUDIO_ROUTING"))
                .append('\n');
        out.append("permission.MODIFY_PHONE_STATE=")
                .append(check(context, "android.permission.MODIFY_PHONE_STATE"))
                .append('\n');

        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_ALL)) {
            out.append("device type=").append(device.getType())
                    .append(" name=").append(device.getProductName())
                    .append(" id=").append(device.getId()).append('\n');
        }

        try {
            Method interceptable = AudioManager.class
                    .getDeclaredMethod("isPstnCallAudioInterceptable");
            interceptable.setAccessible(true);
            Object value = interceptable.invoke(audio);
            out.append("isPstnCallAudioInterceptable=").append(value).append('\n');
        } catch (Throwable t) {
            out.append("isPstnCallAudioInterceptable.error=")
                    .append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }

        if (!allowTrackCreation) {
            out.append("uplink_track_creation=SKIPPED_READ_ONLY_MODE\n");
            return out.toString();
        }

        try {
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            Method create = AudioManager.class.getDeclaredMethod(
                    "getCallUplinkInjectionAudioTrack", AudioFormat.class);
            create.setAccessible(true);
            AudioTrack track = (AudioTrack) create.invoke(audio, format);
            out.append("uplink_track_state=").append(track.getState()).append('\n');
            track.release();
        } catch (Throwable t) {
            out.append("uplink_track_creation.error=")
                    .append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return out.toString();
    }

    private static boolean check(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}

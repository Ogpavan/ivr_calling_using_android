import android.media.AudioDeviceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Runs under app_process as ADB shell UID 2000. */
public final class ProbeMain {
    private static final int SAMPLE_RATE = 44100;
    private static final int DOWNLINK_SAMPLE_RATE = 8000;

    public static void main(String[] args) throws Exception {
        boolean inject = args.length > 0 && "inject".equals(args[0]);
        String pcmPath = args.length > 1 ? args[1] : null;
        String recordPath = args.length > 2 ? args[2] : null;
        Looper.prepareMainLooper();
        AudioManager audio = newAudioManager();
        System.out.println("probe_uid=2000_expected_shell");
        System.out.println("device_count=" + audio.getDevices(AudioManager.GET_DEVICES_ALL).length);
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_ALL)) {
            System.out.println("device type=" + device.getType()
                    + " name=" + device.getProductName()
                    + " id=" + device.getId());
        }

        Method interceptable = AudioManager.class
                .getDeclaredMethod("isPstnCallAudioInterceptable");
        interceptable.setAccessible(true);
        try {
            System.out.println("isPstnCallAudioInterceptable=" + interceptable.invoke(audio));
        } catch (Throwable t) {
            System.out.println("isPstnCallAudioInterceptable.error=" + describe(t));
        }

        if (!inject) {
            System.out.println("uplink_injection=SKIPPED_PRECALL_MODE");
            return;
        }

        AudioTrack track = null;
        DownlinkRecorderStarter recorderStarter = null;
        DownlinkRecorder recorder = null;
        boolean playbackSucceeded = false;
        boolean recordingSucceeded = recordPath == null;
        try {
            AudioFormat format = audioFormat();
            Method create = AudioManager.class.getDeclaredMethod(
                    "getCallUplinkInjectionAudioTrack", AudioFormat.class);
            create.setAccessible(true);
            track = waitForUplinkTrack(audio, format, interceptable, create);
            System.out.println("uplink_track_created=true method=AudioManager state="
                    + track.getState() + " sample_rate=" + SAMPLE_RATE);
            if (recordPath != null) {
                recorderStarter = startDownlinkRecorderAsync(recordPath);
            }
            if (pcmPath == null) writeSample(track);
            else writePcmFile(track, pcmPath);
            playbackSucceeded = true;
        } catch (Throwable t) {
            System.out.println("hidden_uplink_injection.error=" + describe(t));
            release(track);
            track = null;
            if (recordPath != null) {
                System.out.println("recording_required=true; skipping unrecorded fallback");
            } else {
                try {
                    track = directTrack(audioFormat(), shellContext());
                    System.out.println("uplink_track_created=true method=direct_builder state="
                            + track.getState() + " sample_rate=" + SAMPLE_RATE);
                    if (pcmPath == null) writeSample(track);
                    else writePcmFile(track, pcmPath);
                    playbackSucceeded = true;
                } catch (Throwable fallback) {
                    System.out.println("direct_uplink_injection.error=" + describe(fallback));
                }
            }
        } finally {
            if (recorderStarter != null) {
                recorder = recorderStarter.stopAndJoin();
            }
            if (recorder != null) recorder.stopAndJoin();
            recordingSucceeded = recordPath == null || recorder != null;
            release(track);
            System.out.println("uplink_track_released=true");
            hangUpCall();
            System.out.flush();
            System.exit(playbackSucceeded && recordingSucceeded ? 0 : 1);
        }
    }

    private static AudioManager newAudioManager() throws Exception {
        Constructor<AudioManager> fallback = AudioManager.class.getDeclaredConstructor();
        fallback.setAccessible(true);
        return fallback.newInstance();
    }

    private static AudioManager newContextualAudioManager() throws Exception {
        Class<?> contextClass = Class.forName("android.content.Context");
        Constructor<AudioManager> contextual = AudioManager.class
                .getDeclaredConstructor(contextClass);
        contextual.setAccessible(true);
        return contextual.newInstance(shellContext());
    }

    private static Object systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return getSystemContext.invoke(thread);
    }

    private static Object shellContext() throws Exception {
        Object system = systemContext();
        Class<?> contextClass = Class.forName("android.content.Context");
        Method createPackageContext = contextClass.getDeclaredMethod(
                "createPackageContext", String.class, int.class);
        createPackageContext.setAccessible(true);
        return createPackageContext.invoke(system, "com.android.shell", 0);
    }

    private static AudioFormat audioFormat() {
        return new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
    }

    private static AudioTrack waitForUplinkTrack(AudioManager audio, AudioFormat format,
            Method interceptable, Method create) throws Exception {
        final long deadline = System.currentTimeMillis() + 60000L;
        int attempt = 0;
        Throwable last = null;
        while (System.currentTimeMillis() < deadline) {
            boolean ready = false;
            try {
                ready = Boolean.TRUE.equals(interceptable.invoke(audio));
            } catch (Throwable t) {
                last = t;
            }
            if (ready) {
                try {
                    AudioTrack candidate = (AudioTrack) create.invoke(audio, format);
                    if (candidate != null
                            && candidate.getState() == AudioTrack.STATE_INITIALIZED) {
                        System.out.println("call_audio_route_ready=true attempts=" + attempt);
                        return candidate;
                    }
                    release(candidate);
                } catch (Throwable t) {
                    last = t;
                }
            }
            if (attempt % 10 == 0) {
                System.out.println("waiting_for_answered_call_audio=true attempt=" + attempt
                        + " interceptable=" + ready);
            }
            attempt++;
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Timed out waiting for initialized PSTN uplink track",
                last);
    }

    private static DownlinkRecorderStarter startDownlinkRecorderAsync(String path) {
        DownlinkRecorderStarter starter = new DownlinkRecorderStarter(path);
        starter.start();
        return starter;
    }

    private static AudioFormat captureFormat() {
        return new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(DOWNLINK_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
    }

    private static AudioTrack directTrack(AudioFormat format, Object context) throws Exception {
        int usage = hiddenInt(AudioAttributes.class, "USAGE_CALL_ASSISTANT");
        int redirect = hiddenInt(AudioManager.class, "CALL_REDIRECT_PSTN");

        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
        Method setSystemUsage = attributesBuilder.getClass()
                .getDeclaredMethod("setSystemUsage", int.class);
        setSystemUsage.setAccessible(true);
        setSystemUsage.invoke(attributesBuilder, usage);
        AudioAttributes attributes = attributesBuilder.build();

        AudioTrack.Builder builder = new AudioTrack.Builder();
        Method setContext = builder.getClass().getDeclaredMethod(
                "setContext", Class.forName("android.content.Context"));
        setContext.setAccessible(true);
        setContext.invoke(builder, context);
        builder.setAudioAttributes(attributes).setAudioFormat(format);
        Method setCallRedirectionMode = builder.getClass()
                .getDeclaredMethod("setCallRedirectionMode", int.class);
        setCallRedirectionMode.setAccessible(true);
        setCallRedirectionMode.invoke(builder, redirect);
        return builder.build();
    }

    private static int hiddenInt(Class<?> owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void writeSample(AudioTrack track) throws Exception {
        startNative(track);
        final int frames = SAMPLE_RATE / 10;
        short[] pcm = new short[frames];
        int totalAccepted = 0;
        for (int chunk = 0; chunk < 20; chunk++) {
            for (int i = 0; i < frames; i++) {
                int absolute = chunk * frames + i;
                double seconds = absolute / (double) SAMPLE_RATE;
                double frequency;
                if (seconds < 0.55) frequency = 880.0;
                else if (seconds < 0.75) frequency = 0.0;
                else if (seconds < 1.30) frequency = 660.0;
                else if (seconds < 1.50) frequency = 0.0;
                else frequency = 880.0;
                pcm[i] = frequency == 0.0
                        ? 0
                        : (short) (Math.sin(2.0 * Math.PI * frequency * seconds) * 12000.0);
            }
            int accepted = track.write(pcm, 0, pcm.length);
            System.out.println("write_chunk=" + chunk + " accepted_frames=" + accepted);
            if (accepted < 0) throw new IllegalStateException("AudioTrack.write returned " + accepted);
            totalAccepted += accepted;
            Thread.sleep(100L);
        }
        System.out.println("total_accepted_frames=" + totalAccepted);
        Thread.sleep(250L);
    }

    private static void writePcmFile(AudioTrack track, String path) throws Exception {
        int capacity = track.getBufferCapacityInFrames();
        int targetBuffer = Math.min(capacity, SAMPLE_RATE / 2);
        int appliedBuffer = targetBuffer > 0 ? track.setBufferSizeInFrames(targetBuffer) : 0;
        System.out.println("buffer_capacity_frames=" + capacity
                + " buffer_size_frames=" + appliedBuffer);

        byte[] prebuffer = new byte[SAMPLE_RATE]; // 500 ms of mono PCM16
        byte[] buffer = new byte[1764 * 2]; // 20 ms at 44.1 kHz, mono, PCM16
        int totalAccepted = 0;
        try (InputStream input = new FileInputStream(path)) {
            int preRead = readFully(input, prebuffer);
            int preAccepted = 0;
            while (preAccepted < preRead) {
                int accepted = track.write(prebuffer, preAccepted, preRead - preAccepted,
                        AudioTrack.WRITE_NON_BLOCKING);
                if (accepted <= 0) break;
                preAccepted += accepted;
            }
            totalAccepted += preAccepted;
            System.out.println("prebuffer_requested_bytes=" + preRead
                    + " prebuffer_accepted_bytes=" + preAccepted);

            startNative(track);

            if (preAccepted < preRead) {
                totalAccepted += writeBlocking(track, prebuffer, preAccepted, preRead - preAccepted, 0);
            }

            int chunk = 0;
            int read;
            while ((read = input.read(buffer)) > 0) {
                int accepted = writeBlocking(track, buffer, 0, read, chunk);
                totalAccepted += accepted;
                chunk++;
            }
        }
        System.out.println("pcm_total_accepted_bytes=" + totalAccepted);
        System.out.println("underrun_count=" + track.getUnderrunCount());
        Thread.sleep(500L);
    }

    private static int readFully(InputStream input, byte[] buffer) throws Exception {
        int total = 0;
        int read;
        while (total < buffer.length && (read = input.read(buffer, total, buffer.length - total)) > 0) {
            total += read;
        }
        return total;
    }

    private static int writeBlocking(AudioTrack track, byte[] buffer, int offset,
            int length, int chunk) {
        int acceptedTotal = 0;
        while (acceptedTotal < length) {
            int accepted = track.write(buffer, offset + acceptedTotal,
                    length - acceptedTotal, AudioTrack.WRITE_BLOCKING);
            if (chunk % 100 == 0 || accepted != length) {
                System.out.println("pcm_chunk=" + chunk + " requested_bytes=" + length
                        + " accepted_bytes=" + accepted);
            }
            if (accepted <= 0) throw new IllegalStateException(
                    "AudioTrack.write returned " + accepted);
            acceptedTotal += accepted;
        }
        return acceptedTotal;
    }

    private static void startNative(AudioTrack track) throws Exception {
        Method nativeStart = AudioTrack.class.getDeclaredMethod("native_start");
        nativeStart.setAccessible(true);
        nativeStart.invoke(track);
        System.out.println("native_start_invoked=true");
    }

    private static void release(AudioTrack track) {
        if (track != null) {
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }
    }

    private static void release(AudioRecord record) {
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) {}
            try { record.release(); } catch (Throwable ignored) {}
        }
    }

    private static final class DownlinkRecorderStarter implements Runnable {
        private final String path;
        private volatile boolean stopping;
        private volatile DownlinkRecorder recorder;
        private Thread thread;

        DownlinkRecorderStarter(String path) {
            this.path = path;
        }

        void start() {
            thread = new Thread(this, "pstn-downlink-recorder-starter");
            thread.start();
        }

        @Override
        public void run() {
            try {
                // AudioManager's call-redirection registration creates a
                // Handler on the calling thread. This worker therefore needs
                // its own Looper before creating the downlink AudioRecord.
                Looper.prepare();
                // Downlink AudioRecord needs a non-null package context. Keep
                // this separate from the no-context AudioManager used by the
                // working uplink path on this firmware.
                AudioManager recordingAudio = newContextualAudioManager();
                Method create = AudioManager.class.getDeclaredMethod(
                        "getCallDownlinkExtractionAudioRecord", AudioFormat.class);
                create.setAccessible(true);
                long deadline = System.currentTimeMillis() + 60000L;
                int attempt = 0;
                Throwable last = null;
                while (!stopping && System.currentTimeMillis() < deadline) {
                    try {
                        AudioRecord record = (AudioRecord) create.invoke(
                                recordingAudio, captureFormat());
                        if (record != null
                                && record.getState() == AudioRecord.STATE_INITIALIZED) {
                            DownlinkRecorder candidate = new DownlinkRecorder(record, path);
                            candidate.start();
                            recorder = candidate;
                            System.out.println("downlink_recording_started=true sample_rate="
                                    + DOWNLINK_SAMPLE_RATE + " path=" + path
                                    + " attempts=" + attempt);
                            return;
                        }
                        release(record);
                    } catch (Throwable t) {
                        last = t;
                        if (attempt % 10 == 0) {
                            System.out.println("downlink_recording.attempt_error="
                                    + describe(t));
                        }
                    }
                    if (attempt % 10 == 0) {
                        System.out.println("waiting_for_downlink_record=true attempt=" + attempt);
                    }
                    attempt++;
                    Thread.sleep(500L);
                }
                if (!stopping) {
                    System.out.println("downlink_recording.error=Timed out waiting for initialized PSTN downlink record"
                            + " cause=" + describe(last));
                }
            } catch (Throwable t) {
                System.out.println("downlink_recording.error=" + describe(t));
            }
        }

        DownlinkRecorder stopAndJoin() {
            stopping = true;
            if (thread != null) {
                try { thread.join(2500L); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            return recorder;
        }
    }

    private static final class DownlinkRecorder implements Runnable {
        private final AudioRecord record;
        private final String path;
        private volatile boolean stopping;
        private Thread thread;

        DownlinkRecorder(AudioRecord record, String path) {
            this.record = record;
            this.path = path;
        }

        void start() throws Exception {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Downlink AudioRecord did not enter recording state");
            }
            thread = new Thread(this, "pstn-downlink-recorder");
            thread.start();
        }

        @Override
        public void run() {
            byte[] buffer = new byte[DOWNLINK_SAMPLE_RATE * 2 / 10]; // 100 ms mono PCM16
            try (FileOutputStream output = new FileOutputStream(path)) {
                while (!stopping) {
                    int count = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (count > 0) {
                        output.write(buffer, 0, count);
                    } else if (count < 0) {
                        System.out.println("downlink_recording.error=AudioRecord.read:" + count);
                        break;
                    }
                }
                output.flush();
            } catch (Throwable t) {
                System.out.println("downlink_recording.error=" + describe(t));
            }
        }

        void stopAndJoin() {
            stopping = true;
            try { record.stop(); } catch (Throwable ignored) {}
            if (thread != null) {
                try { thread.join(2000L); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            try { record.release(); } catch (Throwable ignored) {}
            System.out.println("downlink_recording_stopped=true");
        }
    }

    private static void hangUpCall() {
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[] {"input", "keyevent", "KEYCODE_ENDCALL"});
            int exitCode = process.waitFor();
            System.out.println("automatic_hangup_invoked=true exit_code=" + exitCode);
        } catch (Throwable t) {
            System.out.println("automatic_hangup.error=" + describe(t));
        }
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) return "none";
        Throwable t = throwable;
        int depth = 0;
        while (t.getCause() != null && t.getCause() != t && depth++ < 10) {
            t = t.getCause();
        }
        return t.getClass().getName() + ":" + String.valueOf(t.getMessage());
    }
}

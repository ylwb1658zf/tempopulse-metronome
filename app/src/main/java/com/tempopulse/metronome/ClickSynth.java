package com.tempopulse.metronome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Synthesises the click banks. No sample assets means instant sound switching and
 * deterministic timing. Accent, normal and subdivision hits are generated separately
 * so subdivisions have their own lighter timbre rather than just a lower gain.
 */
public final class ClickSynth {
    public static final int SOUND_COUNT = 6;
    public static final int STYLE_NORMAL = 0;
    public static final int STYLE_ACCENT = 1;
    public static final int STYLE_SUBDIVISION = 2;

    private static final Map<String, Bank> CACHE = new HashMap<>();
    private static final Object LOCK = new Object();

    private ClickSynth() {}

    public static final class Bank {
        public final float[] normal;
        public final float[] accent;
        public final float[] subdivision;

        Bank(float[] normal, float[] accent, float[] subdivision) {
            this.normal = normal;
            this.accent = accent;
            this.subdivision = subdivision;
        }

        public float[] forStyle(int style) {
            if (style == STYLE_ACCENT) return accent;
            if (style == STYLE_SUBDIVISION) return subdivision;
            return normal;
        }
    }

    public static Bank bank(int sound, int sampleRate) {
        sound = Math.max(0, Math.min(SOUND_COUNT - 1, sound));
        String key = sound + "@" + sampleRate;
        synchronized (LOCK) {
            Bank cached = CACHE.get(key);
            if (cached != null) return cached;
            Bank made = new Bank(
                    build(sound, STYLE_NORMAL, sampleRate),
                    build(sound, STYLE_ACCENT, sampleRate),
                    build(sound, STYLE_SUBDIVISION, sampleRate));
            CACHE.put(key, made);
            return made;
        }
    }

    public static int nativeSampleRate(Context context) {
        try {
            int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
            if (rate < 22050 || rate > 192000) rate = 48000;
            return rate;
        } catch (Exception e) {
            return 48000;
        }
    }

    public static void preview(Context context, int sound, float volume) {
        preview(context, sound, volume, STYLE_ACCENT);
    }

    public static void preview(Context context, int sound, float volume, int style) {
        try {
            int rate = nativeSampleRate(context);
            float[] samples = bank(sound, rate).forStyle(style);
            short[] pcm = new short[samples.length];
            float gain = 0.85f * Math.max(0f, Math.min(1f, volume));
            for (int i = 0; i < samples.length; i++) {
                pcm[i] = (short) (Math.max(-1f, Math.min(1f, samples[i] * gain)) * 32767f);
            }
            int bytes = pcm.length * 2;
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
            track.play();
            Thread release = new Thread(() -> {
                try {
                    Thread.sleep(450);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    track.stop();
                } catch (Exception ignored) {
                }
                try {
                    track.release();
                } catch (Exception ignored) {
                }
            }, "ClickPreview");
            release.setDaemon(true);
            release.start();
        } catch (Exception ignored) {
        }
    }

    private static float[] build(int sound, int style, int rate) {
        boolean subdivision = style == STYLE_SUBDIVISION;
        double length;
        switch (sound) {
            case 2:
                length = subdivision ? 0.11 : 0.18;
                break;
            case 3:
                length = subdivision ? 0.08 : 0.12;
                break;
            case 5:
                length = subdivision ? 0.06 : 0.09;
                break;
            default:
                length = subdivision ? 0.06 : 0.085;
                break;
        }
        int count = Math.max(1, (int) (length * rate));
        float[] out = new float[count];
        Random random = new Random(sound * 97L + style * 13L + rate);
        double noisePrev = 0;

        for (int i = 0; i < count; i++) {
            double t = (double) i / rate;
            double sample;
            switch (sound) {
                case 0: {
                    // Woodblock: hollow fundamental, fast woody decay.
                    double f = style == STYLE_SUBDIVISION ? 1560
                            : style == STYLE_ACCENT ? 1280 : 880;
                    double decay = subdivision ? 96.0 : 55.0;
                    double env = Math.exp(-t * decay) * (1 - Math.exp(-t * 2600.0));
                    double noise = random.nextGaussian() * (subdivision ? 0.10 : 0.18);
                    noisePrev = noisePrev * 0.3 + noise * 0.7;
                    sample = env * (Math.sin(2 * Math.PI * f * t) * 0.78
                            + Math.sin(2 * Math.PI * f * 2.63 * t) * (subdivision ? 0.15 : 0.24)
                            + noisePrev);
                    break;
                }
                case 1: {
                    // Studio click: tight, bright transient.
                    double f = style == STYLE_SUBDIVISION ? 3200
                            : style == STYLE_ACCENT ? 2600 : 1850;
                    double decay = subdivision ? 240.0 : 150.0;
                    double env = Math.exp(-t * decay);
                    double noise = random.nextGaussian() * (subdivision ? 0.12 : 0.24);
                    noisePrev = noise * 0.8 - noisePrev * 0.2;
                    sample = env * (Math.sin(2 * Math.PI * f * t) * 0.62 + noisePrev);
                    break;
                }
                case 2: {
                    // Cowbell: inharmonic metallic partials.
                    double f1 = style == STYLE_SUBDIVISION ? 880
                            : style == STYLE_ACCENT ? 720 : 540;
                    double f2 = style == STYLE_SUBDIVISION ? 1320
                            : style == STYLE_ACCENT ? 1060 : 800;
                    double decay = subdivision ? 38.0 : 17.0;
                    double env = Math.exp(-t * decay) * (1 - Math.exp(-t * 4000.0));
                    double a = Math.sin(2 * Math.PI * f1 * t);
                    double b = Math.sin(2 * Math.PI * f2 * t);
                    double square = Math.tanh(a * 2.2) * 0.45 + Math.tanh(b * 2.0) * 0.36;
                    sample = env * (square + Math.sin(2 * Math.PI * 1710 * t) * 0.12);
                    break;
                }
                case 3: {
                    // Soft beep.
                    double f = style == STYLE_SUBDIVISION ? 1760
                            : style == STYLE_ACCENT ? 1320 : 880;
                    double decay = subdivision ? 60.0 : 34.0;
                    double env = (1 - Math.exp(-t * 1400.0)) * Math.exp(-t * decay);
                    sample = Math.sin(2 * Math.PI * f * t) * env * 0.9;
                    break;
                }
                case 4: {
                    // Drumstick: noise crack plus wooden body.
                    double decay = subdivision ? 260.0 : 180.0;
                    double env = Math.exp(-t * decay);
                    double noise = random.nextGaussian() * (subdivision ? 0.25 : 0.6);
                    noisePrev = noisePrev * 0.15 + noise * 0.85;
                    double f = style == STYLE_SUBDIVISION ? 2400
                            : style == STYLE_ACCENT ? 1900 : 1450;
                    sample = env * (noisePrev * (subdivision ? 0.28 : 0.5)
                            + Math.sin(2 * Math.PI * f * t) * 0.42);
                    break;
                }
                default: {
                    // Mechanical: short tick with a slightly delayed resonator.
                    double decay = subdivision ? 160.0 : 110.0;
                    double env = Math.exp(-t * decay);
                    double delayed = t > 0.004 ? Math.exp(-(t - 0.004) * 80.0) : 0;
                    double noise = random.nextGaussian() * (subdivision ? 0.20 : 0.32);
                    noisePrev = noisePrev * 0.2 + noise * 0.8;
                    double f = style == STYLE_SUBDIVISION ? 1900
                            : style == STYLE_ACCENT ? 1450 : 1120;
                    sample = env * noisePrev
                            + delayed * Math.sin(2 * Math.PI * f * t) * (subdivision ? 0.35 : 0.5)
                            + env * Math.sin(2 * Math.PI * (subdivision ? 760 : 620) * t) * 0.2;
                    break;
                }
            }
            out[i] = (float) sample;
        }
        // Fade the tail to silence so a click can never produce a DC step.
        int fade = Math.min(96, count / 4);
        for (int i = 0; i < fade; i++) {
            out[count - 1 - i] *= (float) i / fade;
        }
        normalize(out, subdivision ? 0.72f : 0.92f);
        return out;
    }

    private static void normalize(float[] data, float peak) {
        float max = 0;
        for (float value : data) max = Math.max(max, Math.abs(value));
        if (max < 1e-6f) return;
        float gain = peak / max;
        for (int i = 0; i < data.length; i++) data[i] *= gain;
    }
}

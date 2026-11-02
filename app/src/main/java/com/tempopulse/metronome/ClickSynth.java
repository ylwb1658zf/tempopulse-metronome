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
 * deterministic timing; both the accent and normal hit are normalised separately.
 */
public final class ClickSynth {
    public static final int SOUND_COUNT = 6;

    private static final Map<String, Bank> CACHE = new HashMap<>();
    private static final Object LOCK = new Object();

    private ClickSynth() {}

    public static final class Bank {
        public final float[] normal;
        public final float[] accent;

        Bank(float[] normal, float[] accent) {
            this.normal = normal;
            this.accent = accent;
        }
    }

    public static Bank bank(int sound, int sampleRate) {
        sound = Math.max(0, Math.min(SOUND_COUNT - 1, sound));
        String key = sound + "@" + sampleRate;
        synchronized (LOCK) {
            Bank cached = CACHE.get(key);
            if (cached != null) return cached;
            Bank made = new Bank(build(sound, false, sampleRate), build(sound, true, sampleRate));
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
        try {
            int rate = nativeSampleRate(context);
            float[] samples = bank(sound, rate).accent;
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

    private static float[] build(int sound, boolean accent, int rate) {
        double length;
        switch (sound) {
            case 2: length = 0.18; break;
            case 3: length = 0.12; break;
            case 5: length = 0.09; break;
            default: length = 0.085; break;
        }
        int count = Math.max(1, (int) (length * rate));
        float[] out = new float[count];
        Random random = new Random(sound * 97L + (accent ? 13 : 0) + rate);
        double noisePrev = 0;

        for (int i = 0; i < count; i++) {
            double t = (double) i / rate;
            double sample;
            switch (sound) {
                case 0: {
                    // Woodblock: hollow fundamental, fast woody decay.
                    double f = accent ? 1280 : 880;
                    double env = Math.exp(-t * 55.0) * (1 - Math.exp(-t * 2600.0));
                    double noise = random.nextGaussian() * 0.18;
                    noisePrev = noisePrev * 0.3 + noise * 0.7;
                    sample = env * (Math.sin(2 * Math.PI * f * t) * 0.78
                            + Math.sin(2 * Math.PI * f * 2.63 * t) * 0.24
                            + noisePrev);
                    break;
                }
                case 1: {
                    // Studio click: tight, bright transient.
                    double f = accent ? 2600 : 1850;
                    double env = Math.exp(-t * 150.0);
                    double noise = random.nextGaussian() * 0.24;
                    noisePrev = noise * 0.8 - noisePrev * 0.2;
                    sample = env * (Math.sin(2 * Math.PI * f * t) * 0.62 + noisePrev);
                    break;
                }
                case 2: {
                    // Cowbell: inharmonic metallic partials.
                    double f1 = accent ? 720 : 540;
                    double f2 = accent ? 1060 : 800;
                    double env = Math.exp(-t * 17.0) * (1 - Math.exp(-t * 4000.0));
                    double a = Math.sin(2 * Math.PI * f1 * t);
                    double b = Math.sin(2 * Math.PI * f2 * t);
                    double square = Math.tanh(a * 2.2) * 0.45 + Math.tanh(b * 2.0) * 0.36;
                    sample = env * (square + Math.sin(2 * Math.PI * 1710 * t) * 0.12);
                    break;
                }
                case 3: {
                    // Soft beep.
                    double f = accent ? 1320 : 880;
                    double env = (1 - Math.exp(-t * 1400.0)) * Math.exp(-t * 34.0);
                    sample = Math.sin(2 * Math.PI * f * t) * env * 0.9;
                    break;
                }
                case 4: {
                    // Drumstick: noise crack plus wooden body.
                    double env = Math.exp(-t * 180.0);
                    double noise = random.nextGaussian() * 0.6;
                    noisePrev = noisePrev * 0.15 + noise * 0.85;
                    sample = env * (noisePrev * 0.5
                            + Math.sin(2 * Math.PI * (accent ? 1900 : 1450) * t) * 0.42);
                    break;
                }
                default: {
                    // Mechanical: short tick with a slightly delayed resonator.
                    double env = Math.exp(-t * 110.0);
                    double delayed = t > 0.004 ? Math.exp(-(t - 0.004) * 80.0) : 0;
                    double noise = random.nextGaussian() * 0.32;
                    noisePrev = noisePrev * 0.2 + noise * 0.8;
                    sample = env * noisePrev
                            + delayed * Math.sin(2 * Math.PI * (accent ? 1450 : 1120) * t) * 0.5
                            + env * Math.sin(2 * Math.PI * 620 * t) * 0.2;
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
        normalize(out, 0.92f);
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

package com.tempopulse.metronome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.Arrays;

/**
 * Sample-clock metronome. The audio thread renders a continuous stream and places clicks
 * on exact frame positions, so tempo accuracy does not depend on thread wake-up jitter.
 */
public final class MetronomeEngine {
    public interface Listener {
        void onTick(int beat, int subdivisionIndex, int beatMode);
        void onError(String message);
    }

    private static final int CHUNK_FRAMES = 256;
    private static final int MAX_VOICES = 8;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private volatile int bpm = 120;
    private volatile int numerator = 4;
    private volatile int subdivision = 1;
    private volatile int sound = 0;
    private volatile float volume = 0.82f;
    private volatile int visualOffsetMs = 0;
    private volatile int[] beatModes = new int[]{1, 0, 0, 0};
    private volatile Listener listener;

    private volatile boolean running;
    private int generation;
    private Thread audioThread;
    private AudioTrack track;

    public MetronomeEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void configure(int bpm, int numerator, int subdivision, int sound,
                          float volume, int[] modes, int visualOffsetMs) {
        this.bpm = Prefs.clampBpm(bpm);
        this.numerator = Math.max(1, Math.min(12, numerator));
        this.subdivision = Math.max(1, Math.min(4, subdivision));
        this.sound = Math.max(0, Math.min(ClickSynth.SOUND_COUNT - 1, sound));
        this.volume = Math.max(0f, Math.min(1f, volume));
        this.visualOffsetMs = Math.max(-100, Math.min(100, visualOffsetMs));
        int[] copy = modes == null ? new int[]{1} : Arrays.copyOf(modes, modes.length);
        this.beatModes = copy;
    }

    public void setBpm(int bpm) {
        this.bpm = Prefs.clampBpm(bpm);
    }

    public void setMeter(int numerator, int[] modes) {
        this.numerator = Math.max(1, Math.min(12, numerator));
        this.beatModes = modes == null ? new int[]{1} : Arrays.copyOf(modes, modes.length);
    }

    public void setSubdivision(int subdivision) {
        this.subdivision = Math.max(1, Math.min(4, subdivision));
    }

    public void setSound(int sound) {
        this.sound = Math.max(0, Math.min(ClickSynth.SOUND_COUNT - 1, sound));
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
    }

    public void setVisualOffsetMs(int offset) {
        this.visualOffsetMs = Math.max(-100, Math.min(100, offset));
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        synchronized (lock) {
            if (running) return;
            if (audioThread != null && audioThread.isAlive()) return;
            running = true;
            final int token = ++generation;
            audioThread = new Thread(() -> audioLoop(token), "MetronomeAudio");
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();
        }
    }

    public void stop() {
        Thread thread;
        synchronized (lock) {
            if (!running && audioThread == null) return;
            running = false;
            generation++;
            AudioTrack current = track;
            if (current != null) {
                try {
                    current.pause();
                    current.flush();
                } catch (Exception ignored) {
                }
            }
            thread = audioThread;
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            if (!running) audioThread = null;
        }
    }

    public void release() {
        stop();
        listener = null;
    }

    private void audioLoop(int token) {
        int rate = ClickSynth.nativeSampleRate(context);
        ClickSynth.Bank bank = ClickSynth.bank(sound, rate);
        int bankSound = sound;
        AudioTrack local = null;
        Voice[] voices = new Voice[MAX_VOICES];
        for (int i = 0; i < voices.length; i++) voices[i] = new Voice();

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            int minBytes = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) minBytes = rate / 10 * 2;
            int targetFrames = Math.max(minBytes / 2, rate / 25);
            targetFrames = Math.max(targetFrames, CHUNK_FRAMES * 4);

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(targetFrames * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            local = builder.build();
            if (local.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioTrack not initialized");
            }
            synchronized (lock) {
                if (!running || token != generation) {
                    local.release();
                    return;
                }
                track = local;
            }
            local.play();

            short[] pcm = new short[CHUNK_FRAMES];
            float[] mix = new float[CHUNK_FRAMES];
            long absFrame = 0;
            double nextClickFrame = 0;
            int beat = 0;
            int sub = 0;
            int lastScheduledFrame = -1;

            while (running && token == generation) {
                if (sound != bankSound) {
                    bankSound = sound;
                    bank = ClickSynth.bank(bankSound, rate);
                }
                Arrays.fill(mix, 0f);

                long chunkEnd = absFrame + CHUNK_FRAMES;
                while (nextClickFrame < chunkEnd) {
                    long clickFrame = Math.round(nextClickFrame);
                    if (clickFrame < absFrame) clickFrame = absFrame;
                    int safeNum = Math.max(1, numerator);
                    int[] modes = beatModes;
                    int mode = modes.length == 0 ? 0 : modes[Math.min(beat, modes.length - 1)];
                    boolean mainBeat = sub == 0;
                    boolean muted = mode == 2;

                    if (!muted) {
                        float gain = volume;
                        if (mainBeat) {
                            gain *= mode == 1 ? 1.0f : 0.68f;
                        } else {
                            gain *= 0.46f;
                        }
                        addVoice(voices, bank, mainBeat && mode == 1, clickFrame, gain);
                    }

                    scheduleVisual(token, clickFrame, beat, sub, mode, local, rate);
                    lastScheduledFrame = (int) Math.min(Integer.MAX_VALUE, nextClickFrame);

                    sub++;
                    if (sub >= Math.max(1, subdivision)) {
                        sub = 0;
                        beat = (beat + 1) % safeNum;
                    }
                    double framesPerBeat = rate * 60.0 / Math.max(1, bpm);
                    nextClickFrame += framesPerBeat / Math.max(1, subdivision);
                }

                renderVoices(voices, absFrame, mix);
                for (int i = 0; i < CHUNK_FRAMES; i++) {
                    float value = mix[i];
                    if (value > 1f) value = 1f;
                    else if (value < -1f) value = -1f;
                    pcm[i] = (short) (value * 32767f);
                }

                int written = 0;
                while (written < pcm.length) {
                    int result = local.write(pcm, written, pcm.length - written,
                            AudioTrack.WRITE_BLOCKING);
                    if (result < 0) throw new IllegalStateException("Audio write failed: " + result);
                    if (result == 0) break;
                    written += result;
                }
                absFrame += CHUNK_FRAMES;
            }
        } catch (Throwable error) {
            if (token == generation && running) {
                final String message = error.getMessage() == null ? "audio" : error.getMessage();
                main.post(() -> {
                    Listener current = listener;
                    if (current != null && token == generation) current.onError(message);
                });
            }
        } finally {
            synchronized (lock) {
                if (track == local) track = null;
                if (audioThread == Thread.currentThread()) audioThread = null;
                if (token == generation) running = false;
            }
            if (local != null) {
                try {
                    local.stop();
                } catch (Exception ignored) {
                }
                try {
                    local.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void addVoice(Voice[] voices, ClickSynth.Bank bank, boolean accent,
                          long startFrame, float gain) {
        if (gain <= 0.0001f) return;
        Voice free = null;
        for (Voice voice : voices) {
            if (!voice.active) {
                free = voice;
                break;
            }
        }
        if (free == null) free = voices[0];
        free.data = accent ? bank.accent : bank.normal;
        free.startFrame = startFrame;
        free.gain = gain;
        free.active = true;
    }

    private void renderVoices(Voice[] voices, long chunkStart, float[] mix) {
        long chunkEnd = chunkStart + CHUNK_FRAMES;
        for (Voice voice : voices) {
            if (!voice.active) continue;
            long voiceEnd = voice.startFrame + voice.data.length;
            if (voiceEnd <= chunkStart) {
                voice.active = false;
                continue;
            }
            int from = (int) Math.max(chunkStart, voice.startFrame);
            int to = (int) Math.min(chunkEnd, voiceEnd);
            for (int frame = from; frame < to; frame++) {
                int mixIndex = (int) (frame - chunkStart);
                int sampleIndex = (int) (frame - voice.startFrame);
                mix[mixIndex] += voice.data[sampleIndex] * voice.gain;
            }
            if (voiceEnd <= chunkEnd) voice.active = false;
        }
    }

    private void scheduleVisual(int token, long clickFrame, int beat, int sub, int mode,
                                AudioTrack local, int rate) {
        long head = 0;
        try {
            head = local.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        } catch (Exception ignored) {
        }
        long delayMs = Math.round((clickFrame - head) * 1000.0 / rate);
        delayMs += visualOffsetMs;
        if (delayMs < 0) delayMs = 0;
        if (delayMs > 1500) delayMs = 1500;
        final int shownBeat = beat;
        final int shownSub = sub;
        final int shownMode = mode;
        main.postDelayed(() -> {
            if (!running || token != generation) return;
            Listener current = listener;
            if (current != null) current.onTick(shownBeat, shownSub, shownMode);
        }, delayMs);
    }

    private static final class Voice {
        float[] data;
        long startFrame;
        float gain;
        boolean active;
    }
}

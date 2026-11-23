package com.tempopulse.metronome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

/** Foreground service that owns the audio engine, notification and beat haptics. */
public final class MetronomeService extends Service {
    public static final String ACTION_START = "com.tempopulse.metronome.START";
    public static final String ACTION_STOP = "com.tempopulse.metronome.STOP";
    public static final String ACTION_TOGGLE = "com.tempopulse.metronome.TOGGLE";

    private static final String CHANNEL_ID = "metronome_playback";
    private static final int NOTIFICATION_ID = 101;
    private static final long NOTIFICATION_THROTTLE_MS = 280;

    public interface Listener {
        void onPlaybackState(boolean playing);
        void onTick(int beat, int subdivisionIndex, int beatMode);
        void onError(String message);

        default void onBpmChanged(int bpm) {
        }

        default void onTrainingState(boolean active, boolean reachedTarget, int remainingSeconds) {
        }
    }

    public final class LocalBinder extends Binder {
        public MetronomeService service() {
            return MetronomeService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private MetronomeEngine engine;
    private Listener listener;
    private boolean playing;
    private boolean bound;
    private int currentBeat = -1;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;
    private long lastNotificationUpdate;
    private boolean notificationUpdatePosted;

    private boolean trainingActive;
    private boolean trainingReached;
    private int trainMode;
    private int trainIntervalSec;
    private int trainBars;
    private int trainIncrementType;
    private int trainIncrement;
    private int trainTarget;
    private int practiceDurationSec;
    private int trainBarCount;
    private long practiceStartedAt;
    private long lastAdvanceAt;

    private final Runnable practiceTicker = new Runnable() {
        @Override
        public void run() {
            if (!trainingActive || !playing) return;
            long now = SystemClock.elapsedRealtime();
            if (trainMode == 0 && now - lastAdvanceAt >= trainIntervalSec * 1000L) {
                advanceTraining();
                lastAdvanceAt = now;
            }
            long elapsed = (now - practiceStartedAt) / 1000L;
            int remaining = Math.max(0, practiceDurationSec - (int) elapsed);
            Listener current = listener;
            if (current != null) current.onTrainingState(true, trainingReached, remaining);
            if (remaining <= 0) {
                finishPracticeAndPlayback();
                return;
            }
            main.postDelayed(this, 250);
        }
    };

    private final Runnable notificationUpdater = () -> {
        notificationUpdatePosted = false;
        lastNotificationUpdate = System.currentTimeMillis();
        if (playing) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        }
    };

    private final MetronomeEngine.Listener engineListener = new MetronomeEngine.Listener() {
        @Override
        public void onTick(int beat, int subdivisionIndex, int beatMode) {
            if (!playing) return;
            if (subdivisionIndex == 0) {
                currentBeat = beat;
                if (prefs.haptic() && beatMode != 2) {
                    vibrateBeat(beatMode == 1);
                }
            }
            Listener current = listener;
            if (current != null) current.onTick(beat, subdivisionIndex, beatMode);
            if (trainingActive && subdivisionIndex == 0 && trainMode == 1
                    && beat == prefs.numerator() - 1) {
                trainBarCount++;
                if (trainBarCount >= trainBars) {
                    trainBarCount = 0;
                    advanceTraining();
                }
            }
        }

        @Override
        public void onError(String message) {
            Listener current = listener;
            if (current != null) current.onError(message);
            main.post(() -> endPlayback(true));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        engine = new MetronomeEngine(this);
        engine.setListener(engineListener);
        createChannel();
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TempoPulse::Metronome");
            wakeLock.setReferenceCounted(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) vibrator = manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            beginPlayback();
        } else if (ACTION_STOP.equals(action)) {
            endPlayback(true);
        } else if (ACTION_TOGGLE.equals(action)) {
            if (playing) endPlayback(true);
            else beginPlayback();
        } else if (!playing && !bound) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        bound = true;
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        bound = false;
        if (!playing) stopSelf();
        return false;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        main.removeCallbacks(practiceTicker);
        if (engine != null) engine.release();
        listener = null;
        super.onDestroy();
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onPlaybackState(playing);
            if (trainingActive) {
                int remaining = remainingPracticeSeconds();
                listener.onTrainingState(true, trainingReached, remaining);
            }
        }
    }

    public boolean isTrainingActive() {
        return trainingActive;
    }

    public void startPractice(int mode, int intervalSec, int bars, int incrementType,
                              int increment, int target, int durationSec) {
        trainMode = mode == 1 ? 1 : 0;
        trainIntervalSec = Math.max(5, Math.min(600, intervalSec));
        trainBars = Math.max(1, Math.min(32, bars));
        trainIncrementType = incrementType == 1 ? 1 : 0;
        trainIncrement = Math.max(1, Math.min(50, increment));
        trainTarget = Prefs.clampBpm(target);
        practiceDurationSec = Math.max(10, Math.min(7200, durationSec));
        trainingReached = false;
        trainBarCount = 0;
        if (!playing) beginPlayback();
        if (!playing) return;
        trainingActive = true;
        practiceStartedAt = SystemClock.elapsedRealtime();
        lastAdvanceAt = practiceStartedAt;
        main.removeCallbacks(practiceTicker);
        main.post(practiceTicker);
        notifyTrainingState();
    }

    public void stopPractice(boolean keepPlaying) {
        if (!trainingActive) return;
        trainingActive = false;
        trainingReached = false;
        main.removeCallbacks(practiceTicker);
        notifyTrainingState();
        if (!keepPlaying) endPlayback(true);
    }

    public int remainingPracticeSeconds() {
        if (!trainingActive) return 0;
        long elapsed = (SystemClock.elapsedRealtime() - practiceStartedAt) / 1000L;
        return Math.max(0, practiceDurationSec - (int) elapsed);
    }

    private void advanceTraining() {
        if (!trainingActive || trainingReached) return;
        int current = prefs.bpm();
        int next = trainIncrementType == 0
                ? current + trainIncrement
                : Math.round(current * (1f + trainIncrement / 100f));
        next = Math.min(trainTarget, Math.min(Prefs.MAX_BPM, next));
        if (next <= current) {
            trainingReached = true;
            notifyTrainingState();
            return;
        }
        prefs.setBpm(next);
        engine.setBpm(next);
        scheduleNotificationUpdate();
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onBpmChanged(next);
        if (next >= trainTarget) trainingReached = true;
        notifyTrainingState();
    }

    private void notifyTrainingState() {
        Listener current = listener;
        if (current == null) return;
        current.onTrainingState(trainingActive, trainingReached,
                trainingActive ? remainingPracticeSeconds() : 0);
    }

    private void finishPracticeAndPlayback() {
        trainingActive = false;
        trainingReached = false;
        main.removeCallbacks(practiceTicker);
        notifyTrainingState();
        endPlayback(true);
    }

    public void beginPlayback() {
        if (playing) {
            refreshFromPrefs();
            updateNotificationNow();
            return;
        }
        refreshFromPrefs();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Listener current = listener;
            if (current != null) current.onError(getString(R.string.audio_error));
            stopSelf();
            return;
        }
        acquireWakeLock();
        playing = true;
        currentBeat = 0;
        engine.start();
        Listener current = listener;
        if (current != null) current.onPlaybackState(true);
    }

    public void endPlayback(boolean stopIfUnbound) {
        boolean wasTraining = trainingActive;
        trainingActive = false;
        trainingReached = false;
        main.removeCallbacks(practiceTicker);
        if (wasTraining) notifyTrainingState();
        if (playing) {
            engine.stop();
            playing = false;
            currentBeat = -1;
            Listener current = listener;
            if (current != null) current.onPlaybackState(false);
        }
        releaseWakeLock();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        if (stopIfUnbound && !bound) stopSelf();
    }

    public void refreshFromPrefs() {
        int[] modes = prefs.beatModes();
        engine.configure(prefs.bpm(), prefs.numerator(), prefs.subdivision(),
                prefs.sound(), prefs.volume(), modes, prefs.visualOffsetMs());
    }

    public void setBpmLive(int bpm) {
        engine.setBpm(bpm);
        scheduleNotificationUpdate();
    }

    public void setMeterLive(int numerator, int[] modes) {
        engine.setMeter(numerator, modes);
        scheduleNotificationUpdate();
    }

    public void setSubdivisionLive(int subdivision) {
        engine.setSubdivision(subdivision);
    }

    public void setSoundLive(int sound) {
        engine.setSound(sound);
    }

    public void setVolumeLive(float volume) {
        engine.setVolume(volume);
    }

    public void setVisualOffsetLive(int offsetMs) {
        engine.setVisualOffsetMs(offsetMs);
    }

    public void previewSound(int sound) {
        ClickSynth.preview(this, sound, prefs.volume());
    }

    public void notifyPrefsChanged() {
        if (playing) scheduleNotificationUpdate();
    }

    private void scheduleNotificationUpdate() {
        if (!playing || notificationUpdatePosted) return;
        long delta = System.currentTimeMillis() - lastNotificationUpdate;
        if (delta >= NOTIFICATION_THROTTLE_MS) {
            updateNotificationNow();
        } else {
            notificationUpdatePosted = true;
            main.postDelayed(notificationUpdater, NOTIFICATION_THROTTLE_MS - delta);
        }
    }

    private void updateNotificationNow() {
        if (!playing) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        lastNotificationUpdate = System.currentTimeMillis();
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 1, open, flags);

        Intent toggle = new Intent(this, MetronomeService.class).setAction(ACTION_TOGGLE);
        PendingIntent toggleIntent = PendingIntent.getService(this, 2, toggle, flags);

        String signature = prefs.numerator() + "/" + prefs.denominator();
        String detail = getString(R.string.notification_text, prefs.bpm(), signature);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_metronome)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(detail)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(Palette.create(this, prefs).accent)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stop, getString(R.string.notification_stop), toggleIntent).build());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }

    private void vibrateBeat(boolean accent) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int amplitude = accent ? 210 : 105;
                long duration = accent ? 30 : 16;
                vibrator.vibrate(VibrationEffect.createOneShot(duration,
                        Math.max(1, Math.min(255, amplitude))));
            } else {
                vibrator.vibrate(accent ? 30 : 16);
            }
        } catch (Exception ignored) {
        }
    }
}

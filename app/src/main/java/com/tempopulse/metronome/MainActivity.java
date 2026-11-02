package com.tempopulse.metronome;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 91;

    private Prefs prefs;
    private Palette palette;
    private MetronomeService service;
    private boolean bound;
    private boolean playing;

    private FrameLayout rootView;
    private LinearLayout shell;
    private TempoDialView dial;
    private BeatDotsView dots;
    private PlayButtonView playButton;
    private TextView statusView;
    private View statusDot;
    private TextView hintView;
    private TextView sigValue;
    private TextView subValue;
    private TextView soundValue;
    private TextView tapButton;
    private View flashOverlay;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Long> tapTimes = new ArrayList<>();
    private long lastTap = 0;
    private boolean notificationRequested;
    private Runnable hintReset;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MetronomeService.LocalBinder) binder).service();
            bound = true;
            service.setListener(serviceListener);
            syncFromPrefs();
            setPlaybackUi(service.isPlaying());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    private final MetronomeService.Listener serviceListener = new MetronomeService.Listener() {
        @Override
        public void onPlaybackState(boolean isPlaying) {
            ui.post(() -> setPlaybackUi(isPlaying));
        }

        @Override
        public void onTick(int beat, int subdivisionIndex, int beatMode) {
            ui.post(() -> handleTick(beat, subdivisionIndex, beatMode));
        }

        @Override
        public void onError(String message) {
            ui.post(() -> {
                Toast.makeText(MainActivity.this, R.string.audio_error, Toast.LENGTH_LONG).show();
                statusView.setText(R.string.status_error);
                setPlaybackUi(false);
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        palette = Palette.create(this, prefs);
        AppFonts.init(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(palette.bg));
        setContentView(buildRoot());
        configureSystemBars();
        applyInsets();
        syncFromPrefs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, MetronomeService.class);
        bindService(intent, connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (bound) {
            if (service != null) service.setListener(null);
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        configureSystemBars();
        applyKeepScreenOn();
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            if (service != null) service.setListener(null);
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        ui.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (prefs.themeMode() == Prefs.THEME_SYSTEM) recreate();
    }

    private View buildRoot() {
        rootView = new FrameLayout(this);
        BackdropView backdrop = new BackdropView(this, palette);
        rootView.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenWidth, Ui.dp(this, 640));
        FrameLayout.LayoutParams shellParams = new FrameLayout.LayoutParams(width,
                ViewGroup.LayoutParams.MATCH_PARENT);
        shellParams.gravity = Gravity.CENTER_HORIZONTAL;
        rootView.addView(shell, shellParams);

        shell.addView(buildTopBar());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        shell.addView(scroll, scrollParams);

        LinearLayout page = Ui.column(this);
        page.setPadding(0, 0, 0, Ui.dp(this, 18));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int density = Math.max(1, (int) getResources().getDisplayMetrics().density);
        int screenHeightDp = getResources().getDisplayMetrics().heightPixels / density;
        int dialDp = Math.min(330, Math.max(268, screenHeightDp - 480));
        dial = new TempoDialView(this, palette);
        page.addView(dial, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, dialDp), 0, Ui.dp(this, 2), 0, 0));

        dots = new BeatDotsView(this, palette);
        page.addView(dots, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 46), 0, Ui.dp(this, 1), 0, 0));

        hintView = Ui.text(this, getString(R.string.hint_beat), 11.5f, palette.faint,
                AppFonts.regular(this), Gravity.CENTER);
        hintView.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        page.addView(hintView, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 24)));

        page.addView(buildChips(), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 52), 0, Ui.dp(this, 14), 0, 0));

        page.addView(buildControls(), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 108), 0, Ui.dp(this, 8), 0, 0));

        LinearLayout statusRow = Ui.row(this);
        statusRow.setGravity(Gravity.CENTER);
        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(palette.faint);
        statusDot.setBackground(dot);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 6), Ui.dp(this, 6));
        dotParams.rightMargin = Ui.dp(this, 7);
        statusRow.addView(statusDot, dotParams);
        statusView = Ui.text(this, getString(R.string.status_ready), 12, palette.dim,
                AppFonts.regular(this), Gravity.CENTER);
        statusRow.addView(statusView);
        page.addView(statusRow, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 26)));

        tapButton = Ui.label(this, getString(R.string.tap_tempo), 12, palette.text,
                Gravity.CENTER);
        tapButton.setLetterSpacing(0.13f);
        tapButton.setBackground(Ui.rippleRound(this, palette.surface, palette.accentSoft, 23,
                Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
        tapButton.setOnClickListener(v -> tapTempo());
        tapButton.setContentDescription(getString(R.string.tap_tempo));
        page.addView(tapButton, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 46), 0, Ui.dp(this, 12), 0, 0));

        LinearLayout footer = Ui.row(this);
        footer.setGravity(Gravity.CENTER);
        View engineDot = new View(this);
        GradientDrawable engine = new GradientDrawable();
        engine.setShape(GradientDrawable.OVAL);
        engine.setColor(palette.success);
        engineDot.setBackground(engine);
        LinearLayout.LayoutParams engineParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 5), Ui.dp(this, 5));
        engineParams.rightMargin = Ui.dp(this, 7);
        footer.addView(engineDot, engineParams);
        footer.addView(Ui.text(this, getString(R.string.footer_engine), 9.5f,
                palette.faint, AppFonts.regular(this), Gravity.CENTER));
        page.addView(footer, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 22)));

        dial.setListener((bpm, committed) -> {
            if (committed) prefs.setBpm(bpm);
            if (service != null) service.setBpmLive(bpm);
        });
        dots.setListener((beat, mode) -> {
            int[] modes = prefs.beatModes();
            if (beat >= 0 && beat < modes.length) {
                modes[beat] = mode;
                prefs.setBeatModes(modes);
                if (service != null) service.setMeterLive(prefs.numerator(), modes);
                showBeatModeHint(beat, mode);
            }
        });
        playButton.setOnClickListener(v -> togglePlayback());

        flashOverlay = new View(this);
        flashOverlay.setBackgroundColor(palette.accent);
        flashOverlay.setAlpha(0f);
        flashOverlay.setClickable(false);
        flashOverlay.setFocusable(false);
        flashOverlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        rootView.addView(flashOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return rootView;
    }

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));

        FrameLayout logoWrap = new FrameLayout(this);
        logoWrap.setBackground(Ui.roundRect(this, palette.surfaceAlt, 13,
                Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
        ImageView logo = Ui.icon(this, R.drawable.ic_metronome, palette.accent, 25);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 25), Ui.dp(this, 25));
        logoParams.gravity = Gravity.CENTER;
        logoWrap.addView(logo, logoParams);
        bar.addView(logoWrap, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 11), 0, 0, 0);
        titles.addView(Ui.label(this, getString(R.string.screen_title), 18,
                palette.text, Gravity.START));
        TextView tagline = Ui.caps(this, getString(R.string.tagline), 7.2f,
                palette.faint, Gravity.START);
        tagline.setPadding(0, Ui.dp(this, 4), 0, 0);
        titles.addView(tagline);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(Ui.iconButton(this, R.drawable.ic_bookmark, palette.dim,
                palette, getString(R.string.presets), v -> showPresetsSheet()));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 44), Ui.dp(this, 44));
        settingsParams.leftMargin = Ui.dp(this, 1);
        bar.addView(Ui.iconButton(this, R.drawable.ic_tune, palette.dim,
                palette, getString(R.string.settings), v -> showSettingsSheet()), settingsParams);
        return bar;
    }

    private View buildChips() {
        LinearLayout row = Ui.row(this);
        sigValue = chipValue();
        subValue = chipValue();
        soundValue = chipValue();
        row.addView(makeChip(getString(R.string.signature), sigValue,
                this::showSignatureSheet), chipParams());
        row.addView(makeChip(getString(R.string.subdivision), subValue,
                this::showSubdivisionSheet), chipParams());
        row.addView(makeChip(getString(R.string.sound), soundValue,
                this::showSoundSheet), chipParams());
        return row;
    }

    private TextView chipValue() {
        TextView value = Ui.label(this, "—", 14, palette.text, Gravity.START);
        Ui.setSingleLine(value);
        return value;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                Ui.dp(this, 52), 1f);
        params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        return params;
    }

    private View makeChip(String label, TextView valueView, Runnable action) {
        LinearLayout chip = Ui.row(this);
        chip.setBackground(Ui.rippleRound(this, palette.surface, palette.accentSoft, 16,
                Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
        chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 8), Ui.dp(this, 7));
        LinearLayout text = Ui.column(this);
        text.addView(Ui.caps(this, label, 8.2f, palette.faint, Gravity.START));
        text.addView(valueView);
        chip.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = Ui.text(this, "›", 17, palette.faint,
                AppFonts.regular(this), Gravity.CENTER);
        chip.addView(chevron);
        chip.setOnClickListener(v -> action.run());
        chip.setContentDescription(label);
        return chip;
    }

    private View buildControls() {
        FrameLayout controls = new FrameLayout(this);

        TextView minus = stepButton("−");
        minus.setContentDescription(getString(R.string.decrease_tempo));
        FrameLayout.LayoutParams minusParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 58), Ui.dp(this, 58));
        minusParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        minusParams.leftMargin = Ui.dp(this, 6);
        controls.addView(minus, minusParams);
        minus.setOnClickListener(v -> changeBpm(-1));
        bindRepeat(minus, () -> changeBpm(-1));

        TextView plus = stepButton("+");
        plus.setContentDescription(getString(R.string.increase_tempo));
        FrameLayout.LayoutParams plusParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 58), Ui.dp(this, 58));
        plusParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        plusParams.rightMargin = Ui.dp(this, 6);
        controls.addView(plus, plusParams);
        plus.setOnClickListener(v -> changeBpm(1));
        bindRepeat(plus, () -> changeBpm(1));

        playButton = new PlayButtonView(this, palette);
        FrameLayout.LayoutParams playParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 96), Ui.dp(this, 96));
        playParams.gravity = Gravity.CENTER;
        controls.addView(playButton, playParams);
        return controls;
    }

    private TextView stepButton(String symbol) {
        TextView view = Ui.text(this, symbol, 28, palette.text,
                AppFonts.light(this), Gravity.CENTER);
        view.setBackground(Ui.rippleRound(this, palette.surface,
                palette.accentSoft, 29,
                Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private void bindRepeat(View view, Runnable action) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            action.run();
            handler.postDelayed(repeat[0], 65);
        };
        view.setOnLongClickListener(v -> {
            action.run();
            handler.postDelayed(repeat[0], 280);
            return true;
        });
        view.setOnTouchListener((v, event) -> {
            int actionMask = event.getActionMasked();
            if (actionMask == android.view.MotionEvent.ACTION_UP
                    || actionMask == android.view.MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(repeat[0]);
            }
            return false;
        });
    }

    private void applyInsets() {
        rootView.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            shell.setPadding(left + Ui.dp(this, 20), top + Ui.dp(this, 5),
                    right + Ui.dp(this, 20), bottom + Ui.dp(this, 8));
            return insets;
        });
        rootView.requestApplyInsets();
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        boolean light = !palette.dark;
        int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lightFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (light) flags |= lightFlags;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void syncFromPrefs() {
        if (dial == null) return;
        dial.setBpm(prefs.bpm(), false);
        dots.setBeats(prefs.numerator(), prefs.beatModes());
        updateChipValues();
        applyKeepScreenOn();
    }

    private void updateChipValues() {
        int beats = prefs.numerator();
        int denominator = prefs.denominator();
        String[] shortNames = getResources().getStringArray(R.array.subdivision_short);
        String[] soundNames = getResources().getStringArray(R.array.sound_names);
        sigValue.setText(getString(R.string.time_signature_format, beats, denominator));
        subValue.setText(shortNames[Math.max(0,
                Math.min(shortNames.length - 1, prefs.subdivision() - 1))]);
        soundValue.setText(soundNames[Math.max(0,
                Math.min(soundNames.length - 1, prefs.sound()))]);
        if (dots != null) dots.setBeats(beats, prefs.beatModes());
        if (dial != null) dial.setBpm(prefs.bpm(), false);
    }

    private void setPlaybackUi(boolean isPlaying) {
        playing = isPlaying;
        if (playButton != null) playButton.setPlaying(playing);
        if (dots != null) dots.setPlaying(playing);
        if (statusDot != null) {
            statusDot.setBackground(oval(playing ? palette.success : palette.faint));
        }
        if (statusView != null) {
            statusView.setText(playing ? R.string.status_playing : R.string.status_ready);
            statusView.setTextColor(playing ? palette.text : palette.dim);
        }
        applyKeepScreenOn();
    }

    private void applyKeepScreenOn() {
        if (playing && prefs.keepAwake()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void handleTick(int beat, int subdivisionIndex, int beatMode) {
        if (!playing) return;
        if (subdivisionIndex == 0) {
            dots.setCurrentBeat(beat);
            playButton.beatPulse();
            dial.pulse(beatMode);
            statusView.setText(getString(R.string.status_beat, beat + 1));
            if (prefs.flash()) flash();
        }
    }

    private void flash() {
        flashOverlay.animate().cancel();
        flashOverlay.setAlpha(palette.dark ? 0.16f : 0.10f);
        flashOverlay.animate().alpha(0f).setDuration(130)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void showBeatModeHint(int beat, int mode) {
        int label = mode == 1 ? R.string.mode_accent : mode == 2 ? R.string.mode_mute
                : R.string.mode_normal;
        hintView.setText(getString(R.string.hint_beat_mode, beat + 1, getString(label)));
        hintView.setTextColor(mode == 2 ? palette.faint : palette.accent);
        if (hintReset != null) ui.removeCallbacks(hintReset);
        hintReset = () -> {
            if (hintView != null) {
                hintView.setText(R.string.hint_beat);
                hintView.setTextColor(palette.faint);
            }
        };
        ui.postDelayed(hintReset, 1600);
        dots.announceForAccessibility(getString(R.string.hint_beat_mode,
                beat + 1, getString(label)));
    }

    private void togglePlayback() {
        if (playing) {
            stopPlayback();
        } else {
            startPlayback();
        }
    }

    private void startPlayback() {
        Intent intent = new Intent(this, MetronomeService.class)
                .setAction(MetronomeService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.audio_error, Toast.LENGTH_LONG).show();
            return;
        }
        ensureNotificationPermission();
    }

    private void stopPlayback() {
        Intent intent = new Intent(this, MetronomeService.class)
                .setAction(MetronomeService.ACTION_STOP);
        startService(intent);
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || notificationRequested) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        notificationRequested = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    private void changeBpm(int delta) {
        int next = Prefs.clampBpm(dial.bpm() + delta);
        dial.setBpm(next, true);
        playButton.animate().cancel();
        playButton.animate().scaleX(0.98f).scaleY(0.98f).setDuration(60)
                .withEndAction(() -> playButton.animate().scaleX(1f).scaleY(1f)
                        .setDuration(100).start()).start();
    }

    private void tapTempo() {
        long now = SystemClock.elapsedRealtime();
        if (lastTap == 0 || now - lastTap > 2000) {
            tapTimes.clear();
        }
        tapTimes.add(now);
        lastTap = now;
        if (tapTimes.size() > 6) tapTimes.remove(0);
        tapButton.animate().cancel();
        tapButton.animate().scaleX(0.98f).scaleY(0.98f).setDuration(55)
                .withEndAction(() -> tapButton.animate().scaleX(1f).scaleY(1f)
                        .setDuration(110).start()).start();
        tapButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        if (tapTimes.size() >= 2) {
            int count = Math.min(4, tapTimes.size() - 1);
            long sum = 0;
            for (int i = tapTimes.size() - count; i < tapTimes.size(); i++) {
                sum += tapTimes.get(i) - tapTimes.get(i - 1);
            }
            double average = sum / (double) count;
            if (average > 100) {
                int bpm = (int) Math.round(60000.0 / average);
                bpm = Prefs.clampBpm(bpm);
                dial.setBpm(bpm, false);
                prefs.setBpm(bpm);
                if (service != null) service.setBpmLive(bpm);
            }
        }
    }

    private GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    // ---------------------------------------------------------------------
    // Sheets
    // ---------------------------------------------------------------------

    private LinearLayout card() {
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14),
                Ui.dp(this, 16), Ui.dp(this, 14));
        card.setBackground(Ui.roundRect(this, palette.surfaceAlt, 20,
                Palette.withAlpha(palette.stroke, 0x88), 0.8f));
        return card;
    }

    private void addSection(LinearLayout body, String label, View content) {
        body.addView(Ui.sectionLabel(this, label, palette));
        body.addView(content, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, Ui.dp(this, 18)));
    }

    private void showSettingsSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.settings),
                getString(R.string.settings_subtitle));
        LinearLayout body = sheet.body();

        LinearLayout appearance = card();
        LinearLayout themeTitle = Ui.row(this);
        themeTitle.addView(Ui.label(this, getString(R.string.theme_mode), 14,
                palette.text, Gravity.START), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        appearance.addView(themeTitle);
        SegmentedView segmented = new SegmentedView(this, palette,
                new String[]{getString(R.string.theme_dark), getString(R.string.theme_light),
                        getString(R.string.theme_system)}, prefs.themeMode());
        segmented.setListener(index -> {
            if (prefs.themeMode() == index) return;
            prefs.setThemeMode(index);
            sheet.dismiss();
            ui.post(this::recreate);
        });
        appearance.addView(segmented, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, Ui.dp(this, 12), 0, 0));

        TextView accentLabel = Ui.label(this, getString(R.string.accent_color), 14,
                palette.text, Gravity.START);
        accentLabel.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 10));
        appearance.addView(accentLabel);
        appearance.addView(buildAccentPicker(sheet));
        addSection(body, getString(R.string.section_appearance), appearance);

        LinearLayout playback = card();
        LinearLayout volumeHeader = Ui.row(this);
        LinearLayout volumeText = Ui.column(this);
        volumeText.addView(Ui.label(this, getString(R.string.volume), 14,
                palette.text, Gravity.START));
        volumeText.addView(Ui.text(this, getString(R.string.volume_desc), 11.5f,
                palette.dim, AppFonts.regular(this), Gravity.START));
        volumeHeader.addView(volumeText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView volumeValue = Ui.label(this, Math.round(prefs.volume() * 100) + "%",
                13, palette.accent, Gravity.END);
        volumeHeader.addView(volumeValue);
        playback.addView(volumeHeader);
        SeekBar volumeSeek = styledSeek(Math.round(prefs.volume() * 100));
        volumeSeek.setContentDescription(getString(R.string.volume));
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText(progress + "%");
                if (fromUser) {
                    prefs.setVolume(progress / 100f);
                    if (service != null) service.setVolumeLive(progress / 100f);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        playback.addView(volumeSeek, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 40), 0, Ui.dp(this, 6), 0, 0));

        addToggleRow(playback, getString(R.string.keep_awake),
                getString(R.string.keep_awake_desc), prefs.keepAwake(), checked -> {
                    prefs.setKeepAwake(checked);
                    applyKeepScreenOn();
                }, true);
        addToggleRow(playback, getString(R.string.vibrate),
                getString(R.string.vibrate_desc), prefs.haptic(), checked -> prefs.setHaptic(checked), true);
        addToggleRow(playback, getString(R.string.flash),
                getString(R.string.flash_desc), prefs.flash(), checked -> prefs.setFlash(checked), false);
        addSection(body, getString(R.string.section_playback), playback);

        LinearLayout info = card();
        info.addView(infoRow(getString(R.string.background_play_title),
                getString(R.string.background_play_desc), palette.success));
        info.addView(Ui.divider(this, palette.stroke), Ui.margins(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1),
                0, Ui.dp(this, 10), 0, Ui.dp(this, 10)));
        info.addView(infoRow(getString(R.string.mix_music_title),
                getString(R.string.mix_music_desc), palette.accent));
        body.addView(info, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, Ui.dp(this, 20)));

        LinearLayout beatSection = card();
        beatSection.addView(Ui.label(this, getString(R.string.accent_help_title), 14,
                palette.text, Gravity.START));
        TextView accentHelp = Ui.text(this, getString(R.string.accent_help_desc), 11.5f,
                palette.dim, AppFonts.regular(this), Gravity.START);
        accentHelp.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 12));
        beatSection.addView(accentHelp);

        LinearLayout offsetHeader = Ui.row(this);
        offsetHeader.addView(Ui.label(this, getString(R.string.visual_offset), 14,
                palette.text, Gravity.START), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView offsetValue = Ui.label(this,
                getString(R.string.offset_value_signed, prefs.visualOffsetMs()), 12,
                palette.accent, Gravity.END);
        offsetHeader.addView(offsetValue);
        beatSection.addView(offsetHeader);
        beatSection.addView(Ui.text(this, getString(R.string.visual_offset_desc), 11.5f,
                palette.dim, AppFonts.regular(this), Gravity.START));
        SeekBar offsetSeek = styledSeek(prefs.visualOffsetMs() + 100, 200);
        offsetSeek.setContentDescription(getString(R.string.visual_offset));
        offsetSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress - 100;
                offsetValue.setText(getString(R.string.offset_value_signed, value));
                if (fromUser) {
                    prefs.setVisualOffsetMs(value);
                    if (service != null) service.setVisualOffsetLive(value);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        beatSection.addView(offsetSeek, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 40), 0, Ui.dp(this, 4), 0, 0));
        TextView reset = Ui.label(this, getString(R.string.reset), 11.5f,
                palette.accent, Gravity.END);
        reset.setPadding(0, Ui.dp(this, 4), 0, 0);
        reset.setOnClickListener(v -> offsetSeek.setProgress(100));
        beatSection.addView(reset);
        addSection(body, getString(R.string.section_beat), beatSection);

        LinearLayout aboutRow = Ui.row(this);
        aboutRow.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        LinearLayout aboutText = Ui.column(this);
        aboutText.addView(Ui.label(this, getString(R.string.settings_privacy), 14,
                palette.text, Gravity.START));
        aboutText.addView(Ui.text(this, getString(R.string.about_subtitle), 11.5f,
                palette.dim, AppFonts.regular(this), Gravity.START));
        aboutRow.addView(aboutText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        aboutRow.addView(Ui.icon(this, R.drawable.ic_chevron_right, palette.faint, 18));
        aboutRow.setBackground(Ui.rippleRound(this, Color.TRANSPARENT,
                palette.accentSoft, 12));
        aboutRow.setOnClickListener(v -> {
            sheet.dismiss();
            showAboutSheet();
        });
        body.addView(aboutRow, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, Ui.dp(this, 4)));
        sheet.showSheet();
    }

    private View buildAccentPicker(Sheet sheet) {
        LinearLayout row = Ui.row(this);
        int[] names = {R.string.accent_amber, R.string.accent_mint,
                R.string.accent_coral, R.string.accent_violet};
        for (int i = 0; i < 4; i++) {
            final int index = i;
            LinearLayout column = Ui.column(this);
            column.setGravity(Gravity.CENTER);
            column.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            FrameLayout circleWrap = new FrameLayout(this);
            View circle = new View(this);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(accentSwatchColor(index));
            fill.setStroke(Ui.dp(this, index == prefs.accentIndex() ? 2 : 0),
                    palette.text);
            circle.setBackground(fill);
            int size = Ui.dp(this, index == prefs.accentIndex() ? 32 : 28);
            FrameLayout.LayoutParams circleParams = new FrameLayout.LayoutParams(size, size);
            circleParams.gravity = Gravity.CENTER;
            circleWrap.addView(circle, circleParams);
            if (index == prefs.accentIndex()) {
                ImageView check = Ui.icon(this, R.drawable.ic_check, palette.onAccent, 15);
                FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(
                        Ui.dp(this, 15), Ui.dp(this, 15));
                checkParams.gravity = Gravity.CENTER;
                circleWrap.addView(check, checkParams);
            }
            column.addView(circleWrap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 36)));
            TextView label = Ui.text(this, getString(names[i]), 10, palette.dim,
                    AppFonts.regular(this), Gravity.CENTER);
            label.setPadding(0, Ui.dp(this, 1), 0, 0);
            column.addView(label);
            column.setBackground(Ui.rippleRound(this, Color.TRANSPARENT,
                    palette.accentSoft, 12));
            column.setOnClickListener(v -> {
                if (prefs.accentIndex() == index) return;
                prefs.setAccentIndex(index);
                sheet.dismiss();
                ui.post(this::recreate);
            });
            row.addView(column, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private int accentSwatchColor(int index) {
        if (palette.dark) {
            int[] colors = {0xFFF7B955, 0xFF53E0B7, 0xFFFF7F8A, 0xFFB49BFF};
            return colors[Math.max(0, Math.min(3, index))];
        }
        int[] colors = {0xFFD97C12, 0xFF0B9A70, 0xFFD94F5D, 0xFF7153D3};
        return colors[Math.max(0, Math.min(3, index))];
    }

    private SeekBar styledSeek(int progress) {
        return styledSeek(progress, 100);
    }

    private SeekBar styledSeek(int progress, int max) {
        SeekBar seek = new SeekBar(this);
        seek.setMax(Math.max(1, max));
        seek.setProgress(Math.max(0, Math.min(max, progress)));
        seek.setProgressTintList(ColorStateList.valueOf(palette.accent));
        seek.setThumbTintList(ColorStateList.valueOf(palette.accent));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(
                Palette.withAlpha(palette.faint, 0x60)));
        seek.setPadding(0, 0, 0, 0);
        seek.setSplitTrack(false);
        return seek;
    }

    private void addToggleRow(LinearLayout parent, String title, String desc,
                              boolean initial, final ToggleListener listener,
                              boolean divider) {
        if (divider) {
            parent.addView(Ui.divider(this, Palette.withAlpha(palette.stroke, 0x99)),
                    Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1),
                            0, Ui.dp(this, 8), 0, Ui.dp(this, 8)));
        }
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
        LinearLayout text = Ui.column(this);
        text.addView(Ui.label(this, title, 13.5f, palette.text, Gravity.START));
        TextView description = Ui.text(this, desc, 11, palette.dim,
                AppFonts.regular(this), Gravity.START);
        description.setPadding(0, Ui.dp(this, 3), Ui.dp(this, 10), 0);
        text.addView(description);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ToggleView toggle = new ToggleView(this, palette, initial);
        toggle.setLabel(title);
        toggle.setOnCheckedChangeListener((view, checked) -> listener.onChanged(checked));
        row.addView(toggle);
        row.setBackground(Ui.rippleRound(this, Color.TRANSPARENT, palette.accentSoft, 12));
        row.setOnClickListener(v -> toggle.performClick());
        parent.addView(row);
    }

    private View infoRow(String title, String desc, int dotColor) {
        LinearLayout row = Ui.row(this);
        View dot = new View(this);
        dot.setBackground(oval(dotColor));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 6), Ui.dp(this, 6));
        dotParams.rightMargin = Ui.dp(this, 9);
        row.addView(dot, dotParams);
        LinearLayout text = Ui.column(this);
        text.addView(Ui.label(this, title, 12.5f, palette.text, Gravity.START));
        text.addView(Ui.text(this, desc, 10.5f, palette.dim,
                AppFonts.regular(this), Gravity.START));
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void showSignatureSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.signature),
                getString(R.string.signature_subtitle));
        LinearLayout body = sheet.body();
        TextView hint = Ui.text(this, getString(R.string.signature_hint), 11.5f,
                palette.dim, AppFonts.regular(this), Gravity.START);
        hint.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 14));
        body.addView(hint);

        int[][] signatures = {{1, 4}, {2, 4}, {3, 4}, {4, 4}, {5, 4},
                {6, 4}, {7, 4}, {3, 8}, {5, 8}, {6, 8}, {7, 8}, {9, 8},
                {12, 8}, {2, 2}, {3, 2}};
        for (int rowIndex = 0; rowIndex < 5; rowIndex++) {
            LinearLayout row = Ui.row(this);
            for (int col = 0; col < 3; col++) {
                int index = rowIndex * 3 + col;
                int[] signature = signatures[index];
                boolean selected = signature[0] == prefs.numerator()
                        && signature[1] == prefs.denominator();
                LinearLayout tile = Ui.column(this);
                tile.setGravity(Gravity.CENTER);
                tile.setBackground(Ui.rippleRound(this,
                        selected ? palette.accent : palette.surfaceAlt, palette.accentSoft, 16,
                        selected ? palette.accent : palette.stroke, 0.8f));
                tile.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
                tile.addView(Ui.label(this, signature[0] + "/" + signature[1], 17,
                        selected ? palette.onAccent : palette.text, Gravity.CENTER));
                tile.addView(Ui.text(this, beatsSubtitle(signature[0], signature[1]), 9.5f,
                        selected ? Palette.withAlpha(palette.onAccent, 0xCC) : palette.faint,
                        AppFonts.regular(this), Gravity.CENTER));
                tile.setOnClickListener(v -> {
                    applyMeter(signature[0], signature[1]);
                    sheet.dismiss();
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                        Ui.dp(this, 58), 1f);
                params.setMargins(Ui.dp(this, 4), Ui.dp(this, 4),
                        Ui.dp(this, 4), Ui.dp(this, 4));
                row.addView(tile, params);
            }
            body.addView(row);
        }
        sheet.showSheet();
    }

    private String beatsSubtitle(int beats, int denominator) {
        if (beats == 4 && denominator == 4) return getString(R.string.beats_common);
        if (beats == 3 && denominator == 4) return getString(R.string.beats_waltz);
        if (beats == 2 && denominator == 2) return getString(R.string.beats_duple);
        if (beats >= 6 && beats % 3 == 0 && denominator == 8) {
            return getString(R.string.beats_compound);
        }
        return getString(R.string.beats_description, beats);
    }

    private void applyMeter(int numerator, int denominator) {
        prefs.setMeter(numerator, denominator);
        int[] modes = prefs.beatModes();
        dots.setBeats(numerator, modes);
        updateChipValues();
        if (service != null) {
            service.setMeterLive(numerator, modes);
            service.notifyPrefsChanged();
        }
    }

    private void showSubdivisionSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.subdivision),
                getString(R.string.subdivision_subtitle));
        LinearLayout body = sheet.body();
        String[] names = getResources().getStringArray(R.array.subdivision_names);
        String[] descs = getResources().getStringArray(R.array.subdivision_desc);
        LinearLayout card = card();
        for (int i = 0; i < names.length; i++) {
            final int subdivision = i + 1;
            boolean selected = prefs.subdivision() == subdivision;
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9));
            TextView number = Ui.label(this, String.valueOf(subdivision), 18,
                    selected ? palette.onAccent : palette.accent, Gravity.CENTER);
            GradientDrawable numberBg = new GradientDrawable();
            numberBg.setShape(GradientDrawable.OVAL);
            numberBg.setColor(selected ? palette.accent : palette.accentSoft);
            number.setBackground(numberBg);
            row.addView(number, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
            LinearLayout text = Ui.column(this);
            text.setPadding(Ui.dp(this, 12), 0, 0, 0);
            text.addView(Ui.label(this, names[i], 14, palette.text, Gravity.START));
            text.addView(Ui.text(this, descs[i], 11, palette.dim,
                    AppFonts.regular(this), Gravity.START));
            row.addView(text, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (selected) {
                row.addView(Ui.icon(this, R.drawable.ic_check, palette.accent, 18));
            }
            row.setBackground(Ui.rippleRound(this,
                    selected ? palette.accentSoft : Color.TRANSPARENT,
                    palette.accentSoft, 14));
            row.setOnClickListener(v -> {
                prefs.setSubdivision(subdivision);
                updateChipValues();
                if (service != null) service.setSubdivisionLive(subdivision);
                sheet.dismiss();
            });
            card.addView(row);
            if (i < names.length - 1) {
                card.addView(Ui.divider(this, Palette.withAlpha(palette.stroke, 0x70)));
            }
        }
        body.addView(card);
        sheet.showSheet();
    }

    private void showSoundSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.sound),
                getString(R.string.sound_subtitle));
        LinearLayout body = sheet.body();
        TextView hint = Ui.text(this, getString(R.string.sound_preview_hint), 11.5f,
                palette.dim, AppFonts.regular(this), Gravity.START);
        hint.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 12));
        body.addView(hint);

        String[] names = getResources().getStringArray(R.array.sound_names);
        String[] descs = getResources().getStringArray(R.array.sound_desc);
        LinearLayout card = card();
        SoundBarsView[] bars = new SoundBarsView[names.length];
        View[] rowViews = new View[names.length];
        ImageView[] checks = new ImageView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            boolean selected = prefs.sound() == index;
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 5), Ui.dp(this, 7), Ui.dp(this, 5), Ui.dp(this, 7));
            SoundBarsView icon = new SoundBarsView(this, palette, i, selected);
            row.addView(icon);
            LinearLayout text = Ui.column(this);
            text.setPadding(Ui.dp(this, 12), 0, 0, 0);
            text.addView(Ui.label(this, names[i], 14, palette.text, Gravity.START));
            text.addView(Ui.text(this, descs[i], 10.5f, palette.dim,
                    AppFonts.regular(this), Gravity.START));
            row.addView(text, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageView check = Ui.icon(this, R.drawable.ic_check, palette.accent, 18);
            check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            row.addView(check);
            row.setBackground(Ui.rippleRound(this,
                    selected ? palette.accentSoft : Color.TRANSPARENT,
                    palette.accentSoft, 14));
            row.setOnClickListener(v -> {
                prefs.setSound(index);
                updateChipValues();
                if (service != null) {
                    service.setSoundLive(index);
                    service.previewSound(index);
                } else {
                    ClickSynth.preview(MainActivity.this, index, prefs.volume());
                }
                for (int j = 0; j < names.length; j++) {
                    boolean active = j == index;
                    bars[j].setSelected(active);
                    rowViews[j].setBackground(Ui.rippleRound(MainActivity.this,
                            active ? palette.accentSoft : Color.TRANSPARENT,
                            palette.accentSoft, 14));
                    checks[j].setVisibility(active ? View.VISIBLE : View.INVISIBLE);
                }
                row.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            });
            card.addView(row);
            if (i < names.length - 1) {
                card.addView(Ui.divider(this, Palette.withAlpha(palette.stroke, 0x70)),
                        Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1),
                                Ui.dp(this, 52), 0, 0, 0));
            }
            bars[i] = icon;
            rowViews[i] = row;
            checks[i] = check;
        }
        body.addView(card);

        TextView done = Ui.label(this, getString(R.string.done), 13,
                palette.onAccent, Gravity.CENTER);
        done.setBackground(Ui.rippleRound(this, palette.accent,
                Palette.withAlpha(palette.onAccent, 0x40), 23));
        done.setOnClickListener(v -> sheet.dismiss());
        body.addView(done, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 46), 0, Ui.dp(this, 16), 0, 0));
        sheet.showSheet();
    }

    private void showPresetsSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.presets),
                getString(R.string.presets_subtitle));
        LinearLayout body = sheet.body();

        body.addView(Ui.sectionLabel(this, getString(R.string.preset_recommended), palette));
        LinearLayout builtins = card();
        addPresetRow(builtins, builtinPreset("slow", getString(R.string.preset_builtin_slow),
                        60, 4, 4, 1), false, sheet);
        builtins.addView(Ui.divider(this, Palette.withAlpha(palette.stroke, 0x70)));
        addPresetRow(builtins, builtinPreset("scales", getString(R.string.preset_builtin_scales),
                        90, 4, 4, 2), false, sheet);
        builtins.addView(Ui.divider(this, Palette.withAlpha(palette.stroke, 0x70)));
        addPresetRow(builtins, builtinPreset("stage", getString(R.string.preset_builtin_stage),
                        120, 4, 4, 1), false, sheet);
        body.addView(builtins, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, Ui.dp(this, 18)));

        body.addView(Ui.sectionLabel(this, getString(R.string.preset_mine), palette));
        List<Preset> presets = prefs.presets();
        if (presets.isEmpty()) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER);
            empty.addView(Ui.label(this, getString(R.string.preset_empty), 14,
                    palette.text, Gravity.CENTER));
            empty.addView(Ui.text(this, getString(R.string.preset_empty_desc), 11.5f,
                    palette.dim, AppFonts.regular(this), Gravity.CENTER));
            body.addView(empty, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, Ui.dp(this, 12)));
        } else {
            LinearLayout list = card();
            for (int i = 0; i < presets.size(); i++) {
                Preset preset = presets.get(i);
                addPresetRow(list, preset, true, sheet);
                if (i < presets.size() - 1) {
                    list.addView(Ui.divider(this, Palette.withAlpha(palette.stroke, 0x70)));
                }
            }
            body.addView(list, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, Ui.dp(this, 12)));
        }

        TextView save = Ui.label(this, "＋  " + getString(R.string.preset_save_current), 13,
                palette.accent, Gravity.CENTER);
        save.setBackground(Ui.rippleRound(this, Color.TRANSPARENT, palette.accentSoft, 23,
                Palette.withAlpha(palette.accent, 0x80), 1f));
        save.setOnClickListener(v -> {
            sheet.dismiss();
            showSavePresetSheet();
        });
        body.addView(save, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 48), 0, 0, 0, Ui.dp(this, 14)));
        sheet.showSheet();
    }

    private Preset builtinPreset(String id, String name, int bpm, int numerator,
                                 int denominator, int subdivision) {
        int[] modes = new int[numerator];
        if (numerator > 0) modes[0] = 1;
        return new Preset("builtin-" + id, name, bpm, numerator, denominator,
                subdivision, 0, modes);
    }

    private void addPresetRow(LinearLayout parent, Preset preset, boolean deletable, Sheet sheet) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 5), Ui.dp(this, 7), Ui.dp(this, 5), Ui.dp(this, 7));
        FrameLayout badge = new FrameLayout(this);
        TextView bpm = Ui.label(this, String.valueOf(preset.bpm), 12,
                palette.accent, Gravity.CENTER);
        View badgeCircle = new View(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(palette.accentSoft);
        badgeCircle.setBackground(circle);
        badge.addView(badgeCircle, new FrameLayout.LayoutParams(
                Ui.dp(this, 42), Ui.dp(this, 42)));
        badge.addView(bpm, new FrameLayout.LayoutParams(
                Ui.dp(this, 42), Ui.dp(this, 42)));
        row.addView(badge);

        LinearLayout text = Ui.column(this);
        text.setPadding(Ui.dp(this, 12), 0, 0, 0);
        text.addView(Ui.label(this, preset.name, 13.5f, palette.text, Gravity.START));
        text.addView(Ui.text(this, preset.detail(this), 10.5f, palette.dim,
                AppFonts.regular(this), Gravity.START));
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (deletable) {
            android.widget.ImageButton delete = new android.widget.ImageButton(this);
            delete.setImageResource(R.drawable.ic_trash);
            delete.setColorFilter(palette.faint);
            delete.setBackground(Ui.rippleRound(this, Color.TRANSPARENT,
                    Palette.withAlpha(palette.danger, 0x30), 18));
            delete.setPadding(Ui.dp(this, 8), Ui.dp(this, 8),
                    Ui.dp(this, 8), Ui.dp(this, 8));
            delete.setContentDescription(getString(R.string.delete));
            delete.setOnClickListener(v -> {
                Dialogs.confirm(this, palette, getString(R.string.delete_preset_title),
                        getString(R.string.delete_preset_message, preset.name),
                        getString(R.string.delete), () -> {
                            prefs.deletePreset(preset.id);
                            Toast.makeText(this, R.string.preset_deleted, Toast.LENGTH_SHORT).show();
                            sheet.dismiss();
                            showPresetsSheet();
                        });
            });
            row.addView(delete, new LinearLayout.LayoutParams(
                    Ui.dp(this, 40), Ui.dp(this, 40)));
        } else {
            row.addView(Ui.icon(this, R.drawable.ic_chevron_right, palette.faint, 16));
        }
        row.setBackground(Ui.rippleRound(this, Color.TRANSPARENT,
                palette.accentSoft, 14));
        row.setOnClickListener(v -> {
            applyPreset(preset);
            sheet.dismiss();
            Toast.makeText(this, getString(R.string.preset_loaded, preset.name),
                    Toast.LENGTH_SHORT).show();
        });
        parent.addView(row);
    }

    private void applyPreset(Preset preset) {
        prefs.setBpm(preset.bpm);
        prefs.setMeter(preset.numerator, preset.denominator);
        prefs.setSubdivision(preset.subdivision);
        prefs.setSound(preset.sound);
        prefs.setBeatModes(preset.beatModes);
        dial.setBpm(prefs.bpm(), false);
        dots.setBeats(prefs.numerator(), prefs.beatModes());
        updateChipValues();
        if (service != null) service.refreshFromPrefs();
    }

    private void showSavePresetSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.preset_dialog_title),
                getString(R.string.preset_dialog_subtitle));
        LinearLayout body = sheet.body();
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setTextSize(15);
        name.setTypeface(AppFonts.regular(this));
        name.setTextColor(palette.text);
        name.setHintTextColor(palette.faint);
        name.setHint(getString(R.string.preset_name_hint));
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        name.setBackground(Ui.roundRect(this, palette.surfaceAlt, 14,
                palette.stroke, 0.8f));
        name.setPadding(Ui.dp(this, 14), Ui.dp(this, 12),
                Ui.dp(this, 14), Ui.dp(this, 12));
        String defaultName = getString(R.string.preset_name_default, prefs.bpm(),
                prefs.numerator() + "/" + prefs.denominator());
        name.setText(defaultName);
        name.setSelection(name.getText().length());
        body.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));

        TextView save = Ui.label(this, getString(R.string.save), 13,
                palette.onAccent, Gravity.CENTER);
        save.setBackground(Ui.rippleRound(this, palette.accent,
                Palette.withAlpha(palette.onAccent, 0x40), 23));
        body.addView(save, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 46), 0, Ui.dp(this, 14), 0, 0));

        Runnable commit = () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) value = defaultName;
            if (value.length() > 40) value = value.substring(0, 40);
            Preset preset = prefs.presetFromCurrent(value);
            if (!prefs.savePreset(preset)) {
                Toast.makeText(this, R.string.preset_limit, Toast.LENGTH_SHORT).show();
                return;
            }
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(name.getWindowToken(), 0);
            sheet.dismiss();
            Toast.makeText(this, R.string.preset_saved, Toast.LENGTH_SHORT).show();
        };
        save.setOnClickListener(v -> commit.run());
        name.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit.run();
                return true;
            }
            return false;
        });
        sheet.showSheet();
        name.postDelayed(() -> {
            name.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT);
        }, 220);
    }

    private void showAboutSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.about_title),
                getString(R.string.about_subtitle));
        LinearLayout body = sheet.body();
        LinearLayout hero = Ui.column(this);
        hero.setGravity(Gravity.CENTER);
        FrameLayout logoWrap = new FrameLayout(this);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setShape(GradientDrawable.OVAL);
        logoBg.setColor(palette.accentSoft);
        logoWrap.setBackground(logoBg);
        ImageView logo = Ui.icon(this, R.drawable.ic_metronome, palette.accent, 38);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 38), Ui.dp(this, 38));
        logoParams.gravity = Gravity.CENTER;
        logoWrap.addView(logo, logoParams);
        hero.addView(logoWrap, new LinearLayout.LayoutParams(
                Ui.dp(this, 72), Ui.dp(this, 72)));
        TextView version = Ui.caps(this, getString(R.string.about_version, versionName()), 9.5f,
                palette.faint, Gravity.CENTER);
        version.setPadding(0, Ui.dp(this, 12), 0, 0);
        hero.addView(version);
        body.addView(hero);

        TextView about = Ui.text(this, getString(R.string.about_body), 12.5f,
                palette.dim, AppFonts.regular(this), Gravity.START);
        about.setLineSpacing(Ui.dp(this, 5), 1.15f);
        about.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));
        body.addView(about);

        TextView made = Ui.caps(this, getString(R.string.about_made), 9.5f,
                palette.accent, Gravity.CENTER);
        made.setLetterSpacing(0.24f);
        body.addView(made, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, Ui.dp(this, 4), 0, Ui.dp(this, 6)));
        sheet.showSheet();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    private interface ToggleListener {
        void onChanged(boolean checked);
    }
}

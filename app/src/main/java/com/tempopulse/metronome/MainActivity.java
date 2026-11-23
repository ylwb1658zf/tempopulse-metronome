package com.tempopulse.metronome;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
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
import android.text.InputFilter;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private RotaryTempoView rotary;
    private BeatBarsView bars;
    private TextView statusView;
    private View statusDot;
    private TextView tempoValue;
    private TextView tempoName;
    private TextView sigValue;
    private TextView subValue;
    private TextView soundValue;
    private TextView bottomMeter;
    private TextView trainingButton;
    private TextView trainingSummaryView;
    private TextView tapButton;
    private boolean trainingActive;
    private boolean trainingReached;
    private int trainingRemaining;
    private View flashOverlay;
    private boolean landscape;
    private boolean isTablet;

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

        @Override
        public void onBpmChanged(int bpm) {
            ui.post(() -> applyTrainingBpm(bpm));
        }

        @Override
        public void onTrainingState(boolean active, boolean reachedTarget, int remainingSeconds) {
            ui.post(() -> setTrainingUi(active, reachedTarget, remainingSeconds));
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
        if (rotary != null) rotary.commitCurrent();
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
        landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        isTablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        rootView = new FrameLayout(this);
        BackdropView backdrop = new BackdropView(this, palette);
        rootView.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int maxWidth = Ui.dp(this, landscape ? 1280 : (isTablet ? 860 : 760));
        FrameLayout.LayoutParams shellParams = new FrameLayout.LayoutParams(
                Math.min(screenWidth, maxWidth), ViewGroup.LayoutParams.MATCH_PARENT);
        shellParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        rootView.addView(shell, shellParams);

        if (landscape) {
            shell.addView(buildLandscapeStage(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            shell.addView(buildPortraitTopBar());
            shell.addView(buildPortraitStage(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

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

    private View buildPortraitTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 1));

        FrameLayout logoWrap = new FrameLayout(this);
        logoWrap.setBackground(Ui.roundRect(this, palette.surfaceAlt, 12,
                Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
        logoWrap.addView(Ui.icon(this, R.drawable.ic_metronome, palette.accent, 23),
                centeredParams(23));
        bar.addView(logoWrap, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        LinearLayout titles = Ui.column(this);
        titles.setPadding(Ui.dp(this, 10), 0, 0, 0);
        titles.addView(Ui.label(this, getString(R.string.screen_title), 17,
                palette.text, Gravity.START));
        TextView tagline = Ui.caps(this, getString(R.string.tagline), 6.8f,
                palette.faint, Gravity.START);
        tagline.setPadding(0, Ui.dp(this, 3), 0, 0);
        titles.addView(tagline);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout actions = Ui.row(this);
        actions.addView(iconButton(R.drawable.ic_bookmark, getString(R.string.presets),
                this::showPresetsSheet));
        actions.addView(iconButton(R.drawable.ic_tune, getString(R.string.settings),
                this::showSettingsSheet));
        bar.addView(actions);
        return bar;
    }

    private View buildPortraitStage() {
        LinearLayout stage = Ui.column(this);
        stage.setPadding(0, Ui.dp(this, 2), 0, 0);

        int infoHeight = isTablet ? 96 : 78;
        int barsHeight = isTablet ? 166 : 128;
        stage.addView(buildInfoCard(false), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, infoHeight)));

        bars = buildBars();
        stage.addView(bars, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, barsHeight), 0, Ui.dp(this, isTablet ? 10 : 7), 0, 0));

        FrameLayout wheelHost = new SquareHost(this);
        wheelHost.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 1));
        rotary = buildRotary();
        if (isTablet) {
            int widthDp = getResources().getConfiguration().screenWidthDp;
            rotary.setMaxDiameterDp(Math.min(440, Math.round(widthDp * 0.58f)));
        }
        FrameLayout.LayoutParams wheelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        wheelParams.gravity = Gravity.CENTER;
        wheelHost.addView(rotary, wheelParams);
        stage.addView(wheelHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        stage.addView(buildStatusRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 21)));
        stage.addView(buildBottomRow(), Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 50), 0, Ui.dp(this, 4), 0, Ui.dp(this, 2)));
        return stage;
    }

    private View buildLandscapeStage() {
        LinearLayout stage = Ui.row(this);
        stage.setPadding(0, Ui.dp(this, 4), 0, 0);

        LinearLayout left = Ui.column(this);
        left.setPadding(0, 0, Ui.dp(this, 10), 0);
        FrameLayout wheelHost = new SquareHost(this);
        wheelHost.setPadding(Ui.dp(this, 2), Ui.dp(this, 2),
                Ui.dp(this, 2), Ui.dp(this, 2));
        rotary = buildRotary();
        FrameLayout.LayoutParams wheelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        wheelParams.gravity = Gravity.CENTER;
        wheelHost.addView(rotary, wheelParams);
        left.addView(wheelHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        FrameLayout controlHost = new FrameLayout(this);
        controlHost.addView(buildLandscapeControls(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54));
        controlParams.topMargin = Ui.dp(this, 24);
        left.addView(controlHost, controlParams);
        left.addView(buildStatusRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 18)));
        stage.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.40f));

        LinearLayout right = Ui.column(this);
        right.setPadding(Ui.dp(this, 10), 0, 0, 0);
        right.addView(buildInfoCard(true), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, isTablet ? 72 : 62)));
        bars = buildBars();
        bars.setFillHeight(true);
        LinearLayout.LayoutParams barsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        barsParams.topMargin = Ui.dp(this, 6);
        right.addView(bars, barsParams);
        stage.addView(right, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.60f));
        return stage;
    }

    private View buildLandscapeControls() {
        LinearLayout controls = Ui.row(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(Ui.dp(this, 3), Ui.dp(this, 3),
                Ui.dp(this, 3), Ui.dp(this, 3));
        controls.setBackground(Ui.roundRect(this, palette.surface, 16,
                Palette.withAlpha(palette.stroke, 0x90), 0.8f));

        controls.addView(controlCell(R.drawable.ic_bookmark, getString(R.string.presets),
                this::showPresetsSheet), weightedParams(1f));
        controls.addView(controlCell(R.drawable.ic_tune, getString(R.string.settings),
                this::showSettingsSheet), weightedParams(1f));
        controls.addView(controlCell(R.drawable.ic_sound, getString(R.string.sound),
                this::showSoundSheet), weightedParams(1f));
        controls.addView(controlCell(R.drawable.ic_training, getString(R.string.training_title),
                this::showTrainingSheet), weightedParams(1f));

        tapButton = Ui.label(this, getString(R.string.tap_short), 11f,
                palette.onAccent, Gravity.CENTER);
        tapButton.setLetterSpacing(0.14f);
        tapButton.setBackground(Ui.rippleRound(this, palette.accent,
                Palette.withAlpha(palette.onAccent, 0x40), 14));
        tapButton.setOnClickListener(v -> tapTempo());
        tapButton.setContentDescription(getString(R.string.tap_tempo));
        controls.addView(tapButton, weightedParams(1.2f));
        return controls;
    }

    private LinearLayout.LayoutParams weightedParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        return params;
    }

    private FrameLayout controlCell(int resource, String description, Runnable action) {
        FrameLayout cell = new FrameLayout(this);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setContentDescription(description);
        cell.setBackground(Ui.rippleRound(this, Color.TRANSPARENT, palette.accentSoft, 12));
        ImageView icon = Ui.icon(this, resource, palette.dim, 22);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 24), Ui.dp(this, 24));
        iconParams.gravity = Gravity.CENTER;
        cell.addView(icon, iconParams);
        cell.setOnClickListener(v -> action.run());
        return cell;
    }

    private LinearLayout buildSideInfoColumn() {
        LinearLayout column = Ui.column(this);
        column.addView(buildSideTempoBlock(), sideParams());
        column.addView(buildSideMeterBlock(), sideParams());
        column.addView(buildSideSubdivisionBlock(), sideParams());
        return column;
    }

    private LinearLayout.LayoutParams sideParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        params.setMargins(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        return params;
    }

    private LinearLayout sideBlockShell(Runnable action, String description) {
        LinearLayout block = Ui.column(this);
        block.setGravity(Gravity.CENTER_VERTICAL);
        block.setPadding(Ui.dp(this, 12), Ui.dp(this, 8),
                Ui.dp(this, 12), Ui.dp(this, 8));
        block.setBackground(Ui.rippleRound(this, palette.surface, palette.accentSoft,
                16, Palette.withAlpha(palette.stroke, 0x90), 0.8f));
        block.setOnClickListener(v -> action.run());
        block.setContentDescription(description);
        return block;
    }

    private View buildSideTempoBlock() {
        LinearLayout block = sideBlockShell(this::showTargetBpmSheet,
                getString(R.string.tempo_label));
        block.addView(Ui.caps(this, getString(R.string.tempo_label), 7.5f,
                palette.faint, Gravity.START));

        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.BOTTOM);
        tempoValue = Ui.label(this, String.valueOf(prefs == null ? 120 : prefs.bpm()),
                isTablet ? 28 : 21, palette.text, Gravity.START);
        tempoValue.setIncludeFontPadding(false);
        row.addView(tempoValue);
        TextView unit = Ui.caps(this, getString(R.string.bpm_unit), 8,
                palette.faint, Gravity.START);
        unit.setPadding(Ui.dp(this, 3), 0, 0, Ui.dp(this, 3));
        row.addView(unit);
        block.addView(row);

        tempoName = Ui.text(this, TempoName.of(this, prefs == null ? 120 : prefs.bpm()),
                isTablet ? 10 : 8.5f, palette.accent,
                AppFonts.regular(this), Gravity.START);
        Ui.setSingleLine(tempoName);
        tempoName.setPadding(0, Ui.dp(this, 2), 0, 0);
        block.addView(tempoName);
        return block;
    }

    private View buildSideMeterBlock() {
        LinearLayout block = sideBlockShell(this::showSignatureSheet,
                getString(R.string.signature));
        block.addView(Ui.caps(this, getString(R.string.ts_label), 7.5f,
                palette.faint, Gravity.START));
        sigValue = Ui.label(this, "4/4", isTablet ? 24 : 18,
                palette.text, Gravity.START);
        sigValue.setPadding(0, Ui.dp(this, 4), 0, 0);
        block.addView(sigValue);
        return block;
    }

    private View buildSideSubdivisionBlock() {
        LinearLayout block = sideBlockShell(this::showSubdivisionSheet,
                getString(R.string.subdivision));
        block.addView(Ui.caps(this, getString(R.string.subdiv_label), 7.5f,
                palette.faint, Gravity.START));
        subValue = Ui.label(this, "—", isTablet ? 18 : 15,
                palette.text, Gravity.START);
        subValue.setPadding(0, Ui.dp(this, 5), 0, 0);
        block.addView(subValue);
        return block;
    }

    private View buildInfoCard(boolean dense) {
        LinearLayout card = Ui.row(this);
        card.setPadding(0, 0, 0, 0);
        card.setBackground(null);

        if (dense) {
            card.addView(buildTempoBlock(true), blockParams(1f, 2));
            card.addView(buildMeterBlock(true, true), blockParams(1f, 2));
            card.addView(buildSubdivisionBlock(true, true), blockParams(1f, 2));
        } else {
            card.addView(buildTempoBlock(false), blockParams(1f, 3));
            card.addView(buildMeterBlock(false, true), blockParams(1f, 3));
            card.addView(buildSubdivisionBlock(false, true), blockParams(1f, 3));
        }
        return card;
    }

    private LinearLayout.LayoutParams blockParams(float weight, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.setMargins(Ui.dp(this, margin), 0, Ui.dp(this, margin), 0);
        return params;
    }

    private LinearLayout infoBlockShell(boolean compact, Runnable action) {
        LinearLayout block = Ui.column(this);
        block.setPadding(Ui.dp(this, compact ? 4 : 8), Ui.dp(this, compact ? 2 : 4),
                Ui.dp(this, compact ? 4 : 8), Ui.dp(this, compact ? 2 : 4));
        block.setBackground(Ui.rippleRound(this, palette.surfaceAlt, palette.accentSoft,
                compact ? 10 : 13, Palette.withAlpha(palette.stroke, 0x88), 0.7f));
        block.setOnClickListener(v -> action.run());
        block.setContentDescription(getString(R.string.settings));
        return block;
    }

    private View buildTempoBlock(boolean compact) {
        LinearLayout block = infoBlockShell(compact, this::showTargetBpmSheet);
        block.setGravity(Gravity.CENTER);
        block.setContentDescription(getString(R.string.tempo_label));
        block.addView(Ui.caps(this, getString(R.string.tempo_label),
                compact ? 6.8f : 7f, palette.faint, Gravity.CENTER));

        LinearLayout valueRow = Ui.row(this);
        valueRow.setGravity(Gravity.CENTER);
        tempoValue = Ui.label(this, String.valueOf(prefs == null ? 120 : prefs.bpm()),
                compact ? 19 : 24, palette.text, Gravity.CENTER);
        tempoValue.setIncludeFontPadding(false);
        valueRow.addView(tempoValue);
        TextView unit = Ui.caps(this, getString(R.string.bpm_unit),
                compact ? 7 : 8, palette.faint, Gravity.CENTER);
        unit.setPadding(Ui.dp(this, 3), 0, 0, Ui.dp(this, compact ? 1 : 3));
        valueRow.addView(unit);
        block.addView(valueRow);

        tempoName = Ui.text(this, TempoName.of(this, prefs == null ? 120 : prefs.bpm()),
                compact ? 7.5f : 8.5f, palette.accent, AppFonts.regular(this), Gravity.CENTER);
        tempoName.setPadding(0, Ui.dp(this, compact ? 0 : 1), 0, 0);
        Ui.setSingleLine(tempoName);
        block.addView(tempoName);
        return block;
    }

    private View buildMeterBlock(boolean compact, boolean centered) {
        LinearLayout block = infoBlockShell(compact, this::showSignatureSheet);
        if (centered) block.setGravity(Gravity.CENTER);
        block.setContentDescription(getString(R.string.signature));
        block.addView(Ui.caps(this, getString(R.string.ts_label),
                compact ? 6.8f : 7f, palette.faint,
                centered ? Gravity.CENTER : Gravity.START));
        sigValue = Ui.label(this, "4/4", compact ? 17 : 21,
                palette.text, centered ? Gravity.CENTER : Gravity.START);
        sigValue.setPadding(0, Ui.dp(this, compact ? 1 : 4), 0, 0);
        block.addView(sigValue);
        return block;
    }

    private View buildSubdivisionBlock(boolean compact, boolean centered) {
        LinearLayout block = infoBlockShell(compact, this::showSubdivisionSheet);
        if (centered) block.setGravity(Gravity.CENTER);
        block.setContentDescription(getString(R.string.subdivision));
        block.addView(Ui.caps(this, getString(R.string.subdiv_label),
                compact ? 6.8f : 7f, palette.faint,
                centered ? Gravity.CENTER : Gravity.START));
        subValue = Ui.label(this, "—", compact ? 14 : 16,
                palette.text, centered ? Gravity.CENTER : Gravity.START);
        subValue.setPadding(0, Ui.dp(this, compact ? 2 : 5), 0, 0);
        block.addView(subValue);
        return block;
    }

    private View buildStatusRow() {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER);
        statusDot = new View(this);
        statusDot.setBackground(oval(palette.faint));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 6), Ui.dp(this, 6));
        dotParams.rightMargin = Ui.dp(this, 6);
        row.addView(statusDot, dotParams);
        statusView = Ui.text(this, getString(R.string.status_ready), 10.5f,
                palette.dim, AppFonts.regular(this), Gravity.CENTER);
        row.addView(statusView);
        return row;
    }

    private View buildBottomRow() {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 1), 0, Ui.dp(this, 1));

        trainingButton = Ui.label(this, getString(R.string.training_short), 12,
                palette.text, Gravity.CENTER);
        trainingButton.setBackground(Ui.rippleRound(this, palette.surface,
                palette.accentSoft, 22,
                Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
        trainingButton.setOnClickListener(v -> showTrainingSheet());
        trainingButton.setContentDescription(getString(R.string.training_title));
        row.addView(trainingButton, bottomParams());

        soundValue = Ui.label(this, "—", 14, palette.text, Gravity.CENTER);
        soundValue.setBackground(Ui.rippleRound(this, palette.surface,
                palette.accentSoft, 22,
                Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
        soundValue.setOnClickListener(v -> showSoundSheet());
        soundValue.setContentDescription(getString(R.string.sound));
        row.addView(soundValue, bottomParams());

        tapButton = Ui.label(this, getString(R.string.tap_short), 12,
                palette.onAccent, Gravity.CENTER);
        tapButton.setLetterSpacing(0.12f);
        tapButton.setBackground(Ui.rippleRound(this, palette.accent,
                Palette.withAlpha(palette.onAccent, 0x40), 22));
        tapButton.setOnClickListener(v -> tapTempo());
        tapButton.setContentDescription(getString(R.string.tap_tempo));
        row.addView(tapButton, bottomParams());
        return row;
    }

    private LinearLayout.LayoutParams bottomParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        return params;
    }

    private BeatBarsView buildBars() {
        BeatBarsView view = new BeatBarsView(this, palette);
        view.setListener((beat, mode) -> {
            int[] modes = prefs.beatModes();
            if (beat >= 0 && beat < modes.length) {
                modes[beat] = mode;
                prefs.setBeatModes(modes);
                if (service != null) service.setMeterLive(prefs.numerator(), modes);
                showBeatModeHint(beat, mode);
            }
        });
        return view;
    }

    private RotaryTempoView buildRotary() {
        RotaryTempoView view = new RotaryTempoView(this, palette);
        view.setListener(new RotaryTempoView.Listener() {
            @Override
            public void onBpmChanged(int bpm, boolean committed) {
                if (committed) prefs.setBpm(bpm);
                if (service != null) service.setBpmLive(bpm);
                updateTempoUi();
            }

            @Override
            public void onPlayTapped() {
                togglePlayback();
            }
        });
        return view;
    }

    private ImageButton iconButton(int resource, String description, Runnable action) {
        ImageButton button = Ui.iconButton(this, resource, palette.dim,
                palette, description, v -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(this, 38), Ui.dp(this, 38)));
        return button;
    }

    private FrameLayout.LayoutParams centeredParams(int sizeDp) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Ui.dp(this, sizeDp), Ui.dp(this, sizeDp));
        params.gravity = Gravity.CENTER;
        return params;
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
        if (bars == null) return;
        if (rotary != null) rotary.setBpm(prefs.bpm(), false);
        bars.setBeats(prefs.numerator(), prefs.beatModes());
        bars.setSubdivision(prefs.subdivision());
        bars.setBpm(prefs.bpm());
        updateChipValues();
        applyKeepScreenOn();
    }

    private void updateChipValues() {
        int beats = prefs.numerator();
        int denominator = prefs.denominator();
        String[] shortNames = getResources().getStringArray(R.array.subdivision_short);
        String[] soundNames = getResources().getStringArray(R.array.sound_names);
        String signature = getString(R.string.time_signature_format, beats, denominator);
        if (sigValue != null) sigValue.setText(signature);
        if (bottomMeter != null) bottomMeter.setText(signature);
        if (subValue != null) {
            subValue.setText(shortNames[Math.max(0,
                    Math.min(shortNames.length - 1, prefs.subdivision() - 1))]);
        }
        if (soundValue != null) {
            soundValue.setText(soundNames[Math.max(0,
                    Math.min(soundNames.length - 1, prefs.sound()))]);
        }
        updateTempoUi();
        if (bars != null) {
            bars.setBeats(beats, prefs.beatModes());
            bars.setSubdivision(prefs.subdivision());
            bars.setBpm(prefs.bpm());
        }
    }

    private int currentBpm() {
        return rotary != null ? rotary.bpm() : prefs.bpm();
    }

    private void updateTempoUi() {
        int bpm = currentBpm();
        if (tempoValue != null) tempoValue.setText(String.valueOf(bpm));
        if (tempoName != null) tempoName.setText(TempoName.of(this, bpm));
        if (bars != null) bars.setBpm(bpm);
    }

    private void setBpmValue(int bpm, boolean commit) {
        int value = Prefs.clampBpm(bpm);
        if (rotary != null) {
            rotary.setBpm(value, commit);
            return;
        }
        prefs.setBpm(value);
        if (service != null) service.setBpmLive(value);
        updateTempoUi();
    }

    private void setPlaybackUi(boolean isPlaying) {
        playing = isPlaying;
        if (rotary != null) rotary.setPlaying(playing);
        if (bars != null) bars.setPlaying(playing);
        if (statusDot != null) {
            statusDot.setBackground(oval(playing ? palette.success : palette.faint));
        }
        if (statusView != null) {
            statusView.setText(playing ? R.string.status_playing : R.string.status_ready);
            statusView.setTextColor(playing ? palette.text : palette.dim);
        }
        if (!playing && trainingActive) {
            setTrainingUi(false, false, 0);
        }
        applyKeepScreenOn();
    }

    private void applyTrainingBpm(int bpm) {
        prefs.setBpm(bpm);
        if (rotary != null) rotary.setBpm(bpm, false);
        updateTempoUi();
    }

    private void setTrainingUi(boolean active, boolean reachedTarget, int remainingSeconds) {
        trainingActive = active;
        trainingReached = reachedTarget;
        trainingRemaining = remainingSeconds;
        String label;
        if (!active) {
            label = getString(R.string.training_short);
        } else if (reachedTarget) {
            label = getString(R.string.training_reached_pill);
        } else {
            label = getString(R.string.training_pill, formatDuration(remainingSeconds));
        }
        if (trainingButton != null) {
            trainingButton.setText(label);
            trainingButton.setTextColor(active ? palette.onAccent : palette.text);
            trainingButton.setBackground(Ui.rippleRound(this,
                    active ? palette.accent : palette.surface,
                    palette.accentSoft, 22,
                    active ? palette.accent : Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
        }
    }

    private String formatDuration(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format(Locale.US, "%d:%02d", safe / 60, safe % 60);
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
            if (bars != null) bars.setCurrentBeat(beat);
            if (rotary != null) rotary.beatPulse();
            if (statusView != null) statusView.setText(getString(R.string.status_beat, beat + 1));
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
        if (statusView != null) {
            statusView.setText(getString(R.string.hint_beat_mode,
                    beat + 1, getString(label)));
            statusView.setTextColor(mode == 2 ? palette.faint : palette.accent);
        }
        if (hintReset != null) ui.removeCallbacks(hintReset);
        hintReset = () -> setPlaybackUi(playing);
        ui.postDelayed(hintReset, 1600);
        if (bars != null) {
            bars.announceForAccessibility(getString(R.string.hint_beat_mode,
                    beat + 1, getString(label)));
        }
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

    private void tapTempo() {
        long now = SystemClock.elapsedRealtime();
        if (lastTap == 0 || now - lastTap > 2000) {
            tapTimes.clear();
        }
        tapTimes.add(now);
        lastTap = now;
        if (tapTimes.size() > 6) tapTimes.remove(0);
        if (tapButton != null) {
            tapButton.animate().cancel();
            tapButton.animate().scaleX(0.97f).scaleY(0.97f).setDuration(55)
                    .withEndAction(() -> tapButton.animate().scaleX(1f).scaleY(1f)
                            .setDuration(110).start()).start();
            tapButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
        if (tapTimes.size() >= 2) {
            int count = Math.min(4, tapTimes.size() - 1);
            long sum = 0;
            for (int i = tapTimes.size() - count; i < tapTimes.size(); i++) {
                sum += tapTimes.get(i) - tapTimes.get(i - 1);
            }
            double average = sum / (double) count;
            if (average > 100) {
                int bpm = (int) Math.round(60000.0 / average);
                setBpmValue(Prefs.clampBpm(bpm), false);
                if (rotary != null) rotary.bump();
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

    private void showSettingsSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.settings),
                getString(R.string.settings_subtitle));
        LinearLayout body = sheet.body();

        SegmentedView tabs = new SegmentedView(this, palette,
                new String[]{getString(R.string.section_appearance),
                        getString(R.string.section_playback),
                        getString(R.string.section_beat)}, 0);
        body.addView(tabs, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));

        FrameLayout pages = new FrameLayout(this);
        View appearance = buildAppearanceSettings(sheet);
        View playback = buildPlaybackSettings();
        View beat = buildBeatSettings();
        pages.addView(appearance);
        pages.addView(playback);
        pages.addView(beat);
        playback.setVisibility(View.GONE);
        beat.setVisibility(View.GONE);
        body.addView(pages, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, Ui.dp(this, 12), 0, 0));

        tabs.setListener(index -> {
            appearance.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
            playback.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
            beat.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        });
        sheet.showSheet();
    }

    private View buildAppearanceSettings(Sheet sheet) {
        LinearLayout page = Ui.column(this);
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
        accentLabel.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8));
        appearance.addView(accentLabel);
        appearance.addView(buildAccentPicker(sheet), Ui.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(appearance);

        LinearLayout aboutRow = Ui.row(this);
        aboutRow.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
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
        page.addView(aboutRow, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, Ui.dp(this, 12), 0, 0));
        return page;
    }

    private View buildPlaybackSettings() {
        LinearLayout page = Ui.column(this);
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
                Ui.dp(this, 34), 0, Ui.dp(this, 2), 0, 0));

        addToggleRow(playback, getString(R.string.keep_awake),
                getString(R.string.keep_awake_desc), prefs.keepAwake(), checked -> {
                    prefs.setKeepAwake(checked);
                    applyKeepScreenOn();
                }, true);
        addToggleRow(playback, getString(R.string.vibrate),
                getString(R.string.vibrate_desc), prefs.haptic(),
                checked -> prefs.setHaptic(checked), true);
        addToggleRow(playback, getString(R.string.flash),
                getString(R.string.flash_desc), prefs.flash(),
                checked -> prefs.setFlash(checked), false);
        page.addView(playback);

        LinearLayout info = card();
        info.addView(infoRow(getString(R.string.background_play_title),
                getString(R.string.background_play_desc), palette.success));
        info.addView(Ui.divider(this, palette.stroke), Ui.margins(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1),
                0, Ui.dp(this, 7), 0, Ui.dp(this, 7)));
        info.addView(infoRow(getString(R.string.mix_music_title),
                getString(R.string.mix_music_desc), palette.accent));
        page.addView(info, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, Ui.dp(this, 12), 0, 0));
        return page;
    }

    private View buildBeatSettings() {
        LinearLayout page = Ui.column(this);
        LinearLayout beatSection = card();
        beatSection.addView(Ui.label(this, getString(R.string.accent_help_title), 14,
                palette.text, Gravity.START));
        TextView accentHelp = Ui.text(this, getString(R.string.accent_help_desc), 11.5f,
                palette.dim, AppFonts.regular(this), Gravity.START);
        accentHelp.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 10));
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
                Ui.dp(this, 34), 0, Ui.dp(this, 2), 0, 0));
        TextView reset = Ui.label(this, getString(R.string.reset), 11.5f,
                palette.accent, Gravity.END);
        reset.setPadding(0, Ui.dp(this, 2), 0, 0);
        reset.setOnClickListener(v -> offsetSeek.setProgress(100));
        beatSection.addView(reset);
        page.addView(beatSection);

        TextView hint = Ui.text(this, getString(R.string.hint_beat), 11.5f,
                palette.faint, AppFonts.regular(this), Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 14), 0, 0);
        page.addView(hint);
        return page;
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

    private void showTrainingSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.training_title),
                getString(R.string.training_subtitle));
        sheet.setTall(true);
        LinearLayout body = sheet.body();
        body.setMinimumHeight(Ui.dp(this, isTablet ? 560 : 520));

        TextView current = Ui.text(this,
                getString(R.string.training_current, prefs.bpm()), 12,
                palette.accent, AppFonts.semibold(this), Gravity.START);
        current.setPadding(0, 0, 0, Ui.dp(this, 14));
        body.addView(current);

        String[] modes = {getString(R.string.training_time), getString(R.string.training_bars)};
        SegmentedView modeTabs = new SegmentedView(this, palette, modes, prefs.trainMode());
        body.addView(modeTabs, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42)));

        TextView intervalValue = trainingValue("");
        LinearLayout intervalRow = trainingRow(getString(R.string.training_interval), intervalValue);
        body.addView(intervalRow);
        intervalRow.setOnClickListener(v -> {
            boolean timeMode = prefs.trainMode() == 0;
            showNumberEntry(getString(R.string.training_interval), null,
                    timeMode ? prefs.trainIntervalSeconds() : prefs.trainBars(),
                    timeMode ? 5 : 1, timeMode ? 600 : 32,
                    timeMode ? getString(R.string.training_seconds_unit) : getString(R.string.training_bars_unit),
                    value -> {
                        if (prefs.trainMode() == 0) prefs.setTrainIntervalSeconds(value);
                        else prefs.setTrainBars(value);
                        refreshTrainingValues(intervalValue, null, null, null);
                    });
        });

        TextView incValue = trainingValue("");
        LinearLayout incRow = trainingRow(getString(R.string.training_increment), incValue);
        body.addView(incRow);
        incRow.setOnClickListener(v -> showNumberEntry(getString(R.string.training_increment), null,
                prefs.trainIncrement(), 1, 50,
                prefs.trainIncrementType() == 0 ? getString(R.string.bpm_unit) : "%",
                value -> {
                    prefs.setTrainIncrement(value);
                    refreshTrainingValues(null, incValue, null, null);
                }));

        String[] incTypes = {"+BPM", "%"};
        SegmentedView incTabs = new SegmentedView(this, palette, incTypes, prefs.trainIncrementType());
        body.addView(incTabs, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 40), 0, Ui.dp(this, 6), 0, 0));
        incTabs.setListener(index -> {
            prefs.setTrainIncrementType(index);
            refreshTrainingValues(null, incValue, null, null);
        });

        TextView targetValue = trainingValue("");
        LinearLayout targetRow = trainingRow(getString(R.string.training_target), targetValue);
        body.addView(targetRow);
        targetRow.setOnClickListener(v -> showNumberEntry(getString(R.string.training_target), null,
                prefs.trainTarget(), 20, 500, getString(R.string.bpm_unit),
                value -> {
                    prefs.setTrainTarget(value);
                    refreshTrainingValues(null, null, targetValue, null);
                }));

        TextView practiceValue = trainingValue("");
        LinearLayout practiceRow = trainingRow(getString(R.string.training_practice), practiceValue);
        body.addView(practiceRow);
        practiceRow.setOnClickListener(v -> showPracticeDurationSheet(practiceValue));

        trainingSummaryView = Ui.text(this, "", 11.5f, palette.dim,
                AppFonts.regular(this), Gravity.START);
        trainingSummaryView.setBackground(Ui.roundRect(this, palette.surfaceAlt, 12,
                Palette.withAlpha(palette.stroke, 0x80), 0.7f));
        trainingSummaryView.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        trainingSummaryView.setLineSpacing(Ui.dp(this, 2), 1f);
        body.addView(trainingSummaryView, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 74), 0, Ui.dp(this, 16), 0, 0));

        TextView start = Ui.label(this, trainingActive
                        ? getString(R.string.training_stop)
                        : getString(R.string.training_start),
                13, palette.onAccent, Gravity.CENTER);
        start.setBackground(Ui.rippleRound(this, palette.accent,
                Palette.withAlpha(palette.onAccent, 0x40), 16));
        start.setOnClickListener(v -> {
            if (trainingActive) {
                if (service != null) service.stopPractice(true);
                sheet.dismiss();
                return;
            }
            if (service == null) {
                Toast.makeText(this, R.string.training_need_service, Toast.LENGTH_SHORT).show();
                return;
            }
            int target = Math.max(prefs.bpm() + 1, prefs.trainTarget());
            prefs.setTrainTarget(target);
            service.startPractice(prefs.trainMode(), prefs.trainIntervalSeconds(),
                    prefs.trainBars(), prefs.trainIncrementType(),
                    prefs.trainIncrement(), target, prefs.practiceSeconds());
            sheet.dismiss();
        });
        body.addView(start, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 50), 0, Ui.dp(this, 18), 0, 0));

        modeTabs.setListener(index -> {
            prefs.setTrainMode(index);
            refreshTrainingValues(intervalValue, incValue, targetValue, practiceValue);
        });
        refreshTrainingValues(intervalValue, incValue, targetValue, practiceValue);
        sheet.showSheet();
    }

    private LinearLayout trainingRow(String label, TextView value) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.rippleRound(this, Color.TRANSPARENT,
                palette.accentSoft, 12));
        row.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        TextView name = Ui.text(this, label, 12, palette.dim,
                AppFonts.regular(this), Gravity.START);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(
                Ui.dp(this, 150), Ui.dp(this, 40)));
        TextView chevron = Ui.label(this, "›", 18, palette.faint, Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(
                Ui.dp(this, 20), Ui.dp(this, 40)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        params.topMargin = Ui.dp(this, 10);
        row.setLayoutParams(params);
        return row;
    }

    private TextView trainingValue(String text) {
        TextView value = Ui.label(this, text, 12, palette.text, Gravity.CENTER);
        value.setBackground(Ui.roundRect(this, palette.surfaceAlt, 10,
                Palette.withAlpha(palette.stroke, 0x90), 0.7f));
        return value;
    }

    private void refreshTrainingValues(TextView intervalValue, TextView incValue,
                                       TextView targetValue, TextView practiceValue) {
        if (intervalValue != null) {
            if (prefs.trainMode() == 0) {
                intervalValue.setText(formatDuration(prefs.trainIntervalSeconds()));
            } else {
                intervalValue.setText(getString(R.string.training_bars_value, prefs.trainBars()));
            }
        }
        if (incValue != null) {
            incValue.setText(prefs.trainIncrementType() == 0
                    ? getString(R.string.training_bpm_value, prefs.trainIncrement())
                    : getString(R.string.training_percent_value, prefs.trainIncrement()));
        }
        if (targetValue != null) {
            targetValue.setText(getString(R.string.training_bpm_value, prefs.trainTarget()));
        }
        if (practiceValue != null) {
            int seconds = prefs.practiceSeconds();
            practiceValue.setText(seconds % 60 == 0
                    ? getString(R.string.training_minutes_value, seconds / 60)
                    : formatDuration(seconds));
        }
        if (trainingSummaryView != null) {
            String interval = prefs.trainMode() == 0
                    ? formatDuration(prefs.trainIntervalSeconds())
                    : getString(R.string.training_bars_value, prefs.trainBars());
            String increment = prefs.trainIncrementType() == 0
                    ? getString(R.string.training_bpm_value, prefs.trainIncrement())
                    : getString(R.string.training_percent_value, prefs.trainIncrement());
            String target = getString(R.string.training_bpm_value, prefs.trainTarget());
            int seconds = prefs.practiceSeconds();
            String practice = seconds % 60 == 0
                    ? getString(R.string.training_minutes_value, seconds / 60)
                    : formatDuration(seconds);
            trainingSummaryView.setText(getString(R.string.training_summary_format,
                    interval, increment, target, practice));
        }
    }

    private void showPracticeDurationSheet(TextView practiceValue) {
        Sheet sheet = new Sheet(this, palette, getString(R.string.training_practice),
                getString(R.string.training_practice_hint));
        LinearLayout body = sheet.body();
        LinearLayout presets = Ui.row(this);
        for (int minutes : new int[]{1, 3, 5}) {
            TextView chip = Ui.label(this, getString(R.string.training_minutes_value, minutes),
                    12, palette.text, Gravity.CENTER);
            chip.setBackground(Ui.rippleRound(this, palette.surfaceAlt, palette.accentSoft,
                    14, Palette.withAlpha(palette.stroke, 0x90), 0.7f));
            chip.setOnClickListener(v -> {
                prefs.setPracticeSeconds(minutes * 60);
                refreshTrainingValues(null, null, null, practiceValue);
                sheet.dismiss();
            });
            presets.addView(chip, weightedChipParams());
        }
        body.addView(presets);

        TextView custom = Ui.label(this, getString(R.string.training_custom), 12,
                palette.text, Gravity.CENTER);
        custom.setBackground(Ui.rippleRound(this, palette.surfaceAlt, palette.accentSoft,
                14, Palette.withAlpha(palette.stroke, 0x90), 0.7f));
        custom.setOnClickListener(v -> showNumberEntry(getString(R.string.training_practice),
                null, prefs.practiceSeconds() / 60, 1, 120,
                getString(R.string.training_minutes_unit), value -> {
                    prefs.setPracticeSeconds(value * 60);
                    refreshTrainingValues(null, null, null, practiceValue);
                    sheet.dismiss();
                }));
        body.addView(custom, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 46), 0, Ui.dp(this, 10), 0, 0));
        sheet.showSheet();
    }

    private void showNumberEntry(String title, String subtitle, int initial,
                                 int min, int max, String suffix,
                                 java.util.function.IntConsumer onApply) {
        Sheet sheet = new Sheet(this, palette, title,
                subtitle == null ? "" : subtitle);
        LinearLayout body = sheet.body();
        NumberPadView pad = new NumberPadView(this, palette, initial, min, max, suffix);
        body.addView(pad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView apply = Ui.label(this, getString(R.string.apply), 13,
                palette.onAccent, Gravity.CENTER);
        apply.setBackground(Ui.rippleRound(this, palette.accent,
                Palette.withAlpha(palette.onAccent, 0x40), 16));
        apply.setOnClickListener(v -> {
            onApply.accept(pad.value());
            sheet.dismiss();
        });
        body.addView(apply, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 46), 0, Ui.dp(this, 14), 0, 0));
        sheet.showSheet();
    }

    private TextView stepButton(String text) {
        TextView button = Ui.label(this, text, 18, palette.text, Gravity.CENTER);
        button.setBackground(Ui.rippleRound(this, palette.surfaceAlt,
                palette.accentSoft, 10,
                Palette.withAlpha(palette.stroke, 0x90), 0.7f));
        return button;
    }

    private LinearLayout.LayoutParams weightedChipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 40), 1f);
        params.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        return params;
    }

    private void showSignatureSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.signature),
                getString(R.string.signature_subtitle));
        LinearLayout body = sheet.body();
        TextView hint = Ui.text(this, getString(R.string.signature_hint), 11.5f,
                palette.dim, AppFonts.regular(this), Gravity.START);
        hint.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 14));
        body.addView(hint);

        LinearLayout custom = card();
        TextView customTitle = Ui.text(this, getString(R.string.custom_signature), 12,
                palette.text, AppFonts.semibold(this), Gravity.START);
        custom.addView(customTitle);

        int[] customNum = {prefs.numerator()};
        int[] customDen = {prefs.denominator()};
        LinearLayout numRow = Ui.row(this);
        numRow.setGravity(Gravity.CENTER_VERTICAL);
        numRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        TextView numeratorLabel = Ui.text(this, getString(R.string.numerator), 11,
                palette.dim, AppFonts.regular(this), Gravity.START);
        numRow.addView(numeratorLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView numMinus = stepButton("−");
        TextView numValue = Ui.label(this, String.valueOf(customNum[0]), 16,
                palette.text, Gravity.CENTER);
        numValue.setBackground(Ui.roundRect(this, palette.surfaceAlt, 10,
                Palette.withAlpha(palette.stroke, 0x90), 0.7f));
        TextView numPlus = stepButton("+");
        numRow.addView(numMinus, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 32)));
        numRow.addView(numValue, new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 32)));
        numRow.addView(numPlus, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 32)));
        custom.addView(numRow);

        LinearLayout denRow = Ui.row(this);
        denRow.setGravity(Gravity.CENTER_VERTICAL);
        denRow.setPadding(0, Ui.dp(this, 6), 0, 0);
        TextView denominatorLabel = Ui.text(this, getString(R.string.denominator), 11,
                palette.dim, AppFonts.regular(this), Gravity.START);
        denRow.addView(denominatorLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int[] denominators = {2, 4, 8, 16};
        TextView[] denChips = new TextView[denominators.length];
        for (int i = 0; i < denominators.length; i++) {
            int den = denominators[i];
            TextView chip = Ui.label(this, String.valueOf(den), 12, palette.text, Gravity.CENTER);
            chip.setOnClickListener(v -> {
                customDen[0] = den;
                for (int j = 0; j < denChips.length; j++) {
                    boolean selected = denominators[j] == customDen[0];
                    denChips[j].setTextColor(selected ? palette.onAccent : palette.text);
                    denChips[j].setBackground(Ui.rippleRound(this,
                            selected ? palette.accent : palette.surfaceAlt,
                            palette.accentSoft, 10,
                            selected ? palette.accent : Palette.withAlpha(palette.stroke, 0x90), 0.7f));
                }
            });
            denChips[i] = chip;
            denRow.addView(chip, Ui.margins(Ui.dp(this, 38), Ui.dp(this, 32), Ui.dp(this, 2), 0,
                    Ui.dp(this, 2), 0));
        }
        custom.addView(denRow);

        TextView apply = Ui.label(this, getString(R.string.apply), 12,
                palette.onAccent, Gravity.CENTER);
        apply.setBackground(Ui.rippleRound(this, palette.accent,
                Palette.withAlpha(palette.onAccent, 0x40), 12));
        apply.setOnClickListener(v -> {
            applyMeter(customNum[0], customDen[0]);
            sheet.dismiss();
        });
        custom.addView(apply, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 34), 0, Ui.dp(this, 8), 0, 0));
        body.addView(custom);

        numMinus.setOnClickListener(v -> {
            customNum[0] = Math.max(1, customNum[0] - 1);
            numValue.setText(String.valueOf(customNum[0]));
        });
        numPlus.setOnClickListener(v -> {
            customNum[0] = Math.min(Prefs.MAX_BEATS, customNum[0] + 1);
            numValue.setText(String.valueOf(customNum[0]));
        });
        for (int j = 0; j < denChips.length; j++) {
            boolean selected = denominators[j] == customDen[0];
            denChips[j].setTextColor(selected ? palette.onAccent : palette.text);
            denChips[j].setBackground(Ui.rippleRound(this,
                    selected ? palette.accent : palette.surfaceAlt,
                    palette.accentSoft, 10,
                    selected ? palette.accent : Palette.withAlpha(palette.stroke, 0x90), 0.7f));
        }

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
        if (bars != null) bars.setBeats(numerator, modes);
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
        setBpmValue(prefs.bpm(), false);
        if (bars != null) bars.setBeats(prefs.numerator(), prefs.beatModes());
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

    private void showTargetBpmSheet() {
        Sheet sheet = new Sheet(this, palette, getString(R.string.target_bpm_title),
                getString(R.string.target_bpm_subtitle));
        sheet.setTall(true);
        LinearLayout body = sheet.body();

        TextView current = Ui.label(this,
                getString(R.string.target_current, currentBpm()), 13, palette.accent,
                Gravity.CENTER);
        current.setPadding(0, 0, 0, Ui.dp(this, 10));
        body.addView(current, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        NumberPadView pad = new NumberPadView(this, palette, currentBpm(),
                Prefs.MIN_BPM, Prefs.MAX_BPM, getString(R.string.bpm_unit));
        body.addView(pad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView jump = Ui.label(this, getString(R.string.target_jump), 13,
                palette.onAccent, Gravity.CENTER);
        jump.setBackground(Ui.rippleRound(this, palette.accent,
                Palette.withAlpha(palette.onAccent, 0x40), 23));
        jump.setOnClickListener(v -> applyTargetBpm(pad.value(), sheet, pad));
        body.addView(jump, Ui.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 44), 0, Ui.dp(this, 10), 0, 0));

        TextView commonLabel = Ui.label(this, getString(R.string.target_common), 11,
                palette.faint, Gravity.START);
        commonLabel.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        body.addView(commonLabel);

        int[] common = {60, 80, 100, 120, 140, 160, 180, 200};
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = Ui.row(this);
            for (int col = 0; col < 4; col++) {
                int value = common[rowIndex * 4 + col];
                TextView chip = Ui.label(this, String.valueOf(value), 13,
                        palette.text, Gravity.CENTER);
                chip.setBackground(Ui.rippleRound(this, palette.surfaceAlt,
                        palette.accentSoft, 14,
                        Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
                chip.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
                chip.setContentDescription(getString(R.string.target_choose_bpm, value));
                chip.setOnClickListener(v -> {
                    pad.setValue(value);
                    applyTargetBpm(value, sheet, pad);
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                        Ui.dp(this, 36), 1f);
                params.setMargins(Ui.dp(this, 2), Ui.dp(this, 2),
                        Ui.dp(this, 2), Ui.dp(this, 2));
                row.addView(chip, params);
            }
            body.addView(row);
        }

        sheet.showSheet();
    }

    private void applyTargetBpm(int value, Sheet sheet, View focus) {
        setBpmValue(Prefs.clampBpm(value), true);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        sheet.dismiss();
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
            return "1.3.1";
        }
    }

    private static final class SquareHost extends FrameLayout {
        SquareHost(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            int size = Math.max(1, Math.min(width, height));
            int exact = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(exact, exact);
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int height = bottom - top;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int childWidth = child.getMeasuredWidth();
                int childHeight = child.getMeasuredHeight();
                int childLeft = getPaddingLeft()
                        + (width - getPaddingLeft() - getPaddingRight() - childWidth) / 2;
                int childTop = getPaddingTop()
                        + (height - getPaddingTop() - getPaddingBottom() - childHeight) / 2;
                child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
            }
        }
    }

    private interface ToggleListener {
        void onChanged(boolean checked);
    }
}

package com.tempopulse.metronome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

/** Per-beat indicators; tapping cycles normal 鈫?accent 鈫?mute. */
public final class BeatDotsView extends View {
    public interface Listener {
        void onBeatModeChanged(int beat, int mode);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Palette palette;
    private Listener listener;
    private int[] modes = new int[]{1, 0, 0, 0};
    private int currentBeat = -1;
    private boolean playing;
    private long pulseStart = -10000L;
    private float[] centers = new float[0];
    private float step;
    private int pressedIndex = -1;

    public BeatDotsView(Context context, Palette palette) {
        super(context);
        this.palette = palette;
        setClickable(true);
        setFocusable(true);
    }

    public void setPalette(Palette palette) {
        this.palette = palette;
        invalidate();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setBeats(int beats, int[] modes) {
        beats = Math.max(1, Math.min(12, beats));
        this.modes = modes == null ? new int[beats] : Arrays.copyOf(modes, beats);
        if (this.modes.length < beats) {
            int[] padded = new int[beats];
            System.arraycopy(this.modes, 0, padded, 0, this.modes.length);
            this.modes = padded;
        }
        if (currentBeat >= beats) currentBeat = -1;
        updateContentDescription();
        if (getWidth() > 0) layoutDots(getWidth());
        requestLayout();
        invalidate();
    }

    public void setCurrentBeat(int beat) {
        if (currentBeat != beat) {
            currentBeat = beat;
            pulseStart = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        if (!playing) currentBeat = -1;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Ui.dp(getContext(), 46);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutDots(w);
    }

    private void layoutDots(int width) {
        int n = modes.length;
        centers = new float[n];
        float maxStep = Ui.dp(getContext(), 26);
        step = Math.min(maxStep, (width - Ui.dp(getContext(), 24)) / (float) Math.max(1, n));
        float total = step * (n - 1);
        float start = width / 2f - total / 2f;
        for (int i = 0; i < n; i++) centers[i] = start + i * step;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (centers.length == 0) return;
        float cy = getHeight() / 2f;
        float dotRadius = Math.min(Ui.dp(getContext(), 4.4f), step * 0.22f);
        long elapsed = SystemClock.uptimeMillis() - pulseStart;
        float t = Math.min(1f, elapsed / 420f);
        float pulseAlpha = (1f - t) * (1f - t);

        for (int i = 0; i < centers.length; i++) {
            float x = centers[i];
            int mode = i < modes.length ? modes[i] : 0;
            boolean isCurrent = playing && i == currentBeat;
            if (isCurrent && t < 1f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Palette.withAlpha(palette.accent, (int) (70 * pulseAlpha)));
                canvas.drawCircle(x, cy, dotRadius + Ui.dp(getContext(), 8) * (0.35f + t), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), 1.2f));
                paint.setColor(Palette.withAlpha(palette.accent, (int) (170 * pulseAlpha)));
                canvas.drawCircle(x, cy, dotRadius + Ui.dp(getContext(), 10) * (0.4f + t * 0.8f), paint);
            }

            if (mode == 2) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), 1.25f));
                paint.setColor(isCurrent ? palette.text : palette.faint);
                canvas.drawCircle(x, cy, dotRadius, paint);
                canvas.drawLine(x - dotRadius * 0.75f, cy + dotRadius * 0.75f,
                        x + dotRadius * 0.75f, cy - dotRadius * 0.75f, paint);
            } else if (mode == 1) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), 1.2f));
                paint.setColor(Palette.withAlpha(palette.accent, isCurrent ? 0xFF : 0x88));
                canvas.drawCircle(x, cy, dotRadius + Ui.dp(getContext(), 3.2f), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(isCurrent ? palette.text : palette.accent);
                canvas.drawCircle(x, cy, dotRadius * 1.06f, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(isCurrent ? palette.accent : palette.dotIdle);
                canvas.drawCircle(x, cy, isCurrent ? dotRadius * 1.16f : dotRadius, paint);
            }
        }
        if (t < 1f && playing) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float cy = getHeight() / 2f;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressedIndex = nearest(event.getX(), event.getY(), cy);
                return pressedIndex >= 0;
            }
            case MotionEvent.ACTION_MOVE:
                if (pressedIndex >= 0) {
                    int next = nearest(event.getX(), event.getY(), cy);
                    if (next != pressedIndex) {
                        pressedIndex = -1;
                        invalidate();
                    }
                }
                return pressedIndex >= 0;
            case MotionEvent.ACTION_UP: {
                int index = nearest(event.getX(), event.getY(), cy);
                if (index >= 0 && index == pressedIndex) {
                    int nextMode = (modes[index] + 1) % 3;
                    modes[index] = nextMode;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    updateContentDescription();
                    invalidate();
                    if (listener != null) listener.onBeatModeChanged(index, nextMode);
                    performClick();
                }
                pressedIndex = -1;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressedIndex = -1;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private int nearest(float x, float y, float cy) {
        if (Math.abs(y - cy) > Ui.dp(getContext(), 22)) return -1;
        for (int i = 0; i < centers.length; i++) {
            if (Math.abs(x - centers[i]) <= Math.max(Ui.dp(getContext(), 16), step * 0.48f)) {
                return i;
            }
        }
        return -1;
    }

    private void updateContentDescription() {
        StringBuilder builder = new StringBuilder();
        builder.append(getContext().getString(R.string.beats_description, modes.length)).append(": ");
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(i + 1).append(' ');
            if (modes[i] == 1) builder.append(getContext().getString(R.string.mode_accent));
            else if (modes[i] == 2) builder.append(getContext().getString(R.string.mode_mute));
            else builder.append(getContext().getString(R.string.mode_normal));
        }
        setContentDescription(builder.toString());
    }
}

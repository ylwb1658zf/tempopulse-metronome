package com.tempopulse.metronome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

/**
 * Large performance beat display. Each column is one beat; subdivision cells
 * fill with the current beat, and tapping a column cycles normal / accent / mute.
 */
public final class BeatBarsView extends View {
    public interface Listener {
        void onBeatModeChanged(int beat, int mode);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Palette palette;
    private Listener listener;
    private int[] modes = new int[]{1, 0, 0, 0};
    private int subdivision = 1;
    private int bpm = 120;
    private int currentBeat = -1;
    private boolean playing;
    private boolean fillHeight;
    private long beatStart = -10000L;
    private int pressedIndex = -1;

    public BeatBarsView(Context context, Palette palette) {
        super(context);
        this.palette = palette;
        setClickable(true);
        setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
        updateDescription();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setBeats(int beats, int[] beatModes) {
        beats = Math.max(1, Math.min(Prefs.MAX_BEATS, beats));
        int[] next = beatModes == null ? new int[beats] : Arrays.copyOf(beatModes, beats);
        if (next.length < beats) {
            int[] padded = new int[beats];
            System.arraycopy(next, 0, padded, 0, next.length);
            next = padded;
        }
        modes = next;
        if (currentBeat >= beats) currentBeat = -1;
        updateDescription();
        requestLayout();
        invalidate();
    }

    public void setSubdivision(int subdivision) {
        this.subdivision = Math.max(1, Math.min(4, subdivision));
        invalidate();
    }

    public void setFillHeight(boolean fillHeight) {
        this.fillHeight = fillHeight;
        invalidate();
    }

    public void setBpm(int bpm) {
        this.bpm = Math.max(20, Math.min(Prefs.MAX_BPM, bpm));
    }

    public void setCurrentBeat(int beat) {
        currentBeat = beat;
        beatStart = SystemClock.uptimeMillis();
        invalidate();
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        if (!playing) currentBeat = -1;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int wanted = Ui.dp(getContext(), 104);
        int height = resolveSize(wanted, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = modes.length;
        float pad = Ui.dp(getContext(), 12);
        float top = Ui.dp(getContext(), 8);
        float progressH = Ui.dp(getContext(), 3);
        float bottom = getHeight() - Ui.dp(getContext(), 10);
        float gap = Ui.dp(getContext(), n > 8 ? 5 : 8);
        float available = getWidth() - pad * 2 - gap * (n - 1);
        float barW = Math.max(Ui.dp(getContext(), 6), available / n);
        float maxBarH = fillHeight ? Float.MAX_VALUE : barW * 2.2f;
        float barH = Math.min(Math.max(Ui.dp(getContext(), 18), bottom - top), maxBarH);

        long now = SystemClock.uptimeMillis();
        float beatDuration = 60000f / Math.max(20, bpm);
        float phase = playing && currentBeat >= 0
                ? Math.min(0.999f, (now - beatStart) / beatDuration) : 0f;

        for (int i = 0; i < n; i++) {
            float left = pad + i * (barW + gap);
            float right = left + barW;
            int mode = i < modes.length ? modes[i] : 0;
            boolean current = playing && i == currentBeat;

            // Base column.
            rect.set(left, top, right, top + barH);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(mode == 2 ? Palette.withAlpha(palette.surfaceHigh,
                    palette.dark ? 0x60 : 0x80) : palette.surfaceHigh);
            canvas.drawRoundRect(rect, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), paint);

            if (mode == 2) {
                paint.setStrokeWidth(Ui.dp(getContext(), 1));
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Palette.withAlpha(palette.faint, 0x55));
                canvas.drawRoundRect(rect, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), paint);
            }

            // Subdivision cells. Non-current beats show the meter structure;
            // the current beat fills cells from the bottom.
            int cells = subdivision;
            float cellGap = cells > 1 ? Ui.dp(getContext(), 2.2f) : 0;
            float cellH = (barH - cellGap * (cells - 1)) / cells;
            for (int cell = 0; cell < cells; cell++) {
                // Cell 0 is the bottom cell, so the current beat fills upward.
                float cellTop = top + barH - (cell + 1) * cellH - cellGap * cell;
                rect.set(left + Ui.dp(getContext(), 1.5f), cellTop,
                        right - Ui.dp(getContext(), 1.5f), cellTop + cellH);
                float fill = 0f;
                if (current) {
                    fill = Math.max(0f, Math.min(1f, phase * cells - cell));
                }
                boolean accent = mode == 1;
                int base = mode == 2 ? Color.TRANSPARENT
                        : Palette.withAlpha(palette.text, palette.dark ? 0x09 : 0x07);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(base);
                if (base != Color.TRANSPARENT) {
                    canvas.drawRoundRect(rect, Ui.dp(getContext(), 2.4f),
                            Ui.dp(getContext(), 2.4f), paint);
                }
                if (fill > 0f) {
                    rect.top = rect.bottom - cellH * fill;
                    paint.setColor(accent ? palette.accent : Palette.withAlpha(
                            palette.accent, mode == 2 ? 0x55 : 0xDD));
                    canvas.drawRoundRect(rect, Ui.dp(getContext(), 2.2f),
                            Ui.dp(getContext(), 2.2f), paint);
                }
            }

            if (mode == 1) {
                float band = Math.min(Ui.dp(getContext(), 12), barH * 0.18f);
                rect.set(left + Ui.dp(getContext(), 1.5f), top + barH - band,
                        right - Ui.dp(getContext(), 1.5f), top + barH);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Palette.withAlpha(palette.accent, palette.dark ? 0xCC : 0xB8));
                canvas.drawRoundRect(rect, Ui.dp(getContext(), 2.4f),
                        Ui.dp(getContext(), 2.4f), paint);
            }

            // Current beat outline.
            if (current || mode == 1) {
                rect.set(left - Ui.dp(getContext(), 1), top - Ui.dp(getContext(), 1),
                        right + Ui.dp(getContext(), 1), top + barH + Ui.dp(getContext(), 1));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), current ? 2.2f : 1f));
                paint.setColor(current ? palette.accent
                        : Palette.withAlpha(palette.accent, 0x88));
                canvas.drawRoundRect(rect, Ui.dp(getContext(), current ? 7 : 6),
                        Ui.dp(getContext(), current ? 7 : 6), paint);
            }

            // Small beat number at the bottom.
            // Small beat number near the top.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(current ? palette.accent
                    : mode == 1 ? palette.accent : palette.faint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(AppFonts.semibold(getContext()));
            paint.setTextSize(Ui.sp(getContext(), 8.5f));
            canvas.drawText(String.valueOf(i + 1), (left + right) / 2f,
                    top + Ui.dp(getContext(), 16), paint);
        }

        // Measure progress rail, immediately below the columns.
        float railTop = Math.min(getHeight() - Ui.dp(getContext(), 5),
                top + barH + Ui.dp(getContext(), 8));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Palette.withAlpha(palette.faint, 0x30));
        rect.set(pad, railTop, getWidth() - pad, railTop + progressH);
        canvas.drawRoundRect(rect, progressH / 2f, progressH / 2f, paint);
        if (currentBeat >= 0) {
            float fraction = (currentBeat + phase) / n;
            rect.set(pad, railTop, pad + (getWidth() - pad * 2) * fraction, railTop + progressH);
            paint.setColor(palette.accent);
            canvas.drawRoundRect(rect, progressH / 2f, progressH / 2f, paint);
        } else {
            rect.set(pad, railTop, pad + (getWidth() - pad * 2) / n, railTop + progressH);
            paint.setColor(Palette.withAlpha(palette.accent, 0x66));
            canvas.drawRoundRect(rect, progressH / 2f, progressH / 2f, paint);
        }
        if (playing) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int n = modes.length;
        float pad = Ui.dp(getContext(), 12);
        float gap = Ui.dp(getContext(), n > 8 ? 5 : 8);
        float barW = (getWidth() - pad * 2 - gap * (n - 1)) / n;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedIndex = indexAt(event.getX(), barW, pad, gap, n);
                return pressedIndex >= 0;
            case MotionEvent.ACTION_MOVE:
                if (pressedIndex >= 0
                        && indexAt(event.getX(), barW, pad, gap, n) != pressedIndex) {
                    pressedIndex = -1;
                }
                return true;
            case MotionEvent.ACTION_UP: {
                int index = indexAt(event.getX(), barW, pad, gap, n);
                if (index >= 0 && index == pressedIndex) {
                    int next = (modes[index] + 1) % 3;
                    modes[index] = next;
                    updateDescription();
                    invalidate();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (listener != null) listener.onBeatModeChanged(index, next);
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

    private int indexAt(float x, float barW, float pad, float gap, int n) {
        if (x < pad || x > getWidth() - pad) return -1;
        for (int i = 0; i < n; i++) {
            float left = pad + i * (barW + gap);
            if (x >= left && x <= left + barW) return i;
        }
        return -1;
    }

    private void updateDescription() {
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

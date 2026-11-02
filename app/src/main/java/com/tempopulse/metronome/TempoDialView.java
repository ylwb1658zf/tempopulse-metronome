package com.tempopulse.metronome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** The tempo wheel: 20鈥?00 BPM on a 270掳 arc, drag anywhere on the ring. */
public final class TempoDialView extends View {
    public interface Listener {
        void onBpmChanged(int bpm, boolean committed);
    }

    private static final int MIN_BPM = Prefs.MIN_BPM;
    private static final int MAX_BPM = Prefs.MAX_BPM;
    private static final float START_ANGLE = 135f;
    private static final float SWEEP = 270f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final Typeface light;
    private final Typeface semibold;

    private Palette palette;
    private Listener listener;
    private int bpm = 120;
    private float cx;
    private float cy;
    private float radius;
    private float arcRadius;
    private boolean dragging;
    private long pulseStart = -10000L;
    private int pulseMode = 1;
    private Shader accentShader;

    public TempoDialView(Context context, Palette palette) {
        super(context);
        this.palette = palette;
        this.light = AppFonts.light(context);
        this.semibold = AppFonts.semibold(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Tempo");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
    }

    public void setPalette(Palette palette) {
        this.palette = palette;
        invalidate();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int bpm() {
        return bpm;
    }

    public void setBpm(int value, boolean notify) {
        int next = Math.max(MIN_BPM, Math.min(MAX_BPM, value));
        if (next == bpm) {
            if (notify && listener != null) listener.onBpmChanged(bpm, true);
            return;
        }
        bpm = next;
        updateContentDescription();
        invalidate();
        if (notify && listener != null) listener.onBpmChanged(bpm, true);
    }

    public void pulse(int beatMode) {
        pulseMode = beatMode;
        pulseStart = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (height == 0) height = width;
        int size = Math.min(width, height);
        setMeasuredDimension(width, Math.max(size, Ui.dp(getContext(), 220)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - Ui.dp(getContext(), 2);
        arcRadius = radius - Ui.dp(getContext(), 22);
        arcRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius);
        accentShader = new LinearGradient(cx - arcRadius, cy + arcRadius,
                cx + arcRadius, cy - arcRadius,
                new int[]{palette.accentDeep, palette.accent,
                        Palette.blend(palette.accent, Color.WHITE, 0.18f)},
                null, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;

        // Soft ambient glow behind the wheel.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, radius * 1.02f,
                new int[]{palette.accentGlow, Palette.withAlpha(palette.accent, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        // Dial body.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(palette.dark ? 0xB3101720 : 0xE6FFFCF7);
        canvas.drawCircle(cx, cy, radius - Ui.dp(getContext(), 1), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(palette.stroke);
        canvas.drawCircle(cx, cy, radius - Ui.dp(getContext(), 1), paint);
        paint.setColor(Palette.withAlpha(palette.stroke, 0x66));
        canvas.drawCircle(cx, cy, radius - Ui.dp(getContext(), 13), paint);

        drawArc(canvas);
        drawTicks(canvas);
        drawIndicator(canvas);
        drawCenter(canvas);
        drawPulse(canvas);
    }

    private void drawTicks(Canvas canvas) {
        int steps = (MAX_BPM - MIN_BPM) / 5;
        float outer = radius - Ui.dp(getContext(), 6);
        paint.setShader(null);
        for (int i = 0; i <= steps; i++) {
            int value = MIN_BPM + i * 5;
            float progress = (value - MIN_BPM) / (float) (MAX_BPM - MIN_BPM);
            float angle = START_ANGLE + SWEEP * progress;
            boolean major = value % 20 == 0;
            float inner = outer - Ui.dp(getContext(), major ? 9 : 4.5f);
            double rad = Math.toRadians(angle);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            paint.setStrokeWidth(Ui.dp(getContext(), major ? 1.4f : 0.9f));
            paint.setColor(major ? Palette.withAlpha(palette.dim, 0xB0)
                    : Palette.withAlpha(palette.faint, 0x66));
            canvas.drawLine(cx + cos * inner, cy + sin * inner,
                    cx + cos * outer, cy + sin * outer, paint);
            if (major && value % 60 == 0 && value >= 60 && value <= 240) {
                drawTickLabel(canvas, String.valueOf(value), angle, radius * 0.70f);
            }
        }
    }

    private void drawTickLabel(Canvas canvas, String label, float angle, float distance) {
        double rad = Math.toRadians(angle);
        float x = cx + (float) Math.cos(rad) * distance;
        float y = cy + (float) Math.sin(rad) * distance;
        textPaint.setTypeface(semibold);
        textPaint.setTextSize(Ui.sp(getContext(), 9.2f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Palette.withAlpha(palette.dim, 0xE0));
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(label, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint);
    }

    private void drawArc(Canvas canvas) {
        float progress = (bpm - MIN_BPM) / (float) (MAX_BPM - MIN_BPM);
        float sweep = SWEEP * progress;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(null);
        paint.setStrokeWidth(Ui.dp(getContext(), 9));
        paint.setColor(Palette.withAlpha(palette.dotIdle, 0xC0));
        canvas.drawArc(arcRect, START_ANGLE, SWEEP, false, paint);

        paint.setStrokeWidth(Ui.dp(getContext(), 18));
        paint.setColor(Palette.withAlpha(palette.accent, 0x1F));
        if (sweep > 0.5f) canvas.drawArc(arcRect, START_ANGLE, sweep, false, paint);

        paint.setStrokeWidth(Ui.dp(getContext(), 9));
        paint.setAlpha(255);
        paint.setShader(accentShader);
        if (sweep > 0.5f) canvas.drawArc(arcRect, START_ANGLE, sweep, false, paint);
        paint.setShader(null);
        paint.setAlpha(255);
    }

    private void drawIndicator(Canvas canvas) {
        float progress = (bpm - MIN_BPM) / (float) (MAX_BPM - MIN_BPM);
        double rad = Math.toRadians(START_ANGLE + SWEEP * progress);
        float x = cx + (float) Math.cos(rad) * arcRadius;
        float y = cy + (float) Math.sin(rad) * arcRadius;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Palette.withAlpha(palette.accent, 0x30));
        canvas.drawCircle(x, y, Ui.dp(getContext(), 12), paint);
        paint.setColor(palette.accent);
        canvas.drawCircle(x, y, Ui.dp(getContext(), 5.2f), paint);
        paint.setColor(palette.onAccent);
        canvas.drawCircle(x, y, Ui.dp(getContext(), 1.7f), paint);
    }

    private void drawCenter(Canvas canvas) {
        float numberSize = Math.min(Ui.sp(getContext(), 105), radius * 0.65f);
        textPaint.setShader(null);
        textPaint.setTypeface(light);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(palette.text);
        textPaint.setTextSize(numberSize);
        Paint.FontMetrics numberMetrics = textPaint.getFontMetrics();
        float numberBaseline = cy + (numberMetrics.descent - numberMetrics.ascent) / 2f
                - numberMetrics.descent - Ui.dp(getContext(), 18);
        canvas.drawText(String.valueOf(bpm), cx, numberBaseline, textPaint);

        textPaint.setTypeface(semibold);
        textPaint.setTextSize(Ui.sp(getContext(), 10f));
        textPaint.setLetterSpacing(0.26f);
        textPaint.setColor(palette.dim);
        canvas.drawText(getContext().getString(R.string.bpm_unit).toUpperCase(), cx,
                cy + Ui.dp(getContext(), 34), textPaint);
        textPaint.setLetterSpacing(0f);

        String marking = TempoName.of(getContext(), bpm);
        textPaint.setTypeface(semibold);
        textPaint.setTextSize(Ui.sp(getContext(), 11.5f));
        textPaint.setColor(palette.accent);
        canvas.drawText(marking, cx, cy + Ui.dp(getContext(), 58), textPaint);
    }

    private void drawPulse(Canvas canvas) {
        long elapsed = SystemClock.uptimeMillis() - pulseStart;
        if (elapsed < 0 || elapsed > 460) return;
        float t = elapsed / 460f;
        float eased = 1f - (1f - t) * (1f - t);
        float progress = (bpm - MIN_BPM) / (float) (MAX_BPM - MIN_BPM);
        double rad = Math.toRadians(START_ANGLE + SWEEP * progress);
        float x = cx + (float) Math.cos(rad) * arcRadius;
        float y = cy + (float) Math.sin(rad) * arcRadius;
        int color = pulseMode == 2 ? palette.faint : palette.accent;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1.6f));
        paint.setColor(Palette.withAlpha(color, (int) (150 * (1f - eased))));
        canvas.drawCircle(x, y, Ui.dp(getContext(), 7 + 20 * eased), paint);
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float dx = event.getX() - cx;
                float dy = event.getY() - cy;
                float distance = (float) Math.hypot(dx, dy);
                if (distance < radius * 0.28f) return false;
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                applyTouch(event.getX(), event.getY(), false);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    applyTouch(event.getX(), event.getY(), false);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (listener != null) listener.onBpmChanged(bpm, true);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void applyTouch(float x, float y, boolean committed) {
        double degrees = Math.toDegrees(Math.atan2(y - cy, x - cx));
        if (degrees < 0) degrees += 360;
        double relative = (degrees - START_ANGLE + 360) % 360;
        if (relative > 270) {
            if (relative < 315) relative = 270;
            else relative = 0;
        }
        float progress = (float) Math.max(0, Math.min(1, relative / SWEEP));
        int next = Math.round(MIN_BPM + progress * (MAX_BPM - MIN_BPM));
        if (next != bpm) {
            int old = bpm;
            bpm = next;
            updateContentDescription();
            if (old / 10 != bpm / 10) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            invalidate();
            if (listener != null) listener.onBpmChanged(bpm, committed);
        }
    }

    private void updateContentDescription() {
        setContentDescription(getContext().getString(R.string.bpm_unit) + " " + bpm);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, MIN_BPM, MAX_BPM, bpm));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            setBpm(bpm + 1, true);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            setBpm(bpm - 1, true);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}

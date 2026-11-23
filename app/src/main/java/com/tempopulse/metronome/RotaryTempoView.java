package com.tempopulse.metronome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A physical-feeling rotary tempo wheel with the transport control in its centre.
 * The outer ridged ring is turned to change BPM; the centre starts/stops playback.
 */
public final class RotaryTempoView extends View {
    public interface Listener {
        void onBpmChanged(int bpm, boolean committed);

        void onPlayTapped();
    }

    private static final float DEGREES_PER_BPM = 2.2f;
    private static final int MODE_NONE = 0;
    private static final int MODE_RING = 1;
    private static final int MODE_CENTER = 2;
    private static final int MODE_LEFT = 3;
    private static final int MODE_RIGHT = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private final RectF rect = new RectF();
    private final Palette palette;

    private Listener listener;
    private int bpm = 120;
    private boolean playing;
    private int touchMode = MODE_NONE;
    private float lastAngle;
    private float dragBpm;
    private long lastMoveAt;
    private float smoothedDegPerSec;
    private long lastHapticAt;
    private ValueAnimator fling;
    private long pulseStart = -10000L;
    private float cx;
    private float cy;
    private float radius;
    private int maxDiameterPx;

    public RotaryTempoView(Context context, Palette palette) {
        super(context);
        this.palette = palette;
        setClickable(true);
        setFocusable(true);
        setBackground(null);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
        updateDescription();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int bpm() {
        return bpm;
    }

    public void setMaxDiameterDp(int diameterDp) {
        maxDiameterPx = Ui.dp(getContext(), Math.max(180, diameterDp));
        requestLayout();
    }

    public void setBpm(int value, boolean committed) {
        int next = Prefs.clampBpm(value);
        if (fling != null) {
            fling.cancel();
            fling = null;
        }
        if (next == bpm) {
            if (committed && listener != null) listener.onBpmChanged(bpm, true);
            return;
        }
        bpm = next;
        updateDescription();
        invalidate();
        if (listener != null) listener.onBpmChanged(bpm, committed);
    }

    public void commitCurrent() {
        if (listener != null) listener.onBpmChanged(bpm, true);
    }

    public void setPlaying(boolean value) {
        if (playing != value) {
            playing = value;
            updateDescription();
            invalidate();
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    public void beatPulse() {
        pulseStart = SystemClock.uptimeMillis();
        invalidate();
    }

    public void bump() {
        animate().cancel();
        animate().scaleX(0.985f).scaleY(0.985f).setDuration(55)
                .withEndAction(() -> animate().scaleX(1f).scaleY(1f)
                        .setDuration(110).start()).start();
    }

    private void updateDescription() {
        setContentDescription(getContext().getString(R.string.rotary_accessibility, bpm));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int available = Math.min(width, height);
        if (maxDiameterPx > 0) available = Math.min(available, maxDiameterPx);
        int size = Math.max(Ui.dp(getContext(), 180), available);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - Ui.dp(getContext(), 5);
    }

    private float angleForBpm() {
        return (bpm - Prefs.MIN_BPM) * DEGREES_PER_BPM;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;
        float r = radius;
        long elapsed = SystemClock.uptimeMillis() - pulseStart;
        float pulse = 0f;
        if (elapsed >= 0 && elapsed < 480) {
            float t = Math.min(1f, elapsed / 480f);
            pulse = (1f - t) * (1f - t);
            postInvalidateOnAnimation();
        }

        // Ambient glow behind the metal wheel.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, r * 1.04f,
                new int[]{Palette.withAlpha(palette.accent, (palette.dark ? 0x16 : 0x0B)
                                + (int) (0x20 * pulse)),
                        Palette.withAlpha(palette.accent, 0)},
                new float[]{0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);

        int metalTop = palette.dark ? 0xFF5A6876 : 0xFFFAF4EB;
        int metalMid = palette.dark ? 0xFF2C3845 : 0xFFE0D4C4;
        int metalLow = palette.dark ? 0xFF121B25 : 0xFFBFAF9B;
        int ridgeA = palette.dark ? 0xFF3B4857 : 0xFFD2C5B3;
        int ridgeB = palette.dark ? 0xFF1B2530 : 0xFFF0E6D8;

        // Soft ground shadow.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        canvas.drawCircle(cx, cy + Ui.dp(getContext(), 5), r, paint);

        // Rotating ridged rim. The rim angle is tied to BPM, so the wheel
        // visibly moves with the tempo rather than being a static backdrop.
        canvas.save();
        canvas.rotate(angleForBpm(), cx, cy);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, r,
                new int[]{metalTop, metalMid, metalLow},
                new float[]{0.55f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float inner = r * 0.885f;
        float outer = r * 0.985f;
        for (int i = 0; i < 72; i++) {
            float a = (float) (i * Math.PI * 2.0 / 72.0);
            paint.setStrokeWidth(Ui.dp(getContext(), i % 2 == 0 ? 3.0f : 1.4f));
            paint.setColor(i % 2 == 0 ? ridgeA : ridgeB);
            canvas.drawLine(cx + (float) Math.cos(a) * inner,
                    cy + (float) Math.sin(a) * inner,
                    cx + (float) Math.cos(a) * outer,
                    cy + (float) Math.sin(a) * outer, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(Palette.withAlpha(palette.text, palette.dark ? 0x26 : 0x30));
        canvas.drawCircle(cx, cy, r * 0.975f, paint);
        canvas.restore();

        // Inner brushed-metal face.
        float face = r * 0.80f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx - face * 0.22f, cy - face * 0.28f, face * 1.35f,
                new int[]{metalTop, metalMid, metalLow},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, face, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1.4f));
        paint.setColor(Palette.withAlpha(palette.text, palette.dark ? 0x30 : 0x38));
        canvas.drawCircle(cx, cy, face, paint);
        paint.setStrokeWidth(Ui.dp(getContext(), 2));
        paint.setColor(Palette.withAlpha(palette.accent, palette.dark ? 0xCC : 0x99));
        canvas.drawCircle(cx, cy, face * 0.985f, paint);

        // Subtle radial brushing.
        paint.setStrokeWidth(Ui.dp(getContext(), 0.8f));
        paint.setColor(Palette.withAlpha(palette.text, palette.dark ? 0x13 : 0x12));
        for (int i = 0; i < 28; i++) {
            float a = (float) (i * Math.PI * 2.0 / 28.0);
            canvas.drawLine(cx + (float) Math.cos(a) * face * 0.36f,
                    cy + (float) Math.sin(a) * face * 0.36f,
                    cx + (float) Math.cos(a) * face * 0.94f,
                    cy + (float) Math.sin(a) * face * 0.94f, paint);
        }

        drawChevrons(canvas, face);
        drawCenter(canvas, face, pulse);
    }

    private void drawChevrons(Canvas canvas, float face) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 2.2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float offset = face * 0.62f;
        float size = Ui.dp(getContext(), 8);
        paint.setColor(touchMode == MODE_LEFT
                ? palette.accent : Palette.withAlpha(palette.dim, 0xAA));
        Path left = new Path();
        left.moveTo(cx - offset + size * 0.55f, cy - size);
        left.lineTo(cx - offset - size * 0.45f, cy);
        left.lineTo(cx - offset + size * 0.55f, cy + size);
        canvas.drawPath(left, paint);
        paint.setColor(touchMode == MODE_RIGHT
                ? palette.accent : Palette.withAlpha(palette.dim, 0xAA));
        Path right = new Path();
        right.moveTo(cx + offset - size * 0.55f, cy - size);
        right.lineTo(cx + offset + size * 0.45f, cy);
        right.lineTo(cx + offset - size * 0.55f, cy + size);
        canvas.drawPath(right, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawCenter(Canvas canvas, float face, float pulse) {
        float centerRadius = face * 0.39f;
        float scale = touchMode == MODE_CENTER ? 0.965f : 1f;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, centerRadius * 1.8f,
                new int[]{Palette.withAlpha(palette.accent,
                                (palette.dark ? 0x24 : 0x12) + (int) (0x50 * pulse)),
                        Palette.withAlpha(palette.accent, 0)},
                new float[]{0.35f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, centerRadius * 1.65f, paint);
        paint.setShader(null);

        paint.setColor(0x66000000);
        canvas.drawCircle(cx, cy + Ui.dp(getContext(), 3), centerRadius * scale, paint);
        int centerTop = palette.dark ? 0xFFD5DDE4 : 0xFFFFFBF4;
        int centerMid = palette.dark ? 0xFF8F9BA6 : 0xFFE6DACB;
        int centerLow = palette.dark ? 0xFF46525E : 0xFFB9AA97;
        paint.setShader(new RadialGradient(cx - centerRadius * 0.25f,
                cy - centerRadius * 0.3f, centerRadius * 1.5f,
                new int[]{centerTop, centerMid, centerLow},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, centerRadius * scale, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 2));
        paint.setColor(Palette.withAlpha(palette.accent, palette.dark ? 0xDD : 0xAA));
        canvas.drawCircle(cx, cy, centerRadius * scale - Ui.dp(getContext(), 1), paint);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(Palette.withAlpha(Color.WHITE, 0x55));
        canvas.drawCircle(cx, cy, centerRadius * scale - Ui.dp(getContext(), 4), paint);

        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setColor(palette.accent);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        float icon = centerRadius * 0.82f;
        if (playing) {
            float barW = icon * 0.15f;
            float barH = icon * 0.46f;
            float gap = icon * 0.17f;
            rect.set(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, iconPaint);
            rect.set(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, iconPaint);
        } else {
            float w = icon * 0.40f;
            float h = icon * 0.48f;
            float left = cx - w / 2f + icon * 0.06f;
            iconPath.reset();
            iconPath.moveTo(left, cy - h / 2f);
            iconPath.lineTo(left + w, cy);
            iconPath.lineTo(left, cy + h / 2f);
            iconPath.close();
            iconPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            iconPaint.setStrokeWidth(icon * 0.08f);
            canvas.drawPath(iconPath, iconPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (radius <= 0) return false;
        float x = event.getX();
        float y = event.getY();
        float dx = x - cx;
        float dy = y - cy;
        float distance = (float) Math.hypot(dx, dy);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (fling != null) {
                    fling.cancel();
                    fling = null;
                }
                if (distance <= radius * 0.30f) {
                    touchMode = MODE_CENTER;
                    invalidate();
                    return true;
                }
                if (distance <= radius * 0.72f && Math.abs(dy) < radius * 0.16f) {
                    if (Math.abs(dx) > radius * 0.38f) {
                        touchMode = dx < 0 ? MODE_LEFT : MODE_RIGHT;
                        invalidate();
                        return true;
                    }
                }
                if (distance >= radius * 0.38f) {
                    touchMode = MODE_RING;
                    lastAngle = (float) Math.atan2(dy, dx);
                    dragBpm = bpm;
                    smoothedDegPerSec = 0f;
                    lastHapticAt = 0L;
                    lastMoveAt = SystemClock.uptimeMillis();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (touchMode == MODE_RING) {
                    long now = SystemClock.uptimeMillis();
                    float angle = (float) Math.atan2(dy, dx);
                    float delta = normalizeAngle(angle - lastAngle);
                    lastAngle = angle;
                    float dt = Math.max(1f, now - lastMoveAt);
                    lastMoveAt = now;
                    float degrees = delta * 57.29578f;
                    float instantDegPerSec = degrees / dt * 1000f;
                    smoothedDegPerSec = smoothedDegPerSec * 0.68f
                            + instantDegPerSec * 0.32f;
                    float speed = Math.abs(smoothedDegPerSec);
                    // ±120°/s stays at the precise ratio; faster turns ramp up
                    // to a modest 1.5x sensitivity.
                    float acceleration = 1f + Math.min(1f,
                            Math.max(0f, (speed - 120f) / 330f)) * 0.5f;
                    dragBpm += degrees * acceleration / DEGREES_PER_BPM;
                    int next = Prefs.clampBpm(Math.round(dragBpm));
                    if (next != bpm) {
                        bpm = next;
                        updateDescription();
                        if (acceleration < 1.6f || now - lastHapticAt > 38) {
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            lastHapticAt = now;
                        }
                        invalidate();
                        if (listener != null) listener.onBpmChanged(bpm, false);
                    }
                    return true;
                }
                if (touchMode == MODE_LEFT || touchMode == MODE_RIGHT) {
                    if (distance > radius * 0.72f) {
                        touchMode = MODE_NONE;
                        invalidate();
                    }
                    return true;
                }
                if (touchMode == MODE_CENTER) {
                    if (distance > radius * 0.40f) {
                        touchMode = MODE_NONE;
                        invalidate();
                    }
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP: {
                int mode = touchMode;
                touchMode = MODE_NONE;
                invalidate();
                if (mode == MODE_CENTER) {
                    performClick();
                } else if (mode == MODE_LEFT) {
                    step(-1);
                } else if (mode == MODE_RIGHT) {
                    step(1);
                } else if (mode == MODE_RING) {
                    if (Math.abs(smoothedDegPerSec) > 150f) {
                        fling(smoothedDegPerSec);
                    } else {
                        commitCurrent();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                touchMode = MODE_NONE;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void step(int direction) {
        int next = Prefs.clampBpm(bpm + direction);
        if (next != bpm) {
            bpm = next;
            updateDescription();
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            invalidate();
            if (listener != null) listener.onBpmChanged(bpm, true);
        }
    }

    private void fling(float speedDegPerSec) {
        int direction = speedDegPerSec >= 0f ? 1 : -1;
        int extra = Math.min(12, Math.round(Math.abs(speedDegPerSec) * 0.12f));
        int target = Prefs.clampBpm(bpm + direction * extra);
        if (target == bpm) {
            commitCurrent();
            return;
        }
        fling = ValueAnimator.ofInt(bpm, target);
        fling.setDuration(Math.min(560, 220 + Math.abs(target - bpm) * 10L));
        fling.setInterpolator(new DecelerateInterpolator());
        fling.addUpdateListener(animation -> {
            int value = (int) animation.getAnimatedValue();
            if (value != bpm) {
                bpm = value;
                updateDescription();
                invalidate();
                if (listener != null) listener.onBpmChanged(bpm, false);
            }
        });
        fling.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                fling = null;
                commitCurrent();
            }
        });
        fling.start();
    }

    private float normalizeAngle(float angle) {
        while (angle > Math.PI) angle -= Math.PI * 2f;
        while (angle < -Math.PI) angle += Math.PI * 2f;
        return angle;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onPlayTapped();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (fling != null) fling.cancel();
        super.onDetachedFromWindow();
    }
}

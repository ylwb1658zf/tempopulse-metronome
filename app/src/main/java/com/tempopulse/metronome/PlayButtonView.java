package com.tempopulse.metronome;

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
import android.view.MotionEvent;
import android.view.View;

/** Large tactile transport button with a soft accent glow. */
public final class PlayButtonView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private final RectF rect = new RectF();
    private Palette palette;
    private boolean playing;
    private boolean pressed;
    private long pulseStart = -10000L;
    private Shader shader;

    public PlayButtonView(Context context, Palette palette) {
        super(context);
        this.palette = palette;
        setClickable(true);
        setFocusable(true);
        setBackground(null);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
        setContentDescription(context.getString(R.string.play));
    }

    public void setPalette(Palette palette) {
        this.palette = palette;
        shader = null;
        invalidate();
    }

    public void setPlaying(boolean playing) {
        if (this.playing != playing) {
            this.playing = playing;
            setContentDescription(getContext().getString(playing ? R.string.pause : R.string.play));
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Ui.dp(getContext(), 96);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f;
        shader = new LinearGradient(cx - r, cy - r, cx + r * 0.6f, cy + r,
                new int[]{Palette.blend(palette.accent, Color.WHITE, 0.14f),
                        palette.accent, palette.accentDeep},
                null, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - Ui.dp(getContext(), 4);
        if (r <= 0) return;

        long elapsed = SystemClock.uptimeMillis() - pulseStart;
        float pulse = 0f;
        if (elapsed >= 0 && elapsed < 500) {
            float t = elapsed / 500f;
            pulse = (1f - t) * (1f - t);
            postInvalidateOnAnimation();
        }

        // Ambient glow kept inside the circular face so the view never shows a square edge.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, r,
                new int[]{Palette.withAlpha(palette.accent,
                                (palette.dark ? 0x3A : 0x24) + (int) (0x24 * pulse)),
                        Palette.withAlpha(palette.accent, palette.dark ? 0x10 : 0x08),
                        Palette.withAlpha(palette.accent, 0)},
                new float[]{0.55f, 0.86f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);

        // Shadow and body.
        paint.setColor(0x55000000);
        canvas.drawCircle(cx, cy + Ui.dp(getContext(), 4), r, paint);
        paint.setAlpha(255);
        paint.setShader(shader);
        canvas.drawCircle(cx, cy, r * (pressed ? 0.965f : 1f), paint);
        paint.setShader(null);
        paint.setAlpha(255);

        // Inner light rim.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(0x55FFFFFF);
        canvas.drawCircle(cx, cy, r - Ui.dp(getContext(), 2), paint);

        drawIcon(canvas, cx, cy, r);
    }

    private void drawIcon(Canvas canvas, float cx, float cy, float r) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(palette.onAccent);
        iconPath.reset();
        if (playing) {
            float barW = r * 0.16f;
            float barH = r * 0.42f;
            float gap = r * 0.16f;
            rect.set(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
            rect.set(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
        } else {
            float width = r * 0.42f;
            float height = r * 0.48f;
            float left = cx - width / 2f + r * 0.06f;
            iconPath.moveTo(left, cy - height / 2f);
            iconPath.lineTo(left + width, cy);
            iconPath.lineTo(left, cy + height / 2f);
            iconPath.close();
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(r * 0.08f);
            canvas.drawPath(iconPath, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                pressed = false;
                invalidate();
                if (isInside(event.getX(), event.getY())) performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean isInside(float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f;
        return Math.hypot(x - cx, y - cy) <= r;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}

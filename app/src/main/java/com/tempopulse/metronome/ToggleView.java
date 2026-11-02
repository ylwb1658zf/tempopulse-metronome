package com.tempopulse.metronome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Small custom switch to avoid the platform's inconsistent default styling. */
public final class ToggleView extends View {
    public interface OnCheckedChangeListener {
        void onCheckedChanged(ToggleView view, boolean checked);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Palette palette;
    private boolean checked;
    private float fraction;
    private OnCheckedChangeListener listener;
    private ValueAnimator animator;
    private String label;

    public ToggleView(Context context, Palette palette, boolean checked) {
        super(context);
        this.palette = palette;
        this.checked = checked;
        this.fraction = checked ? 1f : 0f;
        setClickable(true);
        setFocusable(true);
        updateDescription();
    }

    public void setLabel(String label) {
        this.label = label;
        updateDescription();
    }

    private void updateDescription() {
        String state = getContext().getString(checked ? R.string.toggle_on : R.string.toggle_off);
        setContentDescription(label == null ? state : label + " " + state);
    }

    public void setPalette(Palette palette) {
        this.palette = palette;
        invalidate();
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value) {
        if (checked == value) return;
        checked = value;
        updateDescription();
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(fraction, value ? 1f : 0f);
        animator.setDuration(160);
        animator.addUpdateListener(animation -> {
            fraction = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setCheckedSilently(boolean value) {
        checked = value;
        fraction = value ? 1f : 0f;
        updateDescription();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Ui.dp(getContext(), 46), Ui.dp(getContext(), 28));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float inset = Ui.dp(getContext(), 1.5f);
        rect.set(inset, inset, w - inset, h - inset);
        float radius = rect.height() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Palette.blend(palette.surfaceHigh, palette.accent, fraction));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 0.8f));
        paint.setColor(Palette.withAlpha(palette.stroke, 0xB0));
        canvas.drawRoundRect(rect, radius, radius, paint);

        float knobR = radius - Ui.dp(getContext(), 3.2f);
        float minX = inset + Ui.dp(getContext(), 3.2f) + knobR;
        float maxX = w - inset - Ui.dp(getContext(), 3.2f) - knobR;
        float x = minX + (maxX - minX) * fraction;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawCircle(x, h / 2f + Ui.dp(getContext(), 1), knobR, paint);
        paint.setColor(fraction > 0.5f ? palette.onAccent : palette.text);
        canvas.drawCircle(x, h / 2f, knobR, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClick();
            return true;
        }
        return event.getActionMasked() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        checked = !checked;
        updateDescription();
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(fraction, checked ? 1f : 0f);
        animator.setDuration(160);
        animator.addUpdateListener(animation -> {
            fraction = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
        if (listener != null) listener.onCheckedChanged(this, checked);
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(checked);
        info.setClassName("android.widget.Switch");
    }
}

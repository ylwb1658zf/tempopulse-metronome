package com.tempopulse.metronome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Tiny equaliser glyph used to differentiate the six click sounds. */
public final class SoundBarsView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int seed;
    private final Palette palette;
    private boolean selected;

    public SoundBarsView(Context context, Palette palette, int seed, boolean selected) {
        super(context);
        this.palette = palette;
        this.seed = seed;
        this.selected = selected;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Ui.dp(getContext(), 40), Ui.dp(getContext(), 40));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(selected ? palette.accent : palette.surfaceHigh);
        canvas.drawCircle(cx, cy, Math.min(cx, cy) - Ui.dp(getContext(), 1), paint);

        float[] heights = {0.30f, 0.56f, 0.42f, 0.66f, 0.36f};
        int color = selected ? palette.onAccent : palette.accent;
        paint.setColor(color);
        float barW = Ui.dp(getContext(), 2.4f);
        float gap = Ui.dp(getContext(), 2.2f);
        float total = heights.length * barW + (heights.length - 1) * gap;
        float start = cx - total / 2f;
        for (int i = 0; i < heights.length; i++) {
            int shifted = (i + seed) % heights.length;
            float h = Ui.dp(getContext(), 15) * heights[shifted];
            float left = start + i * (barW + gap);
            rect.set(left, cy - h / 2f, left + barW, cy + h / 2f);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
        }
    }
}

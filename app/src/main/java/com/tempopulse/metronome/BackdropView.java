package com.tempopulse.metronome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Full-bleed background with two low-contrast glows behind the instrument. */
public final class BackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Palette palette;

    public BackdropView(Context context, Palette palette) {
        super(context);
        this.palette = palette;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setPalette(Palette palette) {
        this.palette = palette;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        paint.setShader(new LinearGradient(0, 0, 0, h,
                palette.bg,
                Palette.blend(palette.bg, palette.dark ? 0xFF000000 : 0xFFFFFFFF, 0.08f),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        paint.setShader(new RadialGradient(w * 0.5f, h * 0.18f, Math.max(w, h) * 0.62f,
                new int[]{palette.accentGlow, Palette.withAlpha(palette.accent, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);

        paint.setShader(new RadialGradient(w * 0.08f, h * 0.92f, w * 0.75f,
                new int[]{Palette.withAlpha(palette.accentDeep, palette.dark ? 0x14 : 0x0E),
                        Palette.withAlpha(palette.accentDeep, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }
}

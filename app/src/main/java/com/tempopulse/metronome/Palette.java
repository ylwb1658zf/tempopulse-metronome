package com.tempopulse.metronome;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/** Central runtime palette so the whole UI can switch between light and dark. */
public final class Palette {
    public final boolean dark;
    public final int bg;
    public final int surface;
    public final int surfaceAlt;
    public final int surfaceHigh;
    public final int stroke;
    public final int text;
    public final int dim;
    public final int faint;
    public final int accent;
    public final int accentDeep;
    public final int accentSoft;
    public final int accentGlow;
    public final int onAccent;
    public final int dotIdle;
    public final int danger;
    public final int success;

    private static final String[] ACCENT_NAMES = {"amber", "mint", "coral", "violet"};

    private Palette(boolean dark, int accent, int accentDeep, int onAccent) {
        this.dark = dark;
        this.bg = dark ? 0xFF080B10 : 0xFFF4EEE5;
        this.surface = dark ? 0xFF101720 : 0xFFFFFCF7;
        this.surfaceAlt = dark ? 0xFF151E28 : 0xFFEEE5D8;
        this.surfaceHigh = dark ? 0xFF1B2531 : 0xFFE6DACA;
        this.stroke = dark ? 0xFF24313E : 0xFFE2D5C4;
        this.text = dark ? 0xFFF3F6FA : 0xFF1B1712;
        this.dim = dark ? 0xFF8A9BAC : 0xFF746658;
        this.faint = dark ? 0xFF5C6C7D : 0xFFA39381;
        this.accent = accent;
        this.accentDeep = accentDeep;
        this.onAccent = onAccent;
        this.accentSoft = withAlpha(accent, dark ? 0x2A : 0x1E);
        this.accentGlow = withAlpha(accent, dark ? 0x38 : 0x24);
        this.dotIdle = dark ? 0xFF2A3745 : 0xFFD8C9B7;
        this.danger = dark ? 0xFFFF6B76 : 0xFFD64B55;
        this.success = dark ? 0xFF5BD6A0 : 0xFF0E9670;
    }

    public static Palette create(Context context, Prefs prefs) {
        int mode = prefs.themeMode();
        boolean systemDark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = mode == Prefs.THEME_DARK || (mode == Prefs.THEME_SYSTEM && systemDark);
        int index = Math.max(0, Math.min(3, prefs.accentIndex()));
        if (dark) {
            int[] accents = {0xFFF7B955, 0xFF53E0B7, 0xFFFF7F8A, 0xFFB49BFF};
            int[] deeps = {0xFFE8872F, 0xFF17A97F, 0xFFDC4C61, 0xFF7A5BE0};
            int[] on = {0xFF211607, 0xFF062018, 0xFF2A070B, 0xFF160D2B};
            return new Palette(true, accents[index], deeps[index], on[index]);
        }
        int[] accents = {0xFFD97C12, 0xFF0B9A70, 0xFFD94F5D, 0xFF7153D3};
        int[] deeps = {0xFFB85D00, 0xFF087657, 0xFFB73545, 0xFF5539B8};
        return new Palette(false, accents[index], deeps[index], 0xFFFFFFFF);
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    public String accentNameKey() {
        return "accent_" + ACCENT_NAMES[Math.max(0, Math.min(3, accentIndex()))];
    }

    private int accentIndex() {
        if (accent == 0xFFF7B955 || accent == 0xFFD97C12) return 0;
        if (accent == 0xFF53E0B7 || accent == 0xFF0B9A70) return 1;
        if (accent == 0xFFFF7F8A || accent == 0xFFD94F5D) return 2;
        return 3;
    }
}

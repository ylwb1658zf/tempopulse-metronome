package com.tempopulse.metronome;

import android.content.Context;
import android.graphics.Typeface;

/** Bundled Poppins family; CJK glyphs fall back to the system font automatically. */
public final class AppFonts {
    private static Typeface light;
    private static Typeface regular;
    private static Typeface semibold;
    private static Typeface bold;

    private AppFonts() {}

    public static synchronized void init(Context context) {
        if (regular != null) return;
        Context app = context.getApplicationContext();
        light = load(app, "fonts/Poppins-Light.ttf");
        regular = load(app, "fonts/Poppins-Regular.ttf");
        semibold = load(app, "fonts/Poppins-SemiBold.ttf");
        bold = load(app, "fonts/Poppins-Bold.ttf");
    }

    private static Typeface load(Context context, String path) {
        try {
            return Typeface.createFromAsset(context.getAssets(), path);
        } catch (Exception e) {
            return Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    public static Typeface light(Context context) {
        init(context);
        return light;
    }

    public static Typeface regular(Context context) {
        init(context);
        return regular;
    }

    public static Typeface semibold(Context context) {
        init(context);
        return semibold;
    }

    public static Typeface bold(Context context) {
        init(context);
        return bold;
    }
}

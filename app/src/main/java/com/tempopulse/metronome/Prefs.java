package com.tempopulse.metronome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistent, device-local settings and user presets. */
public final class Prefs {
    public static final int THEME_DARK = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_SYSTEM = 2;
    public static final int MIN_BPM = 20;
    public static final int MAX_BPM = 300;
    public static final int MAX_PRESETS = 20;

    private static final String FILE = "tempopulse_prefs";
    private static final String K_BPM = "bpm";
    private static final String K_NUM = "numerator";
    private static final String K_DEN = "denominator";
    private static final String K_SUB = "subdivision";
    private static final String K_SOUND = "sound";
    private static final String K_VOLUME = "volume";
    private static final String K_MODES = "beat_modes";
    private static final String K_THEME = "theme";
    private static final String K_ACCENT = "accent";
    private static final String K_HAPTIC = "haptic";
    private static final String K_FLASH = "flash";
    private static final String K_AWAKE = "keep_awake";
    private static final String K_OFFSET = "visual_offset";
    private static final String K_PRESETS = "presets";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int bpm() {
        return clampBpm(sp.getInt(K_BPM, 120));
    }

    public void setBpm(int value) {
        sp.edit().putInt(K_BPM, clampBpm(value)).apply();
    }

    public int numerator() {
        return clampNumerator(sp.getInt(K_NUM, 4));
    }

    public int denominator() {
        return clampDenominator(sp.getInt(K_DEN, 4));
    }

    public void setMeter(int numerator, int denominator) {
        int oldNumerator = numerator();
        int oldDenominator = denominator();
        int nextNumerator = clampNumerator(numerator);
        int nextDenominator = clampDenominator(denominator);
        SharedPreferences.Editor editor = sp.edit()
                .putInt(K_NUM, nextNumerator)
                .putInt(K_DEN, nextDenominator);
        if (oldNumerator != nextNumerator || oldDenominator != nextDenominator) {
            editor.putString(K_MODES, defaultModesCsv(nextNumerator, nextDenominator));
        }
        editor.apply();
    }

    public int subdivision() {
        return Math.max(1, Math.min(4, sp.getInt(K_SUB, 1)));
    }

    public void setSubdivision(int value) {
        sp.edit().putInt(K_SUB, Math.max(1, Math.min(4, value))).apply();
    }

    public int sound() {
        return Math.max(0, Math.min(5, sp.getInt(K_SOUND, 0)));
    }

    public void setSound(int value) {
        sp.edit().putInt(K_SOUND, Math.max(0, Math.min(5, value))).apply();
    }

    public float volume() {
        return Math.max(0f, Math.min(1f, sp.getFloat(K_VOLUME, 0.82f)));
    }

    public void setVolume(float value) {
        sp.edit().putFloat(K_VOLUME, Math.max(0f, Math.min(1f, value))).apply();
    }

    public int themeMode() {
        return Math.max(0, Math.min(2, sp.getInt(K_THEME, THEME_DARK)));
    }

    public void setThemeMode(int mode) {
        sp.edit().putInt(K_THEME, Math.max(0, Math.min(2, mode))).apply();
    }

    public int accentIndex() {
        return Math.max(0, Math.min(3, sp.getInt(K_ACCENT, 0)));
    }

    public void setAccentIndex(int index) {
        sp.edit().putInt(K_ACCENT, Math.max(0, Math.min(3, index))).apply();
    }

    public boolean haptic() {
        return sp.getBoolean(K_HAPTIC, false);
    }

    public void setHaptic(boolean value) {
        sp.edit().putBoolean(K_HAPTIC, value).apply();
    }

    public boolean flash() {
        return sp.getBoolean(K_FLASH, false);
    }

    public void setFlash(boolean value) {
        sp.edit().putBoolean(K_FLASH, value).apply();
    }

    public boolean keepAwake() {
        return sp.getBoolean(K_AWAKE, true);
    }

    public void setKeepAwake(boolean value) {
        sp.edit().putBoolean(K_AWAKE, value).apply();
    }

    public int visualOffsetMs() {
        return Math.max(-100, Math.min(100, sp.getInt(K_OFFSET, 0)));
    }

    public void setVisualOffsetMs(int value) {
        sp.edit().putInt(K_OFFSET, Math.max(-100, Math.min(100, value))).apply();
    }

    public int[] beatModes() {
        int n = numerator();
        String raw = sp.getString(K_MODES, null);
        if (raw != null) {
            String[] pieces = raw.split(",");
            if (pieces.length == n) {
                int[] modes = new int[n];
                boolean valid = true;
                for (int i = 0; i < n; i++) {
                    try {
                        modes[i] = Integer.parseInt(pieces[i].trim());
                    } catch (NumberFormatException e) {
                        valid = false;
                        break;
                    }
                    if (modes[i] < 0 || modes[i] > 2) {
                        valid = false;
                        break;
                    }
                }
                if (valid) return modes;
            }
        }
        return defaultModes(n, denominator());
    }

    public void setBeatModes(int[] modes) {
        if (modes == null) return;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(Math.max(0, Math.min(2, modes[i])));
        }
        sp.edit().putString(K_MODES, builder.toString()).apply();
    }

    public List<Preset> presets() {
        ArrayList<Preset> result = new ArrayList<>();
        String raw = sp.getString(K_PRESETS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Preset preset = Preset.fromJson(array.optJSONObject(i));
                if (preset != null) result.add(preset);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public boolean savePreset(Preset preset) {
        if (preset == null) return false;
        List<Preset> all = presets();
        if (all.size() >= MAX_PRESETS) return false;
        all.add(0, preset);
        writePresets(all);
        return true;
    }

    public void deletePreset(String id) {
        List<Preset> all = presets();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        writePresets(all);
    }

    private void writePresets(List<Preset> all) {
        JSONArray array = new JSONArray();
        for (Preset preset : all) {
            try {
                array.put(preset.toJson());
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(K_PRESETS, array.toString()).apply();
    }

    public Preset presetFromCurrent(String name) {
        return new Preset("p" + System.currentTimeMillis(),
                name == null ? "" : name.trim(),
                bpm(), numerator(), denominator(), subdivision(), sound(), beatModes());
    }

    public static int clampBpm(int bpm) {
        return Math.max(MIN_BPM, Math.min(MAX_BPM, bpm));
    }

    private static int clampNumerator(int n) {
        return Math.max(1, Math.min(12, n));
    }

    private static int clampDenominator(int d) {
        if (d == 2 || d == 4 || d == 8 || d == 16) return d;
        return 4;
    }

    private static int[] defaultModes(int n, int denominator) {
        int[] modes = new int[n];
        if (n <= 0) return modes;
        modes[0] = 1;
        if (isCompound(n, denominator)) {
            for (int i = 3; i < n; i += 3) modes[i] = 1;
        }
        return modes;
    }

    private static boolean isCompound(int n, int denominator) {
        return n >= 6 && n % 3 == 0 && (denominator == 4 || denominator == 8);
    }

    private static String defaultModesCsv(int n, int denominator) {
        int[] modes = defaultModes(n, denominator);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(modes[i]);
        }
        return builder.toString();
    }
}

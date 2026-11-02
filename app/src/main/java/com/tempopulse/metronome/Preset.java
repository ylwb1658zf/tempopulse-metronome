package com.tempopulse.metronome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/** A saved tempo, meter, subdivision, sound and accent pattern. */
public final class Preset {
    public final String id;
    public final String name;
    public final int bpm;
    public final int numerator;
    public final int denominator;
    public final int subdivision;
    public final int sound;
    public final int[] beatModes;

    public Preset(String id, String name, int bpm, int numerator, int denominator,
                  int subdivision, int sound, int[] beatModes) {
        this.id = id;
        this.name = name;
        this.bpm = bpm;
        this.numerator = numerator;
        this.denominator = denominator;
        this.subdivision = subdivision;
        this.sound = sound;
        this.beatModes = beatModes == null ? new int[]{1} : Arrays.copyOf(beatModes, beatModes.length);
    }

    public String signature() {
        return numerator + "/" + denominator;
    }

    public String detail(Context context) {
        String[] shortNames = context.getResources().getStringArray(R.array.subdivision_short);
        int index = Math.max(0, Math.min(shortNames.length - 1, subdivision - 1));
        return context.getString(R.string.preset_detail, bpm, signature(), shortNames[index]);
    }

    public JSONObject toJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("name", name);
        object.put("bpm", bpm);
        object.put("numerator", numerator);
        object.put("denominator", denominator);
        object.put("subdivision", subdivision);
        object.put("sound", sound);
        JSONArray modes = new JSONArray();
        for (int mode : beatModes) modes.put(mode);
        object.put("modes", modes);
        return object;
    }

    public static Preset fromJson(JSONObject object) {
        if (object == null) return null;
        try {
            JSONArray modesJson = object.optJSONArray("modes");
            int[] modes;
            if (modesJson != null) {
                modes = new int[modesJson.length()];
                for (int i = 0; i < modesJson.length(); i++) modes[i] = modesJson.optInt(i, 0);
            } else {
                modes = new int[]{1};
            }
            return new Preset(
                    object.optString("id", "p" + System.currentTimeMillis()),
                    object.optString("name", ""),
                    object.optInt("bpm", 120),
                    object.optInt("numerator", 4),
                    object.optInt("denominator", 4),
                    object.optInt("subdivision", 1),
                    object.optInt("sound", 0),
                    modes);
        } catch (Exception e) {
            return null;
        }
    }
}

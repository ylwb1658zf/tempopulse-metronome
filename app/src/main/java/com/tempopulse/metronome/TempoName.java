package com.tempopulse.metronome;

import android.content.Context;

/** Italian tempo markings paired with a practical range. */
public final class TempoName {
    private TempoName() {}

    public static String of(Context context, int bpm) {
        String[] names = context.getResources().getStringArray(R.array.tempo_markings);
        int index;
        if (bpm < 40) index = 0;
        else if (bpm < 50) index = 1;
        else if (bpm < 60) index = 2;
        else if (bpm < 66) index = 3;
        else if (bpm < 76) index = 4;
        else if (bpm < 108) index = 5;
        else if (bpm < 120) index = 6;
        else if (bpm < 156) index = 7;
        else if (bpm < 176) index = 8;
        else if (bpm < 200) index = 9;
        else index = 10;
        if (index >= names.length) index = names.length - 1;
        return names[index];
    }
}

package com.tempopulse.metronome;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Centred, non-scrolling three-digit number entry with an on-screen keypad. */
public final class NumberPadView extends LinearLayout {
    private final Palette palette;
    private final int min;
    private final int max;
    private final String suffix;
    private final TextView display;
    private final StringBuilder digits = new StringBuilder();

    public NumberPadView(Context context, Palette palette, int initial,
                         int min, int max, String suffix) {
        super(context);
        this.palette = palette;
        this.min = min;
        this.max = max;
        this.suffix = suffix == null ? "" : suffix;
        setOrientation(VERTICAL);

        display = Ui.label(context, "", 30, palette.text, Gravity.CENTER);
        display.setTypeface(AppFonts.light(context));
        display.setIncludeFontPadding(false);
        display.setBackground(Ui.roundRect(context, palette.surfaceAlt, 16,
                Palette.withAlpha(palette.stroke, 0xB0), 0.8f));
        addView(display, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 70)));

        String[][] keys = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"清空", "0", "⌫"}
        };
        for (String[] rowKeys : keys) {
            LinearLayout row = Ui.row(context);
            row.setGravity(Gravity.CENTER);
            for (String key : rowKeys) {
                TextView keyView = Ui.label(context, key, key.length() > 1 ? 11 : 22,
                        palette.text, Gravity.CENTER);
                keyView.setBackground(Ui.rippleRound(context, palette.surface,
                        palette.accentSoft, 14,
                        Palette.withAlpha(palette.stroke, 0x70), 0.7f));
                keyView.setOnClickListener(v -> press(key));
                LayoutParams params = new LayoutParams(0, Ui.dp(context, 52), 1f);
                params.setMargins(Ui.dp(context, 3), Ui.dp(context, 3),
                        Ui.dp(context, 3), Ui.dp(context, 3));
                row.addView(keyView, params);
            }
            addView(row);
        }
        setValue(initial);
    }

    public int value() {
        int parsed;
        try {
            parsed = digits.length() == 0 ? min : Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            parsed = min;
        }
        return Math.max(min, Math.min(max, parsed));
    }

    public void setValue(int value) {
        int safe = Math.max(min, Math.min(max, value));
        digits.setLength(0);
        digits.append(safe);
        render();
    }

    private void press(String key) {
        if ("清空".equals(key)) {
            digits.setLength(0);
        } else if ("⌫".equals(key)) {
            if (digits.length() > 0) digits.setLength(digits.length() - 1);
        } else if (digits.length() < 3) {
            if (digits.length() == 0 && "0".equals(key)) return;
            digits.append(key);
        }
        render();
    }

    private void render() {
        String value = digits.length() == 0 ? "0" : digits.toString();
        display.setText(suffix.length() == 0 ? value : value + " " + suffix);
    }
}

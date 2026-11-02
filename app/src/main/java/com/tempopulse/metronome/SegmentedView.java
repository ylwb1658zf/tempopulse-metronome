package com.tempopulse.metronome;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact segmented control used for theme mode. */
public final class SegmentedView extends LinearLayout {
    public interface Listener {
        void onSelected(int index);
    }

    private final Palette palette;
    private final TextView[] cells;
    private int selected;
    private Listener listener;

    public SegmentedView(Context context, Palette palette, String[] labels, int selected) {
        super(context);
        this.palette = palette;
        this.selected = Math.max(0, Math.min(labels.length - 1, selected));
        setOrientation(HORIZONTAL);
        setPadding(Ui.dp(context, 3), Ui.dp(context, 3), Ui.dp(context, 3), Ui.dp(context, 3));
        setBackground(Ui.roundRect(context, palette.surfaceHigh, 15,
                Palette.withAlpha(palette.stroke, 0x88), 0.8f));
        cells = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView cell = Ui.label(context, labels[i], 12f, palette.dim, Gravity.CENTER);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, Ui.dp(context, 9), 0, Ui.dp(context, 9));
            final int index = i;
            cell.setOnClickListener(v -> select(index, true));
            addView(cell, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            cells[i] = cell;
        }
        refresh();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int selected() {
        return selected;
    }

    public void select(int index, boolean notify) {
        index = Math.max(0, Math.min(cells.length - 1, index));
        if (selected == index) {
            refresh();
            return;
        }
        selected = index;
        refresh();
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        if (notify && listener != null) listener.onSelected(index);
    }

    private void refresh() {
        for (int i = 0; i < cells.length; i++) {
            TextView cell = cells[i];
            if (i == selected) {
                cell.setBackground(Ui.roundRect(getContext(), palette.accent, 12));
                cell.setTextColor(palette.onAccent);
            } else {
                cell.setBackgroundColor(Color.TRANSPARENT);
                cell.setTextColor(palette.dim);
            }
            cell.setSelected(i == selected);
            cell.setContentDescription(cell.getText() + (i == selected ? ", selected" : ""));
        }
    }
}

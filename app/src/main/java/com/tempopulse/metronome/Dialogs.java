package com.tempopulse.metronome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small themed confirmation dialog. */
public final class Dialogs {
    private Dialogs() {}

    public static void confirm(Activity activity, Palette palette, String title, String message,
                               String confirmLabel, Runnable onConfirm) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(activity, 22), Ui.dp(activity, 22),
                Ui.dp(activity, 22), Ui.dp(activity, 18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.surface);
        background.setCornerRadius(Ui.dp(activity, 24));
        background.setStroke(Math.max(1, Ui.dp(activity, 0.8f)),
                Palette.withAlpha(palette.stroke, 0xB0));
        card.setBackground(background);
        card.setElevation(Ui.dp(activity, 20));

        TextView titleView = Ui.label(activity, title, 18, palette.text, Gravity.START);
        card.addView(titleView);
        TextView messageView = Ui.text(activity, message, 13.5f, palette.dim,
                AppFonts.regular(activity), Gravity.START);
        messageView.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 22));
        card.addView(messageView);

        LinearLayout buttons = Ui.row(activity);
        buttons.setGravity(Gravity.END);
        TextView cancel = Ui.label(activity, activity.getString(R.string.cancel), 13,
                palette.dim, Gravity.CENTER);
        cancel.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 11),
                Ui.dp(activity, 16), Ui.dp(activity, 11));
        cancel.setBackground(Ui.rippleRound(activity, Color.TRANSPARENT,
                Palette.withAlpha(palette.dim, 0x30), 20));
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel);

        TextView confirm = Ui.label(activity, confirmLabel, 13, Color.WHITE, Gravity.CENTER);
        confirm.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 11),
                Ui.dp(activity, 18), Ui.dp(activity, 11));
        confirm.setBackground(Ui.rippleRound(activity, palette.danger,
                Palette.withAlpha(Color.WHITE, 0x40), 20));
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        confirmParams.leftMargin = Ui.dp(activity, 8);
        buttons.addView(confirm, confirmParams);
        card.addView(buttons);

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = Math.min(activity.getResources().getDisplayMetrics().widthPixels
                            - Ui.dp(activity, 40),
                    Ui.dp(activity, 380));
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = palette.dark ? 0.62f : 0.42f;
            window.setAttributes(params);
        }
        dialog.show();
    }
}

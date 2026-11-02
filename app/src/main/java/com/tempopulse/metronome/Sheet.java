package com.tempopulse.metronome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Bottom-sheet style dialog built from framework views only. */
public class Sheet extends Dialog {
    private final Palette palette;
    private final LinearLayout body;

    public Sheet(Activity activity, Palette palette, String title, String subtitle) {
        super(activity);
        this.palette = palette;
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(activity);
        root.setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), Ui.dp(activity, 8));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.surface);
        background.setCornerRadius(Ui.dp(activity, 26));
        background.setStroke(Math.max(1, Ui.dp(activity, 0.8f)),
                Palette.withAlpha(palette.stroke, 0xB0));
        card.setBackground(background);
        card.setElevation(Ui.dp(activity, 18));
        card.setClipToOutline(true);

        View handle = new View(activity);
        handle.setBackground(Ui.roundRect(activity, Palette.withAlpha(palette.dim, 0x60), 2));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 36), Ui.dp(activity, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = Ui.dp(activity, 9);
        card.addView(handle, handleParams);

        LinearLayout header = Ui.row(activity);
        header.setPadding(Ui.dp(activity, 22), Ui.dp(activity, 12),
                Ui.dp(activity, 12), Ui.dp(activity, 4));
        LinearLayout titles = Ui.column(activity);
        TextView titleView = Ui.label(activity, title, 20, palette.text, Gravity.START);
        TextView subtitleView = Ui.text(activity, subtitle, 12.5f, palette.dim,
                AppFonts.regular(activity), Gravity.START);
        subtitleView.setPadding(0, Ui.dp(activity, 5), 0, 0);
        titles.addView(titleView);
        if (subtitle != null && subtitle.length() > 0) titles.addView(subtitleView);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(Ui.iconButton(activity, R.drawable.ic_close, palette.dim,
                palette, activity.getString(R.string.close), v -> dismiss()));
        card.addView(header);

        MaxHeightScrollView scroll = new MaxHeightScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 8),
                Ui.dp(activity, 20), Ui.dp(activity, 22));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels
                        - Ui.dp(activity, 16),
                Ui.dp(activity, 560));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        root.addView(card, cardParams);
        setContentView(root);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM;
            params.dimAmount = palette.dark ? 0.62f : 0.42f;
            params.windowAnimations = R.style.SheetAnimation;
            window.setAttributes(params);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        card.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(0, 0, 0, Math.max(0, bottom - Ui.dp(activity, 8)));
            return insets;
        });
    }

    public LinearLayout body() {
        return body;
    }

    public Palette palette() {
        return palette;
    }

    public void showSheet() {
        show();
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private static final class MaxHeightScrollView extends ScrollView {
        MaxHeightScrollView(Activity activity) {
            super(activity);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.76f);
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = MeasureSpec.getSize(heightMeasureSpec);
            if (mode == MeasureSpec.UNSPECIFIED || size > max) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}

package com.tempopulse.metronome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small factory of native views that keeps the visual language consistent. */
public final class Ui {
    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static int sp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    public static LinearLayout.LayoutParams lp(int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    public static LinearLayout.LayoutParams margins(int width, int height,
                                                     int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    public static FrameLayout.LayoutParams flp(int width, int height) {
        return new FrameLayout.LayoutParams(width, height);
    }

    public static TextView text(Context context, CharSequence value, float sizeSp,
                                int color, Typeface typeface, int gravity) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setGravity(gravity);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(context, 2), 1f);
        return view;
    }

    public static TextView label(Context context, CharSequence value, float sizeSp,
                                 int color, int gravity) {
        return text(context, value, sizeSp, color, AppFonts.semibold(context), gravity);
    }

    public static TextView caps(Context context, CharSequence value, float sizeSp,
                                int color, int gravity) {
        TextView view = label(context, value, sizeSp, color, gravity);
        view.setAllCaps(true);
        view.setLetterSpacing(0.16f);
        return view;
    }

    public static TextView sectionLabel(Context context, CharSequence value, Palette palette) {
        TextView view = caps(context, value, 10.5f, palette.faint, Gravity.START);
        view.setPadding(dp(context, 4), 0, 0, dp(context, 9));
        return view;
    }

    public static ImageView icon(Context context, int resource, int tintColor, float sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageResource(resource);
        view.setColorFilter(tintColor);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = dp(context, sizeDp);
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return view;
    }

    public static ImageButton iconButton(Context context, int resource, int tintColor,
                                         Palette palette, String description, View.OnClickListener click) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(resource);
        button.setColorFilter(tintColor);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setContentDescription(description);
        button.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
        button.setBackground(rippleRound(context, Color.TRANSPARENT,
                Palette.withAlpha(tintColor, 0x30), 22));
        button.setOnClickListener(click);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
        return button;
    }

    public static GradientDrawable roundRect(Context context, int fill, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable roundRect(Context context, int fill, float radiusDp,
                                             int strokeColor, float strokeDp) {
        GradientDrawable drawable = roundRect(context, fill, radiusDp);
        if (strokeColor != 0) {
            drawable.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        }
        return drawable;
    }

    public static GradientDrawable linearGradient(Context context, int top, int bottom,
                                                  float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static Drawable rippleRound(Context context, int fill, int rippleColor,
                                       float radiusDp) {
        GradientDrawable content = roundRect(context, fill, radiusDp);
        GradientDrawable mask = roundRect(context, Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    public static Drawable rippleRound(Context context, int fill, int rippleColor,
                                       float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable content = roundRect(context, fill, radiusDp, strokeColor, strokeDp);
        GradientDrawable mask = roundRect(context, Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    public static View divider(Context context, int color) {
        View view = new View(context);
        view.setBackgroundColor(color);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(context, 0.7f))));
        return view;
    }

    public static View spacer(Context context, float heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return view;
    }

    public static void pad(View view, float left, float top, float right, float bottom) {
        Context context = view.getContext();
        view.setPadding(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom));
    }

    public static void margin(View view, float left, float top, float right, float bottom) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
        Context context = view.getContext();
        params.setMargins(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom));
        view.setLayoutParams(params);
    }

    public static TextView chipChevron(Context context, Palette palette) {
        TextView view = text(context, "›", 17, palette.faint, AppFonts.regular(context), Gravity.CENTER);
        return view;
    }

    public static void setSingleLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
    }
}

package com.krxzy.krxzyinstall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class Ui {
    private Ui() {
    }

    public static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    public static void toast(Context context, String message) {
        if (context != null && message != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }

    public static void chip(TextView view, String text, int backgroundRes, int textColor) {
        view.setText(text);
        view.setBackgroundResource(backgroundRes);
        view.setTextColor(textColor);
    }

    public static void showHelp(Activity activity) {
        ScrollView scroll = new ScrollView(activity);
        TextView text = new TextView(activity);
        text.setText(R.string.help_body);
        text.setTextSize(15);
        text.setTextColor(activity.getColor(R.color.text));
        text.setLineSpacing(dp(activity, 2), 1f);
        int pad = dp(activity, 22);
        text.setPadding(pad, dp(activity, 8), pad, dp(activity, 8));
        scroll.addView(text, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
                .setTitle(R.string.help_title)
                .setView(scroll)
                .setPositiveButton("Got it", null)
                .show();
    }

    public static void showCredits(Activity activity) {
        TextView text = new TextView(activity);
        text.setText(R.string.credits_body);
        text.setTextSize(18);
        text.setTextColor(activity.getColor(R.color.text));
        text.setLineSpacing(dp(activity, 4), 1f);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(activity, 24);
        text.setPadding(pad, dp(activity, 8), pad, dp(activity, 8));

        new AlertDialog.Builder(activity)
                .setTitle(R.string.credits_title)
                .setView(text)
                .setPositiveButton("Close", null)
                .show();
    }

    public static void confirm(Activity activity, String title, String message,
                               String positive, Runnable onPositive) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, (dialog, which) -> onPositive.run())
                .setNegativeButton(R.string.keep, null)
                .show();
    }

    public static void setVisible(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    public static int color(Context context, int res) {
        return context.getColor(res);
    }

    public static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}

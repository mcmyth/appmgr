package com.mcmyth.appmgr;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

final class CatalogProgressCard extends LinearLayout {
    private static final int ACCENT = 0xFF87E2FC;
    private final TextView title;
    private final TextView percent;
    private final TextView count;
    private final TextView item;
    private final ProgressBar bar;
    private final Runnable dismiss = this::fadeOut;

    CatalogProgressCard(Context context, AppViews ui) {
        super(context);
        setOrientation(VERTICAL);
        ui.pad(this, 18, 15, 18, 16);
        setElevation(ui.dp(12));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{AppViews.DARK_BLUE, 0xFF184B68});
        background.setCornerRadius(ui.dp(22));
        setBackground(background);
        setVisibility(GONE);

        LinearLayout heading = ui.row();
        title = ui.text("", 15, Color.WHITE, true);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LayoutParams(0, -2, 1));
        percent = ui.text("", 24, ACCENT, true);
        percent.setGravity(Gravity.END);
        heading.addView(percent);
        addView(heading);

        LinearLayout metadata = ui.row();
        item = ui.text("", 12, 0xFFE0EEF7, false);
        item.setMaxLines(1);
        item.setEllipsize(TextUtils.TruncateAt.END);
        metadata.addView(item, new LayoutParams(0, -2, 1));
        count = ui.text("", 12, 0xFFE0EEF7, true);
        ui.pad(count, 10, 0, 0, 0);
        count.setGravity(Gravity.END);
        metadata.addView(count);
        addView(metadata);

        bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF416981));
        bar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        // The percentage and count already expose the same progress to accessibility.
        bar.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams track = new LayoutParams(-1, ui.dp(6));
        track.topMargin = ui.dp(9);
        addView(bar, track);
    }

    void begin(String heading, String status) {
        removeCallbacks(dismiss);
        animate().cancel();
        setAlpha(1);
        setVisibility(VISIBLE);
        title.setText(heading);
        item.setText(status);
        count.setText("");
        percent.setText("—");
        bar.setIndeterminate(true);
    }

    void report(int read, int total, String name, String unit) {
        int progress = total == 0 ? 1000 : (int) (1000L * read / total);
        bar.setIndeterminate(false);
        bar.setProgress(progress);
        percent.setText((progress / 10) + "%");
        count.setText(read + " / " + total + " " + unit);
        item.setText(name != null ? name : total == 0 ? "暂无条目" : "正在读取…");
    }

    void finish(boolean success) {
        if (getVisibility() != VISIBLE) return;
        if (!success) {
            hide();
            return;
        }
        title.setText("读取完成");
        postDelayed(dismiss, 450);
    }

    private void fadeOut() {
        if (AppViews.motionEnabled()) {
            animate().alpha(0).setDuration(230).setInterpolator(AppViews.EASE_OUT)
                    .withEndAction(() -> setVisibility(View.GONE)).start();
        } else {
            hide();
        }
    }

    void hide() {
        removeCallbacks(dismiss);
        animate().cancel();
        setVisibility(GONE);
    }

    @Override protected void onDetachedFromWindow() {
        hide();
        super.onDetachedFromWindow();
    }
}


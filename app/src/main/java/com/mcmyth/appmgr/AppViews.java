package com.mcmyth.appmgr;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AppViews {
    static final int BLUE = 0xFF17658E;
    static final int DARK_BLUE = 0xFF12314C;
    static final int TEXT = 0xFF142739;
    static final int MUTED = 0xFF506577;
    static final int SURFACE = 0xFFF1F5F9;
    static final int TINT = 0xFFE1EFF8;
    static final PathInterpolator EASE_OUT = new PathInterpolator(0.2f, 0, 0, 1);

    final Context context;

    AppViews(Context context) { this.context = context; }

    static boolean motionEnabled() { return ValueAnimator.areAnimatorsEnabled(); }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView text(CharSequence label, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    GradientDrawable outline(int color) {
        GradientDrawable background = rounded((color & 0x00FFFFFF) | 0x14000000, 14);
        background.setStroke(dp(1), (color & 0x00FFFFFF) | 0x42000000);
        return background;
    }

    void pad(View view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }

    void ripple(View view, boolean light) {
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(light ? 0x28FFFFFF : 0x22007FAF),
                null, rounded(Color.WHITE, 8)));
    }

    View icon(String symbol, String label, int color, View.OnClickListener action) {
        View view = new Symbol(context, symbol, color);
        view.setContentDescription(label);
        if (action != null) {
            ripple(view, color == Color.WHITE);
            view.setOnClickListener(action);
            view.setFocusable(true);
        }
        return view;
    }

    void enter(View view, float distance, float startAlpha, long duration) {
        view.animate().cancel();
        view.setTranslationX(0);
        view.setAlpha(1);
        if (!motionEnabled()) return;
        view.setTranslationX(distance);
        view.setAlpha(startAlpha);
        view.animate().translationX(0).alpha(1).setDuration(duration)
                .setInterpolator(EASE_OUT).withLayer().start();
    }

    private static final class Symbol extends View {
        private final String symbol;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Symbol(Context context, String symbol, int color) {
            super(context);
            this.symbol = symbol;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float size = Math.min(24 * density, Math.min(getWidth(), getHeight()));
            canvas.save();
            canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
            canvas.scale(size / 24, size / 24);
            switch (symbol) {
                case "menu":
                    line(canvas, 4, 6, 20, 6);
                    line(canvas, 4, 12, 20, 12);
                    line(canvas, 4, 18, 20, 18);
                    break;
                case "sort":
                    line(canvas, 4, 6, 20, 6);
                    line(canvas, 4, 12, 15, 12);
                    line(canvas, 4, 18, 10, 18);
                    break;
                case "refresh":
                    canvas.drawArc(4, 4, 20, 20, 37, 305, false, paint);
                    points(canvas, 20, 4, 20, 10, 14, 10);
                    break;
                case "search":
                    canvas.drawCircle(10.5f, 10.5f, 6, paint);
                    line(canvas, 15, 15, 20.5f, 20.5f);
                    break;
                case "close":
                    line(canvas, 6, 6, 18, 18);
                    line(canvas, 18, 6, 6, 18);
                    break;
                case "back":
                    points(canvas, 11, 5, 4, 12, 11, 19);
                    line(canvas, 4, 12, 20, 12);
                    break;
                case "down":
                    points(canvas, 6, 9, 12, 15, 18, 9);
                    break;
                case "up":
                    points(canvas, 6, 15, 12, 9, 18, 15);
                    break;
                case "open":
                    points(canvas, 8, 4, 20, 12, 8, 20, 8, 4);
                    break;
                case "info":
                    canvas.drawCircle(12, 12, 9, paint);
                    line(canvas, 12, 11, 12, 17);
                    canvas.drawPoint(12, 7.5f, paint);
                    break;
                case "backup":
                case "install":
                    points(canvas, 4, 15, 4, 20, 20, 20, 20, 15);
                    line(canvas, 12, 4, 12, 15);
                    points(canvas, 8, 11, 12, 15, 16, 11);
                    break;
                case "delete":
                    line(canvas, 4, 6, 20, 6);
                    points(canvas, 6, 6, 7, 20, 17, 20, 18, 6);
                    points(canvas, 9, 6, 9, 3, 15, 3, 15, 6);
                    break;
                case "market":
                    points(canvas, 5, 8, 19, 8, 21, 20, 3, 20, 5, 8);
                    canvas.drawArc(8, 3, 16, 13, 180, 180, false, paint);
                    break;
                case "share":
                    canvas.drawCircle(18, 5, 2.5f, paint);
                    canvas.drawCircle(6, 12, 2.5f, paint);
                    canvas.drawCircle(18, 19, 2.5f, paint);
                    line(canvas, 8, 11, 16, 6);
                    line(canvas, 8, 13, 16, 18);
                    break;
                case "folder":
                    points(canvas, 3, 19, 3, 6, 10, 6, 12, 8, 21, 8, 21, 19, 3, 19);
                    break;
                default:
                    canvas.drawPoint(6, 12, paint);
                    canvas.drawPoint(12, 12, paint);
                    canvas.drawPoint(18, 12, paint);
                    break;
            }
            canvas.restore();
        }

        private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
            canvas.drawLine(x1, y1, x2, y2, paint);
        }

        private void points(Canvas canvas, float... coordinates) {
            path.reset();
            path.moveTo(coordinates[0], coordinates[1]);
            for (int i = 2; i < coordinates.length; i += 2) path.lineTo(coordinates[i], coordinates[i + 1]);
            canvas.drawPath(path, paint);
        }
    }
}


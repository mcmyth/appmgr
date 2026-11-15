package com.mcmyth.appmgr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DetailHeader extends FrameLayout {
    private final View surface;
    private final View iconTile;

    DetailHeader(Context context, AppViews ui, String title, String subtitle, Drawable icon) {
        super(context);
        setBackground(ui.rounded(AppViews.BLUE, 26));
        setClipToOutline(true);
        surface = new Atmosphere(context, accentOf(icon));
        addView(surface, new LayoutParams(0, 0));

        LinearLayout stack = ui.column();
        stack.setGravity(Gravity.CENTER_HORIZONTAL);
        ui.pad(stack, 22, 22, 22, 23);
        FrameLayout tile = new FrameLayout(context);
        tile.setBackground(ui.rounded(0xF5FFFFFF, 21));
        tile.setElevation(ui.dp(8));
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageDrawable(icon);
        tile.addView(image, new LayoutParams(ui.dp(55), ui.dp(55), Gravity.CENTER));
        stack.addView(tile, new LinearLayout.LayoutParams(ui.dp(75), ui.dp(75)));
        iconTile = tile;

        TextView name = ui.text(title, 24, Color.WHITE, true);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ui.pad(name, 0, 10, 0, 0);
        stack.addView(name);
        if (!subtitle.isEmpty()) {
            TextView description = ui.text(subtitle, 12, 0xFFDCEBF8, false);
            description.setGravity(Gravity.CENTER);
            description.setMaxLines(2);
            description.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ui.pad(description, 0, 4, 0, 0);
            stack.addView(description);
        }
        addView(stack, new LayoutParams(-1, -2, Gravity.CENTER));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        surface.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    void enter(AppViews ui) {
        if (!AppViews.motionEnabled()) return;
        iconTile.setTranslationY(ui.dp(9));
        iconTile.setScaleX(0.88f);
        iconTile.setScaleY(0.88f);
        iconTile.setAlpha(0.4f);
        iconTile.animate().translationY(0).scaleX(1).scaleY(1).alpha(1)
                .setStartDelay(35).setDuration(310).setInterpolator(AppViews.EASE_OUT).start();
    }

    void parallax(int offset) {
        if (AppViews.motionEnabled()) {
            surface.setTranslationY(Math.min(getHeight() * 0.18f, offset * 0.22f));
        }
    }

    private static int accentOf(Drawable icon) {
        Bitmap sample = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        Rect previous = new Rect(icon.getBounds());
        icon.setBounds(0, 0, 24, 24);
        icon.draw(new Canvas(sample));
        icon.setBounds(previous);
        int[] pixels = new int[24 * 24];
        sample.getPixels(pixels, 0, 24, 0, 0, 24, 24);
        sample.recycle();

        float[] hsv = new float[3];
        float[] weights = new float[24];
        for (int pixel : pixels) {
            if (Color.alpha(pixel) < 160) continue;
            Color.colorToHSV(pixel, hsv);
            if (hsv[1] < 0.25f || hsv[2] < 0.26f) continue;
            weights[Math.min(23, (int) (hsv[0] / 15))] += hsv[1] * hsv[2];
        }
        int best = 0;
        for (int i = 1; i < weights.length; i++) if (weights[i] > weights[best]) best = i;
        float hue = weights[best] == 0 ? 190 : best * 15 + 7;
        return Color.HSVToColor(new float[]{hue, 0.63f, 0.92f});
    }

    private static final class Atmosphere extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF orbit = new RectF();
        private final int accent;
        private Shader base;
        private Shader glow;
        private Shader softGlow;

        Atmosphere(Context context, int accent) {
            super(context);
            this.accent = accent;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
            if (w == 0 || h == 0) return;
            base = new LinearGradient(0, 0, w, h, 0xFF10233D, 0xFF173653, Shader.TileMode.CLAMP);
            glow = new RadialGradient(w * 0.84f, h * 0.12f, w * 0.65f,
                    new int[]{accent & 0x00FFFFFF | 0x82000000, accent & 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP);
            softGlow = new RadialGradient(w * 0.12f, h * 0.77f, w * 0.60f,
                    0x733C90D9, 0x003C90D9, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(base);
            canvas.drawRect(0, -height, width, height, paint);
            paint.setShader(glow);
            canvas.drawRect(0, -height, width, height, paint);
            paint.setShader(softGlow);
            canvas.drawRect(0, -height, width, height, paint);
            paint.setShader(null);

            canvas.save();
            canvas.rotate(-17, width * 0.53f, height * 0.45f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getResources().getDisplayMetrics().density * 0.75f);
            paint.setColor(0x35CDEEFF);
            orbit.set(-width * 0.17f, height * 0.20f, width * 1.13f, height * 0.83f);
            canvas.drawOval(orbit, paint);
            orbit.inset(width * 0.09f, height * 0.09f);
            canvas.drawOval(orbit, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xA8CCEEFF);
            canvas.drawCircle(width * 0.19f, height * 0.28f, height * 0.011f, paint);
            canvas.drawCircle(width * 0.83f, height * 0.60f, height * 0.016f, paint);
            canvas.drawCircle(width * 0.73f, height * 0.32f, height * 0.009f, paint);
            canvas.restore();
        }
    }
}


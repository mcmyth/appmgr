package com.mcmyth.appmgr;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

final class HorizontalSwipeFrame extends FrameLayout {
    interface Swipe {
        boolean canDrag(float startX, float delta);
        void drag(float delta);
        void finish(float delta, boolean committed);
    }

    private final int slop;
    private final float threshold;
    private final float flingVelocity;
    private final Swipe listener;
    private float startX;
    private float startY;
    private boolean dragging;
    private boolean rejected;
    private VelocityTracker velocity;

    HorizontalSwipeFrame(Context context, Swipe listener) {
        super(context);
        this.listener = listener;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        float density = getResources().getDisplayMetrics().density;
        threshold = 66 * density;
        flingVelocity = 520 * density;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                dragging = rejected = false;
                break;
            case MotionEvent.ACTION_MOVE:
                return detect(event);
            case MotionEvent.ACTION_POINTER_DOWN:
                rejected = true;
                break;
        }
        return false;
    }

    private boolean detect(MotionEvent event) {
        if (dragging) return true;
        if (rejected) return false;
        float dx = event.getX() - startX;
        float dy = event.getY() - startY;
        if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) {
            rejected = true;
        } else if (Math.abs(dx) > slop && Math.abs(dx) > 1.3f * Math.abs(dy)) {
            if (!listener.canDrag(startX, dx)) {
                rejected = true;
                return false;
            }
            dragging = true;
            if (velocity == null) velocity = VelocityTracker.obtain();
            else velocity.clear();
            velocity.addMovement(event);
            listener.drag(dx);
        }
        return dragging;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float delta = event.getX() - startX;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!detect(event)) return true;
                velocity.addMovement(event);
                listener.drag(delta);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (dragging) {
                    velocity.addMovement(event);
                    velocity.computeCurrentVelocity(1000);
                    float speed = velocity.getXVelocity();
                    boolean fling = Math.abs(delta) >= slop * 2 && Math.abs(speed) >= flingVelocity
                            && Math.signum(speed) == Math.signum(delta);
                    boolean commit = event.getActionMasked() == MotionEvent.ACTION_UP
                            && (Math.abs(delta) >= threshold || fling);
                    listener.finish(delta, commit);
                    dragging = false;
                }
                rejected = true;
                return true;
            default:
                return true;
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        dragging = false;
        super.onDetachedFromWindow();
    }
}


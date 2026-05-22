package desu.inugram.ui.drawer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.Property;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.DrawerLayoutContainer;

/**
 * Old Layout side drawer mechanics for {@link DrawerLayoutContainer}: swipe
 * tracking, open/close animation, scrim + edge-shadow rendering. Ported from
 * 11.14.1's stock DrawerLayoutContainer so the stock patch stays a thin set of
 * delegating overrides instead of carrying the whole state machine.
 */
public class DrawerSwipeController {

    // ObjectAnimator with a string property name uses JavaBeans naming; an
    // explicit Property bypasses that.
    private static final Property<DrawerSwipeController, Float> DRAWER_POSITION =
            new Property<DrawerSwipeController, Float>(Float.class, "drawerPosition") {
                @Override public Float get(DrawerSwipeController o) { return o.drawerPosition; }
                @Override public void set(DrawerSwipeController o, Float v) { o.setDrawerPosition(v); }
            };

    private final DrawerLayoutContainer host;

    private FrameLayout drawerLayout;
    private View drawerListView;
    private float drawerPosition;
    private boolean drawerOpened;
    private boolean allowOpenDrawer;
    private boolean maybeStartTracking;
    private boolean startedTracking;
    private int startedTrackingX;
    private int startedTrackingY;
    private int startedTrackingPointerId;
    private VelocityTracker velocityTracker;
    private boolean beginTrackingSent;
    private AnimatorSet currentAnimation;
    private float scrimOpacity;
    private final Paint scrimPaint = new Paint();
    private Drawable shadowLeft;

    public DrawerSwipeController(DrawerLayoutContainer host) {
        this.host = host;
    }

    public void setDrawerLayout(FrameLayout layout, View listView, FrameLayout.LayoutParams lp) {
        drawerLayout = layout;
        drawerListView = listView;
        host.addView(drawerLayout, lp);
        drawerLayout.setVisibility(View.INVISIBLE);
        if (shadowLeft == null) {
            try { shadowLeft = host.getResources().getDrawable(R.drawable.menu_shadow); } catch (Exception ignored) {}
        }
    }

    public boolean isDrawerChild(View child) {
        return child != null && child == drawerLayout;
    }

    public void setAllowOpenDrawer(boolean value, boolean animated) {
        allowOpenDrawer = value;
        if (!allowOpenDrawer && drawerPosition != 0) {
            if (!animated) { setDrawerPosition(0); onDrawerAnimationEnd(false); }
            else closeDrawer(true);
        }
    }

    public boolean isDrawerOpened() { return drawerOpened; }

    @androidx.annotation.Keep
    public void setDrawerPosition(float value) {
        if (drawerLayout == null) return;
        drawerPosition = Math.max(0, Math.min(value, drawerLayout.getMeasuredWidth()));
        drawerLayout.setTranslationX(drawerPosition);
        if (drawerPosition > 0 && drawerListView != null && drawerListView.getVisibility() != View.VISIBLE) {
            drawerListView.setVisibility(View.VISIBLE);
        }
        drawerLayout.setVisibility(drawerPosition > 0 ? View.VISIBLE : View.INVISIBLE);
        scrimOpacity = drawerPosition / (float) drawerLayout.getMeasuredWidth();
        host.invalidate();
    }

    public void openDrawer(boolean fast) {
        if (!allowOpenDrawer || drawerLayout == null) return;
        cancelCurrentAnimation();
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(ObjectAnimator.ofFloat(this, DRAWER_POSITION, (float) drawerLayout.getMeasuredWidth()));
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.setDuration(fast ? Math.max((int) (200f / drawerLayout.getMeasuredWidth() * (drawerLayout.getMeasuredWidth() - drawerPosition)), 50) : 250);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { onDrawerAnimationEnd(true); }
        });
        animatorSet.start();
        currentAnimation = animatorSet;
    }

    public void closeDrawer(boolean fast) {
        if (drawerLayout == null) return;
        cancelCurrentAnimation();
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(ObjectAnimator.ofFloat(this, DRAWER_POSITION, 0f));
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.setDuration(fast ? Math.max((int) (200f / drawerLayout.getMeasuredWidth() * drawerPosition), 50) : 250);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { onDrawerAnimationEnd(false); }
        });
        animatorSet.start();
        currentAnimation = animatorSet;
    }

    private void cancelCurrentAnimation() {
        if (currentAnimation != null) { currentAnimation.cancel(); currentAnimation = null; }
    }

    private void onDrawerAnimationEnd(boolean opened) {
        startedTracking = false;
        currentAnimation = null;
        drawerOpened = opened;
        host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    /**
     * Once the drawer is open (or mid-drag) gestures must keep working to close it.
     * Otherwise only start tracking when the top fragment allows it — a DialogsActivity
     * on a non-first folder tab owns the horizontal swipe for tab paging.
     */
    private boolean canTrackGesture() {
        // Once open/mid-drag, gestures must keep working to close it regardless of stack.
        if (drawerOpened || drawerPosition > 0) return true;
        if (host.parentActionBarLayout.getFragmentStack().size() != 1) return false;
        org.telegram.ui.ActionBar.BaseFragment top = host.parentActionBarLayout.getLastFragment();
        if (top instanceof org.telegram.ui.DialogsActivity) {
            org.telegram.ui.Components.FilterTabsView tabs = ((org.telegram.ui.DialogsActivity) top).filterTabsView;
            return tabs == null || tabs.getVisibility() != View.VISIBLE || tabs.isFirstTabSelected();
        }
        return true;
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (drawerLayout == null || host.parentActionBarLayout.checkTransitionAnimation()) {
            // A fragment transition mid-drag aborts the gesture; drop the stale
            // tracking state so the next gesture doesn't resume from it.
            if (startedTracking || maybeStartTracking) {
                startedTracking = false;
                maybeStartTracking = false;
                if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
            }
            return false;
        }
        if (drawerOpened && ev != null && ev.getX() > drawerPosition && !startedTracking) {
            if (ev.getAction() == MotionEvent.ACTION_UP) closeDrawer(false);
            return true;
        }
        if (allowOpenDrawer && canTrackGesture()) {
            if (ev != null && (ev.getAction() == MotionEvent.ACTION_DOWN || ev.getAction() == MotionEvent.ACTION_MOVE)
                    && !startedTracking && !maybeStartTracking) {
                startedTrackingX = (int) ev.getX();
                startedTrackingY = (int) ev.getY();
                startedTrackingPointerId = ev.getPointerId(0);
                maybeStartTracking = true;
                cancelCurrentAnimation();
                if (velocityTracker != null) velocityTracker.clear();
            } else if (ev != null && ev.getAction() == MotionEvent.ACTION_MOVE
                    && ev.getPointerId(0) == startedTrackingPointerId) {
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                float dx = ev.getX() - startedTrackingX;
                float dy = Math.abs(ev.getY() - startedTrackingY);
                velocityTracker.addMovement(ev);
                if (maybeStartTracking && !startedTracking
                        && (dx > 0 && dx / 3f > dy && Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.2f, true)
                            || drawerOpened && dx < 0 && Math.abs(dx) >= dy && Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.4f, true))) {
                    maybeStartTracking = false;
                    startedTracking = true;
                    startedTrackingX = (int) ev.getX();
                    beginTrackingSent = false;
                    host.requestDisallowInterceptTouchEvent(true);
                } else if (startedTracking) {
                    if (!beginTrackingSent) { beginTrackingSent = true; }
                    setDrawerPosition(drawerPosition + dx);
                    startedTrackingX = (int) ev.getX();
                }
            } else if (ev == null || ev.getPointerId(0) == startedTrackingPointerId
                    && (ev.getAction() == MotionEvent.ACTION_CANCEL
                        || ev.getAction() == MotionEvent.ACTION_UP
                        || ev.getAction() == MotionEvent.ACTION_POINTER_UP)) {
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                velocityTracker.computeCurrentVelocity(1000);
                if (startedTracking || (drawerPosition != 0 && drawerPosition != drawerLayout.getMeasuredWidth())) {
                    float velX = velocityTracker.getXVelocity();
                    float velY = velocityTracker.getYVelocity();
                    boolean back = drawerPosition < drawerLayout.getMeasuredWidth() / 2f
                            && (velX < 3500 || Math.abs(velX) < Math.abs(velY))
                            || velX < 0 && Math.abs(velX) >= 3500;
                    if (!back) openDrawer(!drawerOpened && Math.abs(velX) >= 3500);
                    else closeDrawer(drawerOpened && Math.abs(velX) >= 3500);
                }
                startedTracking = false;
                maybeStartTracking = false;
                if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
            }
        } else {
            if (ev == null || ev.getPointerId(0) == startedTrackingPointerId
                    && (ev.getAction() == MotionEvent.ACTION_CANCEL
                        || ev.getAction() == MotionEvent.ACTION_UP
                        || ev.getAction() == MotionEvent.ACTION_POINTER_UP)) {
                startedTracking = false;
                maybeStartTracking = false;
                if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
            }
        }
        return startedTracking;
    }

    public boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (drawerLayout == null) {
            return host.inu_superDrawChild(canvas, child, drawingTime);
        }
        final int height = host.getHeight();
        final boolean drawingContent = child != drawerLayout;
        int lastVisibleChild = 0;
        int clipLeft = 0, clipRight = host.getWidth();

        final int restoreCount = canvas.save();
        if (drawingContent) {
            final int childCount = host.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View v = host.getChildAt(i);
                if (v.getVisibility() == View.VISIBLE && v != drawerLayout) {
                    lastVisibleChild = i;
                }
                if (v == child || v.getVisibility() != View.VISIBLE || v != drawerLayout || v.getHeight() < height) {
                    continue;
                }
                final int vright = (int) Math.ceil(v.getX()) + v.getMeasuredWidth();
                if (vright > clipLeft) clipLeft = vright;
            }
            if (clipLeft != 0) {
                canvas.clipRect(clipLeft - AndroidUtilities.dp(1), 0, clipRight, host.getHeight());
            }
        }
        final boolean result = host.inu_superDrawChild(canvas, child, drawingTime);
        canvas.restoreToCount(restoreCount);

        if (scrimOpacity > 0 && drawingContent) {
            if (host.indexOfChild(child) == lastVisibleChild) {
                scrimPaint.setColor((int) (0x99 * scrimOpacity) << 24);
                canvas.drawRect(clipLeft, 0, clipRight, host.getHeight(), scrimPaint);
            }
        } else if (shadowLeft != null && drawerPosition > 0) {
            float alpha = Math.max(0, Math.min(drawerPosition / AndroidUtilities.dp(20), 1f));
            if (alpha != 0) {
                shadowLeft.setBounds((int) drawerPosition, child.getTop(), (int) drawerPosition + shadowLeft.getIntrinsicWidth(), child.getBottom());
                shadowLeft.setAlpha((int) (0xff * alpha));
                shadowLeft.draw(canvas);
            }
        }
        return result;
    }
}

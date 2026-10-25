package studio.invrse.mero;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.PathInterpolator;

/** One glass selection plate follows remote focus without delaying the focus itself. */
final class DockFocusView extends View {
    private final LiquidGlassDrawable glass;
    private final RectF position = new RectF();
    private final RectF destination = new RectF();
    private final Rect targetBounds = new Rect();
    private final int[] plateLocation = new int[2];
    private final int[] targetLocation = new int[2];
    private ValueAnimator animator;
    private View target;
    private boolean positioned;
    private boolean visible;
    private int draws;
    private final Runnable reconcile = this::reconcileLayout;
    private final Runnable checkFocus = () -> {
        if (target == null || !target.isFocused()) reset();
    };

    DockFocusView(Context context, float radiusPx) {
        super(context);
        glass = new LiquidGlassDrawable(radiusPx, getResources().getDisplayMetrics().density, true);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void moveTo(View focused, boolean animate) {
        target = focused;
        if (!readBounds()) {
            removeCallbacks(reconcile);
            post(reconcile);
            return;
        }
        RectF next = new RectF(targetBounds);
        boolean canAnimate = animate && positioned && visible && isShown() && hasWindowFocus()
                && ValueAnimator.areAnimatorsEnabled();
        if (next.equals(destination) && positioned) {
            visible = hasWindowFocus() && isShown();
            invalidate();
            return;
        }
        cancelMotion();
        destination.set(next);
        visible = hasWindowFocus() && isShown();
        if (!canAnimate) {
            position.set(destination);
            positioned = true;
            invalidate();
            return;
        }
        RectF from = new RectF(position);
        ValueAnimator motion = ValueAnimator.ofFloat(0, 1);
        animator = motion;
        motion.setDuration(230);
        motion.setInterpolator(new PathInterpolator(0.16f, 1f, 0.3f, 1f));
        motion.addUpdateListener(frame -> {
            float progress = (float) frame.getAnimatedValue();
            position.set(from.left + (next.left - from.left) * progress,
                    from.top + (next.top - from.top) * progress,
                    from.right + (next.right - from.right) * progress,
                    from.bottom + (next.bottom - from.bottom) * progress);
            invalidate();
        });
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator == animation) animator = null;
            }
        });
        motion.start();
    }

    void focusLost() {
        // Android reports the old child's loss before the new child's gain.
        removeCallbacks(checkFocus);
        post(checkFocus);
    }

    void reconcileLayout() {
        if (!readBounds()) return;
        if (!positioned || !destination.equals(new RectF(targetBounds))) moveTo(target, false);
        else if (hasWindowFocus() && isShown()) {
            visible = true;
            invalidate();
        }
    }

    void reset() {
        cancelMotion();
        removeCallbacks(reconcile);
        removeCallbacks(checkFocus);
        target = null;
        positioned = false;
        visible = false;
        destination.setEmpty();
        invalidate();
    }

    String state() {
        return "visible=" + visible + " positioned=" + positioned
                + " targetFocused=" + (target != null && target.isFocused())
                + " shown=" + isShown() + " windowFocus=" + hasWindowFocus()
                + " size=" + getWidth() + "x" + getHeight()
                + " targetSize=" + (target == null ? "none" : target.getWidth() + "x" + target.getHeight())
                + " position=" + position + " destination=" + destination
                + " draws=" + draws + " willNotDraw=" + willNotDraw()
                + " animating=" + (animator != null && animator.isRunning());
    }

    private boolean readBounds() {
        if (target == null || !target.isFocused() || target.getWidth() == 0 || target.getHeight() == 0
                || getWidth() == 0 || getHeight() == 0 || getParent() == null) return false;
        // A single-line TextView can scroll its text layout far beyond its own bounds.
        // Window locations track the view box without subtracting that internal text scroll.
        target.getLocationInWindow(targetLocation);
        getLocationInWindow(plateLocation);
        int left = targetLocation[0] - plateLocation[0];
        int top = targetLocation[1] - plateLocation[1];
        targetBounds.set(left, top, left + target.getWidth(), top + target.getHeight());
        return true;
    }

    private void cancelMotion() {
        ValueAnimator old = animator;
        animator = null;
        if (old != null) old.cancel();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        draws++;
        if (!visible || !positioned || position.isEmpty()) return;
        int saved = canvas.save();
        canvas.translate(position.left, position.top);
        glass.setBounds(0, 0, Math.round(position.width()), Math.round(position.height()));
        glass.draw(canvas);
        canvas.restoreToCount(saved);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        reconcileLayout();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        cancelMotion();
        visible = false;
        positioned = false;
        if (focused) reconcileLayout();
        invalidate();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) {
            cancelMotion();
            visible = false;
            positioned = false;
            invalidate();
        } else post(reconcile);
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }
}

package io.github.staperfeed.facebridge

import android.animation.ArgbEvaluator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motion tokens from the PICO design guidelines ("Animation effects"):
 *  - small feedback elements: < 300 ms with a fast easing curve;
 *  - large / framework elements: 300–800 ms with a gentle easing curve;
 *  - direct-manipulation responses: spring (elastic) physics;
 *  - property changes: cubic-bezier(x1, y1, x2, y2) easing.
 * Only 2D window animations are used (no Z motion on a PICO OS 5 panel).
 */
object Motion {
    /** cubic-bezier(0.2, 0, 0, 1): gentle, decelerating — page transitions, large elements. */
    val gentle: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** cubic-bezier(0.3, 0, 0.1, 1): fast-paced — hover/press feedback, small elements. */
    val fast: TimeInterpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
    /** cubic-bezier(0.4, 0, 1, 1): accelerating — elements leaving. */
    val exit: TimeInterpolator = PathInterpolator(0.4f, 0f, 1f, 1f)

    const val FEEDBACK_MS = 160L     // hover / press
    const val SMALL_MS = 240L        // value changes, status color
    const val PAGE_MS = 360L         // page / panel transitions (300–800 ms band)
    const val SPRING_MS = 520L

    /**
     * Damped spring as an interpolator (mass-spring model, normalized time).
     * dampingRatio < 1 overshoots slightly and settles — natural rebound for direct responses.
     */
    class Spring(private val dampingRatio: Float = 0.72f, private val stiffness: Float = 11f) : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            val w = stiffness
            val z = dampingRatio
            val wd = w * sqrt(1f - z * z)
            return 1f - exp(-z * w * t) * (cos(wd * t) + (z * w / wd) * sin(wd * t))
        }
    }
    val spring: TimeInterpolator = Spring()

    /** Press: quick shrink with the fast curve; release: spring back. */
    fun pressFeedback(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(FEEDBACK_MS).setInterpolator(fast).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(SPRING_MS).setInterpolator(spring).start()
            }
            false
        }
    }

    /** Animated color change (status indicators, selection text). */
    fun colorTo(from: Int, to: Int, ms: Long = SMALL_MS, apply: (Int) -> Unit): ValueAnimator? {
        if (from == to) { apply(to); return null }
        return ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = ms
            interpolator = fast
            addUpdateListener { apply(it.animatedValue as Int) }
            start()
        }
    }
}

package com.mitas.ppnam.station3aa

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Instant, interruptible press feedback: scales the view down the moment a finger
 * touches it (not on release) and springs back on lift, per Apple's fluid-interface
 * principles - response lives on press-down, and a critically-damped spring (no
 * overshoot) reads as a settle rather than a bounce, which is the right choice for
 * a tap that carries no gesture momentum. Because it's a spring, a fast repeated
 * tap re-targets smoothly instead of restarting a fixed animation.
 *
 * No-ops when the system's "remove animations" accessibility setting is on.
 */
fun View.applyPressScaleFeedback(pressedScale: Float = 0.96f) {
    if (!ValueAnimator.areAnimatorsEnabled()) return

    // One spring per axis for the lifetime of the view. Creating a fresh SpringAnimation on
    // every touch event let the press-down spring and the release spring run at the same time,
    // and whichever settled last won - which is how buttons stayed at 0.96 after a tap
    // (audit S3-07). animateToFinalPosition retargets the running spring instead.
    fun spring(property: FloatPropertyCompat<View>) = SpringAnimation(this, property).apply {
        spring = SpringForce().apply {
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            stiffness = SpringForce.STIFFNESS_HIGH
        }
    }
    val springs = PressSprings(spring(DynamicAnimation.SCALE_X), spring(DynamicAnimation.SCALE_Y))
    setTag(R.id.press_scale_springs, springs)

    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> springs.animateTo(pressedScale)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springs.animateTo(1f)
        }
        // Let the view's normal click/ripple handling still run.
        v.onTouchEvent(event)
    }
}

/**
 * Returns a view to its resting scale. A view disabled by its own click handler (Log In while
 * the request is in flight, Select Source while a scan is pending) stops receiving touch
 * events, so its ACTION_UP never reaches the listener above; call this when re-enabling it.
 */
fun View.releasePressScale() {
    (getTag(R.id.press_scale_springs) as? PressSprings)?.animateTo(1f)
}

private class PressSprings(private val scaleX: SpringAnimation, private val scaleY: SpringAnimation) {
    fun animateTo(target: Float) {
        scaleX.animateToFinalPosition(target)
        scaleY.animateToFinalPosition(target)
    }
}

/**
 * A one-shot attention pulse (scale up, spring back to rest) - used to nudge the
 * operator toward a button the instant it becomes actionable (e.g. a form just
 * became valid), rather than relying on a state color change alone to be noticed.
 * No-ops under reduced motion, same as [applyPressScaleFeedback].
 */
fun View.flashAttention(peakScale: Float = 1.08f) {
    if (!ValueAnimator.areAnimatorsEnabled()) return

    fun pulse(property: FloatPropertyCompat<View>) {
        SpringAnimation(this, property, peakScale).apply {
            spring = SpringForce(peakScale).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
            addEndListener { _, _, _, _ ->
                SpringAnimation(this@flashAttention, property, 1f).apply {
                    spring = SpringForce(1f).apply {
                        dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                        stiffness = SpringForce.STIFFNESS_HIGH
                    }
                }.start()
            }
        }.start()
    }

    pulse(DynamicAnimation.SCALE_X)
    pulse(DynamicAnimation.SCALE_Y)
}

package com.childpaint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat

/**
 * The look and feel shared by every control in the chrome.
 *
 * A button is a *face standing on a lip*: a rounded face with a darker band
 * under it, like a key on a toy keyboard. Pressing it sinks the face onto the
 * lip and squishes the whole thing a little; letting go springs it back. The
 * selected tool wears the accent instead of white. All of it is built here, in
 * code, from the tokens in colors.xml, so there is exactly one button in the app.
 */

/** Height of the lip under a face, in dp. */
private const val LIP_DP = 4f

/** How far a pressed control shrinks. Visible, not violent. */
private const val SQUISH = 0.9f

/** The rest scale of a view, kept while a press squishes it away from that scale. */
var View.restScale: Float
    get() = getTag(R.id.rest_scale) as? Float ?: 1f
    set(value) { setTag(R.id.rest_scale, value) }

private fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

private fun Context.pill(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radius
    setColor(color)
}

/**
 * One state of a face-on-a-lip button. At rest the face sits [LIP_DP] above the
 * bottom edge and the lip shows underneath; pressed, both drop by the lip height
 * so the key reads as pushed flat.
 */
private fun Context.lipState(face: Int, lip: Int, radius: Float, pressed: Boolean): Drawable {
    val lipPx = dp(LIP_DP).toInt()
    val layers = LayerDrawable(arrayOf(pill(lip, radius), pill(face, radius)))
    if (pressed) {
        layers.setLayerInset(0, 0, lipPx, 0, 0)
        layers.setLayerInset(1, 0, lipPx, 0, 0)
    } else {
        layers.setLayerInset(1, 0, 0, 0, lipPx)
    }
    return layers
}

/** The background for any rectangular control: white on tan, or accent on deep accent. */
fun Context.lipButton(face: Int, lip: Int, radiusDp: Float = 16f): Drawable {
    val radius = dp(radiusDp)
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), lipState(face, lip, radius, pressed = true))
        addState(intArrayOf(), lipState(face, lip, radius, pressed = false))
    }
}

/** The plain button: white face, tan lip. */
fun Context.plainButton(radiusDp: Float = 16f): Drawable =
    lipButton(ContextCompat.getColor(this, R.color.face), ContextCompat.getColor(this, R.color.lip), radiusDp)

/** The chosen one: accent face, deep accent lip. */
fun Context.accentButton(radiusDp: Float = 16f): Drawable =
    lipButton(ContextCompat.getColor(this, R.color.accent), ContextCompat.getColor(this, R.color.accent_lip), radiusDp)

/**
 * Squishes the view while a finger is on it and springs it back on release. The
 * touch is not consumed, so clicks and long presses still arrive as before.
 */
fun View.squishOnPress() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = view.restScale * SQUISH
                view.animate().scaleX(target).scaleY(target)
                    .setDuration(70).setInterpolator(DecelerateInterpolator()).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.springBack()
        }
        false
    }
}

/** Back to the rest scale with a little overshoot: the key pops up under the finger. */
fun View.springBack() {
    animate().scaleX(restScale).scaleY(restScale)
        .setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
}

/** Settles the view at [scale] with a bounce — used when a swatch becomes the chosen one. */
fun View.settleAt(scale: Float) {
    restScale = scale
    springBack()
}

/** A light tick under the finger when something is chosen. */
fun View.tick() {
    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
}

/** A rounded swatch's darker lip: the colour itself, deepened. */
fun lipOf(color: Int): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    return when {
        // Near-black has no darker to go; a grey lip shows its edge instead.
        hsv[2] < 0.25f -> Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], hsv[2] + 0.28f))
        // Near-white: the tray's own lip, so the white swatch has an edge on cream.
        hsv[1] < 0.12f && hsv[2] > 0.92f -> 0xFFE0CDB8.toInt()
        else -> Color.HSVToColor(floatArrayOf(hsv[0], minOf(1f, hsv[1] * 1.08f), hsv[2] * 0.74f))
    }
}

/**
 * A paint blob of one colour: a round face on a lip with a gloss on it, and a
 * double ring when it is the chosen colour. Drawn to its bounds, so the same
 * drawable fits whatever width the row's weights hand the swatch.
 */
class SwatchDrawable(private val color: Int, private val selected: Boolean, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val lipDrop = size * 0.07f
        val r = size / 2f - lipDrop - 1f

        paint.style = Paint.Style.FILL
        paint.color = lipOf(color)
        canvas.drawCircle(cx, cy + lipDrop, r, paint)
        paint.color = color
        canvas.drawCircle(cx, cy - lipDrop * 0.3f, r, paint)

        // The gloss: a soft highlight up and to the left, like a wet drop of paint.
        paint.color = 0x59FFFFFF
        val gloss = RectF(cx - r * 0.55f, cy - r * 0.78f, cx - r * 0.05f, cy - r * 0.35f)
        canvas.save()
        canvas.rotate(-30f, gloss.centerX(), gloss.centerY())
        canvas.drawOval(gloss, paint)
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = lipOf(color)
        canvas.drawCircle(cx, cy - lipDrop * 0.3f, r - paint.strokeWidth / 2f, paint)

        if (selected) {
            // Ink outside, white inside: legible on the black swatch and the white one.
            val ink = 3f * density
            paint.strokeWidth = ink
            paint.color = 0xFF3F2E22.toInt()
            canvas.drawCircle(cx, cy - lipDrop * 0.3f, r - ink / 2f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy - lipDrop * 0.3f, r - ink * 1.5f, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

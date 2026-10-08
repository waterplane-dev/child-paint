package com.childpaint

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The brush-size control: a wedge in the current colour, thin at one end and fat
 * at the other, with a round thumb riding on it that shows the size as a dot.
 *
 * A system seek bar says "a number between 0 and 100". A wedge says "this thin
 * to this fat", and the dot in the thumb says exactly how fat, in the colour
 * that will come out — which is what a child is choosing. The thumb is sized for
 * a small finger, and the whole wedge is a target: a tap anywhere along it jumps
 * the size there.
 */
class BrushSizeSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 0..100, mapped to a stroke width by the activity. */
    var progress: Int = 16
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    /** The colour the wedge and the dot are painted in. */
    var color: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    var onProgressChanged: ((progress: Int, fromUser: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wedge = Path()

    private val thumbRadius get() = 18f * density
    private val sidePad get() = thumbRadius + 4f * density

    init {
        isClickable = true
        contentDescription = context.getString(R.string.brush_size)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (48f * density).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    private fun trackLeft() = paddingLeft + sidePad
    private fun trackRight() = width - paddingRight - sidePad

    private fun thumbX(): Float = trackLeft() + (trackRight() - trackLeft()) * (progress / 100f)

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val left = trackLeft()
        val right = trackRight()
        val thin = 2.5f * density
        val fat = 13f * density

        // One path, not a quad plus two end caps: three overlapping shapes in a
        // translucent paint blend twice where they meet, and the fat end came out
        // as a darker disc. A single winding fill paints the union once.
        wedge.reset()
        wedge.addCircle(left, cy, thin, Path.Direction.CW)
        wedge.addCircle(right, cy, fat, Path.Direction.CW)
        wedge.moveTo(left, cy - thin)
        wedge.lineTo(right, cy - fat)
        wedge.lineTo(right, cy + fat)
        wedge.lineTo(left, cy + thin)
        wedge.close()

        // The lip under the wedge, then the wedge: faint beyond the thumb, solid
        // up to it, so the thumb reads as "this much of the way to fat".
        paint.style = Paint.Style.FILL
        paint.color = 0x1A000000
        canvas.save()
        canvas.translate(0f, 2.5f * density)
        drawWedge(canvas)
        canvas.restore()

        paint.color = (color and 0x00FFFFFF) or 0x55000000
        drawWedge(canvas)
        paint.color = color
        canvas.save()
        canvas.clipRect(0f, 0f, thumbX(), height.toFloat())
        drawWedge(canvas)
        canvas.restore()

        // The thumb: a white button with its own shadow and the dot in it.
        val x = thumbX()
        val r = thumbRadius
        paint.color = 0x33000000
        canvas.drawCircle(x + 1f * density, cy + 3f * density, r, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(x, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xFFE6D4C1.toInt()
        canvas.drawCircle(x, cy, r - paint.strokeWidth / 2f, paint)

        paint.style = Paint.Style.FILL
        paint.color = color
        val t = progress / 100f
        // Quadratic, like the width mapping, so the dot and the stroke agree.
        val dot = 2.5f * density + (r - 7f * density - 2.5f * density) * t * t
        canvas.drawCircle(x, cy, dot, paint)
        if (isLight(color)) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = 0x55000000
            canvas.drawCircle(x, cy, dot, paint)
        }
    }

    private fun drawWedge(canvas: Canvas) = canvas.drawPath(wedge, paint)

    private fun isLight(c: Int): Boolean =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 200

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tick()
                moveTo(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                moveTo(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun moveTo(x: Float) {
        val span = (trackRight() - trackLeft()).coerceAtLeast(1f)
        val next = (((x - trackLeft()) / span) * 100f).toInt().coerceIn(0, 100)
        if (next == progress) return
        progress = next
        onProgressChanged?.invoke(progress, true)
    }
}

package com.childpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PointF
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.UUID

/**
 * The paper. Everything is composited into a single opaque [Bitmap] so that the
 * paint-bucket has real pixels to flood into; the op list on top of it is what
 * makes undo/redo possible without keeping bitmap snapshots around.
 */
class PaintCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * Called when text is wanted, with whatever the box already says — empty for a
     * new box, the current text when an existing one was tapped to be edited. The
     * box itself is held here until [addText] or [cancelText] answers.
     */
    var onTextRequested: ((existing: String) -> Unit)? = null

    /** Called on every touch that lands on the paper, before it is acted on. */
    var onCanvasTouched: (() -> Unit)? = null

    /** Called whenever the undo/redo availability changes. */
    var onHistoryChanged: (() -> Unit)? = null

    var paperColor: Int = Color.WHITE

    /** Changing tool settles whatever shape is still being adjusted. */
    var tool: Tool = Tool.BRUSH
        set(value) {
            if (field != value) commitLive()
            field = value
        }

    // Colour, size and style follow the shape that is still live, so a child can
    // place a hat and then decide it should be red without starting over.
    var color: Int = Color.BLACK
        set(value) {
            field = value
            liveShape?.let { if (it.color != value) { it.color = value; it.changed = true } }
            liveText?.let { if (it.color != value) { it.color = value; it.changed = true } }
            invalidate()
        }

    var strokeWidth: Float = 18f
        set(value) {
            field = value
            liveShape?.let { if (it.width != value) { it.width = value; it.changed = true } }
            // The slider is the font control: it retypes the live text at the new
            // size, which rewraps it, which is the whole point of a box.
            liveText?.let {
                if (it.size != value * TEXT_SIZE_FACTOR) {
                    it.size = value * TEXT_SIZE_FACTOR
                    it.changed = true
                    it.reflow()
                }
            }
            invalidate()
        }

    /** Filled by default: an outline a child then has to aim the bucket into is a
     *  second step nobody asked for. */
    var shapeStyle: ShapeStyle = ShapeStyle.FILLED
        set(value) {
            field = value
            liveShape?.let { if (it.style != value) { it.style = value; it.changed = true } }
            invalidate()
        }

    /** Text is sized off the brush slider so one control drives both. */
    val textSize: Float get() = strokeWidth * TEXT_SIZE_FACTOR

    /** The box the pending text will land in, held while the dialog is up. */
    private var pendingTextBox: RectF? = null

    /** Set when the dialog was opened to edit existing text rather than add new. */
    private var editingText: LiveText? = null

    private val ops = mutableListOf<DrawOp>()
    private val redoStack = mutableListOf<DrawOp>()

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    /**
     * A picture restored from a previous session. It sits underneath the op list as
     * the starting paper, so undo still walks back through this session's ops and
     * stops at the restored image rather than at a blank page.
     */
    private var basePicture: Bitmap? = null

    /**
     * Names [basePicture] on disk. A new one is minted whenever the paper changes,
     * so the saved op list always says which photo it was drawn on and a half-
     * finished save can never pair new ops with an old photo.
     */
    private var basePictureId: String? = null

    /**
     * A saved session waiting for the paper to be laid out at the size it was drawn
     * at. Op coordinates are canvas pixels, so ops restored onto a paper of another
     * size would land in the wrong places; until the sizes agree the flat picture
     * stands in, and the first touch gives up on the session for good.
     */
    private var pendingSession: OpCodec.Session? = null
    private var pendingPaper: Bitmap? = null

    /**
     * Bitmap snapshots taken every [CHECKPOINT_INTERVAL] committed ops, so a replay
     * only has to re-run a bounded suffix of the history.
     *
     * The op list keeps memory flat, but replay cost grows with it, and a [FillOp]
     * is not cheap to re-run: it walks every pixel of the bitmap. A picture with a
     * dozen fills in it made undo a visible freeze. Two snapshots (~8 MB apiece)
     * are kept so undoing back past the newest one still lands on a snapshot.
     */
    private class Checkpoint(val opCount: Int, val bitmap: Bitmap)

    private val checkpoints = mutableListOf<Checkpoint>()

    /**
     * Something that has been put on the paper but not yet committed to the
     * bitmap: a shape that was just drawn, or a rectangle of pixels just lifted
     * off the picture.
     *
     * Everything else in this view is final the moment the finger lifts, which is
     * fine for a brush stroke — you drew where you dragged — but wrong for a
     * shape: a moustache lands in the right place only by luck. So the thing that
     * was just placed stays live and adjustable, and only settles when the child
     * does something else. Because it is not in the bitmap, moving it costs one
     * redraw and no replay at all.
     */
    private abstract class Live(
        var x0: Float, var y0: Float, var x1: Float, var y1: Float,
        var angle: Float
    ) {
        val centerX: Float get() = (x0 + x1) / 2f
        val centerY: Float get() = (y0 + y1) / 2f

        /**
         * Op indices taken off the paper while this is held — set when the hand
         * picked up something already placed. Replay skips them, and settling
         * records a [RelocateOp] that keeps skipping them.
         */
        var hidden: IntArray = IntArray(0)

        /** Set by every drag, knob, pinch or recolour. A pick-up nobody moved records nothing. */
        var changed = false

        val lifted: Boolean get() = hidden.isNotEmpty()

        /** [content] as the op to record: itself for a new thing, a relocation for a picked-up one. */
        protected fun settle(content: DrawOp?): DrawOp? = when {
            !lifted -> content
            !changed -> null
            else -> RelocateOp(hidden, content)
        }

        fun translate(dx: Float, dy: Float) {
            x0 += dx; x1 += dx; y0 += dy; y1 += dy
        }

        /** Scales about the centre, so a pinch grows the shape where it already is. */
        open fun scaleBy(factor: Float) {
            val cx = centerX
            val cy = centerY
            x0 = cx + (x0 - cx) * factor
            x1 = cx + (x1 - cx) * factor
            y0 = cy + (y0 - cy) * factor
            y1 = cy + (y1 - cy) * factor
        }

        /**
         * The touch point moved into the object's own upright frame — the touch is
         * rotated back rather than the box being rotated forward, because every
         * box here is stored upright with an angle beside it.
         */
        fun toLocal(px: Float, py: Float): PointF {
            val cx = centerX
            val cy = centerY
            val radians = Math.toRadians(-angle.toDouble())
            val dx = px - cx
            val dy = py - cy
            return PointF(
                cx + (dx * cos(radians) - dy * sin(radians)).toFloat(),
                cy + (dx * sin(radians) + dy * cos(radians)).toFloat()
            )
        }

        fun contains(px: Float, py: Float, margin: Float): Boolean {
            val local = toLocal(px, py)
            return local.x >= minOf(x0, x1) - margin && local.x <= maxOf(x0, x1) + margin &&
                local.y >= minOf(y0, y1) - margin && local.y <= maxOf(y0, y1) + margin
        }

        /** The upright box the live thing currently occupies. */
        fun box() = RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))

        /** The op that settles this into the bitmap, or null if there is nothing to record. */
        abstract fun toOp(): DrawOp?
    }

    private class LiveShape(
        val kind: ShapeKind,
        x0: Float, y0: Float, x1: Float, y1: Float,
        var color: Int,
        var width: Float,
        var style: ShapeStyle
    ) : Live(x0, y0, x1, y1, 0f) {
        override fun toOp() = settle(ShapeOp(kind, x0, y0, x1, y1, color, width, style, angle))
    }

    /**
     * A rectangle of pixels lifted off the picture and floating above it.
     *
     * The pixels are still in the bitmap underneath — nothing is cut until the
     * selection settles — so the hole is painted at draw time and undoing is just
     * a matter of dropping the selection and letting the paper show through again.
     */
    private class LiveSelection(
        val src: Rect,
        val piece: Bitmap,
        val paperColor: Int
    ) : Live(
        src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat(), 0f
    ) {
        /** Selecting and then thinking better of it must not land an op in history. */
        private val moved: Boolean
            get() = angle != 0f || box().let {
                abs(it.left - src.left) > 0.5f || abs(it.top - src.top) > 0.5f ||
                    abs(it.right - src.right) > 0.5f || abs(it.bottom - src.bottom) > 0.5f
            }

        override fun toOp(): DrawOp? =
            if (moved) MoveOp(Rect(src), box(), angle, paperColor) else null
    }

    /**
     * Text that has been typed but not yet committed, laid out inside a box.
     *
     * The box width is the wrap width, so dragging the corner handle rewraps the
     * text instead of stretching it, and the height is never an input: it is
     * whatever the wrapped text turned out to be, recomputed by [reflow] after
     * every change. Font size is separate — the size slider drives it — which is
     * what makes "smaller letters" and "narrower column" two different moves.
     */
    private class LiveText(
        var text: String,
        x0: Float, y0: Float, x1: Float, y1: Float,
        var color: Int,
        var size: Float
    ) : Live(x0, y0, x1, y1, 0f) {
        /** A pinch scales the lettering with the column, or the text would rewrap mid-pinch. */
        override fun scaleBy(factor: Float) {
            super.scaleBy(factor)
            size *= factor
            reflow()
        }

        /** Normalises the box and drops the bottom edge onto the wrapped text. */
        fun reflow() {
            val left = minOf(x0, x1)
            val width = maxOf(abs(x1 - x0), MIN_TEXT_WIDTH)
            val top = minOf(y0, y1)
            x0 = left
            x1 = left + width
            y0 = top
            y1 = top + textHeight(text, width, size)
        }

        /** Emptied text that was picked up records its deletion, so undo can bring it back. */
        override fun toOp(): DrawOp? = settle(if (text.isBlank()) null else TextOp(text, box(), color, size, angle))
    }

    /**
     * Things picked up off the paper together — or one brush line — carried as one
     * rendered layer. It only ever scales evenly: [frame] is where the layer came
     * from, and a carried face stretched sideways is not a face any more.
     */
    private class LiveGroup(
        val members: List<DrawOp>,
        val frame: Rect,
        val layer: Bitmap,
        dst: RectF,
        angle: Float
    ) : Live(dst.left, dst.top, dst.right, dst.bottom, angle) {
        override fun toOp(): DrawOp? = settle(content())

        private fun content(): DrawOp {
            val box = box()
            val single = members.singleOrNull()
            // One line is put back as a line, redrawn at its new place, rather than
            // as a picture of a line that would blur every time it grew.
            if (single is StrokeOp) {
                return single.mapped(
                    Transform(
                        frame.exactCenterX(), frame.exactCenterY(), box.centerX(), box.centerY(),
                        box.width() / frame.width(), angle
                    )
                )
            }
            return GroupOp(members, Rect(frame), box, angle, layer)
        }
    }

    /** The shape or selection still floating above the bitmap, if there is one. */
    private var live: Live? = null
    private val liveShape: LiveShape? get() = live as? LiveShape
    private val liveSelection: LiveSelection? get() = live as? LiveSelection
    private val liveText: LiveText? get() = live as? LiveText

    /** True while the gesture in flight is moving the live shape, not drawing. */
    private var adjusting = false

    /**
     * The knobs on every live thing. A four-year-old's fingertip covers a small
     * shape completely, so grabbing the shape itself hides exactly what they are
     * trying to place. Each knob hangs off the thing on a string instead: the
     * finger is on the knob, and the thing stays in view above it. The fourth,
     * the bin, is tapped rather than dragged, and throws the thing away.
     */
    private enum class Knob { MOVE, SIZE, TURN, DELETE }

    /** The knob the gesture in flight is dragging, if any. */
    private var knobDrag: Knob? = null

    /** Set for the gesture that picked something up, so letting go is not read as "retype". */
    private var justPicked = false

    /** Set when this gesture's touch put a live thing down; see [finishGesture]. */
    private var settledOnDown = false

    /** When the last pick-up happened; drives the little wiggle that says "got it". */
    private var pickedAt = 0L
    private var pinching = false
    private var pinchDistance = 0f
    private var pinchAngle = 0f

    // Two strokes, not one: a pale solid line underneath and dark dashes on top.
    // A single translucent grey box vanished into a dark scribble, which is
    // exactly the picture a child is most likely to be moving something around on.
    private val liveOutlineHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6FFFFFF.toInt()
    }

    private val liveOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xDD3A2A1C.toInt()
        pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
    }

    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    private val stringHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6FFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    private val stringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xDD3A2A1C.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    /** Paints whatever passes through it as a soft dark silhouette: the held thing's shadow. */
    private val shadowPaint = Paint().apply {
        colorFilter = PorterDuffColorFilter(SHADOW_COLOR, PorterDuff.Mode.SRC_IN)
    }

    private val knobGlyphs: Map<Knob, Drawable?> by lazy {
        mapOf(
            Knob.MOVE to R.drawable.ic_knob_move,
            Knob.SIZE to R.drawable.ic_knob_size,
            Knob.TURN to R.drawable.ic_knob_turn,
            // The same bin as the clear button: one picture for "throw away".
            Knob.DELETE to R.drawable.ic_clear
        ).mapValues { (_, id) -> context.getDrawable(id)?.mutate()?.apply { setTint(Color.WHITE) } }
    }

    // In-progress gesture, drawn as a preview over the bitmap until the finger lifts.
    private var activePath: Path? = null
    /** The same stroke as raw points, which is what gets recorded and saved. */
    private val activePoints = ArrayList<Float>()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    val canUndo: Boolean get() = ops.isNotEmpty() || live != null
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // Op coordinates are canvas pixels, so settle whatever is floating before
        // the paper changes size under it.
        commitLive()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap = bmp
        bitmapCanvas = Canvas(bmp)
        dropCheckpointsAfter(0)
        pendingSession?.let { session ->
            if (session.width == w && session.height == h) {
                ops.clear()
                ops.addAll(session.ops)
                redoStack.clear()
                basePicture = pendingPaper
                basePictureId = session.paperId
                pendingSession = null
                pendingPaper = null
            }
        }
        replayAll()
        onHistoryChanged?.invoke()
    }

    /** Every op index that is off the paper right now: relocated, or held in the hand. */
    private fun hiddenIndices(): Set<Int> =
        ObjectPicker.hiddenIn(ops).apply { live?.hidden?.forEach { add(it) } }

    /**
     * Whether a snapshot of the first [count] ops is still the right starting point.
     * It is not once something later — a relocation, or the thing in the hand —
     * takes back an op the snapshot already has painted in.
     */
    private fun checkpointUsable(count: Int): Boolean {
        if (live?.hidden?.any { it < count } == true) return false
        for (k in count until ops.size) {
            val op = ops[k]
            if (op is RelocateOp && op.hidden.any { it < count }) return false
        }
        return true
    }

    private fun replayAll() {
        val bmp = bitmap ?: return
        val canvas = bitmapCanvas ?: return
        val hidden = hiddenIndices()
        val from = checkpoints.lastOrNull { checkpointUsable(it.opCount) }
        if (from != null) {
            canvas.drawBitmap(from.bitmap, 0f, 0f, null)
        } else {
            canvas.drawColor(paperColor)
            // Scaled to fit rather than stretched to fill. In the normal case the
            // picture is already exactly canvas-sized and this is the identity; it
            // matters when the canvas has since changed height, where stretching
            // would squash a face to fit the new shape.
            basePicture?.let { canvas.drawBitmap(it, null, fitRect(it), null) }
        }
        for (i in (from?.opCount ?: 0) until ops.size) {
            if (i !in hidden) ops[i].apply(canvas, bmp)
            checkpointDuringReplay(i + 1, bmp)
        }
        invalidate()
    }

    /**
     * A long history restored from disk would otherwise be replayed from the very
     * first op on every undo; snapshotting along the way bounds that like a history
     * built up by hand. Never while something is held: the snapshot would be
     * missing it, and would be wrong the moment it is put back.
     */
    private fun checkpointDuringReplay(count: Int, bmp: Bitmap) {
        if (live?.lifted == true) return
        val last = checkpoints.lastOrNull()?.opCount ?: 0
        if (count - last < CHECKPOINT_INTERVAL || !checkpointUsable(count)) return
        dropCheckpointsAfter(count - 1)
        checkpoints += Checkpoint(count, bmp.copy(Bitmap.Config.ARGB_8888, false))
        while (checkpoints.size > MAX_CHECKPOINTS) checkpoints.removeAt(0).bitmap.recycle()
    }

    private fun fitRect(picture: Bitmap): RectF {
        val scale = minOf(width / picture.width.toFloat(), height / picture.height.toFloat())
        val w = picture.width * scale
        val h = picture.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    /** Snapshots the bitmap once enough ops have piled up since the last snapshot. */
    private fun maybeCheckpoint() {
        val bmp = bitmap ?: return
        if (live?.lifted == true) return
        val since = ops.size - (checkpoints.lastOrNull()?.opCount ?: 0)
        if (since < CHECKPOINT_INTERVAL) return
        checkpoints += Checkpoint(ops.size, bmp.copy(Bitmap.Config.ARGB_8888, false))
        while (checkpoints.size > MAX_CHECKPOINTS) {
            checkpoints.removeAt(0).bitmap.recycle()
        }
    }

    /** Undo invalidates every snapshot taken past the new end of the op list. */
    private fun dropCheckpointsAfter(opCount: Int) {
        while (checkpoints.isNotEmpty() && checkpoints.last().opCount > opCount) {
            checkpoints.removeAt(checkpoints.lastIndex).bitmap.recycle()
        }
    }

    /**
     * Replaces the paper with [picture] and starts a fresh history on top of it.
     *
     * Unlike [restorePicture] this drops the op list: the new paper is a new
     * picture, and leaving the old ops in place would replay a child's drawing on
     * top of a photo they have never seen.
     */
    fun setPaperPicture(picture: Bitmap) {
        live = null
        ops.clear()
        redoStack.clear()
        dropCheckpointsAfter(0)
        basePicture = picture
        basePictureId = UUID.randomUUID().toString()
        pendingSession = null
        replayAll()
        onHistoryChanged?.invoke()
    }

    /**
     * Throws the paper away — a photo with it — and starts a blank page.
     *
     * Clearing paints over a photo but leaves it installed underneath, so undo
     * walks back onto it. That is the right behaviour for the clear button and the
     * wrong one for "I am done with this photo", which is what this is for.
     */
    fun useBlankPaper() {
        live = null
        ops.clear()
        redoStack.clear()
        dropCheckpointsAfter(0)
        basePicture = null
        basePictureId = null
        pendingSession = null
        replayAll()
        onHistoryChanged?.invoke()
    }

    /**
     * Puts the last session back; safe to call before the view is laid out.
     *
     * [session] is the op list with [paper] (the photo, if any) under it; [flat]
     * is the whole picture as one image, which stands in whenever the ops cannot
     * be used — unreadable, from an older version, or drawn at another paper size.
     */
    fun restoreSession(session: OpCodec.Session?, paper: Bitmap?, flat: Bitmap?) {
        live = null
        ops.clear()
        redoStack.clear()
        dropCheckpointsAfter(0)
        basePicture = flat
        basePictureId = flat?.let { UUID.randomUUID().toString() }
        val usable = session != null && (session.paperId == null || paper != null)
        pendingSession = if (usable) session else null
        pendingPaper = if (usable) paper else null
        if (usable && width == session!!.width && height == session.height) {
            ops.addAll(session.ops)
            basePicture = paper
            basePictureId = session.paperId
            pendingSession = null
            pendingPaper = null
        }
        replayAll()
        onHistoryChanged?.invoke()
    }

    /** What autosave writes: the op list as JSON, plus the photo it sits on. */
    class SavedSession(val json: String, val paperId: String?, val paper: Bitmap?)

    /**
     * The session as it stands, with the thing in the hand counted as put down, or
     * null while a restored session is still waiting for its paper size — saving
     * then would overwrite the saved objects with the flat stand-in.
     */
    fun saveSession(): SavedSession? {
        if (width <= 0 || height <= 0 || pendingSession != null) return null
        val all = ops.toMutableList()
        live?.toOp()?.let { all += it }
        val json = OpCodec.encode(OpCodec.Session(width, height, basePictureId, all))
        return SavedSession(json, basePictureId, basePicture)
    }

    /**
     * Settles the live shape or selection into the bitmap. Called by anything that
     * reads or rewrites history — undo, clear, export, a new gesture somewhere
     * else — so nothing can be left hovering outside the picture it belongs to.
     */
    fun commitLive() {
        val current = live ?: return
        live = null
        val op = current.toOp()
        if (op == null) {
            // A selection that was never moved, or a thing picked up and put back
            // untouched. Nothing happened, so nothing is recorded — but the box has
            // to come off the screen, and a picked-up thing has to go back on it.
            if (current.lifted) replayAll()
            invalidate()
            onHistoryChanged?.invoke()
            return
        }
        commit(op)
    }

    private fun discardLive(): Boolean {
        val current = live ?: return false
        live = null
        if (current.lifted) replayAll()
        invalidate()
        onHistoryChanged?.invoke()
        return true
    }

    private fun commit(op: DrawOp) {
        val bmp = bitmap ?: return
        val canvas = bitmapCanvas ?: return
        ops.add(op)
        redoStack.clear()
        op.apply(canvas, bmp)
        maybeCheckpoint()
        invalidate()
        onHistoryChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        live?.let {
            drawHeld(canvas, it)
            drawLiveMarker(canvas, it)
        }

        // Live preview of the gesture in flight.
        if (!dragging) return
        val activeColor = if (tool == Tool.ERASER) paperColor else color
        activePath?.let { canvas.drawPath(it, strokePaint(activeColor, strokeWidth)) }
        if (!tool.dragsBox) return
        val corner = dragCorner(lastX, lastY)
        tool.asShape()?.let { kind ->
            drawShape(canvas, kind, downX, downY, corner.x, corner.y, activeColor, strokeWidth, shapeStyle)
        }
        // The box being dragged out. These two are the tools whose preview is the
        // box itself rather than the thing the box will hold — the selection is
        // whatever the box lands on, the text is whatever the box is asked for.
        if (tool == Tool.SELECT || tool == Tool.TEXT) {
            drawMarquee(canvas, RectF(minOf(downX, corner.x), minOf(downY, corner.y), maxOf(downX, corner.x), maxOf(downY, corner.y)))
        }
        // The corner being pulled hangs on a string above the finger, so the child
        // sees the corner rather than the back of their own fingertip.
        drawString(canvas, lastX, lastY, corner.x, corner.y)
        canvas.drawCircle(corner.x, corner.y, 7f * resources.displayMetrics.density, fillPaint(KNOB_SIZE_COLOR))
    }

    /**
     * Paints whatever is floating above the bitmap. A selection also has to paint
     * its own hole: the pixels it lifted are still in the bitmap underneath and
     * would otherwise show through as a copy left behind.
     */
    private fun drawLive(canvas: Canvas, live: Live) {
        if (live is LiveSelection) canvas.drawRect(live.src, fillPaint(live.paperColor))
        drawLiveBody(canvas, live)
    }

    private fun drawLiveBody(canvas: Canvas, live: Live) {
        when (live) {
            is LiveShape -> drawShape(
                canvas, live.kind, live.x0, live.y0, live.x1, live.y1,
                live.color, live.width, live.style, live.angle
            )
            is LiveSelection -> drawPiece(canvas, live.piece, live.box(), live.angle)
            is LiveText -> drawTextBox(canvas, live.text, live.box(), live.color, live.size, live.angle)
            is LiveGroup -> drawPiece(canvas, live.layer, live.box(), live.angle)
        }
    }

    /**
     * The live thing as the child sees it. While a finger is on it, it is "picked
     * up": a little bigger, with a shadow under it, the way a sticker lifts off
     * the page — the finger may be over most of it, but the shadow and the size
     * still say it is being held. A fresh pick-up also wiggles once.
     */
    private fun drawHeld(canvas: Canvas, live: Live) {
        if (live is LiveSelection) canvas.drawRect(live.src, fillPaint(live.paperColor))
        canvas.save()
        canvas.rotate(wiggleAngle(), live.centerX, live.centerY)
        if (adjusting || knobDrag != null) {
            val density = resources.displayMetrics.density
            canvas.saveLayer(null, shadowPaint)
            canvas.translate(4f * density, 6f * density)
            drawLiveBody(canvas, live)
            canvas.restore()
            canvas.scale(HELD_SCALE, HELD_SCALE, live.centerX, live.centerY)
        }
        drawLiveBody(canvas, live)
        canvas.restore()
    }

    private fun wiggleAngle(): Float {
        val t = (SystemClock.uptimeMillis() - pickedAt) / WIGGLE_MS.toFloat()
        if (t >= 1f) return 0f
        postInvalidateOnAnimation()
        return (WIGGLE_DEGREES * sin(t * Math.PI * 6) * (1f - t)).toFloat()
    }

    /**
     * A dashed box around the thing that is still adjustable, and its three knobs.
     * Without the box nothing on screen says that this one thing can still be
     * moved and the rest cannot.
     */
    private fun drawLiveMarker(canvas: Canvas, live: Live) {
        val inset = 8f
        canvas.save()
        canvas.rotate(live.angle, live.centerX, live.centerY)
        val box = live.box()
        box.inset(-inset, -inset)
        drawMarquee(canvas, box)
        canvas.restore()
        val places = knobPlaces(live)
        for ((anchor, center) in places.values) drawString(canvas, anchor.x, anchor.y, center.x, center.y)
        for ((knob, place) in places) drawKnob(canvas, knob, place.second)
    }

    private fun drawMarquee(canvas: Canvas, box: RectF) {
        val density = resources.displayMetrics.density
        val path = Path().apply { addRect(box, Path.Direction.CW) }
        liveOutlineHalo.strokeWidth = 6f * density
        liveOutlinePaint.strokeWidth = 3f * density
        canvas.drawPath(path, liveOutlineHalo)
        canvas.drawPath(path, liveOutlinePaint)
    }

    private fun drawString(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val density = resources.displayMetrics.density
        stringHalo.strokeWidth = 6f * density
        stringPaint.strokeWidth = 3f * density
        canvas.drawLine(x0, y0, x1, y1, stringHalo)
        canvas.drawLine(x0, y0, x1, y1, stringPaint)
    }

    private fun drawKnob(canvas: Canvas, knob: Knob, at: PointF) {
        val density = resources.displayMetrics.density
        val r = knobRadius()
        val color = when (knob) {
            Knob.MOVE -> KNOB_MOVE_COLOR
            Knob.SIZE -> KNOB_SIZE_COLOR
            Knob.TURN -> KNOB_TURN_COLOR
            Knob.DELETE -> KNOB_DELETE_COLOR
        }
        canvas.drawCircle(at.x + 2f * density, at.y + 3f * density, r, fillPaint(SHADOW_COLOR))
        canvas.drawCircle(at.x, at.y, r, fillPaint(color))
        knobRingPaint.strokeWidth = 3f * density
        canvas.drawCircle(at.x, at.y, r, knobRingPaint)
        knobGlyphs[knob]?.let { glyph ->
            val g = (r * 0.62f).roundToInt()
            glyph.setBounds(at.x.roundToInt() - g, at.y.roundToInt() - g, at.x.roundToInt() + g, at.y.roundToInt() + g)
            glyph.draw(canvas)
        }
    }

    /**
     * Where [knob] hangs for [live]: the point on the thing its string is tied to,
     * and the knob's centre.
     *
     * The move knob hangs straight down, whatever the angle — "below it" is the
     * one direction a child does not have to work out. Size and turn ride on the
     * thing's own corner and top, so they turn with it and show which way is up.
     * Any knob that would fall off the paper is pulled back on: a knob that cannot
     * be reached is worse than one sitting over the thing.
     */
    private fun knobPlaces(live: Live): Map<Knob, Pair<PointF, PointF>> {
        val r = knobRadius()
        val cx = live.centerX
        val cy = live.centerY
        val placed = LinkedHashMap<Knob, Pair<PointF, PointF>>()
        // Once the thing is turned, the size knob's corner can swing round onto the
        // move knob hanging below. Two knobs on top of each other is one knob the
        // child cannot reach, so a knob that lands on another is swung round the
        // thing, a step at a time either way, until it is clear.
        for (knob in listOf(Knob.MOVE, Knob.TURN, Knob.SIZE, Knob.DELETE)) {
            val (anchor, center) = knobPlace(live, knob)
            fun clear(p: PointF) = placed.values.none { hypot(it.second.x - p.x, it.second.y - p.y) < r * 2.2f }
            var best = center
            if (!clear(center)) {
                search@ for (step in 1..12) {
                    for (sign in intArrayOf(1, -1)) {
                        val p = rotateAbout(center.x, center.y, cx, cy, sign * step * 15f)
                        p.x = p.x.coerceIn(r, (width - r).coerceAtLeast(r))
                        p.y = p.y.coerceIn(r, (height - r).coerceAtLeast(r))
                        if (clear(p)) { best = p; break@search }
                    }
                }
            }
            placed[knob] = anchor to best
        }
        return placed
    }

    private fun knobPlace(live: Live, knob: Knob): Pair<PointF, PointF> {
        val string = stringLength()
        val box = live.box()
        val cx = live.centerX
        val cy = live.centerY
        val r = knobRadius()
        val (anchor, center) = when (knob) {
            Knob.MOVE -> {
                val outer = rotatedBounds(box, live.angle)
                when {
                    outer.bottom + string + r <= height -> PointF(outer.centerX(), outer.bottom) to PointF(outer.centerX(), outer.bottom + string)
                    outer.left - string - r >= 0 -> PointF(outer.left, outer.centerY()) to PointF(outer.left - string, outer.centerY())
                    outer.right + string + r <= width -> PointF(outer.right, outer.centerY()) to PointF(outer.right + string, outer.centerY())
                    else -> PointF(outer.centerX(), outer.top) to PointF(outer.centerX(), outer.top - string)
                }
            }
            Knob.TURN -> rotateAbout(cx, box.top, cx, cy, live.angle) to
                rotateAbout(cx, box.top - string, cx, cy, live.angle)
            Knob.SIZE -> {
                val line = (live as? LiveShape)?.kind == ShapeKind.LINE
                val px = if (line) live.x1 else box.right
                val py = if (line) live.y1 else box.bottom
                var dx = if (line) px - cx else 1f
                var dy = if (line) py - cy else 1f
                val len = hypot(dx, dy).takeIf { it > 0.5f } ?: run { dx = 1f; dy = 1f; hypot(1f, 1f) }
                dx = dx / len * string * 0.8f
                dy = dy / len * string * 0.8f
                rotateAbout(px, py, cx, cy, live.angle) to rotateAbout(px + dx, py + dy, cx, cy, live.angle)
            }
            // The corner opposite the size knob, so the two never start out together.
            Knob.DELETE -> {
                val line = (live as? LiveShape)?.kind == ShapeKind.LINE
                val px = if (line) live.x0 else box.left
                val py = if (line) live.y0 else box.top
                var dx = if (line) px - cx else -1f
                var dy = if (line) py - cy else -1f
                val len = hypot(dx, dy).takeIf { it > 0.5f } ?: run { dx = -1f; dy = -1f; hypot(1f, 1f) }
                dx = dx / len * string * 0.8f
                dy = dy / len * string * 0.8f
                rotateAbout(px, py, cx, cy, live.angle) to rotateAbout(px + dx, py + dy, cx, cy, live.angle)
            }
        }
        center.x = center.x.coerceIn(r, (width - r).coerceAtLeast(r))
        center.y = center.y.coerceIn(r, (height - r).coerceAtLeast(r))
        return anchor to center
    }

    private fun knobAt(live: Live, x: Float, y: Float): Knob? {
        val reach = knobRadius() * KNOB_REACH
        // Nearest wins, in case two were pulled back on top of each other at an edge.
        return knobPlaces(live).map { (knob, place) -> knob to place.second }
            .filter { (_, c) -> hypot(c.x - x, c.y - y) <= reach }
            .minByOrNull { (_, c) -> hypot(c.x - x, c.y - y) }
            ?.first
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onCanvasTouched?.invoke()
                // Drawing on the stand-in picture makes it the picture; a session
                // still waiting for its paper size is given up on.
                pendingSession = null
                pendingPaper = null
                activePointerId = event.getPointerId(0)
                val x = event.x
                val y = event.y
                downX = x; downY = y; lastX = x; lastY = y
                justPicked = false

                // Touching a knob or the live thing adjusts it; touching anywhere
                // else settles it and starts a new gesture there. No done button:
                // the next thing the child does is the confirmation.
                val current = live
                val knob = current?.let { knobAt(it, x, y) }
                if (knob != null) {
                    knobDrag = knob
                    adjusting = false
                    dragging = false
                    invalidate()
                    return true
                }
                if (current != null && current.contains(x, y, touchMargin())) {
                    adjusting = true
                    pinching = false
                    dragging = false
                    invalidate()
                    return true
                }
                settledOnDown = current != null
                commitLive()

                // The hand picks up whatever is under the finger and, without the
                // finger lifting, starts carrying it.
                if (tool == Tool.SELECT && pickUpAt(x, y)) {
                    adjusting = true
                    pinching = false
                    dragging = false
                    justPicked = true
                    return true
                }

                adjusting = false
                dragging = true
                activePoints.clear()
                when (tool) {
                    Tool.BRUSH, Tool.ERASER -> {
                        activePath = Path().apply { moveTo(x, y) }
                        activePoints += x; activePoints += y
                    }
                    else -> activePath = null
                }
                invalidate()
                return true
            }

            // A second finger — usually the palm of the hand holding the phone — must
            // not steal the stroke. Only the pointer that started the gesture draws.
            // The single exception is a shape being adjusted, where the second finger
            // is the whole point: pinch to resize, twist to rotate.
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (adjusting && event.pointerCount >= 2) {
                    pinching = true
                    pinchDistance = spanBetween(event)
                    pinchAngle = angleBetween(event)
                    // Re-anchor on the centroid. Leaving the anchor on the first
                    // finger made the shape jump by the gap between them the moment
                    // the second finger landed.
                    lastX = (event.getX(0) + event.getX(1)) / 2f
                    lastY = (event.getY(0) + event.getY(1)) / 2f
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                knobDrag?.let { knob ->
                    val index = event.findPointerIndex(activePointerId)
                    if (index >= 0) dragKnob(knob, event.getX(index), event.getY(index))
                    return true
                }
                if (adjusting) {
                    adjustLiveShape(event)
                    return true
                }
                if (!dragging) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                when (tool) {
                    Tool.BRUSH, Tool.ERASER -> {
                        // Quadratic smoothing through the midpoint keeps freehand lines
                        // from looking like a chain of straight segments.
                        activePath?.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
                        activePoints += x; activePoints += y
                    }
                    else -> Unit
                }
                lastX = x; lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (adjusting) {
                    // Back to one finger: re-anchor on whichever pointer is left, or the
                    // shape jumps by the distance between the two.
                    pinching = false
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(remaining)
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    return true
                }
                if (knobDrag != null) return true
                // The drawing finger lifted while another is still down. End the gesture
                // here: without this the stroke stayed live and the next MOVE snapped it
                // across the paper to whichever pointer became index 0.
                if (event.getPointerId(event.actionIndex) != activePointerId) return true
                endGesture(event.getX(event.actionIndex), event.getY(event.actionIndex))
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (knobDrag == Knob.DELETE) {
                    knobDrag = null
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    // Only if the finger is still on the bin: sliding off is how a
                    // child who touched it by mistake gets out of it.
                    val bin = live?.let { knobPlaces(it)[Knob.DELETE]?.second }
                    if (bin != null && hypot(event.x - bin.x, event.y - bin.y) <= knobRadius() * KNOB_REACH) {
                        deleteLive()
                    } else {
                        invalidate()
                    }
                    return true
                }
                if (knobDrag != null) {
                    knobDrag = null
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    invalidate()
                    onHistoryChanged?.invoke()
                    return true
                }
                if (adjusting) {
                    adjusting = false
                    pinching = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    // A tap that never moved is not a drag but a request to retype:
                    // there is nowhere else to put "edit this text" that a child
                    // would find, and on anything else it does nothing. Not on the
                    // tap that just picked the text up, though — that tap was "grab".
                    if (!justPicked && abs(lastX - downX) < TAP_SLOP && abs(lastY - downY) < TAP_SLOP) {
                        liveText?.let { requestTextEdit(it) }
                    }
                    justPicked = false
                    invalidate()
                    onHistoryChanged?.invoke()
                    return true
                }
                if (!dragging) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) cancelGesture() else endGesture(event.getX(index), event.getY(index))
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                adjusting = false
                knobDrag = null
                pinching = false
                cancelGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * One finger drags the live shape; two pinch and twist it about its centre.
     *
     * Both are applied incrementally against the previous event rather than
     * against the start of the gesture, so lifting one finger mid-pinch cannot
     * snap the shape back to where the pinch began.
     */
    private fun adjustLiveShape(event: MotionEvent) {
        val live = live ?: return

        if (pinching && event.pointerCount >= 2) {
            val span = spanBetween(event)
            val angle = angleBetween(event)
            if (pinchDistance > MIN_PINCH_SPAN && span > MIN_PINCH_SPAN) {
                live.scaleBy(span / pinchDistance)
            }
            live.angle += angle - pinchAngle
            pinchDistance = span
            pinchAngle = angle
            // The pair of fingers also carries the shape around with it.
            val cx = (event.getX(0) + event.getX(1)) / 2f
            val cy = (event.getY(0) + event.getY(1)) / 2f
            live.translate(cx - lastX, cy - lastY)
            lastX = cx
            lastY = cy
            live.changed = true
            invalidate()
            return
        }

        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        val x = event.getX(index)
        val y = event.getY(index)
        if (x != lastX || y != lastY) live.changed = true
        live.translate(x - lastX, y - lastY)
        lastX = x
        lastY = y
        invalidate()
    }

    /**
     * Follows a knob. Every one works off the finger's movement since the last
     * event rather than off where the knob is drawn, so a knob that was pulled
     * back inside the paper at an edge still moves the thing by exactly as much
     * as the finger moved.
     */
    private fun dragKnob(knob: Knob, x: Float, y: Float) {
        val live = live ?: return
        if (x == lastX && y == lastY) return
        when (knob) {
            Knob.MOVE -> live.translate(x - lastX, y - lastY)
            Knob.TURN -> {
                val cx = live.centerX
                val cy = live.centerY
                // Too close to the middle and a tiny wobble is a huge turn.
                if (hypot(x - cx, y - cy) > knobRadius() && hypot(lastX - cx, lastY - cy) > knobRadius()) {
                    val before = atan2(lastY - cy, lastX - cx)
                    val after = atan2(y - cy, x - cx)
                    live.angle += Math.toDegrees((after - before).toDouble()).toFloat()
                }
            }
            Knob.SIZE -> resizeLive(live, x, y)
            // The bin does not follow the finger; it acts when the finger lifts.
            Knob.DELETE -> return
        }
        live.changed = true
        lastX = x
        lastY = y
        invalidate()
    }

    /**
     * The size knob. A shape or selection stretches freely, both sides moving so
     * the corner stays under the knob; a line moves its end; text changes the
     * width it wraps to, as the corner handle used to; a carried group only grows
     * or shrinks evenly.
     */
    private fun resizeLive(live: Live, x: Float, y: Float) {
        val min = minObjectPx()
        val radians = Math.toRadians(-live.angle.toDouble())
        val fx = x - lastX
        val fy = y - lastY
        val dx = (fx * cos(radians) - fy * sin(radians)).toFloat()
        val dy = (fx * sin(radians) + fy * cos(radians)).toFloat()
        when {
            live is LiveGroup -> {
                val cx = live.centerX
                val cy = live.centerY
                val before = hypot(lastX - cx, lastY - cy)
                val after = hypot(x - cx, y - cy)
                if (before < 1f) return
                val factor = after / before
                val box = live.box()
                if (factor < 1f && minOf(box.width(), box.height()) * factor < min) return
                live.scaleBy(factor)
            }
            live is LiveText -> {
                val left = minOf(live.x0, live.x1)
                val right = maxOf(live.x0, live.x1) + dx
                live.x0 = left
                live.x1 = right
                live.reflow()
            }
            live is LiveShape && live.kind == ShapeKind.LINE -> {
                live.x1 += dx
                live.y1 += dy
            }
            else -> {
                val box = live.box()
                val cx = box.centerX()
                val cy = box.centerY()
                val halfW = (box.width() / 2f + dx).coerceAtLeast(min / 2f)
                val halfH = (box.height() / 2f + dy).coerceAtLeast(min / 2f)
                live.x0 = cx - halfW; live.x1 = cx + halfW
                live.y0 = cy - halfH; live.y1 = cy + halfH
            }
        }
    }

    /**
     * Throws the live thing away, as one step undo can take back.
     *
     * A thing that was only just made is first put in the history and then
     * relocated to nowhere, rather than simply dropped: a child who bins the wrong
     * star presses undo, and the star has to come back. A selection erases the
     * pixels it lifted, wherever it has been dragged to since.
     */
    private fun deleteLive() {
        val current = live ?: return
        live = null
        when {
            current is LiveSelection -> commit(EraseOp(Rect(current.src), current.paperColor))
            // Already off the paper while held, so recording it gone is all there is.
            current.lifted -> commit(RelocateOp(current.hidden, null))
            else -> {
                val op = current.toOp()
                if (op == null) {
                    invalidate()
                    onHistoryChanged?.invoke()
                    return
                }
                // Recorded without being painted: the bitmap never had it.
                ops.add(op)
                commit(RelocateOp(intArrayOf(ops.lastIndex), null))
            }
        }
    }

    /**
     * Picks up the thing under ([x], [y]) — and whatever is drawn on it — off the
     * paper and makes it live. False if there is nothing there to hold.
     */
    private fun pickUpAt(x: Float, y: Float): Boolean {
        val hidden = ObjectPicker.hiddenIn(ops)
        val index = ObjectPicker.find(ops, hidden, x, y, pickSlop()) ?: return false
        val pick = ObjectPicker.pick(ops, hidden, index, width, height) ?: return false
        val picked: Live = when {
            pick.layer != null -> LiveGroup(pick.members, pick.frame!!, pick.layer, RectF(pick.frame), 0f)
            else -> when (val m = pick.members.single()) {
                is ShapeOp -> LiveShape(m.kind, m.x0, m.y0, m.x1, m.y1, m.color, m.width, m.style)
                    .also { it.angle = m.angle }
                is TextOp -> LiveText(m.text, m.box.left, m.box.top, m.box.right, m.box.top, m.color, m.size)
                    .also { it.angle = m.angle; it.reflow() }
                is GroupOp -> LiveGroup(m.members, m.frame, m.layer, RectF(m.dst), m.angle)
                is StrokeOp -> {
                    val frame = Rect()
                    m.bounds().roundOut(frame)
                    LiveGroup(listOf(m), frame, renderLayer(listOf(m), frame), RectF(frame), 0f)
                }
                else -> return false
            }
        }
        picked.hidden = pick.hidden
        live = picked
        pickedAt = SystemClock.uptimeMillis()
        replayAll()
        onHistoryChanged?.invoke()
        return true
    }

    private fun spanBetween(event: MotionEvent): Float =
        hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

    private fun angleBetween(event: MotionEvent): Float = Math.toDegrees(
        atan2(
            (event.getY(1) - event.getY(0)).toDouble(),
            (event.getX(1) - event.getX(0)).toDouble()
        )
    ).toFloat()

    /** A small shape still has to be grabbable by a fingertip. */
    private fun touchMargin(): Float = 24f * resources.displayMetrics.density

    /**
     * [value] real millimetres in pixels. Measured in millimetres because a
     * fingertip is, and clamped because a handful of devices report a nonsense
     * [android.util.DisplayMetrics.xdpi].
     */
    private fun mm(value: Float, minDp: Float, maxDp: Float): Float {
        val density = resources.displayMetrics.density
        return TypedValue
            .applyDimension(TypedValue.COMPLEX_UNIT_MM, value, resources.displayMetrics)
            .coerceIn(minDp * density, maxDp * density)
    }

    /** Twelve millimetres across: bigger than a four-year-old's fingertip. */
    private fun knobRadius(): Float = mm(KNOB_MM / 2f, 18f, 36f)

    /** How far a knob hangs from the thing — far enough that the finger on it hides nothing. */
    private fun stringLength(): Float = mm(STRING_MM, 40f, 100f)

    private fun pickSlop(): Float = mm(PICK_SLOP_MM, 8f, 30f)

    private fun minObjectPx(): Float = mm(MIN_OBJECT_MM, 16f, 60f)

    /**
     * The smallest selection worth having, in real millimetres rather than pixels.
     *
     * A box measured in pixels is a different size on every phone, and the one it
     * came out smallest on is the one where a child could not grab it.
     */
    private fun minSelectionPx(): Float = mm(MIN_SELECTION_MM, 48f, 240f)

    /** Tools that drag out a box from a corner rather than following the finger. */
    private val Tool.dragsBox: Boolean get() = asShape() != null || this == Tool.SELECT || this == Tool.TEXT

    /**
     * The corner a box drag is pulling, given the finger at ([fx], [fy]).
     *
     * It hangs a string's length above the finger, so the child watches the corner
     * rather than their fingertip. The offset grows in over the first few
     * millimetres of the drag instead of appearing at once: a corner that jumped
     * away the instant the finger landed would read as the shape running off.
     */
    private fun dragCorner(fx: Float, fy: Float): PointF {
        val travelled = hypot(fx - downX, fy - downY)
        val ramp = ((travelled - TAP_SLOP) / mm(RAMP_MM, 12f, 40f)).coerceIn(0f, 1f)
        return PointF(
            fx.coerceIn(0f, width.toFloat()),
            (fy - stringLength() * ramp).coerceIn(0f, height.toFloat())
        )
    }

    private fun requestTextEdit(text: LiveText) {
        editingText = text
        pendingTextBox = text.box()
        onTextRequested?.invoke(text.text)
    }

    private fun endGesture(x: Float, y: Float) {
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        finishGesture(x, y)
    }

    private fun cancelGesture() {
        dragging = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        activePath = null
        activePoints.clear()
        invalidate()
    }

    private fun finishGesture(fingerX: Float, fingerY: Float) {
        val tappedInPlace = abs(fingerX - downX) < TAP_SLOP && abs(fingerY - downY) < TAP_SLOP
        // Box tools finish where the hanging corner is, not under the finger.
        val corner = if (tool.dragsBox && !tappedInPlace) dragCorner(fingerX, fingerY) else PointF(fingerX, fingerY)
        val x = corner.x
        val y = corner.y
        lastX = fingerX; lastY = fingerY

        when (tool) {
            Tool.BRUSH, Tool.ERASER -> {
                activePath ?: return
                activePath = null
                // A tap should leave a dot, not nothing.
                activePoints += x; activePoints += if (tappedInPlace) y + 0.1f else y
                val eraser = tool == Tool.ERASER
                commit(StrokeOp(activePoints.toFloatArray(), if (eraser) paperColor else color, strokeWidth, eraser))
                activePoints.clear()
            }

            Tool.FILL -> commit(FillOp(x.toInt(), y.toInt(), color, FloodFill.DEFAULT_TOLERANCE))

            Tool.TEXT -> {
                // Dragging a box picks the column the text will wrap into; a plain
                // tap gets a sensible default one, because a child who just wants a
                // word should not have to know about boxes at all.
                pendingTextBox = if (tappedInPlace) {
                    val half = width * DEFAULT_TEXT_WIDTH / 2f
                    RectF(x - half, y, x + half, y)
                } else {
                    RectF(minOf(downX, x), minOf(downY, y), maxOf(downX, x), maxOf(downY, y))
                }
                editingText = null
                invalidate()
                onTextRequested?.invoke("")
            }

            // With the hand, tapping the empty paper is how a child puts the held
            // thing down; that tap must not also grab a square of blank paper.
            Tool.SELECT -> if (!(tappedInPlace && settledOnDown)) liftSelection(x, y)

            else -> {
                val kind = tool.asShape() ?: return
                // A tap stamps a ready-made shape where the finger was. Dragging one
                // out is the harder skill; a child who can only tap still gets a
                // star, and then has the knobs to size it.
                live = if (tappedInPlace) {
                    stampedShape(kind, x, y)
                } else {
                    LiveShape(kind, downX, downY, x, y, color, strokeWidth, shapeStyle)
                }
                invalidate()
                onHistoryChanged?.invoke()
            }
        }
    }

    /** A [STAMP_MM]-sized shape centred on the tap, slid back onto the paper if it hangs off. */
    private fun stampedShape(kind: ShapeKind, x: Float, y: Float): LiveShape {
        val half = minOf(mm(STAMP_MM, 80f, 280f), width.toFloat(), height.toFloat()) / 2f
        val cx = x.coerceIn(half, width - half)
        val cy = y.coerceIn(half, height - half)
        return if (kind == ShapeKind.LINE) {
            LiveShape(kind, cx - half, cy, cx + half, cy, color, strokeWidth, shapeStyle)
        } else {
            LiveShape(kind, cx - half, cy - half, cx + half, cy + half, color, strokeWidth, shapeStyle)
        }
    }

    /**
     * Lifts the dragged rectangle of pixels off the picture so it can be carried
     * around, the way a selection works in every paint program.
     *
     * Nothing is cut yet: the pixels stay in the bitmap and the floating copy is
     * drawn over the hole. The cut only becomes real when the selection settles,
     * which is what makes dropping it a free undo.
     */
    private fun liftSelection(x: Float, y: Float) {
        val bmp = bitmap ?: return
        val rect = Rect(
            minOf(downX, x).roundToInt(), minOf(downY, y).roundToInt(),
            maxOf(downX, x).roundToInt(), maxOf(downY, y).roundToInt()
        )
        // A short drag is grown to the minimum rather than thrown away, and a plain
        // tap grabs a minimum-sized box around the finger. Rejecting them was the
        // reason selecting felt like it did nothing: a child's "drag" is a smudge.
        growToMinimum(rect, bmp)
        live = liftRect(rect)
        invalidate()
        onHistoryChanged?.invoke()
    }

    /**
     * Grows [rect] about its centre to at least the minimum selection size, then
     * slides it back inside the paper rather than letting it hang off the edge —
     * a box clipped to a sliver at the edge is the same unusable thing again.
     */
    private fun growToMinimum(rect: Rect, bmp: Bitmap) {
        val min = minSelectionPx().roundToInt()
        val width = minOf(min, bmp.width)
        val height = minOf(min, bmp.height)
        if (rect.width() < width) {
            val centerX = rect.centerX()
            rect.left = centerX - width / 2
            rect.right = rect.left + width
        }
        if (rect.height() < height) {
            val centerY = rect.centerY()
            rect.top = centerY - height / 2
            rect.bottom = rect.top + height
        }
        rect.offset(
            -minOf(rect.left, 0) - maxOf(rect.right - bmp.width, 0),
            -minOf(rect.top, 0) - maxOf(rect.bottom - bmp.height, 0)
        )
    }

    /** Lifts [rect] off the current bitmap, or null if it does not fit on it. */
    private fun liftRect(rect: Rect): LiveSelection? {
        val bmp = bitmap ?: return null
        if (!rect.intersect(0, 0, bmp.width, bmp.height)) return null
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val piece = Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
        // createBitmap returns the source itself for a whole-bitmap subset, and the
        // source keeps being painted on; freeze a copy so the piece stops changing.
        val frozen = if (piece === bmp) bmp.copy(Bitmap.Config.ARGB_8888, false) else piece
        return LiveSelection(rect, frozen, paperColor)
    }

    /**
     * Answers the text dialog. The result is not committed: like a shape it lands
     * as a live object, so it can be dragged, rewrapped and retyped before it
     * becomes part of the picture.
     */
    fun addText(text: String) {
        val box = pendingTextBox ?: return
        pendingTextBox = null
        val editing = editingText
        editingText = null

        if (editing != null) {
            if (text.isBlank()) {
                // Emptying the box is how you delete text you have not settled yet.
                // Text the hand picked up off the paper is already history, so its
                // deletion is recorded — and can be undone — rather than dropped.
                if (editing.lifted) {
                    editing.text = ""
                    editing.changed = true
                    commitLive()
                } else {
                    discardLive()
                }
                return
            }
            if (editing.text != text) editing.changed = true
            editing.text = text
            editing.reflow()
            invalidate()
            return
        }

        if (text.isBlank()) return
        commitLive()
        live = LiveText(text, box.left, box.top, box.right, box.top, color, textSize)
            .also { it.reflow() }
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** The dialog was dismissed without an answer; forget the box it was for. */
    fun cancelText() {
        pendingTextBox = null
        editingText = null
    }

    fun undo() {
        // A shape, selection or text that has not settled yet is undone by dropping
        // it, not by committing it first and then walking history back over it. For
        // a selection that is exactly right: the pixels it lifted never left the
        // bitmap, so dropping it puts the picture back as it was.
        val current = live
        if (current != null) {
            live = null
            // A picked-up thing goes back where it was.
            if (current.lifted) replayAll()
            if (current.toOp() != null) {
                invalidate()
                onHistoryChanged?.invoke()
                return
            }
            // An untouched selection records nothing, so dropping it changed nothing
            // on screen. Undo restores one of those after a move; stopping here would
            // spend the press without the picture moving, so walk history instead.
        }
        if (ops.isEmpty()) {
            invalidate()
            onHistoryChanged?.invoke()
            return
        }
        val undone = ops.removeAt(ops.lastIndex)
        redoStack.add(undone)
        dropCheckpointsAfter(ops.size)
        replayAll()
        // Undoing a move or a caption hands the object back rather than throwing it
        // away: both are placed by eye, and "not quite there" is the usual reason
        // for undoing one. Everything else stays a plain step backwards.
        live = when (undone) {
            is MoveOp -> liftRect(Rect(undone.src))
            is TextOp -> LiveText(
                undone.text, undone.box.left, undone.box.top, undone.box.right,
                undone.box.top, undone.color, undone.size
            ).also { it.angle = undone.angle; it.reflow() }
            else -> null
        }
        invalidate()
        onHistoryChanged?.invoke()
    }

    fun redo() {
        // Settling a shape is new work, and new work clears the redo stack — the
        // same rule every other committed op follows.
        commitLive()
        if (redoStack.isEmpty()) return
        val op = redoStack.removeAt(redoStack.lastIndex)
        ops.add(op)
        // A relocation takes things out from under later ops, so painting it on top
        // is not enough: the whole picture has to be replayed without them.
        if (op is RelocateOp) {
            replayAll()
            onHistoryChanged?.invoke()
            return
        }
        val bmp = bitmap ?: return
        val canvas = bitmapCanvas ?: return
        op.apply(canvas, bmp)
        maybeCheckpoint()
        invalidate()
        onHistoryChanged?.invoke()
    }

    /** Clear is itself an op, so it can be undone like anything else. */
    fun clearPaper() {
        // Settle first, so undoing the clear brings the shape back with the rest.
        commitLive()
        commit(ClearOp(paperColor))
    }

    /** A copy, so the caller can hand it to an encoder without racing the canvas. */
    fun exportBitmap(): Bitmap? {
        val current = live
        // Mutable only when there is something live to paint in; an immutable copy
        // is the better thing to hand an encoder, and Canvas rejects one outright.
        val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, current != null) ?: return null
        // The live shape is painted into the exported copy rather than committed.
        // Autosave runs off this every 1.2 s, and committing here settled the shape
        // before the child could touch it — the adjustable window never opened.
        // Drawing it means a kill still saves the shape; it comes back as part of
        // the restored paper, which is where it would have ended up anyway.
        current?.let { drawLive(Canvas(copy), it) }
        return copy
    }

    private companion object {
        const val TAP_SLOP = 6f
        /** Below this the two fingers are close enough that the ratio is noise. */
        const val MIN_PINCH_SPAN = 24f
        /** Anything smaller than this is a slip of the finger, not a selection. */
        /** Two centimetres of real paper, whatever the screen density says. */
        const val MIN_SELECTION_MM = 20f
        /** Text is sized off the brush slider; this is the multiplier between them. */
        const val TEXT_SIZE_FACTOR = 4f
        /** A tapped text box gets this share of the paper width to wrap into. */
        const val DEFAULT_TEXT_WIDTH = 0.7f
        /** Narrower than this and the box can no longer fit a single letter. */
        const val MIN_TEXT_WIDTH = 40f
        /** Knob diameter in real millimetres. */
        const val KNOB_MM = 12f
        /** How much further out than its drawn edge a knob still catches a finger. */
        const val KNOB_REACH = 1.25f
        /** How far a knob hangs from the thing it moves. */
        const val STRING_MM = 14f
        /** A box drag's corner reaches its full height above the finger over this much travel. */
        const val RAMP_MM = 5f
        /** How near a tap has to land to pick up a thin line it just missed. */
        const val PICK_SLOP_MM = 3f
        /** Nothing is resized smaller than this; below it there is nothing left to grab. */
        const val MIN_OBJECT_MM = 6f
        /** The size of a shape stamped by a tap. */
        const val STAMP_MM = 30f
        const val KNOB_MOVE_COLOR = 0xFF2F80ED.toInt()
        const val KNOB_SIZE_COLOR = 0xFFF2994A.toInt()
        const val KNOB_TURN_COLOR = 0xFF27AE60.toInt()
        const val KNOB_DELETE_COLOR = 0xFFEB5757.toInt()
        const val SHADOW_COLOR = 0x55000000
        /** How much bigger a held thing is drawn — lifted off the page. */
        const val HELD_SCALE = 1.05f
        const val WIGGLE_MS = 450L
        const val WIGGLE_DEGREES = 6.0
        const val CHECKPOINT_INTERVAL = 12
        const val MAX_CHECKPOINTS = 2
    }
}

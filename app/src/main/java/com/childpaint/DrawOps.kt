package com.childpaint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which tool the user currently has selected. */
enum class Tool {
    BRUSH, ERASER, FILL, TEXT, SELECT,
    LINE, RECT, OVAL, TRIANGLE,
    // Everything below here lives behind the "..." button. The toolbar holds what
    // a child reaches for without thinking; the rest is a drawer, not a wall.
    STAR, HEART, DIAMOND, PENTAGON, HEXAGON, ARROW, CLOUD, MOON, SUN, LIGHTNING,
    FLOWER, BUBBLE,
    // Dress-up shapes. Off by default and switched on by a grown-up: they only
    // make sense on top of a photo, and on a blank page they are clutter.
    GLASSES, MUSTACHE, UMBRELLA, CROWN
}

/** Shape tools that are drawn from a drag rectangle. */
enum class ShapeKind {
    LINE, RECT, OVAL, TRIANGLE, STAR, HEART,
    DIAMOND, PENTAGON, HEXAGON, ARROW, CLOUD, MOON, SUN, LIGHTNING, FLOWER, BUBBLE,
    GLASSES, MUSTACHE, UMBRELLA, CROWN
}

/**
 * How a shape is painted. Filled is the default: a child draws a star, not the
 * idea of a star, and an outline they then have to aim the paint bucket into is a
 * second step they did not ask for.
 *
 * [FILLED_OUTLINE] draws the rim in a shade derived from the fill rather than in
 * a separately chosen colour — a second colour picker would double the selection
 * UI for a control that exists to make an edge visible.
 */
enum class ShapeStyle { OUTLINE, FILLED, FILLED_OUTLINE }

fun Tool.asShape(): ShapeKind? = when (this) {
    Tool.LINE -> ShapeKind.LINE
    Tool.RECT -> ShapeKind.RECT
    Tool.OVAL -> ShapeKind.OVAL
    Tool.TRIANGLE -> ShapeKind.TRIANGLE
    Tool.STAR -> ShapeKind.STAR
    Tool.HEART -> ShapeKind.HEART
    Tool.DIAMOND -> ShapeKind.DIAMOND
    Tool.PENTAGON -> ShapeKind.PENTAGON
    Tool.HEXAGON -> ShapeKind.HEXAGON
    Tool.ARROW -> ShapeKind.ARROW
    Tool.CLOUD -> ShapeKind.CLOUD
    Tool.MOON -> ShapeKind.MOON
    Tool.SUN -> ShapeKind.SUN
    Tool.LIGHTNING -> ShapeKind.LIGHTNING
    Tool.FLOWER -> ShapeKind.FLOWER
    Tool.BUBBLE -> ShapeKind.BUBBLE
    Tool.GLASSES -> ShapeKind.GLASSES
    Tool.MUSTACHE -> ShapeKind.MUSTACHE
    Tool.UMBRELLA -> ShapeKind.UMBRELLA
    Tool.CROWN -> ShapeKind.CROWN
    else -> null
}

/**
 * One user action. The canvas keeps a list of these instead of a stack of bitmap
 * snapshots: undo replays the list onto a blank bitmap, which costs a few ms but
 * keeps memory flat (a full-screen ARGB snapshot is ~8 MB apiece).
 *
 * Every op is plain data — no Path, no Bitmap that could not be rebuilt — so the
 * list can be written to disk and a picture comes back after a restart as the
 * same separate things it was drawn as, still pickable by the hand.
 */
sealed class DrawOp {
    abstract fun apply(canvas: Canvas, bitmap: Bitmap)

    /** The area this op paints, or null when that is not knowable up front (a fill). */
    open fun bounds(): RectF? = null

    /** True if the ink this op put down covers ([x], [y]). Only objects answer. */
    open fun hits(x: Float, y: Float): Boolean = false
}

class StrokeOp(
    /** Raw touch points, x/y interleaved; the smoothing is rebuilt by [strokePath]. */
    val points: FloatArray,
    val color: Int,
    val width: Float,
    /** An eraser stroke is a stroke in the paper colour, not a thing to pick up. */
    val eraser: Boolean = false
) : DrawOp() {
    val path: Path by lazy { strokePath(points) }

    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawPath(path, strokePaint(color, width))
    }

    override fun bounds(): RectF = RectF().also {
        path.computeBounds(it, true)
        it.inset(-width / 2f - 1f, -width / 2f - 1f)
    }

    override fun hits(x: Float, y: Float): Boolean {
        if (eraser) return false
        val outline = Path()
        strokePaint(color, width).getFillPath(path, outline)
        return regionContains(outline, x, y)
    }

    /** The same stroke carried by [transform]; the line gets fatter or thinner with it. */
    fun mapped(transform: Transform): StrokeOp {
        val out = FloatArray(points.size)
        transform.matrix().mapPoints(out, points)
        return StrokeOp(out, color, width * transform.scale, eraser)
    }
}

/**
 * The freehand path through [points]: quadratic curves through each midpoint, the
 * same smoothing the live preview draws, so the stroke that lands is the stroke
 * that was watched being drawn.
 */
fun strokePath(points: FloatArray): Path = Path().apply {
    val n = points.size / 2
    if (n == 0) return@apply
    moveTo(points[0], points[1])
    for (k in 1 until n - 1) {
        val px = points[2 * k - 2]
        val py = points[2 * k - 1]
        quadTo(px, py, (px + points[2 * k]) / 2f, (py + points[2 * k + 1]) / 2f)
    }
    if (n > 1) lineTo(points[2 * n - 2], points[2 * n - 1])
}

class ShapeOp(
    val kind: ShapeKind,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val color: Int,
    val width: Float,
    val style: ShapeStyle,
    val angle: Float = 0f
) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        drawShape(canvas, kind, x0, y0, x1, y1, color, width, style, angle)
    }

    override fun bounds(): RectF = rotatedBounds(
        RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1)), angle
    ).also { it.inset(-width / 2f - 1f, -width / 2f - 1f) }

    override fun hits(x: Float, y: Float): Boolean {
        val local = rotateAbout(x, y, (x0 + x1) / 2f, (y0 + y1) / 2f, -angle)
        return regionContains(shapeHitPath(kind, x0, y0, x1, y1, width), local.x, local.y)
    }
}

/**
 * Text laid out inside a box rather than a single line hung off a point.
 *
 * The box is what makes the tool usable: its width is the wrap width, so making
 * it narrower breaks the text onto more lines, and typed newlines survive as
 * themselves. Only the left, top and right of [box] are input — the bottom is
 * wherever the laid-out text ends up.
 */
class TextOp(
    val text: String,
    val box: RectF,
    val color: Int,
    val size: Float,
    val angle: Float = 0f
) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) =
        drawTextBox(canvas, text, box, color, size, angle)

    override fun bounds(): RectF = rotatedBounds(box, angle)

    override fun hits(x: Float, y: Float): Boolean {
        val local = rotateAbout(x, y, box.centerX(), box.centerY(), -angle)
        return box.contains(local.x, local.y)
    }
}

class FillOp(
    val x: Int,
    val y: Int,
    val color: Int,
    val tolerance: Int
) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        FloodFill.fill(bitmap, x, y, color, tolerance)
    }

    /** The same fill into a bitmap whose top-left pixel is ([originX], [originY]) of the paper. */
    fun applyAt(bitmap: Bitmap, originX: Int, originY: Int) {
        FloodFill.fill(bitmap, x - originX, y - originY, color, tolerance)
    }
}

/**
 * Cuts a rectangle of pixels out of the paper and puts it back down somewhere
 * else — the paint-bucket-and-scissors move every drawing program has.
 *
 * The pixels are **not** stored in the op. [apply] reads them out of the bitmap
 * it is handed, which is exactly the state this op saw when it was first
 * committed, so a replay reproduces the same cut. Keeping a copy instead would
 * hang a bitmap off every drag and undo it back into memory again.
 */
class MoveOp(
    /** Where the pixels were cut from. Undo re-lifts exactly this rectangle. */
    val src: Rect,
    val dst: RectF,
    val angle: Float,
    val paperColor: Int
) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        // Normally the identity. It bites when the canvas has since changed height
        // (the hide-the-tools extra does that) and the cut no longer fits: better a
        // stretched piece than a crash on an out-of-bounds createBitmap.
        val cut = Rect(src)
        if (!cut.intersect(0, 0, bitmap.width, bitmap.height)) return
        var piece = Bitmap.createBitmap(bitmap, cut.left, cut.top, cut.width(), cut.height())
        // createBitmap hands back the source itself when the subset is the whole
        // bitmap — and that is the bitmap we are about to paint the hole into.
        if (piece === bitmap) piece = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        canvas.drawRect(cut, fillPaint(paperColor))
        drawPiece(canvas, piece, dst, angle)
        piece.recycle()
    }

    /** Everything this op touched: the hole it left and where the piece went. */
    override fun bounds(): RectF = RectF(src).apply { union(rotatedBounds(dst, angle)) }
}

/**
 * Paints a rectangle of paper over whatever was there: a selection thrown away.
 * Like [MoveOp] it is about pixels, not things, so the hand treats it the same.
 */
class EraseOp(val rect: Rect, val paperColor: Int) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawRect(rect, fillPaint(paperColor))
    }

    override fun bounds(): RectF = RectF(rect)
}

class ClearOp(val color: Int) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(color)
    }
}

/**
 * Several ops picked up together — a circle and the face painted on it — and put
 * down as one thing.
 *
 * The members are rendered onto their own transparent layer and the layer is
 * placed, rather than each member being moved separately. That is what keeps a
 * paint-bucket fill honest: inside the layer the fill only ever meets the things
 * it was carried with, so a red heart moved onto a red sun does not flood into it.
 * Members stay in the coordinates they were drawn at; [frame] is the patch of
 * paper they were lifted from and [dst]/[angle] is where that patch now sits.
 */
class GroupOp(
    val members: List<DrawOp>,
    val frame: Rect,
    val dst: RectF,
    val angle: Float,
    prebuilt: Bitmap? = null
) : DrawOp() {
    /** Built once: the members never change, and replay would otherwise re-run every fill. */
    val layer: Bitmap by lazy { prebuilt ?: renderLayer(members, frame) }

    override fun apply(canvas: Canvas, bitmap: Bitmap) = drawPiece(canvas, layer, dst, angle)

    override fun bounds(): RectF = rotatedBounds(dst, angle)

    /** Hit on the layer's own pixels, so a tap on a gap inside the group misses it. */
    override fun hits(x: Float, y: Float): Boolean {
        val local = rotateAbout(x, y, dst.centerX(), dst.centerY(), -angle)
        if (!dst.contains(local.x, local.y)) return false
        val lx = ((local.x - dst.left) / dst.width() * layer.width).toInt()
        val ly = ((local.y - dst.top) / dst.height() * layer.height).toInt()
        if (lx !in 0 until layer.width || ly !in 0 until layer.height) return false
        return Color.alpha(layer.getPixel(lx, ly)) > 0
    }
}

/** Renders [members] onto a transparent bitmap covering [frame] of the paper. */
fun renderLayer(members: List<DrawOp>, frame: Rect): Bitmap {
    val layer = Bitmap.createBitmap(
        frame.width().coerceAtLeast(1), frame.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(layer)
    canvas.translate(-frame.left.toFloat(), -frame.top.toFloat())
    for (member in members) drawMember(member, canvas, layer, frame)
    return layer
}

/** One member onto a layer; a fill has to be told where the layer sits. */
fun drawMember(member: DrawOp, canvas: Canvas, layer: Bitmap, frame: Rect) {
    if (member is FillOp) member.applyAt(layer, frame.left, frame.top) else member.apply(canvas, layer)
}

/**
 * A thing that was picked up again and put down somewhere else.
 *
 * The ops it was made from stay where they are in the list — replay skips the
 * indices in [hidden] — so undoing the move is just popping this op, and the
 * originals reappear exactly as they were. [content] is the thing at its new
 * place, or null when it was picked up and deleted (emptied text).
 */
class RelocateOp(
    val hidden: IntArray,
    val content: DrawOp?
) : DrawOp() {
    override fun apply(canvas: Canvas, bitmap: Bitmap) {
        content?.apply(canvas, bitmap)
    }

    override fun bounds(): RectF? = content?.bounds()

    override fun hits(x: Float, y: Float): Boolean = content?.hits(x, y) ?: false
}

/**
 * Where a picked-up stroke went: moved so its old centre lands on [toX]/[toY],
 * scaled evenly by [scale] and turned [angle] degrees about that centre. Even
 * scaling on purpose — a carried face would otherwise squash.
 */
class Transform(
    val fromX: Float, val fromY: Float,
    val toX: Float, val toY: Float,
    val scale: Float,
    val angle: Float
) {
    fun matrix(): Matrix = Matrix().apply {
        setTranslate(-fromX, -fromY)
        postScale(scale, scale)
        postRotate(angle)
        postTranslate(toX, toY)
    }
}

/** ([x], [y]) turned [degrees] about ([cx], [cy]). */
fun rotateAbout(x: Float, y: Float, cx: Float, cy: Float, degrees: Float): PointF {
    if (degrees == 0f) return PointF(x, y)
    val radians = Math.toRadians(degrees.toDouble())
    val dx = x - cx
    val dy = y - cy
    return PointF(
        cx + (dx * cos(radians) - dy * sin(radians)).toFloat(),
        cy + (dx * sin(radians) + dy * cos(radians)).toFloat()
    )
}

/** The upright box that holds [box] once it is turned [angle] degrees about its centre. */
fun rotatedBounds(box: RectF, angle: Float): RectF {
    if (angle == 0f) return RectF(box)
    val corners = floatArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom)
    Matrix().apply { setRotate(angle, box.centerX(), box.centerY()) }.mapPoints(corners)
    val out = RectF(corners[0], corners[1], corners[0], corners[1])
    for (i in 1 until 4) out.union(corners[2 * i], corners[2 * i + 1])
    return out
}

private fun regionContains(path: Path, x: Float, y: Float): Boolean {
    val bounds = RectF()
    path.computeBounds(bounds, true)
    if (!bounds.contains(x, y)) return false
    val clip = Region(
        bounds.left.toInt() - 1, bounds.top.toInt() - 1,
        bounds.right.toInt() + 1, bounds.bottom.toInt() + 1
    )
    return Region().apply { setPath(path, clip) }.contains(x.toInt(), y.toInt())
}

fun textPaint(color: Int, size: Float): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    textSize = size
    // Bold because this is a child's caption over a drawing, not body copy: a
    // hairline glyph over a scribble is unreadable at any size.
    isFakeBoldText = true
}

/**
 * Wraps [text] to [width]. Centred, because the text tool is used for labels and
 * names rather than paragraphs, and a ragged left edge reads as a mistake.
 */
fun textLayout(text: String, width: Float, color: Int, size: Float): StaticLayout =
    StaticLayout.Builder
        .obtain(text, 0, text.length, textPaint(color, size), width.roundToInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setIncludePad(false)
        .build()

/** How tall [text] ends up once wrapped to [width] — the height of the box. */
fun textHeight(text: String, width: Float, size: Float): Float =
    textLayout(text, width, Color.BLACK, size).height.toFloat()

/** Draws the wrapped text with its first line at the top of [box]. */
fun drawTextBox(canvas: Canvas, text: String, box: RectF, color: Int, size: Float, angle: Float) {
    if (text.isEmpty()) return
    val layout = textLayout(text, box.width(), color, size)
    canvas.save()
    canvas.rotate(angle, box.centerX(), box.centerY())
    canvas.translate(box.left, box.top)
    layout.draw(canvas)
    canvas.restore()
}

fun strokePaint(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/**
 * Puts a lifted rectangle of pixels down inside [dst], turned [angle] degrees
 * about its centre — the same transform for the floating preview and for the
 * committed [MoveOp], so what the child drags is what lands.
 */
fun drawPiece(canvas: Canvas, piece: Bitmap, dst: RectF, angle: Float) {
    canvas.save()
    canvas.rotate(angle, dst.centerX(), dst.centerY())
    // A moved piece is almost always scaled or turned by a pixel or two, so it is
    // resampled rather than blitted; unfiltered it comes back visibly chewed.
    canvas.drawBitmap(piece, null, dst, piecePaint)
    canvas.restore()
}

private val piecePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

fun fillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.FILL
}

/**
 * The rim colour for [ShapeStyle.FILLED_OUTLINE]: the fill pushed 45% towards
 * black, or towards white when the fill is already dark. Deriving it means every
 * one of the 16 palette colours gets a visible edge, including black and white,
 * without asking a child to choose a second colour.
 */
fun shapeOutlineColor(color: Int): Int {
    val r = Color.red(color)
    val g = Color.green(color)
    val b = Color.blue(color)
    val luminance = 0.299f * r + 0.587f * g + 0.114f * b
    val target = if (luminance < 70f) 255 else 0
    fun mix(channel: Int) = (channel + (target - channel) * OUTLINE_MIX).roundToInt()
    return Color.rgb(mix(r), mix(g), mix(b))
}

/**
 * The rim is deliberately thinner than the brush. At the fat end of the size
 * slider a full-width stroke is wider than most shapes are tall, and the shape
 * disappears inside its own border.
 */
private fun outlineWidth(width: Float): Float = (width * 0.5f).coerceAtLeast(3f)

private const val OUTLINE_MIX = 0.45f

/**
 * Draws [kind] into the box the finger dragged, turned [angle] degrees about the
 * box centre.
 *
 * The rotation is applied to the canvas rather than to the path maths: every
 * shape here is built from an upright [RectF], and teaching each of them about
 * an angle would be the same transform written thirteen times.
 */
fun drawShape(
    canvas: Canvas,
    kind: ShapeKind,
    x0: Float, y0: Float, x1: Float, y1: Float,
    color: Int, width: Float, style: ShapeStyle, angle: Float = 0f
) {
    if (angle != 0f) {
        canvas.save()
        canvas.rotate(angle, (x0 + x1) / 2f, (y0 + y1) / 2f)
        drawShape(canvas, kind, x0, y0, x1, y1, color, width, style, 0f)
        canvas.restore()
        return
    }

    // A line has no inside, so the style never applies to it.
    if (kind == ShapeKind.LINE) {
        canvas.drawLine(x0, y0, x1, y1, strokePaint(color, width))
        return
    }

    val rect = RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    val body = shapePath(kind, rect)

    if (style != ShapeStyle.OUTLINE) canvas.drawPath(body, fillPaint(color))
    if (style != ShapeStyle.FILLED) {
        val plain = style == ShapeStyle.OUTLINE
        canvas.drawPath(
            body,
            strokePaint(
                if (plain) color else shapeOutlineColor(color),
                if (plain) width else outlineWidth(width)
            )
        )
    }
    // Wire parts — the glasses bridge, the umbrella handle — are zero-area paths.
    // They are always stroked in the chosen colour, whatever the body is doing,
    // because filling them draws nothing at all.
    shapeWires(kind, rect)?.let { canvas.drawPath(it, strokePaint(color, width)) }
}

/**
 * Where a finger counts as touching the shape, in its own upright frame: the
 * inside, the rim, and any wire parts. The inside counts even for an outline — a
 * child reaches for the middle of a ring, not for its line.
 */
fun shapeHitPath(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float, width: Float): Path {
    val out = Path()
    val paint = strokePaint(Color.BLACK, width)
    if (kind == ShapeKind.LINE) {
        paint.getFillPath(Path().apply { moveTo(x0, y0); lineTo(x1, y1) }, out)
        return out
    }
    val rect = RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    val body = shapePath(kind, rect)
    paint.getFillPath(body, out)
    out.op(body, Path.Op.UNION)
    shapeWires(kind, rect)?.let { wires ->
        val stroked = Path()
        paint.getFillPath(wires, stroked)
        out.op(stroked, Path.Op.UNION)
    }
    return out
}

private fun shapePath(kind: ShapeKind, r: RectF): Path = when (kind) {
    ShapeKind.LINE -> Path()
    ShapeKind.RECT -> Path().apply { addRect(r, Path.Direction.CW) }
    ShapeKind.OVAL -> Path().apply { addOval(r, Path.Direction.CW) }
    ShapeKind.TRIANGLE -> trianglePath(r)
    ShapeKind.STAR -> starPath(r)
    ShapeKind.HEART -> heartPath(r)
    ShapeKind.DIAMOND -> polygonPath(r, 4)
    ShapeKind.PENTAGON -> polygonPath(r, 5)
    ShapeKind.HEXAGON -> polygonPath(r, 6)
    ShapeKind.ARROW -> arrowPath(r)
    ShapeKind.CLOUD -> cloudPath(r)
    ShapeKind.MOON -> moonPath(r)
    ShapeKind.SUN -> sunPath(r)
    ShapeKind.LIGHTNING -> lightningPath(r)
    ShapeKind.FLOWER -> flowerPath(r)
    ShapeKind.BUBBLE -> bubblePath(r)
    ShapeKind.GLASSES -> glassesLenses(r)
    ShapeKind.MUSTACHE -> mustachePath(r)
    ShapeKind.UMBRELLA -> umbrellaCanopy(r)
    ShapeKind.CROWN -> crownPath(r)
}

private fun shapeWires(kind: ShapeKind, r: RectF): Path? = when (kind) {
    ShapeKind.GLASSES -> glassesBridge(r)
    ShapeKind.UMBRELLA -> umbrellaHandle(r)
    else -> null
}

/**
 * A regular polygon squashed into the drag box, first vertex at the top. One
 * function covers diamond, pentagon and hexagon: they differ only in [sides].
 */
private fun polygonPath(r: RectF, sides: Int): Path {
    val cx = r.centerX()
    val cy = r.centerY()
    val rx = r.width() / 2f
    val ry = r.height() / 2f
    return Path().apply {
        for (i in 0 until sides) {
            val angle = Math.toRadians(-90.0 + 360.0 * i / sides)
            val px = cx + rx * cos(angle).toFloat()
            val py = cy + ry * sin(angle).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

/**
 * Merges overlapping parts into one outline.
 *
 * A cloud drawn as three loose ovals fills correctly but shows every internal arc
 * the moment the child switches to outline mode. Unioning them first means the
 * outline is the silhouette, which is what a cloud is.
 */
private fun union(parts: List<Path>): Path = Path().apply {
    for (part in parts) op(part, Path.Op.UNION)
}

private fun ovalAt(l: Float, t: Float, rt: Float, b: Float) =
    Path().apply { addOval(RectF(l, t, rt, b), Path.Direction.CW) }

private fun arrowPath(r: RectF): Path {
    val w = r.width()
    val h = r.height()
    return Path().apply {
        moveTo(r.left, r.top + h * 0.3f)
        lineTo(r.left + w * 0.58f, r.top + h * 0.3f)
        lineTo(r.left + w * 0.58f, r.top)
        lineTo(r.right, r.centerY())
        lineTo(r.left + w * 0.58f, r.bottom)
        lineTo(r.left + w * 0.58f, r.top + h * 0.7f)
        lineTo(r.left, r.top + h * 0.7f)
        close()
    }
}

private fun cloudPath(r: RectF): Path {
    val w = r.width()
    val h = r.height()
    return union(
        listOf(
            ovalAt(r.left, r.top + h * 0.38f, r.left + w * 0.52f, r.bottom),
            ovalAt(r.left + w * 0.18f, r.top, r.left + w * 0.78f, r.top + h * 0.82f),
            ovalAt(r.left + w * 0.5f, r.top + h * 0.32f, r.right, r.bottom),
            Path().apply {
                addRect(
                    RectF(r.left + w * 0.2f, r.top + h * 0.6f, r.right - w * 0.15f, r.bottom),
                    Path.Direction.CW
                )
            }
        )
    )
}

/** A disc with a second disc bitten out of it — the only way to get a crescent. */
private fun moonPath(r: RectF): Path {
    val w = r.width()
    val h = r.height()
    val disc = ovalAt(r.left, r.top, r.right, r.bottom)
    val bite = ovalAt(
        r.left + w * 0.3f, r.top - h * 0.06f, r.right + w * 0.28f, r.bottom + h * 0.06f
    )
    return Path().apply { op(disc, bite, Path.Op.DIFFERENCE) }
}

private fun sunPath(r: RectF, rays: Int = 8): Path {
    val cx = r.centerX()
    val cy = r.centerY()
    val rx = r.width() / 2f
    val ry = r.height() / 2f
    val parts = mutableListOf(ovalAt(cx - rx * 0.55f, cy - ry * 0.55f, cx + rx * 0.55f, cy + ry * 0.55f))
    for (i in 0 until rays) {
        val angle = 360f / rays * i
        val ray = Path().apply {
            moveTo(cx - rx * 0.16f, cy - ry * 0.5f)
            lineTo(cx + rx * 0.16f, cy - ry * 0.5f)
            lineTo(cx, cy - ry)
            close()
        }
        ray.transform(Matrix().apply { setRotate(angle, cx, cy) })
        parts += ray
    }
    return union(parts)
}

private fun lightningPath(r: RectF): Path {
    val w = r.width()
    val h = r.height()
    return Path().apply {
        moveTo(r.left + w * 0.62f, r.top)
        lineTo(r.left + w * 0.12f, r.top + h * 0.56f)
        lineTo(r.left + w * 0.46f, r.top + h * 0.56f)
        lineTo(r.left + w * 0.34f, r.bottom)
        lineTo(r.left + w * 0.9f, r.top + h * 0.4f)
        lineTo(r.left + w * 0.52f, r.top + h * 0.4f)
        close()
    }
}

private fun flowerPath(r: RectF, petals: Int = 6): Path {
    val cx = r.centerX()
    val cy = r.centerY()
    val rx = r.width() / 2f
    val ry = r.height() / 2f
    val petal = ovalAt(cx - rx * 0.24f, r.top, cx + rx * 0.24f, cy - ry * 0.12f)
    val parts = mutableListOf<Path>()
    for (i in 0 until petals) {
        parts += Path(petal).apply { transform(Matrix().apply { setRotate(360f / petals * i, cx, cy) }) }
    }
    parts += ovalAt(cx - rx * 0.26f, cy - ry * 0.26f, cx + rx * 0.26f, cy + ry * 0.26f)
    return union(parts)
}

private fun bubblePath(r: RectF): Path {
    val w = r.width()
    val h = r.height()
    val body = Path().apply {
        addRoundRect(
            RectF(r.left, r.top, r.right, r.bottom - h * 0.24f),
            w * 0.14f, h * 0.16f, Path.Direction.CW
        )
    }
    val tail = Path().apply {
        moveTo(r.left + w * 0.24f, r.bottom - h * 0.3f)
        lineTo(r.left + w * 0.2f, r.bottom)
        lineTo(r.left + w * 0.5f, r.bottom - h * 0.3f)
        close()
    }
    return union(listOf(body, tail))
}

private fun trianglePath(r: RectF) = Path().apply {
    moveTo(r.centerX(), r.top)
    lineTo(r.right, r.bottom)
    lineTo(r.left, r.bottom)
    close()
}

private fun starPath(r: RectF, points: Int = 5): Path {
    val cx = r.centerX()
    val cy = r.centerY()
    val outer = min(r.width(), r.height()) / 2f
    val inner = outer * 0.42f
    val path = Path()
    // Start at the top point and alternate outer/inner radius.
    val step = Math.PI / points
    var angle = -Math.PI / 2
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) outer else inner
        val px = cx + (radius * cos(angle)).toFloat()
        val py = cy + (radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        angle += step
    }
    path.close()
    return path
}

private fun heartPath(r: RectF): Path {
    val w = r.width()
    val h = r.height()
    return Path().apply {
        moveTo(r.left + w / 2f, r.top + h)
        cubicTo(
            r.left - w * 0.2f, r.top + h * 0.45f,
            r.left + w * 0.2f, r.top - h * 0.1f,
            r.left + w / 2f, r.top + h * 0.28f
        )
        cubicTo(
            r.left + w * 0.8f, r.top - h * 0.1f,
            r.left + w * 1.2f, r.top + h * 0.45f,
            r.left + w / 2f, r.top + h
        )
        close()
    }
}

// ---- dress-up shapes ---------------------------------------------------------
//
// All of these are built inside the drag rectangle, like every other shape, so a
// child sizes a moustache the same way they size a star. They are drawn as paths
// rather than as stamped bitmaps so they scale to any size without blurring and
// take the current colour.

private fun glassesLenses(r: RectF): Path {
    val lens = r.width() * 0.4f
    val top = r.top + r.height() * 0.1f
    val bottom = r.bottom - r.height() * 0.1f
    return Path().apply {
        addOval(RectF(r.left, top, r.left + lens, bottom), Path.Direction.CW)
        addOval(RectF(r.right - lens, top, r.right, bottom), Path.Direction.CW)
    }
}

private fun glassesBridge(r: RectF): Path {
    val lens = r.width() * 0.4f
    val y = r.centerY()
    return Path().apply {
        moveTo(r.left + lens, y)
        quadTo(r.centerX(), y - r.height() * 0.25f, r.right - lens, y)
    }
}

private fun mustachePath(r: RectF): Path {
    val w = r.width()
    val h = r.height()
    val cx = r.centerX()
    return Path().apply {
        // Two swept wings meeting at a point at each tip, with a dip under the nose
        // on the top edge and a notch on the bottom one. Earlier versions rounded
        // the tips off and read as a pair of blobs rather than as a moustache.
        moveTo(r.left, r.top + h * 0.2f)
        cubicTo(r.left + w * 0.2f, r.top + h * 0.22f, r.left + w * 0.36f, r.top + h * 0.4f, cx, r.top + h * 0.42f)
        cubicTo(r.right - w * 0.36f, r.top + h * 0.4f, r.right - w * 0.2f, r.top + h * 0.22f, r.right, r.top + h * 0.2f)
        cubicTo(r.right - w * 0.08f, r.top + h * 0.62f, r.right - w * 0.3f, r.top + h * 0.92f, cx, r.top + h * 0.72f)
        cubicTo(r.left + w * 0.3f, r.top + h * 0.92f, r.left + w * 0.08f, r.top + h * 0.62f, r.left, r.top + h * 0.2f)
        close()
    }
}

private fun umbrellaCanopy(r: RectF, scallops: Int = 4): Path {
    val w = r.width()
    val hem = r.top + r.height() * 0.55f
    val path = Path()
    path.moveTo(r.left, hem)
    path.cubicTo(r.left + w * 0.06f, r.top, r.right - w * 0.06f, r.top, r.right, hem)
    val step = w / scallops
    var x = r.right
    repeat(scallops) {
        val next = x - step
        path.quadTo(x - step / 2f, hem + r.height() * 0.12f, next, hem)
        x = next
    }
    path.close()
    return path
}

private fun umbrellaHandle(r: RectF): Path {
    val cx = r.centerX()
    val hem = r.top + r.height() * 0.55f
    return Path().apply {
        moveTo(cx, hem)
        lineTo(cx, r.bottom - r.height() * 0.1f)
        quadTo(cx, r.bottom, cx - r.width() * 0.15f, r.bottom)
    }
}

private fun crownPath(r: RectF): Path {
    val w = r.width()
    val h = r.height()
    return Path().apply {
        moveTo(r.left, r.bottom)
        lineTo(r.left, r.top + h * 0.2f)
        lineTo(r.left + w * 0.25f, r.top + h * 0.6f)
        lineTo(r.centerX(), r.top)
        lineTo(r.right - w * 0.25f, r.top + h * 0.6f)
        lineTo(r.right, r.top + h * 0.2f)
        lineTo(r.right, r.bottom)
        close()
    }
}

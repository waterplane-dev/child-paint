package com.childpaint

import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The saved-picture format. Ops have no equals, so a round trip is checked by
 * encoding again: whatever comes back must write out exactly as it went in.
 */
@RunWith(RobolectricTestRunner::class)
class OpCodecTest {

    private val red = 0xFFFF0000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private val everyKind: List<DrawOp> = listOf(
        StrokeOp(floatArrayOf(1.5f, 2f, 30.2f, 40.7f), red, 12f),
        StrokeOp(floatArrayOf(5f, 5f), white, 30f, eraser = true),
        ShapeOp(ShapeKind.HEART, 10f, 20f, 110f, 140f, red, 8f, ShapeStyle.FILLED_OUTLINE, 33f),
        TextOp("Mama", RectF(50f, 60f, 250f, 120f), red, 42f, -10f),
        FillOp(70, 80, red, 48),
        MoveOp(Rect(0, 0, 100, 100), RectF(200f, 200f, 300f, 320f), 15f, white),
        EraseOp(Rect(5, 6, 7, 8), white),
        ClearOp(white),
        GroupOp(
            listOf(ShapeOp(ShapeKind.OVAL, 0f, 0f, 50f, 50f, red, 4f, ShapeStyle.FILLED, 0f), FillOp(25, 25, white, 48)),
            Rect(-4, -4, 54, 54), RectF(100f, 100f, 158f, 158f), 90f
        ),
        RelocateOp(intArrayOf(2, 3), ShapeOp(ShapeKind.STAR, 1f, 2f, 3f, 4f, red, 5f, ShapeStyle.OUTLINE, 0f)),
        RelocateOp(intArrayOf(0), null)
    )

    private fun session(ops: List<DrawOp>, paper: String? = "photo-1") = OpCodec.Session(1080, 1800, paper, ops)

    @Test
    fun everyOpKindSurvivesARoundTrip() {
        val json = OpCodec.encode(session(everyKind))
        val back = OpCodec.decode(json)
        assertNotNull(back)
        back!!
        assertEquals(1080, back.width)
        assertEquals(1800, back.height)
        assertEquals("photo-1", back.paperId)
        assertEquals(everyKind.size, back.ops.size)
        assertTrue(back.ops.none { it is UnknownOp })
        assertEquals(json, OpCodec.encode(back))
    }

    @Test
    fun fieldsComeBackAsTheyWent() {
        val back = OpCodec.decode(OpCodec.encode(session(everyKind, paper = null)))!!
        assertNull(back.paperId)
        val text = back.ops[3] as TextOp
        assertEquals("Mama", text.text)
        assertEquals(RectF(50f, 60f, 250f, 120f), text.box)
        assertEquals(-10f, text.angle)
        val move = back.ops[5] as MoveOp
        assertEquals(Rect(0, 0, 100, 100), move.src)
        val relocate = back.ops[9] as RelocateOp
        assertEquals(listOf(2, 3), relocate.hidden.toList())
        assertNull((back.ops[10] as RelocateOp).content)
        assertTrue((back.ops[1] as StrokeOp).eraser)
    }

    @Test
    fun anEmptyStrokeSurvives() {
        val back = OpCodec.decode(OpCodec.encode(session(listOf(StrokeOp(FloatArray(0), red, 4f)))))!!
        assertEquals(0, (back.ops.single() as StrokeOp).points.size)
    }

    /** RelocateOp.hidden is positional: an op from a newer version must keep its slot. */
    @Test
    fun anUnknownOpKeepsItsPlaceAndIsWrittenBackUnchanged() {
        val json = JSONObject(OpCodec.encode(session(everyKind.take(3))))
        val future = JSONObject().put("t", "sticker").put("id", "unicorn").put("r", 7)
        val ops = json.getJSONArray("ops")
        val shifted = (0 until ops.length()).map { ops.get(it) }
        json.put("ops", org.json.JSONArray().put(future).apply { shifted.forEach { put(it) } })

        val back = OpCodec.decode(json.toString())!!
        assertEquals(4, back.ops.size)
        assertTrue(back.ops[0] is UnknownOp)
        assertTrue(back.ops[1] is StrokeOp)

        val again = JSONObject(OpCodec.encode(back)).getJSONArray("ops").getJSONObject(0)
        assertEquals("sticker", again.getString("t"))
        assertEquals("unicorn", again.getString("id"))
    }

    @Test
    fun aShapeKindThisVersionDoesNotKnowCostsOnlyThatShape() {
        val json = OpCodec.encode(session(everyKind.take(3))).replace("\"HEART\"", "\"DRAGON\"")
        val back = OpCodec.decode(json)!!
        assertEquals(3, back.ops.size)
        assertTrue(back.ops[2] is UnknownOp)
    }

    @Test
    fun aFileFromANewerVersionIsRefused() {
        val json = JSONObject(OpCodec.encode(session(everyKind))).put("v", 99).toString()
        assertNull(OpCodec.decode(json))
    }

    @Test
    fun aFileFromAnOlderVersionIsRead() {
        val json = JSONObject(OpCodec.encode(session(everyKind))).put("v", 0).toString()
        assertNotNull(OpCodec.decode(json))
    }

    @Test
    fun aTruncatedFileIsRefusedRatherThanThrown() {
        val json = OpCodec.encode(session(everyKind))
        assertNull(OpCodec.decode(json.substring(0, json.length / 2)))
        assertNull(OpCodec.decode(""))
    }
}

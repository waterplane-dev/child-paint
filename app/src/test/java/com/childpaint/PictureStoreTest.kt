package com.childpaint

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The kept pictures on disk: that the op list always comes back with the photo it
 * names, whatever else is lying in the folder.
 */
@RunWith(RobolectricTestRunner::class)
class PictureStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = PictureStore(context)

    private fun bitmap(w: Int) = Bitmap.createBitmap(w, 8, Bitmap.Config.ARGB_8888)

    private fun session(paperId: String?, paper: Bitmap?) =
        PaintCanvasView.SavedSession(OpCodec.Session(8, 8, paperId, listOf(ClearOp(0))), paper)

    private fun dir(id: String) = File(context.filesDir, "pictures/$id")

    @Test
    fun aNewPhotoReplacesTheOldOneAndTheOldFileIsSwept() {
        store.save("p1", session("a", bitmap(8)), bitmap(8))
        store.save("p1", session("b", bitmap(16)), bitmap(8))
        val loaded = store.load("p1")!!
        assertEquals("b", loaded.session!!.paperId)
        assertEquals(16, loaded.paper!!.width)
        assertFalse(File(dir("p1"), "paper-a.png").exists())
    }

    /** What a kill between the photo and the op list leaves: the new photo, the old list. */
    @Test
    fun theOpListOpensWithThePhotoItNamesNotTheNewestOne() {
        store.save("p1", session("a", bitmap(8)), bitmap(8))
        bitmap(16).let { File(dir("p1"), "paper-b.png").writePngAtomically(it) }
        val loaded = store.load("p1")!!
        assertEquals("a", loaded.session!!.paperId)
        assertEquals(8, loaded.paper!!.width)
    }

    @Test
    fun aPictureWithoutAPhotoLoadsNone() {
        store.save("p1", session("a", bitmap(8)), bitmap(8))
        store.save("p1", session(null, null), bitmap(8))
        assertNull(store.load("p1")!!.paper)
        assertTrue(dir("p1").listFiles()!!.none { it.name.startsWith("paper") })
    }

    /** Pictures saved before photos were named by id kept theirs as paper.png. */
    @Test
    fun aPictureFromBeforeNamedPhotosStillOpensWithIt() {
        store.save("p1", session("a", bitmap(8)), bitmap(8))
        val named = File(dir("p1"), "paper-a.png")
        assertTrue(named.renameTo(File(dir("p1"), "paper.png")))
        assertNotNull(store.load("p1")!!.paper)
    }
}

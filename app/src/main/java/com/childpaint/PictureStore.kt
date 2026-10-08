package com.childpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Pictures kept inside the app so they can be opened again and worked on — every
 * shape, line and caption still a separate thing the hand can pick up.
 *
 * The gallery PNG the save button also writes is for showing people; it is flat,
 * and a flat picture can only be painted over. Each picture here is a folder:
 * the op list, the photo it was drawn on (if any), the flat picture as a stand-in
 * for when the ops cannot be used, and a thumbnail for the grid.
 */
class PictureStore(context: Context) {

    class Entry(val id: String, val thumb: File, val modified: Long)

    class Loaded(val session: OpCodec.Session?, val paper: Bitmap?, val flat: Bitmap?)

    private val root = File(context.filesDir, "pictures")

    /** Newest first: the one just made is the one most likely to be wanted. */
    fun list(): List<Entry> =
        (root.listFiles() ?: emptyArray())
            .filter { File(it, THUMB).exists() }
            .map { Entry(it.name, File(it, THUMB), File(it, THUMB).lastModified()) }
            .sortedByDescending { it.modified }

    /**
     * Writes a picture under [id], replacing what was there. Everything goes to
     * temporary names first and is renamed into place, the op list last, so a
     * kill halfway leaves the old picture whole rather than half of each.
     */
    fun save(id: String, session: PaintCanvasView.SavedSession?, flat: Bitmap) {
        val dir = File(root, id).apply { mkdirs() }
        File(dir, FLAT).writePngAtomically(flat)
        val thumb = Bitmap.createScaledBitmap(
            flat, (flat.width / THUMB_SCALE).coerceAtLeast(1), (flat.height / THUMB_SCALE).coerceAtLeast(1), true
        )
        try {
            File(dir, THUMB).writePngAtomically(thumb)
        } finally {
            thumb.recycle()
        }
        // No session means the paper is still showing the flat stand-in for ops it
        // has not placed yet (see PaintCanvasView.saveSession). Nothing was drawn,
        // so the ops already stored here are still the truth: leave them.
        if (session == null) return
        val paperFile = File(dir, PAPER)
        if (session.paper != null) paperFile.writePngAtomically(session.paper) else paperFile.delete()
        File(dir, OPS).writeTextAtomically(OpCodec.encode(session.session))
    }

    fun load(id: String): Loaded? {
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        val session = File(dir, OPS).takeIf { it.exists() }?.let {
            try { OpCodec.decode(it.readText()) } catch (e: Exception) { null }
        }
        val paper = File(dir, PAPER).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val flat = File(dir, FLAT).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (session == null && flat == null) return null
        return Loaded(session, paper, flat)
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    private companion object {
        const val OPS = "ops.json"
        const val PAPER = "paper.png"
        const val FLAT = "flat.png"
        const val THUMB = "thumb.png"
        const val THUMB_SCALE = 4
    }
}

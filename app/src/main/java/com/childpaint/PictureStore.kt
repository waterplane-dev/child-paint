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
     * Writes a picture under [id], replacing what was there. Each file is written
     * atomically, and the pair that has to agree — the op list and the photo under
     * it — is never overwritten in place: the photo is stored under its own paper
     * id, before the op list that names it, and old photos are swept only after.
     * A kill anywhere leaves an op list with the photo it was drawn on. The flat
     * picture and thumbnail may already be the new ones; they are stand-ins.
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
        val paperName = session.paperId?.let { paperName(it) }
        if (paperName != null && session.paper != null) {
            val paperFile = File(dir, paperName)
            if (!paperFile.exists()) paperFile.writePngAtomically(session.paper)
        }
        File(dir, OPS).writeTextAtomically(OpCodec.encode(session.session))
        dir.listFiles()?.forEach { file ->
            if (file.name != paperName && (file.name == LEGACY_PAPER || file.name.startsWith(PAPER_PREFIX))) file.delete()
        }
    }

    fun load(id: String): Loaded? {
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        val session = File(dir, OPS).takeIf { it.exists() }?.let {
            try { OpCodec.decode(it.readText()) } catch (e: Exception) { null }
        }
        // Only the photo the op list names. Pictures saved before photos were named
        // kept theirs as paper.png, which was the right one whenever it was whole.
        val paper = session?.paperId
            ?.let { id -> File(dir, paperName(id)).takeIf { it.exists() } ?: File(dir, LEGACY_PAPER).takeIf { it.exists() } }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val flat = File(dir, FLAT).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (session == null && flat == null) return null
        return Loaded(session, paper, flat)
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    private fun paperName(paperId: String) = "$PAPER_PREFIX$paperId.png"

    private companion object {
        const val OPS = "ops.json"
        /** The photo under the ops, one file per photo: paper-<id>.png. */
        const val PAPER_PREFIX = "paper-"
        const val LEGACY_PAPER = "paper.png"
        const val FLAT = "flat.png"
        const val THUMB = "thumb.png"
        const val THUMB_SCALE = 4
    }
}

package com.childpaint

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Replaces this file with whatever [write] puts out, all or nothing.
 *
 * Everything is written to a temporary file next to it, flushed to disk, and only
 * then renamed over the old one; a rename within a directory is atomic. In kid mode
 * killing the app is the one exit a child can reach, so a kill in the middle of a
 * save is the normal case, not the edge case: it must leave the previous file
 * whole, never half of the new one.
 */
fun File.writeAtomically(write: (OutputStream) -> Unit) {
    val temp = File(parentFile, "$name.tmp")
    try {
        FileOutputStream(temp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(this)) throw IOException("could not move $temp over $this")
    } catch (e: Exception) {
        temp.delete()
        throw e
    }
}

fun File.writePngAtomically(bitmap: Bitmap) = writeAtomically {
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("PNG encoding failed for $this")
}

fun File.writeTextAtomically(text: String) = writeAtomically { it.write(text.toByteArray(Charsets.UTF_8)) }

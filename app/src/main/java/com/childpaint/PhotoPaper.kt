package com.childpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a picked photo into a sheet of paper the canvas can draw on.
 *
 * The result is always exactly canvas-sized, with the photo letterboxed into it
 * on the paper colour. That matters because the canvas stretches its base picture
 * across the whole view: handing it a photo of some other aspect ratio would
 * squash a face, and a squashed face is precisely what this feature is for.
 */
object PhotoPaper {

    fun load(context: Context, uri: Uri, width: Int, height: Int, paperColor: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val photo = decode(context, uri, width, height) ?: return null
        val paper = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(paper).apply {
            drawColor(paperColor)
            val scale = min(width / photo.width.toFloat(), height / photo.height.toFloat())
            val w = photo.width * scale
            val h = photo.height * scale
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            drawBitmap(photo, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        photo.recycle()
        return paper
    }

    private fun decode(context: Context, uri: Uri, width: Int, height: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, width, height))
                // A hardware bitmap cannot be drawn into a software Canvas, and the
                // canvas bitmap is exactly that.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(context, uri, width, height)
        }
    } catch (e: Exception) {
        // A corrupt or unreadable pick must not take the app down mid-session.
        null
    }

    /** Pre-P: BitmapFactory ignores EXIF, so the rotation has to be applied by hand. */
    private fun decodeLegacy(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees == 0f) return decoded

        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    /** Largest power-of-two shrink that still covers the canvas at full detail. */
    private fun sampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) sample *= 2
        return max(1, sample)
    }
}

package com.childpaint

import android.content.Context

/**
 * The extras a grown-up can switch on, persisted next to [KidModeState].
 *
 * Both default to off, and both are reachable only from the parent panel behind
 * the lock. Photo paper opens the system picker, which is not something a child
 * should be able to reach on their own; the dress-up shapes are off by default
 * because they double the size of the tool grid and only make sense on a photo.
 */
class ParentSettings(context: Context) {

    private val prefs = context.getSharedPreferences("parent_settings", Context.MODE_PRIVATE)

    var photoPaper: Boolean
        get() = prefs.getBoolean(KEY_PHOTO, false)
        set(value) = prefs.edit().putBoolean(KEY_PHOTO, value).apply()

    var funShapes: Boolean
        get() = prefs.getBoolean(KEY_FUN_SHAPES, false)
        set(value) = prefs.edit().putBoolean(KEY_FUN_SHAPES, value).apply()

    /** Tools hidden behind a handle at the bottom edge, leaving the paper alone. */
    var hideToolbar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_TOOLBAR, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_TOOLBAR, value).apply()

    private companion object {
        const val KEY_PHOTO = "photo_paper"
        const val KEY_FUN_SHAPES = "fun_shapes"
        const val KEY_HIDE_TOOLBAR = "hide_toolbar"
    }
}

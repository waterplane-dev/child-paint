package com.childpaint

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * A view that is always as tall as it is wide.
 *
 * The colour swatches are laid out with `layout_weight`, so their width is
 * whatever the screen divides into — a fixed height made them ellipses on every
 * device where that width was not coincidentally the same number of dp.
 */
class SquareView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

package com.subtitleedit

import android.view.View
import android.view.ViewGroup
import com.google.android.material.card.MaterialCardView

internal object ToolCardShadow {
    fun remove(vararg cards: MaterialCardView) {
        cards.forEach { card -> card.outlineProvider = null }
    }

    fun removeFrom(root: View) {
        if (root is MaterialCardView) {
            root.outlineProvider = null
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                removeFrom(root.getChildAt(index))
            }
        }
    }
}

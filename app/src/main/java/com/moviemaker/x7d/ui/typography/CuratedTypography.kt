package com.moviemaker.x7d.ui.typography

import android.content.Context
import android.graphics.Typeface
import com.moviemaker.x7d.model.CuratedFont

/**
 * Resolves the curated fonts to a native [Typeface].
 * If a matching .ttf exists in res/font (e.g. res/font/inter_regular.ttf) it is used;
 * otherwise a close built-in system font is used so the app works with zero font files.
 */
object CuratedTypography {

    private val cache = mutableMapOf<CuratedFont, Typeface>()

    fun getNativeTypeface(context: Context, font: CuratedFont): Typeface {
        return cache.getOrPut(font) { load(context, font) }
    }

    private fun load(context: Context, font: CuratedFont): Typeface {
        val resName = font.assetFileName.removeSuffix(".ttf")
        val resId = context.resources.getIdentifier(resName, "font", context.packageName)
        if (resId != 0) {
            try {
                return context.resources.getFont(resId)
            } catch (ignored: Exception) {
                // fall through to system fallback
            }
        }
        return when (font) {
            CuratedFont.INTER -> Typeface.create("sans-serif", Typeface.NORMAL)
            CuratedFont.MERRIWEATHER -> Typeface.create("serif", Typeface.NORMAL)
            CuratedFont.COURIER_PRIME -> Typeface.create("monospace", Typeface.NORMAL)
            CuratedFont.CINZEL -> Typeface.create("serif", Typeface.BOLD)
            CuratedFont.OSWALD -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            CuratedFont.CAVEAT -> Typeface.create("cursive", Typeface.BOLD)
        }
    }
}

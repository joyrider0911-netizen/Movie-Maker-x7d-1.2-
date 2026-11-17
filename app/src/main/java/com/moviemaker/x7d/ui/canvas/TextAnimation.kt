package com.moviemaker.x7d.ui.canvas

import com.moviemaker.x7d.model.TextAnimationType
import com.moviemaker.x7d.model.TextOverlay

data class TextAnimationValues(
    val visibleText: String,
    val alpha: Float,
    val scaleMultiplier: Float,
    val translationYPx: Float
)

private fun easeOutCubic(t: Float): Float {
    val inv = 1f - t
    return 1f - inv * inv * inv
}

/**
 * Computes the animated state of a [TextOverlay] at [timeUs] (timeline time in microseconds).
 * Shared by the export overlay renderer.
 */
fun computeTextAnimationValues(overlay: TextOverlay, timeUs: Long): TextAnimationValues {
    val animUs = overlay.animationDurationUs.coerceAtLeast(1L)
    val sinceStart = (timeUs - overlay.startUs).coerceAtLeast(0L)
    val untilEnd = (overlay.endUs - timeUs).coerceAtLeast(0L)
    val inP = (sinceStart.toFloat() / animUs.toFloat()).coerceIn(0f, 1f)
    val outP = (untilEnd.toFloat() / animUs.toFloat()).coerceIn(0f, 1f)

    return when (overlay.animation) {
        TextAnimationType.NONE ->
            TextAnimationValues(overlay.text, 1f, 1f, 0f)

        TextAnimationType.FADE_IN_OUT ->
            TextAnimationValues(overlay.text, minOf(inP, outP), 1f, 0f)

        TextAnimationType.TYPEWRITER -> {
            val count = (overlay.text.length * inP).toInt().coerceIn(0, overlay.text.length)
            TextAnimationValues(overlay.text.take(count), outP, 1f, 0f)
        }

        TextAnimationType.SLIDE_UP -> {
            val e = easeOutCubic(inP)
            TextAnimationValues(overlay.text, minOf(e, outP), 1f, 80f * (1f - e))
        }

        TextAnimationType.SCALE_POP -> {
            val e = easeOutCubic(inP)
            TextAnimationValues(overlay.text, minOf(e, outP), 0.6f + 0.4f * e, 0f)
        }
    }
}

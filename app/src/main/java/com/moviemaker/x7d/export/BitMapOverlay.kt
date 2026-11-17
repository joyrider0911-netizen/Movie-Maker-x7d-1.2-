package com.moviemaker.x7d.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.moviemaker.x7d.model.TextOverlay
import com.moviemaker.x7d.ui.canvas.computeTextAnimationValues
import com.moviemaker.x7d.ui.typography.CuratedTypography
import kotlin.math.ceil

/**
 * Renders an animated [TextOverlay] with its curated font, color, scale, rotation,
 * and normalized position directly inside Media3's OpenGL OverlayEffect pipeline.
 */
class Media3TextBitmapOverlay(
    context: Context,
    private val overlay: TextOverlay,
    private val outputWidth: Int,
    private val outputHeight: Int
) : BitmapOverlay() {

    private val transparent1x1: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var cachedText: String? = null
    private var cachedBitmap: Bitmap? = null

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = overlay.textColorArgb.toInt()
        typeface = CuratedTypography.getNativeTypeface(context, overlay.font)
        // Scale SP relative to 1080p export height for visual parity with preview
        textSize = overlay.baseFontSizeSp * (outputHeight / 360f)
        setShadowLayer(4f, 2f, 2f, Color.argb(140, 0, 0, 0))
    }

    private val bgPaint = overlay.backgroundColorArgb?.let { bgColor ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor.toInt() }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (presentationTimeUs !in overlay.startUs..overlay.endUs) {
            return transparent1x1
        }
        val anim = computeTextAnimationValues(overlay, presentationTimeUs)
        val textToDraw = anim.visibleText.ifEmpty { " " }

        if (textToDraw == cachedText && cachedBitmap != null) {
            return cachedBitmap!!
        }

        val maxTextWidth = (outputWidth * 0.8f).toInt().coerceAtLeast(100)
        val staticLayout = StaticLayout.Builder
            .obtain(textToDraw, 0, textToDraw.length, textPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        val actualWidth = (0 until staticLayout.lineCount)
            .maxOfOrNull { ceil(staticLayout.getLineWidth(it)).toInt() }
            ?.coerceAtLeast(16) ?: 16
        val padX = 28
        val padY = 16
        val bmpW = actualWidth + padX * 2
        val bmpH = staticLayout.height.coerceAtLeast(16) + padY * 2

        cachedBitmap?.recycle()
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        bgPaint?.let { paint ->
            canvas.drawRoundRect(
                RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat()),
                14f, 14f, paint
            )
        }

        canvas.save()
        canvas.translate(padX.toFloat() - (maxTextWidth - actualWidth) / 2f, padY.toFloat())
        staticLayout.draw(canvas)
        canvas.restore()

        cachedText = textToDraw
        cachedBitmap = bmp
        return bmp
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        if (presentationTimeUs !in overlay.startUs..overlay.endUs) {
            return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        }
        val anim = computeTextAnimationValues(overlay, presentationTimeUs)

        // Convert UI normalized [0..1] (top-left origin) to OpenGL NDC [-1..1] (bottom-left origin)
        val ndcX = (overlay.normX * 2f - 1f).coerceIn(-1f, 1f)
        val slideOffsetNdcY = (anim.translationYPx / outputHeight.toFloat()) * 2f
        val ndcY = (1f - overlay.normY * 2f - slideOffsetNdcY).coerceIn(-1f, 1f)

        val effectiveScale = (overlay.scale * anim.scaleMultiplier).coerceIn(0.05f, 5f)
        return StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(ndcX, ndcY)
            .setScale(effectiveScale, effectiveScale)
            .setRotationDegrees(-overlay.rotationDeg)
            .setAlphaScale(anim.alpha.coerceIn(0f, 1f))
            .build()
    }

    override fun release() {
        super.release()
        cachedBitmap?.recycle()
        cachedBitmap = null
        transparent1x1.recycle()
    }
}
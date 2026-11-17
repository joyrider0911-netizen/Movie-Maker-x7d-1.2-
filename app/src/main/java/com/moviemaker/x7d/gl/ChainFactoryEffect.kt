package com.moviemaker.x7d.gl

import androidx.media3.common.Effect
import com.moviemaker.x7d.model.VideoClip

/**
 * Builds the exact ordered GPU shader chain for a given [VideoClip].
 * Shared identically between ExoPlayer live preview and Media3 Transformer export.
 */
object ClipEffectChainFactory {

    fun buildEffectsForClip(clip: VideoClip): List<Effect> {
        val effects = mutableListOf<Effect>()

        // 1. Cadence (Stop-Motion) runs first to avoid computing grain/blur on dropped frames
        if (clip.choppyCadence.enabled) {
            effects += ChoppyCadenceGlEffect(clip.choppyCadence.targetFps)
        }

        // 2. Color Grading & Exposure
        if (clip.colorAdjustment.enabled) {
            effects += ColorGradingGlEffect(
                brightness = clip.colorAdjustment.brightness,
                contrast = clip.colorAdjustment.contrast,
                saturation = clip.colorAdjustment.saturation,
                preset = clip.colorAdjustment.preset
            )
        }

        // 3. Gaussian / Box Blur
        if (clip.blur.enabled) {
            effects += BoxGaussianBlurGlEffect(clip.blur.radiusPx)
        }

        // 4. Glitch (RGB split + horizontal tearing)
        if (clip.glitch.enabled) {
            effects += GlitchGlEffect(clip.glitch.intensity)
        }

        // 5. Film Grain (runs after blur/color so grain stays crisp)
        if (clip.filmGrain.enabled) {
            effects += FilmGrainGlEffect(clip.filmGrain.intensity)
        }

        // 6. Intro / Outro Clip Transitions
        val introOut = ClipIntroOutroGlEffect(
            clipDurationUs = clip.durationUs,
            intro = clip.introAnimation,
            outro = clip.outroAnimation
        )
        effects += introOut

        return effects
    }
}
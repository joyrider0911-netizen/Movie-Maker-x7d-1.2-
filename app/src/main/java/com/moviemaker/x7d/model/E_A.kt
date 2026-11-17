package com.moviemaker.x7d.model

import androidx.compose.runtime.Immutable

/**
 * Color grading presets implemented in our single-pass Adreno 610 color shader.
 */
enum class ColorGradePreset(val label: String) {
    NONE("Natural"),
    VINTAGE_SEPIA("Vintage Sepia"),
    COLD_DOCUMENTARY("Cold Documentary")
}

/**
 * Per-clip toggleable OpenGL ES shader effects.
 */
@Immutable
sealed interface ClipEffect {
    val enabled: Boolean

    @Immutable
    data class FilmGrain(
        override val enabled: Boolean = false,
        val intensity: Float = 0.25f // 0.0f .. 1.0f
    ) : ClipEffect

    @Immutable
    data class ChoppyCadence(
        override val enabled: Boolean = false,
        val targetFps: Float = 12f // e.g., 8f or 12f inside a 24–30fps timeline
    ) : ClipEffect

    @Immutable
    data class Glitch(
        override val enabled: Boolean = false,
        val intensity: Float = 0.35f // 0.0f .. 1.0f
    ) : ClipEffect

    @Immutable
    data class GaussianBlur(
        override val enabled: Boolean = false,
        val radiusPx: Float = 4.0f // 0.0f .. 16.0f
    ) : ClipEffect

    @Immutable
    data class ColorAdjustment(
        override val enabled: Boolean = false,
        val brightness: Float = 0.0f,   // -0.5f .. 0.5f
        val contrast: Float = 1.0f,     // 0.5f .. 1.8f
        val saturation: Float = 1.0f,   // 0.0f .. 2.0f
        val preset: ColorGradePreset = ColorGradePreset.NONE
    ) : ClipEffect
}

/**
 * Movie Maker-style clip start (Intro) & end (Outro) animations.
 */
enum class ClipAnimationType(val label: String) {
    NONE("None"),
    FADE_BLACK("Fade (Black)"),
    CROSS_DISSOLVE("Cross-Dissolve"),
    SLIDE_LEFT("Slide Left"),
    SLIDE_RIGHT("Slide Right"),
    WIPE_HORIZONTAL("Wipe"),
    SLOW_ZOOM_KEN_BURNS("Slow Zoom")
}

@Immutable
data class ClipTransitionSpec(
    val type: ClipAnimationType = ClipAnimationType.NONE,
    val durationUs: Long = 500_000L // Default 0.5s (range 150ms .. 2500ms)
)
package com.moviemaker.x7d.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.util.UUID

enum class VideoTrackType {
    MAIN_A_ROLL,
    OVERLAY_B_ROLL_PIP
}

enum class AudioTrackType {
    VOICEOVER_DIALOGUE,
    MUSIC_SFX
}

@Immutable
data class PipTransform(
    val normX: Float = 0.72f,      // Normalized center X (0f..1f)
    val normY: Float = 0.25f,      // Normalized center Y (0f..1f)
    val scale: Float = 0.32f,      // Relative width fraction of canvas (0.15f..1.0f)
    val opacity: Float = 1.0f      // 0.0f..1.0f
)

@Immutable
data class VideoClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val proxyUri: Uri? = null, // 540p cached proxy for lag-free Adreno 610 scrubbing
    val displayName: String,
    val trackType: VideoTrackType = VideoTrackType.MAIN_A_ROLL,
    val timelineStartUs: Long,
    val trimStartUs: Long = 0L,
    val trimEndUs: Long,
    val sourceDurationUs: Long,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val pipTransform: PipTransform = PipTransform(),
    val introAnimation: ClipTransitionSpec = ClipTransitionSpec(),
    val outroAnimation: ClipTransitionSpec = ClipTransitionSpec(),
    val filmGrain: ClipEffect.FilmGrain = ClipEffect.FilmGrain(),
    val choppyCadence: ClipEffect.ChoppyCadence = ClipEffect.ChoppyCadence(),
    val glitch: ClipEffect.Glitch = ClipEffect.Glitch(),
    val blur: ClipEffect.GaussianBlur = ClipEffect.GaussianBlur(),
    val colorAdjustment: ClipEffect.ColorAdjustment = ClipEffect.ColorAdjustment()
) {
    val durationUs: Long
        get() = (trimEndUs - trimStartUs).coerceAtLeast(100_000L)

    val timelineEndUs: Long
        get() = timelineStartUs + durationUs

    /** Active playback URI: 540p proxy for preview when available, full URI for export. */
    fun playbackUri(preferProxy: Boolean): Uri =
        if (preferProxy && proxyUri != null) proxyUri else uri
}

@Immutable
data class AudioClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val trackType: AudioTrackType,
    val timelineStartUs: Long,
    val trimStartUs: Long = 0L,
    val trimEndUs: Long,
    val sourceDurationUs: Long,
    val volume: Float = 1.0f,       // 0.0f .. 1.5f
    val isMuted: Boolean = false,
    val fadeInUs: Long = 0L,        // 0 .. 5_000_000L
    val fadeOutUs: Long = 0L,       // 0 .. 5_000_000L
    val waveformPeaks: FloatArray = FloatArray(0), // Normalized 0f..1f PCM peaks
    val sampleIntervalMs: Int = HardwareCaps.WAVEFORM_SAMPLE_INTERVAL_MS
) {
    val durationUs: Long
        get() = (trimEndUs - trimStartUs).coerceAtLeast(100_000L)

    val timelineEndUs: Long
        get() = timelineStartUs + durationUs

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioClip) return false
        return id == other.id &&
            uri == other.uri &&
            displayName == other.displayName &&
            trackType == other.trackType &&
            timelineStartUs == other.timelineStartUs &&
            trimStartUs == other.trimStartUs &&
            trimEndUs == other.trimEndUs &&
            sourceDurationUs == other.sourceDurationUs &&
            volume == other.volume &&
            isMuted == other.isMuted &&
            fadeInUs == other.fadeInUs &&
            fadeOutUs == other.fadeOutUs &&
            sampleIntervalMs == other.sampleIntervalMs &&
            waveformPeaks.contentEquals(other.waveformPeaks)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + trackType.hashCode()
        result = 31 * result + timelineStartUs.hashCode()
        result = 31 * result + trimStartUs.hashCode()
        result = 31 * result + trimEndUs.hashCode()
        result = 31 * result + volume.hashCode()
        result = 31 * result + isMuted.hashCode()
        result = 31 * result + fadeInUs.hashCode()
        result = 31 * result + fadeOutUs.hashCode()
        result = 31 * result + waveformPeaks.contentHashCode()
        return result
    }
}

@Immutable
data class BeatMarker(
    val id: String = UUID.randomUUID().toString(),
    val positionUs: Long,
    val label: String = "Beat",
    val sourceAudioClipId: String? = null
)

/**
  * Strictly curated 6-font bundle (in the res/font folder). Zero bloat.
 */
enum class CuratedFont(
    val displayName: String,
    val assetFileName: String
) {
    INTER("Inter", "inter_regular.ttf"),
    MERRIWEATHER("Merriweather", "merriweather_regular.ttf"),
    COURIER_PRIME("Courier Prime", "courier_prime_regular.ttf"),
    CINZEL("Cinzel", "cinzel_bold.ttf"),
    OSWALD("Oswald", "oswald_bold.ttf"),
    CAVEAT("Caveat", "caveat_bold.ttf")
}

enum class TextAnimationType(val label: String) {
    NONE("Static"),
    FADE_IN_OUT("Fade In / Out"),
    TYPEWRITER("Typewriter"),
    SLIDE_UP("Slide Up"),
    SCALE_POP("Scale Pop")
}

@Immutable
data class TextOverlay(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "Title Text",
    val startUs: Long = 0L,
    val endUs: Long = 3_000_000L,
    val normX: Float = 0.5f,          // Normalized center X (0f..1f)
    val normY: Float = 0.5f,          // Normalized center Y (0f..1f)
    val scale: Float = 1.0f,          // Pinch scale (0.3f..4.0f)
    val rotationDeg: Float = 0f,      // Gesture rotation (-180f..180f)
    val font: CuratedFont = CuratedFont.INTER,
    val baseFontSizeSp: Float = 28f,
    val textColorArgb: Long = 0xFFFFFFFF,
    val backgroundColorArgb: Long? = null,
    val animation: TextAnimationType = TextAnimationType.FADE_IN_OUT,
    val animationDurationUs: Long = 450_000L
) {
    val durationUs: Long
        get() = (endUs - startUs).coerceAtLeast(250_000L)
}

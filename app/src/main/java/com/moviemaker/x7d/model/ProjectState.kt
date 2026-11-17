package com.moviemaker.x7d.model

import androidx.compose.runtime.Immutable

@Immutable
data class TrackAudioMix(
    val voiceTrackVolume: Float = 1.0f,
    val voiceTrackMuted: Boolean = false,
    val musicTrackVolume: Float = 0.65f,
    val musicTrackMuted: Boolean = false
)

@Immutable
data class ProjectState(
    val projectName: String = "Untitled_X7d_Cut",
    val mainVideoClips: List<VideoClip> = emptyList(),
    val overlayVideoClips: List<VideoClip> = emptyList(),
    val voiceAudioClips: List<AudioClip> = emptyList(),
    val musicAudioClips: List<AudioClip> = emptyList(),
    val beatMarkers: List<BeatMarker> = emptyList(),
    val textOverlays: List<TextOverlay> = emptyList(),
    val trackMix: TrackAudioMix = TrackAudioMix(),
    val playheadUs: Long = 0L,
    val isPlaying: Boolean = false,
    val snapToBeatMarkers: Boolean = true,
    val zoomDpPerSecond: Float = 48f,
    val selectedClipId: String? = null,
    val selectedTextOverlayId: String? = null,
    val exportFrameRate: ExportFrameRate = ExportFrameRate.FPS_30,
    val exportResolution: ExportResolution = ExportResolution.FHD_1080P
) {
    val totalDurationUs: Long
        get() {
            val maxMain = mainVideoClips.maxOfOrNull { it.timelineEndUs } ?: 0L
            val maxOverlay = overlayVideoClips.maxOfOrNull { it.timelineEndUs } ?: 0L
            val maxVoice = voiceAudioClips.maxOfOrNull { it.timelineEndUs } ?: 0L
            val maxMusic = musicAudioClips.maxOfOrNull { it.timelineEndUs } ?: 0L
            val maxText = textOverlays.maxOfOrNull { it.endUs } ?: 0L
            return maxOf(maxMain, maxOverlay, maxVoice, maxMusic, maxText, 1_000_000L)
        }
}
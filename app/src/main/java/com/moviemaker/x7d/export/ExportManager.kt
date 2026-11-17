package com.moviemaker.x7d.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.moviemaker.x7d.gl.ClipEffectChainFactory
import com.moviemaker.x7d.model.AudioClip
import com.moviemaker.x7d.model.ExportFrameRate
import com.moviemaker.x7d.model.ExportResolution
import com.moviemaker.x7d.model.HardwareCaps
import com.moviemaker.x7d.model.ProjectState
import com.moviemaker.x7d.model.VideoClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ExportState {
    data object Idle : ExportState
    data class Exporting(val progress: Float) : ExportState
    data class Completed(val outputUri: Uri, val fileName: String) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Hardware-accelerated MP4 (H.264 AVC + AAC) exporter tuned for HONOR X7d 4G (Snapdragon 685).
 * Strictly enforces 24/25/30 FPS, <= 1080p output, zero watermarks, and saves atomically
 * to /Movies/MovieMakerX7d/ via Android 15 Scoped Storage.
 */
class ExportManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private var activeTransformer: Transformer? = null
    private var progressJob: Job? = null
    private var tempOutputFile: File? = null

    fun startExport(project: ProjectState) {
        if (_exportState.value is ExportState.Exporting) return
        if (project.mainVideoClips.isEmpty()) {
            _exportState.value = ExportState.Failed("Main video track cannot be empty.")
            return
        }

        // Strict hardware caps check
        val resolution: ExportResolution = project.exportResolution
        val frameRate: ExportFrameRate = project.exportFrameRate

        val safeName = project.projectName
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .ifBlank { "MovieMakerX7d" }
        val fileName = "${safeName}_${resolution.height}p_${frameRate.fps}fps_${System.currentTimeMillis()}.mp4"

        val tempDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val tempFile = File(tempDir, fileName)
        if (tempFile.exists()) tempFile.delete()
        tempOutputFile = tempFile

        _exportState.value = ExportState.Exporting(0f)

        val composition = buildComposition(project, resolution, frameRate)

        val videoEncoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(resolution.targetBitrateBps)
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .setEnableFallback(true)
            .build()

        val transformer = Transformer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressJob?.cancel()
                    scope.launch {
                        val savedUri = publishToMoviesMediaStore(tempFile, fileName)
                        tempFile.delete()
                        _exportState.value = if (savedUri != null) {
                            ExportState.Completed(savedUri, fileName)
                        } else {
                            ExportState.Failed("Failed to save MP4 to ${HardwareCaps.EXPORT_RELATIVE_DIR}")
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    progressJob?.cancel()
                    tempFile.delete()
                    _exportState.value = ExportState.Failed(
                        exportException.localizedMessage ?: "Hardware H.264 export error (${exportException.errorCode})"
                    )
                }
            })
            .build()

        activeTransformer = transformer
        transformer.start(composition, tempFile.absolutePath)

        // Poll Transformer progress on Main Looper every 120ms
        progressJob = scope.launch(Dispatchers.Main) {
            val holder = ProgressHolder()
            while (isActive && _exportState.value is ExportState.Exporting) {
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _exportState.value = ExportState.Exporting((holder.progress / 100f).coerceIn(0f, 0.99f))
                }
                delay(120L)
            }
        }
    }

    fun cancelExport() {
        progressJob?.cancel()
        activeTransformer?.cancel()
        activeTransformer = null
        tempOutputFile?.delete()
        _exportState.value = ExportState.Idle
    }

    fun resetState() {
        _exportState.value = ExportState.Idle
    }

    private fun buildComposition(
        project: ProjectState,
        resolution: ExportResolution,
        frameRate: ExportFrameRate
    ): Composition {
        val sequences = mutableListOf<EditedMediaItemSequence>()

        // 1. Primary Video Sequence (A-Roll)
        sequences += buildVideoSequence(
            clips = project.mainVideoClips.sortedBy { it.timelineStartUs },
            includeAudio = true,
            targetFps = frameRate.fps
        )

        // 2. Optional Overlay Video Sequence (B-Roll / PiP)
        val sortedOverlay = project.overlayVideoClips.sortedBy { it.timelineStartUs }
        if (sortedOverlay.isNotEmpty()) {
            sequences += buildVideoSequence(
                clips = sortedOverlay,
                includeAudio = false, // A-Roll + Dedicated Audio tracks carry audio cleanly
                targetFps = frameRate.fps
            )
        }

        // 3. Dedicated Voiceover Audio Sequence
        val sortedVoice = project.voiceAudioClips.sortedBy { it.timelineStartUs }
        if (sortedVoice.isNotEmpty()) {
            sequences += buildAudioSequence(
                clips = sortedVoice,
                trackVolume = project.trackMix.voiceTrackVolume,
                trackMuted = project.trackMix.voiceTrackMuted
            )
        }

        // 4. Dedicated Music / SFX Audio Sequence
        val sortedMusic = project.musicAudioClips.sortedBy { it.timelineStartUs }
        if (sortedMusic.isNotEmpty()) {
            sequences += buildAudioSequence(
                clips = sortedMusic,
                trackVolume = project.trackMix.musicTrackVolume,
                trackMuted = project.trackMix.musicTrackMuted
            )
        }

        // 5. Global Composition Effects: Freeform Text Overlays + Resolution Cap + FPS Cap
        val globalVideoEffects = mutableListOf<Effect>()

        if (project.textOverlays.isNotEmpty()) {
            val bitmapOverlays = ImmutableList.Builder<TextureOverlay>()
            project.textOverlays.forEach { textItem ->
                bitmapOverlays.add(
                    Media3TextBitmapOverlay(
                        context = context,
                        overlay = textItem,
                        outputWidth = resolution.width,
                        outputHeight = resolution.height
                    )
                )
            }
            globalVideoEffects += OverlayEffect(bitmapOverlays.build())
        }

        globalVideoEffects += Presentation.createForWidthAndHeight(
            resolution.width,
            resolution.height,
            Presentation.LAYOUT_SCALE_TO_FIT
        )

        // Enforce strict 24 / 25 / 30 FPS cap
        globalVideoEffects += FrameDropEffect.createDefaultFrameDropEffect(frameRate.fps.toFloat())

        val compositorSettings = buildPipCompositorSettings(sortedOverlay, resolution)

        return Composition.Builder(sequences)
            .setVideoCompositorSettings(compositorSettings)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC)
            .setEffects(Effects(emptyList(), globalVideoEffects))
            .build()
    }

    private fun buildVideoSequence(
        clips: List<VideoClip>,
        includeAudio: Boolean,
        targetFps: Int
    ): EditedMediaItemSequence {
        val trackTypes = if (includeAudio) {
            setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
        } else {
            setOf(C.TRACK_TYPE_VIDEO)
        }
        val builder = EditedMediaItemSequence.Builder(trackTypes)
        var cursorUs = 0L

        clips.forEach { clip ->
            val gapUs = clip.timelineStartUs - cursorUs
            if (gapUs > 10_000L) {
                builder.addGap(gapUs)
                cursorUs += gapUs
            }

            // Always use full-resolution original URI for export (never 540p proxy)
            val mediaItem = MediaItem.Builder()
                .setUri(clip.playbackUri(preferProxy = false))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(clip.trimStartUs)
                        .setEndPositionUs(clip.trimEndUs)
                        .build()
                )
                .build()

            val audioProcessors = if (includeAudio) {
                listOf(
                    ClipVolumeFadeAudioProcessor(
                        clipDurationUs = clip.durationUs,
                        gain = clip.volume,
                        isMuted = clip.isMuted,
                        fadeInUs = 0L,
                        fadeOutUs = 0L
                    )
                )
            } else emptyList()

            val videoEffects = ClipEffectChainFactory.buildEffectsForClip(clip)

            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(!includeAudio || clip.isMuted)
                .setFrameRate(targetFps)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()

            builder.addItem(editedItem)
            cursorUs = clip.timelineEndUs
        }

        return builder.build()
    }

    private fun buildAudioSequence(
        clips: List<AudioClip>,
        trackVolume: Float,
        trackMuted: Boolean
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var cursorUs = 0L

        clips.forEach { clip ->
            val gapUs = clip.timelineStartUs - cursorUs
            if (gapUs > 10_000L) {
                builder.addGap(gapUs)
                cursorUs += gapUs
            }

            val mediaItem = MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(clip.trimStartUs)
                        .setEndPositionUs(clip.trimEndUs)
                        .build()
                )
                .build()

            val processor = ClipVolumeFadeAudioProcessor(
                clipDurationUs = clip.durationUs,
                gain = clip.volume * trackVolume,
                isMuted = trackMuted || clip.isMuted,
                fadeInUs = clip.fadeInUs,
                fadeOutUs = clip.fadeOutUs
            )

            val editedItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(processor), emptyList()))
                .build()

            builder.addItem(editedItem)
            cursorUs = clip.timelineEndUs
        }

        return builder.build()
    }

    private fun buildPipCompositorSettings(
        overlayClips: List<VideoClip>,
        resolution: ExportResolution
    ): VideoCompositorSettings {
        return object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size =
                Size(resolution.width, resolution.height)

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 0) {
                    // Main A-Roll fills frame
                    return StaticOverlaySettings.Builder().build()
                }

                // inputId == 1: B-Roll / PiP Sequence
                val activePip = overlayClips.firstOrNull {
                    presentationTimeUs in it.timelineStartUs..it.timelineEndUs
                }
                if (activePip == null) {
                    // Hide 1x1 black gap placeholder frames completely
                    return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
                }

                val pip = activePip.pipTransform
                val ndcX = (pip.normX * 2f - 1f).coerceIn(-1f, 1f)
                val ndcY = (1f - pip.normY * 2f).coerceIn(-1f, 1f)
                val s = pip.scale.coerceIn(0.15f, 1.0f)

                return StaticOverlaySettings.Builder()
                    .setOverlayFrameAnchor(0f, 0f)
                    .setBackgroundFrameAnchor(ndcX, ndcY)
                    .setScale(s, s)
                    .setAlphaScale(pip.opacity.coerceIn(0f, 1f))
                    .build()
            }
        }
    }

    private suspend fun publishToMoviesMediaStore(sourceFile: File, fileName: String): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, HardwareCaps.EXPORT_RELATIVE_DIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val targetUri = resolver.insert(collection, values) ?: return@withContext null

            try {
                resolver.openOutputStream(targetUri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                } ?: run {
                    resolver.delete(targetUri, null, null)
                    return@withContext null
                }

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(targetUri, values, null, null)
                targetUri
            } catch (ignored: Exception) {
                resolver.delete(targetUri, null, null)
                null
            }
        }
}
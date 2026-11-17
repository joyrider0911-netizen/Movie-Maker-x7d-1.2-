package com.moviemaker.x7d.export

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Sample-accurate PCM16 volume, mute, fade-in, and fade-out processor for Media3
 * Transformer compositions (Voiceover & Music tracks).
 */
class ClipVolumeFadeAudioProcessor(
    private val clipDurationUs: Long,
    private val gain: Float,
    private val isMuted: Boolean,
    private val fadeInUs: Long,
    private val fadeOutUs: Long
) : BaseAudioProcessor() {

    private var processedFrames: Long = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        processedFrames = 0L
    }

    override fun onReset() {
        processedFrames = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)

        while (inputBuffer.hasRemaining()) {
            val timeUs = (processedFrames * 1_000_000L) / sampleRate
            val env = computeEnvelope(timeUs)

            for (ch in 0 until channels) {
                if (!inputBuffer.hasRemaining()) break
                val sample = inputBuffer.getShort()
                val scaled = (sample * env).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outputBuffer.putShort(scaled.toShort())
            }
            processedFrames++
        }
        outputBuffer.flip()
    }

    private fun computeEnvelope(timeUs: Long): Float {
        if (isMuted || gain <= 0.001f) return 0f
        var factor = gain
        if (fadeInUs > 0L && timeUs < fadeInUs) {
            factor *= (timeUs.toFloat() / fadeInUs.toFloat()).coerceIn(0f, 1f)
        }
        val fadeOutStartUs = (clipDurationUs - fadeOutUs).coerceAtLeast(0L)
        if (fadeOutUs > 0L && timeUs >= fadeOutStartUs) {
            val remain = (clipDurationUs - timeUs).coerceAtLeast(0L)
            factor *= (remain.toFloat() / fadeOutUs.toFloat()).coerceIn(0f, 1f)
        }
        return factor
    }
}
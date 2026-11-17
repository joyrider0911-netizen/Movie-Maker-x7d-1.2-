package com.moviemaker.x7d.gl

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Stop-Motion / Choppy cadence frame-hold shader effect.
 * Holds each sampled texture in a local FBO across [stepUs] intervals so a 12fps or 8fps
 * cadence renders crisply inside a 24/25/30 FPS timeline without Media3 FBO lifecycle glitches.
 */
class ChoppyCadenceGlEffect(
    private val targetFps: Float
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = targetFps >= 30f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ChoppyCadenceShaderProgram(useHdr, targetFps.coerceIn(2f, 29f))
    }

    private class ChoppyCadenceShaderProgram(
        private val useHdr: Boolean,
        targetFps: Float
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 2) {

        private val glProgram: GlProgram =
            GlShaderCommons.createStandardProgram(COPY_FRAGMENT_SHADER)
        private val stepUs: Long = (1_000_000f / targetFps).toLong().coerceAtLeast(33_333L)

        private var cacheTexId: Int = C.INDEX_UNSET
        private var cacheFboId: Int = C.INDEX_UNSET
        private var cachedWidth: Int = 0
        private var cachedHeight: Int = 0
        private var lastBucketIndex: Long = Long.MIN_VALUE

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth != cachedWidth || inputHeight != cachedHeight) {
                releaseCacheResources()
                cachedWidth = inputWidth
                cachedHeight = inputHeight
            }
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                ensureCacheAllocated()

                // Remember BaseGlShaderProgram's currently bound output FBO
                val outputFbo = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outputFbo, 0)

                val currentBucket = presentationTimeUs / stepUs
                if (currentBucket != lastBucketIndex) {
                    lastBucketIndex = currentBucket
                    // Capture current input frame into our offscreen cache FBO
                    GlUtil.focusFramebufferUsingCurrentContext(cacheFboId, cachedWidth, cachedHeight)
                    GLES20.glViewport(0, 0, cachedWidth, cachedHeight)
                    blitTexture(inputTexId)
                }

                // Draw the held cache texture into Media3's output FBO
                GlUtil.focusFramebufferUsingCurrentContext(outputFbo[0], cachedWidth, cachedHeight)
                GLES20.glViewport(0, 0, cachedWidth, cachedHeight)
                blitTexture(cacheTexId)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun blitTexture(texId: Int) {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", texId, 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun ensureCacheAllocated() {
            if (cacheTexId == C.INDEX_UNSET && cachedWidth > 0 && cachedHeight > 0) {
                cacheTexId = GlUtil.createTexture(cachedWidth, cachedHeight, useHdr)
                cacheFboId = GlUtil.createFboForTexture(cacheTexId)
                lastBucketIndex = Long.MIN_VALUE
            }
        }

        override fun flush() {
            lastBucketIndex = Long.MIN_VALUE
            super.flush()
        }

        override fun signalEndOfCurrentInputStream() {
            lastBucketIndex = Long.MIN_VALUE
            super.signalEndOfCurrentInputStream()
        }

        override fun release() {
            releaseCacheResources()
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        private fun releaseCacheResources() {
            try {
                if (cacheFboId != C.INDEX_UNSET) {
                    GlUtil.deleteFbo(cacheFboId)
                    cacheFboId = C.INDEX_UNSET
                }
                if (cacheTexId != C.INDEX_UNSET) {
                    GlUtil.deleteTexture(cacheTexId)
                    cacheTexId = C.INDEX_UNSET
                }
                lastBucketIndex = Long.MIN_VALUE
            } catch (ignored: GlUtil.GlException) {
                cacheFboId = C.INDEX_UNSET
                cacheTexId = C.INDEX_UNSET
            }
        }

        companion object {
            private const val COPY_FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uTexSampler;
                varying vec2 vTexSamplingCoord;
                void main() {
                    gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
                }
            """
        }
    }
}
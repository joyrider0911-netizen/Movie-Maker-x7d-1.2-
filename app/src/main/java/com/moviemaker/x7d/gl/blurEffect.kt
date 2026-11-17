package com.moviemaker.x7d.gl

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Single-pass 13-tap Gaussian-weighted kernel optimized for Adreno 610 tile memory.
 * Scales blur radius proportionally to frame resolution so 540p preview matches 1080p export.
 */
class BoxGaussianBlurGlEffect(
    private val radiusPx: Float
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = radiusPx <= 0.1f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return BlurShaderProgram(useHdr, radiusPx.coerceIn(0f, 16f))
    }

    private class BlurShaderProgram(
        useHdr: Boolean,
        private val radiusPx: Float
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

        private val glProgram: GlProgram =
            GlShaderCommons.createStandardProgram(FRAGMENT_SHADER)
        private var texelWidth = 1f / 1920f
        private var texelHeight = 1f / 1080f
        private var resolutionScale = 1f

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texelWidth = 1f / inputWidth.coerceAtLeast(1)
            texelHeight = 1f / inputHeight.coerceAtLeast(1)
            // Normalize radius against 1080p height so 540p proxy preview looks identical
            resolutionScale = inputHeight.toFloat() / 1080f
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                val step = (radiusPx * resolutionScale).coerceAtLeast(0.5f)
                glProgram.setFloatsUniform(
                    "uTexelStep",
                    floatArrayOf(texelWidth * step, texelHeight * step)
                )
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        companion object {
            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uTexSampler;
                uniform vec2 uTexelStep;
                varying vec2 vTexSamplingCoord;

                void main() {
                    vec2 s = uTexelStep;
                    vec4 acc = texture2D(uTexSampler, vTexSamplingCoord) * 0.20;

                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( s.x,  0.0), 0.0, 1.0)) * 0.10;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(-s.x,  0.0), 0.0, 1.0)) * 0.10;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.0,  s.y), 0.0, 1.0)) * 0.10;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.0, -s.y), 0.0, 1.0)) * 0.10;

                    vec2 d1 = s * 0.7071;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( d1.x,  d1.y), 0.0, 1.0)) * 0.06;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(-d1.x,  d1.y), 0.0, 1.0)) * 0.06;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( d1.x, -d1.y), 0.0, 1.0)) * 0.06;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(-d1.x, -d1.y), 0.0, 1.0)) * 0.06;

                    vec2 outer = s * 1.6;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( outer.x,  0.0), 0.0, 1.0)) * 0.04;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2(-outer.x,  0.0), 0.0, 1.0)) * 0.04;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.0,  outer.y), 0.0, 1.0)) * 0.04;
                    acc += texture2D(uTexSampler, clamp(vTexSamplingCoord + vec2( 0.0, -outer.y), 0.0, 1.0)) * 0.04;

                    gl_FragColor = acc;
                }
            """
        }
    }
}
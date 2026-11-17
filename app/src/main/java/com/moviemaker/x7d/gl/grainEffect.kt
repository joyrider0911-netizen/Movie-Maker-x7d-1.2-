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

class FilmGrainGlEffect(
    private val intensity: Float
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = intensity <= 0.005f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return FilmGrainShaderProgram(useHdr, intensity.coerceIn(0f, 1f))
    }

    private class FilmGrainShaderProgram(
        useHdr: Boolean,
        private val intensity: Float
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

        private val glProgram: GlProgram =
            GlShaderCommons.createStandardProgram(FRAGMENT_SHADER)

        override fun configure(inputWidth: Int, inputHeight: Int): Size =
            Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                // Wrap time modulo 100s to keep highp hash precision crisp on Adreno 610
                val timeSec = ((presentationTimeUs % 100_000_000L) / 1_000_000.0f)
                glProgram.setFloatUniform("uTimeSec", timeSec)
                glProgram.setFloatUniform("uIntensity", intensity)
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
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform float uTimeSec;
                uniform float uIntensity;
                varying vec2 vTexSamplingCoord;

                float hash12(vec2 p) {
                    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                    p3 += dot(p3, p3.yzx + 33.33);
                    return fract((p3.x + p3.y) * p3.z);
                }

                void main() {
                    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
                    float seed = fract(uTimeSec * 29.97) * 17.13;
                    float n1 = hash12(vTexSamplingCoord * 512.0 + seed);
                    float n2 = hash12(vTexSamplingCoord * 1024.0 - seed * 1.37);
                    float grain = ((n1 + n2) * 0.5) - 0.5;
                    // Photographic response: stronger in midtones, softer in clipped highlights
                    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
                    float response = 1.0 - smoothstep(0.65, 1.0, luma) * 0.6;
                    color.rgb = clamp(color.rgb + grain * (uIntensity * 0.38) * response, 0.0, 1.0);
                    gl_FragColor = color;
                }
            """
        }
    }
}
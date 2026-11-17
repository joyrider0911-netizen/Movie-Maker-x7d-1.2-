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

class GlitchGlEffect(
    private val intensity: Float
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = intensity <= 0.005f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return GlitchShaderProgram(useHdr, intensity.coerceIn(0f, 1f))
    }

    private class GlitchShaderProgram(
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
                val timeSec = ((presentationTimeUs % 120_000_000L) / 1_000_000.0f)
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

                float rand(float n) {
                    return fract(sin(n) * 43758.5453123);
                }

                float rand2(vec2 co) {
                    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
                }

                void main() {
                    float stepTime = floor(uTimeSec * 15.0);
                    float burst = step(0.42, rand(stepTime));

                    // Horizontal block tearing bands
                    float band = floor(vTexSamplingCoord.y * 28.0);
                    float bandRand = rand2(vec2(band, stepTime));
                    float bandTrigger = step(1.0 - (0.35 * uIntensity + 0.12), bandRand);
                    float tearShift = (rand2(vec2(band + 7.0, stepTime)) - 0.5)
                        * 0.14 * uIntensity * bandTrigger * burst;

                    vec2 uv = vTexSamplingCoord;
                    uv.x = clamp(uv.x + tearShift, 0.001, 0.999);

                    // Chromatic RGB channel split
                    float rgbSplit = (0.006 + 0.022 * burst) * uIntensity;
                    float r = texture2D(uTexSampler, vec2(clamp(uv.x + rgbSplit, 0.0, 1.0), uv.y)).r;
                    float g = texture2D(uTexSampler, uv).g;
                    float b = texture2D(uTexSampler, vec2(clamp(uv.x - rgbSplit, 0.0, 1.0), uv.y)).b;
                    float a = texture2D(uTexSampler, uv).a;

                    // Subtle VHS scanline darkening
                    float scanline = 1.0 - (0.14 * uIntensity * step(0.5, fract(uv.y * 180.0)));
                    gl_FragColor = vec4(vec3(r, g, b) * scanline, a);
                }
            """
        }
    }
}
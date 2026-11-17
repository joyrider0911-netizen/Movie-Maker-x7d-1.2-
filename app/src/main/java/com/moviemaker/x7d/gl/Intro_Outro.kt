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
import com.moviemaker.x7d.model.ClipAnimationType
import com.moviemaker.x7d.model.ClipTransitionSpec

/**
 * Movie Maker-style clip Start (Intro) & End (Outro) animations executed in a single
 * OpenGL ES fragment pass (Fade from/to Black, Cross-Dissolve alpha, Slide Left/Right,
 * Horizontal Wipe, and Slow Zoom Ken Burns).
 */
class ClipIntroOutroGlEffect(
    private val clipDurationUs: Long,
    private val intro: ClipTransitionSpec,
    private val outro: ClipTransitionSpec
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        return intro.type == ClipAnimationType.NONE && outro.type == ClipAnimationType.NONE
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return IntroOutroShaderProgram(useHdr, clipDurationUs, intro, outro)
    }

    private class IntroOutroShaderProgram(
        useHdr: Boolean,
        private val clipDurationUs: Long,
        private val intro: ClipTransitionSpec,
        private val outro: ClipTransitionSpec
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

        private val glProgram: GlProgram =
            GlShaderCommons.createStandardProgram(FRAGMENT_SHADER)

        override fun configure(inputWidth: Int, inputHeight: Int): Size =
            Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val introProgress = if (intro.type != ClipAnimationType.NONE && intro.durationUs > 0) {
                    (presentationTimeUs.toFloat() / intro.durationUs.toFloat()).coerceIn(0f, 1f)
                } else 1f

                val outroStartUs = (clipDurationUs - outro.durationUs).coerceAtLeast(0L)
                val outroProgress = if (outro.type != ClipAnimationType.NONE && outro.durationUs > 0 &&
                    presentationTimeUs >= outroStartUs
                ) {
                    ((presentationTimeUs - outroStartUs).toFloat() / outro.durationUs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                val totalProgress = (presentationTimeUs.toFloat() / clipDurationUs.coerceAtLeast(1L)).coerceIn(0f, 1f)

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setIntUniform("uIntroType", intro.type.ordinal)
                glProgram.setIntUniform("uOutroType", outro.type.ordinal)
                glProgram.setFloatUniform("uIntroP", introProgress)
                glProgram.setFloatUniform("uOutroP", outroProgress)
                glProgram.setFloatUniform("uClipP", totalProgress)
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
            // Enum ordinals: 0=NONE, 1=FADE_BLACK, 2=CROSS_DISSOLVE, 3=SLIDE_LEFT,
            // 4=SLIDE_RIGHT, 5=WIPE_HORIZONTAL, 6=SLOW_ZOOM_KEN_BURNS
            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uTexSampler;
                uniform int uIntroType;
                uniform int uOutroType;
                uniform float uIntroP;
                uniform float uOutroP;
                uniform float uClipP;
                varying vec2 vTexSamplingCoord;

                float easeOutCubic(float t) {
                    float inv = 1.0 - t;
                    return 1.0 - inv * inv * inv;
                }

                void main() {
                    vec2 uv = vTexSamplingCoord;
                    float alphaMask = 1.0;
                    float blackFactor = 1.0;

                    // Ken Burns Slow Zoom (Intro zooms 1.15->1.0, Outro zooms 1.0->1.15)
                    if (uIntroType == 6 && uIntroP < 1.0) {
                        float z = mix(1.18, 1.0, easeOutCubic(uIntroP));
                        uv = (uv - 0.5) / z + 0.5;
                    } else if (uOutroType == 6 && uOutroP > 0.0) {
                        float z = mix(1.0, 1.18, uOutroP);
                        uv = (uv - 0.5) / z + 0.5;
                    }

                    // Intro Slide / Wipe / Fade
                    if (uIntroP < 1.0) {
                        float e = easeOutCubic(uIntroP);
                        if (uIntroType == 1) blackFactor *= e;
                        else if (uIntroType == 2) alphaMask *= e;
                        else if (uIntroType == 3) {
                            uv.x += (1.0 - e);
                            if (uv.x > 1.0 || uv.x < 0.0) blackFactor = 0.0;
                        } else if (uIntroType == 4) {
                            uv.x -= (1.0 - e);
                            if (uv.x > 1.0 || uv.x < 0.0) blackFactor = 0.0;
                        } else if (uIntroType == 5) {
                            if (vTexSamplingCoord.x > e) blackFactor = 0.0;
                        }
                    }

                    // Outro Slide / Wipe / Fade
                    if (uOutroP > 0.0) {
                        float inv = 1.0 - uOutroP;
                        if (uOutroType == 1) blackFactor *= inv;
                        else if (uOutroType == 2) alphaMask *= inv;
                        else if (uOutroType == 3) {
                            uv.x -= uOutroP;
                            if (uv.x > 1.0 || uv.x < 0.0) blackFactor = 0.0;
                        } else if (uOutroType == 4) {
                            uv.x += uOutroP;
                            if (uv.x > 1.0 || uv.x < 0.0) blackFactor = 0.0;
                        } else if (uOutroType == 5) {
                            if (vTexSamplingCoord.x < uOutroP) blackFactor = 0.0;
                        }
                    }

                    vec4 color = texture2D(uTexSampler, clamp(uv, 0.0, 1.0));
                    gl_FragColor = vec4(color.rgb * blackFactor, color.a * alphaMask);
                }
            """
        }
    }
}
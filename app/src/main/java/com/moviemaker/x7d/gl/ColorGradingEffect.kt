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
import com.moviemaker.x7d.model.ColorGradePreset
import kotlin.math.abs

/**
 * Single-pass Brightness, Contrast, Saturation + Color Preset (Vintage Sepia / Cold Documentary).
 */
class ColorGradingGlEffect(
    private val brightness: Float,
    private val contrast: Float,
    private val saturation: Float,
    private val preset: ColorGradePreset
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        return abs(brightness) < 0.005f &&
            abs(contrast - 1f) < 0.005f &&
            abs(saturation - 1f) < 0.005f &&
            preset == ColorGradePreset.NONE
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ColorGradingShaderProgram(useHdr, brightness, contrast, saturation, preset)
    }

    private class ColorGradingShaderProgram(
        useHdr: Boolean,
        private val brightness: Float,
        private val contrast: Float,
        private val saturation: Float,
        private val preset: ColorGradePreset
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

        private val glProgram: GlProgram =
            GlShaderCommons.createStandardProgram(FRAGMENT_SHADER)

        override fun configure(inputWidth: Int, inputHeight: Int): Size =
            Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatUniform("uBrightness", brightness)
                glProgram.setFloatUniform("uContrast", contrast)
                glProgram.setFloatUniform("uSaturation", saturation)
                glProgram.setIntUniform("uPreset", preset.ordinal)
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
                uniform float uBrightness;
                uniform float uContrast;
                uniform float uSaturation;
                uniform int uPreset;
                varying vec2 vTexSamplingCoord;

                void main() {
                    vec4 tex = texture2D(uTexSampler, vTexSamplingCoord);
                    vec3 rgb = tex.rgb;

                    // 1. Brightness & Contrast
                    rgb += uBrightness;
                    rgb = (rgb - 0.5) * uContrast + 0.5;

                    // 2. Saturation (Rec.709 luma weights)
                    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                    rgb = mix(vec3(luma), rgb, uSaturation);

                    // 3. Preset Matrix (1 = Vintage Sepia, 2 = Cold Documentary)
                    if (uPreset == 1) {
                        vec3 sepia = vec3(
                            dot(rgb, vec3(0.393, 0.769, 0.189)),
                            dot(rgb, vec3(0.349, 0.686, 0.168)),
                            dot(rgb, vec3(0.272, 0.534, 0.131))
                        );
                        // Warm archival fade + slight shadow lift
                        rgb = mix(rgb, sepia, 0.85) * vec3(1.02, 0.99, 0.93) + vec3(0.02, 0.015, 0.01);
                    } else if (uPreset == 2) {
                        // Desaturated cyan/slate highlights & crisp deep shadows
                        float docLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                        vec3 cold = mix(vec3(docLuma), rgb, 0.72);
                        cold = (cold - 0.5) * 1.10 + 0.5;
                        cold *= vec3(0.91, 0.98, 1.10);
                        rgb = cold;
                    }

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), tex.a);
                }
            """
        }
    }
}
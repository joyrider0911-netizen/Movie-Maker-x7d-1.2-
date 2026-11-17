package com.moviemaker.x7d.gl

import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil

internal object GlShaderCommons {
    const val STANDARD_VERTEX_SHADER = """
        attribute vec4 aFramePosition;
        uniform mat4 uTransformationMatrix;
        uniform mat4 uTexTransformationMatrix;
        varying vec2 vTexSamplingCoord;
        void main() {
            gl_Position = uTransformationMatrix * aFramePosition;
            vec4 texPos = vec4(aFramePosition.xy * 0.5 + 0.5, 0.0, 1.0);
            vTexSamplingCoord = (uTexTransformationMatrix * texPos).xy;
        }
    """

    @Throws(VideoFrameProcessingException::class)
    fun createStandardProgram(fragmentShader: String): GlProgram {
        return try {
            GlProgram(STANDARD_VERTEX_SHADER, fragmentShader).apply {
                setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
                )
                val identity = GlUtil.create4x4IdentityMatrix()
                setFloatsUniform("uTransformationMatrix", identity)
                setFloatsUniform("uTexTransformationMatrix", identity)
            }
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}
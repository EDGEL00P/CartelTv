package com.carteltv.tv.engine.playback

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.carteltv.tv.R
import java.io.IOException

/**
 * GL post-processing for low-bitrate rips: a single-pass deband (smooths only the low-gradient
 * stepped bands compression leaves in dark gradients, then dithers them) plus an unsharp mask
 * that restores edge definition. Real edges pass untouched, so it never looks blurred.
 *
 * Runs the video through Media3's DefaultVideoFrameProcessor, so it costs one GPU pass per
 * frame and cannot coexist with tunneling — PlayerScreen guards that. Applies to the next
 * video opened after the setting changes (the player is built once per stream).
 */
@UnstableApi
class VideoEnhancement(
    /** Display height in px; taps widen to keep the neighborhood > 1 output pixel on upscales. */
    private val displayHeightPx: Int = 0,
    private val sharpen: Float = 0.35f,
    private val deband: Float = 0.6f,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        EnhanceShaderProgram(context, useHdr, displayHeightPx, sharpen, deband)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    companion object {

        @Volatile
        private var usable: Boolean? = null

        /**
         * Whether this device's GL stack can carry decoder frames through DefaultVideoFrameProcessor.
         * Verified on SHIELD Android TV (mdarcy, Tegra GLES without EXT_YUV_target): the graph renders
         * plain SDR as black frames while reports keep advancing, and HDR hard-fails with
         * ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED. Direct decode-to-surface on the same device is fine,
         * so the probe gates the effect instead of the picture: no YUV sampling support in the GL
         * extension string, no GL post-processing.
         */
        fun deviceSupportsGlPostProcessing(): Boolean {
            usable?.let { return it }
            val supported = runCatching { yuvTargetExtensionSupported() }.getOrDefault(false)
            usable = supported
            return supported
        }

        /** Reads GL_EXTENSIONS through a throwaway pbuffer context; needs no GL thread of our own. */
        private fun yuvTargetExtensionSupported(): Boolean {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display === EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) return false
            val configs = arrayOfNulls<EGLConfig>(1)
            val found = IntArray(1)
            val configAttribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, found, 0) || found[0] == 0) return false
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            ) ?: return false
            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == null || context === EGL14.EGL_NO_CONTEXT) return false
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
                return GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("EXT_YUV_target") == true
            } finally {
                // Never eglTerminate: Android gives the whole process one EGLDisplay, and the UI's
                // hardware renderer shares it.
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglDestroySurface(display, surface)
            }
        }
    }
}

@UnstableApi
internal class EnhanceShaderProgram(
    context: Context,
    useHdr: Boolean,
    displayHeightPx: Int,
    sharpen: Float,
    deband: Float,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ useHdr,
    /* texturePoolCapacity = */ 1,
) {

    private val glProgram: GlProgram
    private val displayHeightPx: Int = displayHeightPx

    init {
        try {
            glProgram = GlProgram(
                context,
                /* vertexShaderResId = */ R.raw.enhance_vertex,
                /* fragmentShaderResId = */ R.raw.enhance_fragment,
            )
        } catch (e: IOException) {
            throw VideoFrameProcessingException(e)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        glProgram.setFloatsUniform("uTransformationMatrix", identity)
        glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
        glProgram.setFloatUniform("uSharpen", sharpen)
        glProgram.setFloatUniform("uDeband", deband)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
        // On upscales (1080p rips shown on a 4K panel) one input texel is several output
        // pixels wide, so widen the neighborhood to keep covering the visible band step.
        val radius = if (displayHeightPx > 0) (displayHeightPx.toFloat() / inputHeight).coerceAtLeast(1f) else 1f
        glProgram.setFloatUniform("uRadius", radius)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            glProgram.bindAttributesAndUniforms()
            // The four-vertex triangle strip forms a quad.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }
}

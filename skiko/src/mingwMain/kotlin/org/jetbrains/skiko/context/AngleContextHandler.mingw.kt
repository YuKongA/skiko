package org.jetbrains.skiko.context

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.LayerDrawScope
import org.jetbrains.skiko.RenderException
import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.impl.Native
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.SkiaLayer

/**
 * ANGLE context handler for mingwX64.
 *
 * Uses a native C++ bridge (angle_bridge.cc) that manages EGL/ANGLE lifecycle.
 * The DirectContext is created in the bridge from ANGLE's assembled GL interface.
 */
internal class MingwAngleContextHandler(
    layer: SkiaLayer,
    private val devicePtr: NativePointer,
) : ContextHandler(layer, layer::draw) {

    override fun initContext(): Boolean {
        if (context == null) {
            var ctxPtr = nAngleGetContext(devicePtr)
            if (ctxPtr == Native.NullPointer) {
                // First draw: create DirectContext lazily (deferred from device init)
                ctxPtr = nAngleMakeContext(devicePtr)
                if (ctxPtr == Native.NullPointer) {
                    println("[angle] Failed to create DirectContext")
                    return false
                }
            }
            context = DirectContext(ctxPtr)
        }
        return context != null
    }

    private var currentWidth = 0
    private var currentHeight = 0

    override fun LayerDrawScope.initCanvas() {
        val w = scaledLayerWidth
        val h = scaledLayerHeight
        if (w != currentWidth || h != currentHeight || surface == null) {
            disposeCanvas()
            context?.flush()

            val fbId = nAngleResizeSurface(devicePtr, w, h)
            if (fbId < 0) throw RenderException("Failed to resize ANGLE surface")

            currentWidth = w
            currentHeight = h

            renderTarget = BackendRenderTarget.makeGL(
                w, h, 0, 8, fbId, FramebufferFormat.GR_GL_RGBA8
            )
            surface = Surface.makeFromBackendRenderTarget(
                context!!,
                renderTarget!!,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = layer.pixelGeometry)
            ) ?: throw RenderException("Cannot create surface from ANGLE render target")
        }

        canvas = surface!!.canvas
    }

    override fun rendererInfo(): String {
        return "Renderer: ANGLE (D3D11)\n" +
                super.rendererInfo()
    }
}

@ExternalSymbolName("angle_get_context")
private external fun nAngleGetContext(devicePtr: NativePointer): NativePointer

@ExternalSymbolName("angle_make_context")
private external fun nAngleMakeContext(devicePtr: NativePointer): NativePointer

@ExternalSymbolName("angle_resize_surface")
private external fun nAngleResizeSurface(devicePtr: NativePointer, width: Int, height: Int): Int

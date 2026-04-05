package org.jetbrains.skiko

import org.jetbrains.skia.impl.Native
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.redrawer.MingwAngleRedrawer
import org.jetbrains.skiko.redrawer.MingwSoftwareRedrawer
import org.jetbrains.skiko.redrawer.Redrawer
import org.jetbrains.skiko.redrawer.nAnglePreloadEgl
import org.jetbrains.skiko.redrawer.nAnglePreinitDisplay
import org.jetbrains.skiko.redrawer.nAngleDisposePreinit

internal fun createNativeRedrawer(
    layer: SkiaLayer,
    renderApi: GraphicsApi
): Redrawer = when (renderApi) {
    GraphicsApi.ANGLE -> MingwAngleRedrawer(layer)
    GraphicsApi.SOFTWARE_FAST -> MingwSoftwareRedrawer(layer)
    else -> throw IllegalArgumentException("Unsupported API $renderApi for mingwX64")
}

/**
 * Holds pre-initialized EGL display/context state created before any window exists.
 * The pre-init handle is consumed once by the first MingwAngleRedrawer, which
 * transfers ownership to AngleDevice via angle_attach_window().
 */
internal object AnglePreInitHolder {
    private var ptr: NativePointer = Native.NullPointer

    fun preInit(): Boolean {
        if (ptr != Native.NullPointer) return true
        ptr = nAnglePreinitDisplay()
        return ptr != Native.NullPointer
    }

    fun consumePreInit(): NativePointer {
        val result = ptr
        ptr = Native.NullPointer
        return result
    }

    fun dispose() {
        if (ptr != Native.NullPointer) {
            nAngleDisposePreinit(ptr)
            ptr = Native.NullPointer
        }
    }
}

/**
 * Preloads ANGLE's EGL library and pre-initializes the EGL display/context
 * before any window exists. This moves the heavy D3D11 device creation
 * (~50-150ms) out of the SkiaLayer.attachTo() critical path.
 *
 * Call this early in main() before window creation for best results.
 * Safe to call multiple times; subsequent calls are no-ops.
 *
 * If not called, the first SkiaLayer.attachTo() will do full initialization
 * (backward compatible fallback).
 *
 * @return true if pre-initialization succeeded
 */
fun preloadAngleEgl(): Boolean {
    if (nAnglePreloadEgl() == 0) return false
    return AnglePreInitHolder.preInit()
}

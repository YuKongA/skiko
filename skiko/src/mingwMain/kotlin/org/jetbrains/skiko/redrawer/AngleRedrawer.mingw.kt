package org.jetbrains.skiko.redrawer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.ExternalSymbolName
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.Native
import org.jetbrains.skiko.FrameDispatcher
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoDispatchers
import org.jetbrains.skiko.AnglePreInitHolder
import org.jetbrains.skiko.context.MingwAngleContextHandler
import platform.windows.HWND
import platform.windows.IsIconic
import kotlin.concurrent.Volatile

@OptIn(ExperimentalForeignApi::class)
internal class MingwAngleRedrawer(
    private val skiaLayer: SkiaLayer
) : Redrawer {

    private val hwnd: HWND = skiaLayer.component as? HWND
        ?: throw RenderException("SkiaLayer is not attached to an HWND")

    private val device: NativePointer = run {
        val preInit = AnglePreInitHolder.consumePreInit()
        if (preInit != Native.NullPointer) {
            nAngleAttachWindow(preInit, hwnd.toLong())  // Fast path: only create surface
        } else {
            nAngleCreateDevice(hwnd.toLong())           // Fallback: full init
        }
    }.also {
        if (it == Native.NullPointer) throw RenderException("Failed to create ANGLE device")
    }

    private val contextHandler = MingwAngleContextHandler(skiaLayer, device)
    private val coroutineScope = CoroutineScope(SkikoDispatchers.Main + Job())
    private val frameDispatcher = FrameDispatcher(coroutineScope) { draw() }

    private val windowOcclusionChannel = Channel<Boolean>(Channel.CONFLATED)
    @Volatile private var isWindowOccluded = false

    private var isDisposed = false
    private var isRendering = false
    private var firstFrame = true

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        frameDispatcher.cancel()
        contextHandler.dispose()
        nAngleDispose(device)
    }

    override fun needRender(throttledToVsync: Boolean) {
        check(!isDisposed) { "MingwAngleRedrawer is disposed" }
        frameDispatcher.scheduleFrame()
    }

    override fun update(nanoTime: Long) {
        check(!isDisposed) { "MingwAngleRedrawer is disposed" }
        skiaLayer.update(nanoTime)
    }

    override fun renderImmediately() {
        check(!isDisposed) { "MingwAngleRedrawer is disposed" }
        if (isRendering) return
        isRendering = true
        skiaLayer.isRendering = true
        try {
            nAngleMakeCurrent(device)
            update()
            if (!isDisposed) {
                skiaLayer.inDrawScope {
                    contextHandler.draw()
                }
                val vsync = if (firstFrame) { firstFrame = false; 0 } else 1
                nAngleSwapBuffers(device, vsync)
            }
        } finally {
            skiaLayer.isRendering = false
            isRendering = false
        }
    }

    private suspend fun draw() {
        if (isDisposed || isRendering) return

        // Check window minimized state (cheap Win32 call)
        val occluded = IsIconic(hwnd) != 0
        if (occluded != isWindowOccluded) {
            isWindowOccluded = occluded
            windowOcclusionChannel.trySend(occluded)
        }

        isRendering = true
        skiaLayer.isRendering = true
        try {
            nAngleMakeCurrent(device)
            update()
            skiaLayer.inDrawScope {
                contextHandler.draw()
            }
            val vsync = if (firstFrame) { firstFrame = false; 0 } else 1
            nAngleSwapBuffers(device, vsync)
        } finally {
            skiaLayer.isRendering = false
            isRendering = false
        }

        // When window is minimized — throttle to avoid wasting GPU/CPU.
        if (isWindowOccluded) {
            withTimeoutOrNull(300) {
                @Suppress("ControlFlowWithEmptyBody")
                while (windowOcclusionChannel.receive()) { }
            }
        }
    }

    override val renderInfo: String
        get() = contextHandler.rendererInfo()

    override fun isTransparentBackgroundSupported(): Boolean =
        defaultIsTransparentBackgroundSupported(skiaLayer)
}

@ExternalSymbolName("angle_preload_egl")
internal external fun nAnglePreloadEgl(): Int

@ExternalSymbolName("angle_preinit_display")
internal external fun nAnglePreinitDisplay(): NativePointer

@ExternalSymbolName("angle_attach_window")
internal external fun nAngleAttachWindow(preInitPtr: NativePointer, hwndPtr: Long): NativePointer

@ExternalSymbolName("angle_dispose_preinit")
internal external fun nAngleDisposePreinit(preInitPtr: NativePointer)

@ExternalSymbolName("angle_create_device")
private external fun nAngleCreateDevice(hwndPtr: Long): NativePointer

@ExternalSymbolName("angle_make_current")
private external fun nAngleMakeCurrent(devicePtr: NativePointer)

@ExternalSymbolName("angle_swap_buffers")
private external fun nAngleSwapBuffers(devicePtr: NativePointer, vsync: Int)

@ExternalSymbolName("angle_dispose")
private external fun nAngleDispose(devicePtr: NativePointer)

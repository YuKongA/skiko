package org.jetbrains.skiko.context

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.LayerDrawScope
import org.jetbrains.skiko.SkiaLayer
import platform.windows.BI_RGB
import platform.windows.BITMAPINFO
import platform.windows.DIB_RGB_COLORS
import platform.windows.GetDC
import platform.windows.ReleaseDC
import platform.windows.SRCCOPY
import platform.windows.StretchDIBits

@OptIn(ExperimentalForeignApi::class)
internal class MingwSoftwareContextHandler(layer: SkiaLayer) : ContextHandler(layer, layer::draw) {

    private val storage = Bitmap()

    override fun initContext(): Boolean = true

    override fun LayerDrawScope.initCanvas() {
        disposeCanvas()
        val w = scaledLayerWidth
        val h = scaledLayerHeight
        if (w <= 0 || h <= 0) return

        if (storage.width != w || storage.height != h) {
            storage.allocPixelsFlags(ImageInfo.makeS32(w, h, ColorAlphaType.PREMUL), false)
        }
        canvas = Canvas(storage, SurfaceProps(pixelGeometry = pixelGeometry))
    }

    override fun flush(scope: LayerDrawScope) {
        val w = scope.scaledLayerWidth
        val h = scope.scaledLayerHeight
        if (w <= 0 || h <= 0) return

        val hwnd = layer.component ?: return
        val bytes = storage.readPixels(storage.imageInfo, w * 4, 0, 0) ?: return

        bytes.usePinned { pinned ->
            memScoped {
                val bmi = alloc<BITMAPINFO>()
                bmi.bmiHeader.biSize = sizeOf<platform.windows.BITMAPINFOHEADER>().toUInt()
                bmi.bmiHeader.biWidth = w
                bmi.bmiHeader.biHeight = -h // negative = top-down DIB (matches Skia row order)
                bmi.bmiHeader.biPlanes = 1u
                bmi.bmiHeader.biBitCount = 32u
                bmi.bmiHeader.biCompression = BI_RGB.toUInt()

                @Suppress("UNCHECKED_CAST")
                val hdc = GetDC(hwnd as platform.windows.HWND?)
                StretchDIBits(
                    hdc,
                    0, 0, w, h,   // destination
                    0, 0, w, h,   // source
                    pinned.addressOf(0),
                    bmi.ptr,
                    DIB_RGB_COLORS.toUInt(),
                    SRCCOPY.toUInt()
                )
                @Suppress("UNCHECKED_CAST")
                ReleaseDC(hwnd as platform.windows.HWND?, hdc)
            }
        }
    }

    override fun rendererInfo(): String {
        return "Renderer: Software (StretchDIBits)\n" +
                super.rendererInfo()
    }

    override fun dispose() {
        storage.close()
        super.dispose()
    }
}

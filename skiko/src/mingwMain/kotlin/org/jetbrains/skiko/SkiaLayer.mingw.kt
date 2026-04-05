package org.jetbrains.skiko

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.redrawer.Redrawer
import platform.windows.GetClientRect
import platform.windows.GetDC
import platform.windows.GetDeviceCaps
import platform.windows.HWND
import platform.windows.LOGPIXELSX
import platform.windows.RECT
import platform.windows.ReleaseDC

@OptIn(ExperimentalForeignApi::class)
actual open class SkiaLayer {

    fun isShowing(): Boolean = hwnd != null

    actual var renderApi: GraphicsApi = GraphicsApi.ANGLE

    actual val contentScale: Float
        get() {
            // When the process is DPI-aware (SetProcessDPIAware), GetClientRect returns
            // physical pixels and we render at 1:1. The DPI scale is handled separately
            // by Compose's Density for dp→px conversion.
            return 1f
        }

    /**
     * Returns the system DPI scale factor (e.g., 1.5 for 150% scaling).
     * Use this for Compose Density, not for rendering surface scaling.
     */
    val systemDpiScale: Float
        get() {
            val hdc = GetDC(null)
            val dpi = GetDeviceCaps(hdc, LOGPIXELSX)
            ReleaseDC(null, hdc)
            return dpi.toFloat() / 96f
        }

    actual var fullscreen: Boolean = false

    private var hwnd: HWND? = null

    actual val component: Any?
        get() = hwnd

    actual var renderDelegate: SkikoRenderDelegate? = null

    internal var redrawer: Redrawer? = null

    /**
     * True while a frame is being rendered. Used by the platform window to skip
     * input message processing during rendering and prevent re-entrant layout.
     */
    var isRendering: Boolean = false

    private var picture: PictureHolder? = null
    private val pictureRecorder = PictureRecorder()

    actual fun attachTo(container: Any) {
        check(hwnd == null) { "Already attached to another HWND" }
        @Suppress("UNCHECKED_CAST")
        hwnd = container as HWND
        redrawer = createNativeRedrawer(this, renderApi).apply {
            syncBounds()
            needRender()
        }
    }

    actual fun detach() {
        redrawer?.dispose()
        redrawer = null
        picture?.instance?.close()
        picture = null
        pictureRecorder.close()
        hwnd = null
    }

    actual fun needRender(throttledToVsync: Boolean) {
        redrawer?.needRender(throttledToVsync)
    }

    /**
     * Synchronously render a single frame. Used to draw the first frame before
     * showing the window, avoiding a white flash on startup.
     */
    fun renderImmediately() {
        redrawer?.renderImmediately()
    }

    @Deprecated(
        message = "Use needRender() instead",
        replaceWith = ReplaceWith("needRender()")
    )
    actual fun needRedraw() = needRender()

    /**
     * Records the renderDelegate output into a Picture.
     */
    internal fun update(nanoTime: Long) {
        val h = hwnd ?: return
        val (width, height) = getClientSize(h)
        if (width <= 0 || height <= 0) return

        val scale = contentScale
        val pictureWidth = (width * scale).coerceAtLeast(0f)
        val pictureHeight = (height * scale).coerceAtLeast(0f)

        val canvas = pictureRecorder.beginRecording(
            0f, 0f, pictureWidth, pictureHeight
        ).apply {
            clear(Color.WHITE)
        }
        renderDelegate?.onRender(canvas, pictureWidth.toInt(), pictureHeight.toInt(), nanoTime)

        val pic = pictureRecorder.finishRecordingAsPicture()
        picture?.instance?.close()
        this.picture = PictureHolder(pic, pictureWidth.toInt(), pictureHeight.toInt())
    }

    internal actual fun draw(canvas: Canvas) {
        picture?.also {
            canvas.drawPicture(it.instance)
        }
    }

    actual val pixelGeometry: PixelGeometry
        get() = PixelGeometry.UNKNOWN

    private fun createDrawScope(): LayerDrawScope {
        val h = hwnd
        if (h == null) return LayerDrawScope(PixelGeometry.UNKNOWN, 0, 0)

        val (width, height) = getClientSize(h)
        return LayerDrawScope(
            pixelGeometry = pixelGeometry,
            layerWidth = width.toDouble(),
            layerHeight = height.toDouble(),
            scale = contentScale
        )
    }

    internal fun inDrawScope(block: LayerDrawScope.() -> Unit) {
        with(createDrawScope()) {
            block()
        }
    }

    private fun getClientSize(h: HWND): Pair<Float, Float> {
        memScoped {
            val rect = alloc<RECT>()
            GetClientRect(h, rect.ptr)
            return Pair(
                (rect.right - rect.left).toFloat(),
                (rect.bottom - rect.top).toFloat()
            )
        }
    }
}

actual val currentSystemTheme: SystemTheme
    get() {
        memScoped {
            val result = alloc<UIntVar>()
            val size = alloc<UIntVar>()
            size.value = sizeOf<UIntVar>().toUInt()
            val status = platform.windows.RegGetValueW(
                platform.windows.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "AppsUseLightTheme",
                platform.windows.RRF_RT_DWORD.toUInt(),
                null,
                result.ptr,
                size.ptr
            )
            return when {
                status.toInt() != 0 -> SystemTheme.UNKNOWN
                result.value != 0u -> SystemTheme.LIGHT
                else -> SystemTheme.DARK
            }
        }
    }

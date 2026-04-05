package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.jetbrains.skiko.FrameDispatcher
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoDispatchers
import org.jetbrains.skiko.context.MingwSoftwareContextHandler

internal class MingwSoftwareRedrawer(
    private val skiaLayer: SkiaLayer
) : Redrawer {

    private val contextHandler = MingwSoftwareContextHandler(skiaLayer)
    private val coroutineScope = CoroutineScope(SkikoDispatchers.Main + Job())
    private val frameDispatcher = FrameDispatcher(coroutineScope) { draw() }
    private var isDisposed = false
    private var isRendering = false

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        frameDispatcher.cancel()
        contextHandler.dispose()
    }

    override fun needRender(throttledToVsync: Boolean) {
        check(!isDisposed) { "MingwSoftwareRedrawer is disposed" }
        frameDispatcher.scheduleFrame()
    }

    override fun update(nanoTime: Long) {
        check(!isDisposed) { "MingwSoftwareRedrawer is disposed" }
        skiaLayer.update(nanoTime)
    }

    override fun renderImmediately() {
        check(!isDisposed) { "MingwSoftwareRedrawer is disposed" }
        if (isRendering) return
        isRendering = true
        skiaLayer.isRendering = true
        try {
            update()
            if (!isDisposed) {
                skiaLayer.inDrawScope {
                    contextHandler.draw()
                }
            }
        } finally {
            skiaLayer.isRendering = false
            isRendering = false
        }
    }

    private fun draw() {
        if (isDisposed || isRendering) return
        isRendering = true
        skiaLayer.isRendering = true
        try {
            update()
            skiaLayer.inDrawScope {
                contextHandler.draw()
            }
        } finally {
            skiaLayer.isRendering = false
            isRendering = false
        }
    }

    override val renderInfo: String
        get() = contextHandler.rendererInfo()

    override fun isTransparentBackgroundSupported(): Boolean =
        defaultIsTransparentBackgroundSupported(skiaLayer)
}

package org.jetbrains.skiko

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.wcstr
import kotlin.concurrent.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.windows.CreateEventW
import platform.windows.CreateWindowExW
import platform.windows.DefWindowProcW
import platform.windows.GetCurrentThreadId
import platform.windows.GetModuleHandleW
import platform.windows.HANDLE
import platform.windows.HWND
import platform.windows.KillTimer
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.RegisterClassExW
import platform.windows.SetEvent
import platform.windows.SetTimer
import platform.windows.UINT
import platform.windows.WM_TIMER
import platform.windows.WNDCLASSEXW
import platform.windows.WPARAM
import kotlin.coroutines.CoroutineContext

private const val DISPATCHER_WINDOW_CLASS = "SkikoDispatcherWindow"
private var dispatcherClassRegistered = false

object SkikoDispatchers {
    val Main: MainCoroutineDispatcher = Win32Dispatcher
}

/**
 * Runs the Win32 message loop with integrated coroutine dispatcher support.
 *
 * Uses [MsgWaitForMultipleObjects][platform.windows.MsgWaitForMultipleObjects]
 * to wait on both Win32 messages and the dispatcher's wake event. Each iteration
 * processes all pending Win32 messages first, then drains coroutine tasks.
 * This ensures input events and dispatched tasks have the same priority.
 *
 * Call this from [application] after creating windows. Returns when WM_QUIT is received.
 */
@OptIn(ExperimentalForeignApi::class)
fun runMainMessageLoop() {
    memScoped {
        val handles = allocArrayOf(Win32Dispatcher.wakeEvent)
        val msg = alloc<platform.windows.MSG>()
        var running = true
        while (running) {
            platform.windows.MsgWaitForMultipleObjects(
                1u, handles, 0,
                platform.windows.INFINITE, platform.windows.QS_ALLINPUT.toUInt()
            )
            // Process all pending Win32 messages (input, paint, timer, etc.)
            while (platform.windows.PeekMessageW(msg.ptr, null, 0u, 0u, platform.windows.PM_REMOVE.toUInt()) != 0) {
                if (msg.message == platform.windows.WM_QUIT.toUInt()) {
                    running = false
                    break
                }
                platform.windows.TranslateMessage(msg.ptr)
                platform.windows.DispatchMessageW(msg.ptr)
            }
            // Then drain coroutine tasks
            Win32Dispatcher.drainTasks()
        }
    }
}

/**
 * Win32 main thread dispatcher using a task queue + Event object.
 *
 * Coroutine tasks are placed in an in-memory queue and the message loop is woken
 * via [SetEvent]. The message loop (in Application.mingw.kt) uses
 * [MsgWaitForMultipleObjects][platform.windows.MsgWaitForMultipleObjects] to wait
 * on both the event and Win32 messages, processing them at the **same priority**.
 *
 * This matches the behavior of macOS (dispatch_async + RunLoop) and Swing
 * (EventQueue.invokeLater + EDT), preventing input starvation during animations.
 *
 * Timer-based delays still use a hidden message window with [SetTimer]/[WM_TIMER].
 */
@OptIn(ExperimentalForeignApi::class, InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal object Win32Dispatcher : MainCoroutineDispatcher(), Delay {

    override val immediate: MainCoroutineDispatcher get() = ImmediateWin32Dispatcher

    override fun toString(): String = "Dispatchers.Main"

    /** Event object signaled when tasks are added to the queue. */
    internal val wakeEvent: HANDLE = CreateEventW(null, 0, 0, null)
        ?: error("Failed to create dispatcher wake event")

    internal val mainThreadId: UInt = GetCurrentThreadId()

    // Task queue: dispatch() appends from any thread, drainTasks() swaps on main thread.
    // AtomicReference ensures thread-safe access without synchronized (unavailable in K/N).
    private val taskQueue = AtomicReference(ArrayList<Runnable>())

    // Timer management (still uses a hidden HWND for WM_TIMER)
    internal val timerHwnd: HWND
    private var nextTimerId = 1uL
    private val timerActions = mutableMapOf<ULong, () -> Unit>()

    init {
        if (!dispatcherClassRegistered) {
            memScoped {
                val hInstance = GetModuleHandleW(null)
                val wc = alloc<WNDCLASSEXW>()
                wc.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
                wc.lpfnWndProc = staticCFunction(::dispatcherWndProc)
                wc.hInstance = hInstance
                wc.lpszClassName = DISPATCHER_WINDOW_CLASS.wcstr.ptr
                RegisterClassExW(wc.ptr)
            }
            dispatcherClassRegistered = true
        }

        val hInstance = GetModuleHandleW(null)
        @Suppress("UNCHECKED_CAST")
        val hwndMessage = (-3L).toCPointer<platform.windows.HWND__>()
        timerHwnd = CreateWindowExW(
            0u, DISPATCHER_WINDOW_CLASS, null, 0u,
            0, 0, 0, 0,
            hwndMessage, null, hInstance, null
        ) ?: error("Failed to create dispatcher timer window")
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // Atomically swap in a new list with the block appended.
        // This is lock-free and safe from any thread.
        while (true) {
            val current = taskQueue.value
            val updated = ArrayList<Runnable>(current.size + 1).apply {
                addAll(current)
                add(block)
            }
            if (taskQueue.compareAndSet(current, updated)) break
        }
        SetEvent(wakeEvent)
    }

    /**
     * Drain and execute all pending tasks. Called by the message loop after
     * processing Win32 messages, ensuring input events are handled first.
     */
    internal fun drainTasks() {
        // Atomically grab all pending tasks, replacing with an empty list.
        val tasks = taskQueue.getAndSet(ArrayList())
        if (tasks.isEmpty()) return
        for (task in tasks) {
            task.run()
        }
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val timerId = nextTimerId++
        timerActions[timerId] = { continuation.resume(Unit) { it.printStackTrace() } }
        SetTimer(timerHwnd, timerId, timeMillis.coerceAtLeast(0).toUInt(), null)
        continuation.invokeOnCancellation {
            KillTimer(timerHwnd, timerId)
            timerActions.remove(timerId)
        }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val timerId = nextTimerId++
        timerActions[timerId] = { block.run() }
        SetTimer(timerHwnd, timerId, timeMillis.coerceAtLeast(0).toUInt(), null)
        return object : DisposableHandle {
            override fun dispose() {
                KillTimer(timerHwnd, timerId)
                timerActions.remove(timerId)
            }
        }
    }

    /** Process WM_TIMER messages from the hidden timer window. */
    internal fun processTimerMessage(msg: UINT, wParam: WPARAM): LRESULT? {
        if (msg.toInt() == WM_TIMER) {
            val timerId = wParam.toULong()
            KillTimer(timerHwnd, timerId)
            timerActions.remove(timerId)?.invoke()
            return 0
        }
        return null
    }
}

/**
 * Immediate variant: executes inline on the main thread, posts from other threads.
 */
@OptIn(ExperimentalForeignApi::class, InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal object ImmediateWin32Dispatcher : MainCoroutineDispatcher(), Delay {

    override val immediate: MainCoroutineDispatcher get() = this

    override fun toString(): String = "Dispatchers.Main.immediate"

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        return GetCurrentThreadId() != Win32Dispatcher.mainThreadId
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!isDispatchNeeded(context)) {
            block.run()
        } else {
            Win32Dispatcher.dispatch(context, block)
        }
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        Win32Dispatcher.scheduleResumeAfterDelay(timeMillis, continuation)
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        return Win32Dispatcher.invokeOnTimeout(timeMillis, block, context)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun dispatcherWndProc(hwnd: HWND?, msg: UINT, wParam: WPARAM, lParam: LPARAM): LRESULT {
    val result = Win32Dispatcher.processTimerMessage(msg, wParam)
    if (result != null) return result
    return DefWindowProcW(hwnd, msg, wParam, lParam)
}

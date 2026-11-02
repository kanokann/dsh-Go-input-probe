package io.github.ccch1mneyyy.dsh_app.phone.background

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A disposable, inert touch receiver. UI and synchronous injection must use different threads. */
internal class InputProbeWindow(
    private val context: Context,
    private val targetDisplay: Display,
    private val sendTap: (Int, Int, Long) -> Unit,
) : InputProbe.Window {
    private val thread = HandlerThread("dsh-input-probe")
    private var handler: Handler? = null
    private var presentation: Presentation? = null // Only touched on the UI thread.
    private val closed = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val received = CountDownLatch(1)
    private val receipt = InputProbe.Receipt(targetDisplay.displayId)
    @Volatile private var target: InputProbe.Target? = null
    @Volatile private var failure: Throwable? = null

    override fun awaitReady(): InputProbe.Target? {
        if (targetDisplay.displayId <= Display.DEFAULT_DISPLAY || !targetDisplay.isValid ||
            targetDisplay.flags and Display.FLAG_PRESENTATION == 0 ||
            targetDisplay.flags and Display.FLAG_PRIVATE != 0) throw InputProbe.Failure("inputWindowNotReady")
        thread.start()
        val ui = Handler(thread.looper)
        handler = ui
        ui.post {
            if (closed.get()) return@post
            try {
                val dialog = Presentation(context, targetDisplay, android.R.style.Theme_Material_Light_NoActionBar)
                presentation = dialog
                dialog.setCancelable(false)
                val window = checkNotNull(dialog.window)
                // PUBLIC + PRESENTATION selects TYPE_PRESENTATION in Presentation's constructor.
                // Do not override its type: Android 12+ binds the window context to that type.
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                window.setWindowAnimations(0)
                val view = object : View(dialog.context) {
                    private var frameRequested = false

                    override fun onDraw(canvas: Canvas) { canvas.drawColor(android.graphics.Color.BLACK) }

                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                        super.onSizeChanged(w, h, oldw, oldh)
                        if (frameRequested || !isAttachedToWindow || w < 4 || h < 4) return
                        frameRequested = true
                        // The display flags already require a recent Android release. Wait for
                        // the laid-out window's first submitted frame, rather than a fixed sleep.
                        viewTreeObserver.registerFrameCommitCallback {
                            ui.post {
                                if (!closed.get() && isAttachedToWindow && targetDisplay.isValid &&
                                    this.display?.displayId == targetDisplay.displayId) {
                                    val location = IntArray(2)
                                    getLocationOnScreen(location)
                                    target = InputProbe.Target(targetDisplay.displayId,
                                        location[0] + width / 2, location[1] + height / 2)
                                    ready.countDown()
                                }
                            }
                        }
                    }

                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        if (!closed.get() && receipt.accept(event.displayId, event.downTime,
                                event.actionMasked, event.rawX, event.rawY)) received.countDown()
                        return true
                    }
                }
                dialog.setContentView(view)
                dialog.show()
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            } catch (e: Throwable) {
                failure = e
                ready.countDown()
            }
        }
        if (!ready.await(3000, TimeUnit.MILLISECONDS)) return null
        failure?.let {
            if (it is SecurityException) throw it
            throw InputProbe.Failure("inputWindowNotReady")
        }
        return target
    }

    override fun inject(target: InputProbe.Target) {
        if (closed.get() || target != this.target || target.displayId != targetDisplay.displayId || !targetDisplay.isValid)
            throw InputProbe.Failure("inputWindowNotReady")
        val time = SystemClock.uptimeMillis()
        receipt.arm(target, time)
        sendTap(target.x, target.y, time)
    }

    override fun awaitReceipt(): Boolean = received.await(1000, TimeUnit.MILLISECONDS)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val ui = handler ?: return
        // Cleanup still runs when the waiting worker was interrupted. Queued creation checks
        // closed, so a late UI callback cannot recreate a window after the timeout.
        val interrupted = Thread.interrupted()
        val done = CountDownLatch(1)
        var cleanupFailure: Throwable? = null
        ui.post {
            try { presentation?.dismiss() }
            catch (e: Throwable) { cleanupFailure = e }
            finally {
                presentation = null
                thread.quitSafely()
                done.countDown()
            }
        }
        try {
            if (!done.await(2000, TimeUnit.MILLISECONDS)) throw InputProbe.Failure("inputProbeCleanupFailed")
            thread.join(1000)
            if (thread.isAlive || cleanupFailure != null) throw InputProbe.Failure("inputProbeCleanupFailed")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

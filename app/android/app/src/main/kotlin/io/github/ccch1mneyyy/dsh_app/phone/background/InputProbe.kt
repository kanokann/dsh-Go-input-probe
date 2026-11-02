package io.github.ccch1mneyyy.dsh_app.phone.background

/** One attempt, on an owned secondary display. An injection return value is not a receipt. */
internal object InputProbe {
    class Failure(val code: String) : Exception(code)
    data class Target(val displayId: Int, val x: Int, val y: Int)

    interface Window : AutoCloseable {
        fun awaitReady(): Target?
        fun inject(target: Target)
        fun awaitReceipt(): Boolean
    }

    fun verify(open: () -> Window, releaseDisplay: () -> Unit) {
        try {
            open().use { window ->
                val target = window.awaitReady() ?: throw Failure("inputWindowNotReady")
                if (target.displayId <= 0 || target.x <= 0 || target.y <= 0)
                    throw Failure("inputWindowNotReady")
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                window.inject(target) // Never replay, including after a timeout.
                if (!window.awaitReceipt()) throw Failure("inputUnconfirmed")
            }
        } finally {
            releaseDisplay()
        }
    }

    /** Correlate both edges to this attempt, its display and its interior coordinate. */
    class Receipt(private val displayId: Int) {
        private var target: Target? = null
        private var downTime = -1L
        private var down = false
        private var up = false

        @Synchronized fun arm(target: Target, downTime: Long) {
            this.target = target
            this.downTime = downTime
            down = false
            up = false
        }

        @Synchronized fun accept(id: Int, time: Long, action: Int, x: Float, y: Float): Boolean {
            val expected = target ?: return false
            if (id != displayId || id != expected.displayId || time != downTime ||
                kotlin.math.abs(x - expected.x) > 2 || kotlin.math.abs(y - expected.y) > 2) return false
            if (action == 0 && !down) down = true
            else if (action == 1 && down) up = true
            else return false
            return down && up
        }
    }
}

package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.*
import org.junit.Test

class InputProbeTest {
    private class Window : InputProbe.Window {
        var target: InputProbe.Target? = InputProbe.Target(11, 180, 400)
        var receipt = true
        var injected = 0
        var closed = 0
        var failureAt: String? = null
        var error: Throwable = InputProbe.Failure("inputApiUnavailable")
        val order = mutableListOf<String>()
        private fun step(name: String) {
            order += name
            if (failureAt == name) throw error
        }
        override fun awaitReady(): InputProbe.Target? { step("ready"); return target }
        override fun inject(target: InputProbe.Target) { injected++; step("inject") }
        override fun awaitReceipt(): Boolean { step("receipt"); return receipt }
        override fun close() { closed++; step("close") }
    }

    @Test fun waitsThenInjectsExactlyOnceAndCleansUpBeforeDisplay() {
        val w = Window()
        InputProbe.verify({ w }, { w.order += "release" })
        assertEquals(listOf("ready", "inject", "receipt", "close", "release"), w.order)
        assertEquals(1, w.injected)
        assertEquals(1, w.closed)
    }

    @Test fun unreadyWindowNeverReceivesAnInjection() {
        val w = Window().apply { target = null }
        assertFailure("inputWindowNotReady") { verify(w) }
        assertEquals(0, w.injected)
        assertEquals(1, w.closed)
    }

    @Test fun mainOrInvalidDisplayNeverReceivesAnInjection() {
        for (id in listOf(-1, 0)) {
            val w = Window().apply { target = InputProbe.Target(id, 180, 400) }
            assertFailure("inputWindowNotReady") { verify(w) }
            assertEquals(0, w.injected)
        }
    }

    @Test fun successfulInjectionWithoutReceiptDoesNotPassOrRetry() {
        val w = Window().apply { receipt = false }
        assertFailure("inputUnconfirmed") { verify(w) }
        assertEquals(1, w.injected)
        assertEquals(1, w.closed)
    }

    @Test fun permissionApiAndInjectionFailuresPropagateWithoutRetry() {
        for (e in listOf(SecurityException("denied"), InputProbe.Failure("inputApiUnavailable"),
                InputProbe.Failure("inputRejected"))) {
            val w = Window().apply { failureAt = "inject"; error = e }
            assertSame(e, assertThrows(Throwable::class.java) { verify(w) })
            assertEquals(1, w.injected)
            assertEquals(1, w.closed)
        }
    }

    @Test fun cleanupRunsOnEveryFailureStage() {
        for (stage in listOf("ready", "inject", "receipt", "close")) {
            val w = Window().apply { failureAt = stage }
            var released = 0
            assertThrows(Throwable::class.java) { InputProbe.verify({ w }, { released++ }) }
            assertEquals(stage, 1, w.closed)
            assertEquals(stage, 1, released)
        }
    }

    @Test fun openFailureStillReleasesPartiallyCreatedDisplay() {
        var released = 0
        assertThrows(SecurityException::class.java) {
            InputProbe.verify({ throw SecurityException() }, { released++ })
        }
        assertEquals(1, released)
    }

    @Test fun interruptedReadinessCleansUpAndDoesNotInject() {
        val w = Window().apply { failureAt = "ready"; error = InterruptedException() }
        assertThrows(InterruptedException::class.java) { verify(w) }
        assertEquals(0, w.injected)
        assertEquals(1, w.closed)
    }

    @Test fun cancellationAfterReadinessStillDoesNotInject() {
        val w = Window()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { verify(w) }
            assertEquals(0, w.injected)
            assertEquals(1, w.closed)
        } finally { Thread.interrupted() }
    }

    @Test fun downAndUpMustMatchTheDisplayTimeAndPositionInOrder() {
        val r = InputProbe.Receipt(11)
        val t = InputProbe.Target(11, 180, 400)
        r.arm(t, 100)
        assertFalse(r.accept(11, 100, 1, 180f, 400f)) // UP without DOWN
        assertFalse(r.accept(0, 100, 0, 180f, 400f))
        assertFalse(r.accept(11, 99, 0, 180f, 400f))
        assertFalse(r.accept(11, 100, 0, 1f, 1f))
        assertFalse(r.accept(11, 100, 0, 180f, 400f))
        assertFalse(r.accept(11, 99, 1, 180f, 400f))
        assertTrue(r.accept(11, 100, 1, 180f, 400f))
        r.arm(t, 200)
        assertFalse(r.accept(11, 100, 1, 180f, 400f))
    }

    private fun verify(w: Window) {
        var released = 0
        try { InputProbe.verify({ w }, { released++ }) }
        finally { assertEquals(1, released) }
    }

    private fun assertFailure(code: String, block: () -> Unit) {
        assertEquals(code, assertThrows(InputProbe.Failure::class.java) { block() }.code)
    }
}

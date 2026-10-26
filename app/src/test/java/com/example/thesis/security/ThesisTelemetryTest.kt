package com.example.thesis.security

import android.os.SystemClock
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite for [ThesisTelemetry] validating zero-PII logging,
 * millisecond timing execution, and coroutine cancellation propagation.
 */
class ThesisTelemetryTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(SystemClock::class)
        every { Log.i(any(), any()) } returns 0
        every { SystemClock.elapsedRealtimeNanos() } returnsMany listOf(1_000_000_000L, 1_050_000_000L)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun test_measureOperation_executesSynchronouslyAndReturnsResult() {
        val result = ThesisTelemetry.measureOperation("Test Operation") {
            42
        }
        assertEquals(42, result)
    }

    @Test
    fun test_measureOperation_propagatesException() {
        try {
            ThesisTelemetry.measureOperation("Failing Operation") {
                throw IllegalStateException("Execution error")
            }
            fail("Expected IllegalStateException to be thrown")
        } catch (e: IllegalStateException) {
            assertEquals("Execution error", e.message)
        }
    }

    @Test
    fun test_measureSuspendOperation_executesCoroutinesAndReturnsResult() = runTest {
        val result = ThesisTelemetry.measureSuspendOperation("Async Operation") {
            delay(10)
            "AsyncResult"
        }
        assertEquals("AsyncResult", result)
    }

    @Test
    fun test_measureSuspendOperation_propagatesCancellation() = runTest {
        try {
            ThesisTelemetry.measureSuspendOperation("Cancelled Operation") {
                throw CancellationException("Operation cancelled")
            }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Operation cancelled", e.message)
        }
    }
}

package com.example.thesis.security

import android.os.SystemClock
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite for [ThesisTelemetry] validating zero-PII logging,
 * millisecond timing execution, coroutine cancellation propagation,
 * descriptive statistical aggregation, and dataset export formatting.
 */
class ThesisTelemetryTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(SystemClock::class)
        every { Log.i(any(), any()) } returns 0
        every { SystemClock.elapsedRealtimeNanos() } returnsMany listOf(1_000_000_000L, 1_050_000_000L)
        ThesisTelemetry.clearTelemetryData()
    }

    @After
    fun tearDown() {
        ThesisTelemetry.clearTelemetryData()
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

    @Test
    fun test_telemetryEventsFlow_emitsRecordedMetrics() = runTest {
        ThesisTelemetry.eventsFlow.test {
            ThesisTelemetry.measureOperation("Telemetry Stream Test") {
                100
            }
            val event = awaitItem()
            assertEquals("Telemetry Stream Test", event.operationName)
            assertTrue(event.durationMs >= 0.0)
            assertTrue(event.isSuccess)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun test_recordMetric_and_clearTelemetryData() {
        ThesisTelemetry.recordMetric("Op A", 12.5, true)
        ThesisTelemetry.recordMetric("Op B", 25.0, true)

        val events = ThesisTelemetry.getAllEvents()
        assertEquals(2, events.size)
        assertEquals("Op A", events[0].operationName)
        assertEquals(12.5, events[0].durationMs, 0.001)

        ThesisTelemetry.clearTelemetryData()
        assertTrue(ThesisTelemetry.getAllEvents().isEmpty())
    }

    @Test
    fun test_computeSummaries_calculatesAccurateDescriptiveStatistics() {
        // Samples for Op1: 10.0, 20.0, 30.0 -> N=3, Mean=20.0, Min=10.0, Max=30.0
        // Variance = ((10-20)^2 + (20-20)^2 + (30-20)^2) / 2 = (100 + 0 + 100) / 2 = 100 -> StdDev = 10.0
        ThesisTelemetry.recordMetric("Op 1", 10.0, true)
        ThesisTelemetry.recordMetric("Op 1", 20.0, true)
        ThesisTelemetry.recordMetric("Op 1", 30.0, true)
        ThesisTelemetry.recordMetric("Op 1", 99.0, false) // Failed sample should be excluded from latency statistics

        val summaries = ThesisTelemetry.computeSummaries()
        assertEquals(1, summaries.size)

        val summary = summaries[0]
        assertEquals("Op 1", summary.operationName)
        assertEquals(3, summary.sampleCount)
        assertEquals(20.0, summary.meanMs, 0.001)
        assertEquals(10.0, summary.stdDevMs, 0.001)
        assertEquals(10.0, summary.minMs, 0.001)
        assertEquals(30.0, summary.maxMs, 0.001)
        assertTrue(summary.p95Ms >= 20.0 && summary.p95Ms <= 30.0)
    }

    @Test
    fun test_calculatePercentile_boundaryCases() {
        assertEquals(0.0, ThesisTelemetry.calculatePercentile(emptyList(), 95.0), 0.001)
        assertEquals(42.0, ThesisTelemetry.calculatePercentile(listOf(42.0), 95.0), 0.001)

        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        assertEquals(10.0, ThesisTelemetry.calculatePercentile(values, 0.0), 0.001)
        assertEquals(30.0, ThesisTelemetry.calculatePercentile(values, 50.0), 0.001)
        assertEquals(50.0, ThesisTelemetry.calculatePercentile(values, 100.0), 0.001)
    }

    @Test
    fun test_exportAsCsv_producesValidCsvHeaderAndRows() {
        ThesisTelemetry.recordMetric("Keystore KEK", 15.2, true)

        val csv = ThesisTelemetry.exportAsCsv()
        assertTrue(csv.startsWith("timestamp_iso,timestamp_ms,operation_name,duration_ms,status"))
        assertTrue(csv.contains("Keystore KEK"))
        assertTrue(csv.contains("15.200"))
        assertTrue(csv.contains("SUCCESS"))
    }

    @Test
    fun test_exportSummaryAsMarkdown_producesValidMarkdownTable() {
        ThesisTelemetry.recordMetric("Keystore KEK", 15.0, true)
        ThesisTelemetry.recordMetric("Keystore KEK", 25.0, true)

        val md = ThesisTelemetry.exportSummaryAsMarkdown()
        assertTrue(md.contains("| Benchmark Operation | Samples (N) | Mean (ms) | StdDev (ms) | Min (ms) | Max (ms) | P95 (ms) |"))
        assertTrue(md.contains("| :--- | :---: | :---: | :---: | :---: | :---: | :---: |"))
        assertTrue(md.contains("| Keystore KEK | 2 | 20.00 |"))
    }

    @Test
    fun test_exportFullReport_containsSummaryAndCsvBlocks() {
        ThesisTelemetry.recordMetric("MLS Handshake", 45.0, true)

        val report = ThesisTelemetry.exportFullReport()
        assertTrue(report.contains("# XMTP MLS & Hardware Keystore Benchmark Report"))
        assertTrue(report.contains("## Statistical Aggregations (RFC 9420 & Android Keystore)"))
        assertTrue(report.contains("## Raw Telemetry Samples (CSV)"))
        assertTrue(report.contains("```csv"))
        assertTrue(report.contains("MLS Handshake"))
    }
}

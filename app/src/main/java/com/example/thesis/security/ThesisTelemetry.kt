package com.example.thesis.security

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

/**
 * Structured metric event for telemetry and action log consumption.
 */
data class TelemetryEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val operationName: String,
    val durationMs: Double,
    val isSuccess: Boolean = true
)

/**
 * Aggregated descriptive statistics for a benchmark operation across N samples.
 */
data class BenchmarkSummary(
    val operationName: String,
    val sampleCount: Int,
    val meanMs: Double,
    val stdDevMs: Double,
    val minMs: Double,
    val maxMs: Double,
    val p95Ms: Double
)

/**
 * Performance benchmarking logger (Zero-PII) for thesis experimentation.
 * Tracks hardware Keystore latency, SQLCipher initialization, EIP-191 signing, MLS handshake, and GDPR Shred.
 * Provides statistical aggregation and multi-format dataset export (CSV and Markdown tables).
 */
object ThesisTelemetry {

    const val TAG = "THESIS_BENCHMARK"
    private const val MAX_EVENT_CAPACITY = 2048

    private val recordedEvents = Collections.synchronizedList(mutableListOf<TelemetryEvent>())

    private val _eventsFlow = MutableSharedFlow<TelemetryEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val eventsFlow: SharedFlow<TelemetryEvent> = _eventsFlow.asSharedFlow()

    fun recordMetric(operationName: String, durationMs: Double, isSuccess: Boolean) {
        val event = TelemetryEvent(
            timestamp = System.currentTimeMillis(),
            operationName = operationName,
            durationMs = durationMs,
            isSuccess = isSuccess
        )

        synchronized(recordedEvents) {
            if (recordedEvents.size >= MAX_EVENT_CAPACITY) {
                recordedEvents.removeAt(0)
            }
            recordedEvents.add(event)
        }

        _eventsFlow.tryEmit(event)
    }

    /**
     * Returns an immutable snapshot of all recorded telemetry events.
     */
    fun getAllEvents(): List<TelemetryEvent> = synchronized(recordedEvents) {
        recordedEvents.toList()
    }

    /**
     * Clears all in-memory recorded telemetry events.
     */
    fun clearTelemetryData() {
        synchronized(recordedEvents) {
            recordedEvents.clear()
        }
    }

    /**
     * Calculates descriptive statistics per operation name across all successful samples.
     */
    fun computeSummaries(): List<BenchmarkSummary> {
        val eventsSnapshot = getAllEvents()
        val grouped = eventsSnapshot.filter { it.isSuccess }.groupBy { it.operationName }

        return grouped.map { (opName, events) ->
            val durations = events.map { it.durationMs }
            val count = durations.size
            val mean = if (count > 0) durations.average() else 0.0
            val variance = if (count > 1) {
                durations.sumOf { (it - mean) * (it - mean) } / (count - 1)
            } else {
                0.0
            }
            val stdDev = sqrt(variance)
            val min = durations.minOrNull() ?: 0.0
            val max = durations.maxOrNull() ?: 0.0
            val sorted = durations.sorted()
            val p95 = calculatePercentile(sorted, 95.0)

            BenchmarkSummary(
                operationName = opName,
                sampleCount = count,
                meanMs = mean,
                stdDevMs = stdDev,
                minMs = min,
                maxMs = max,
                p95Ms = p95
            )
        }.sortedBy { it.operationName }
    }

    /**
     * Calculates the linear interpolation percentile for a sorted list of values.
     */
    fun calculatePercentile(sortedValues: List<Double>, percentile: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        if (sortedValues.size == 1) return sortedValues[0]
        val clampedPercentile = percentile.coerceIn(0.0, 100.0)
        val rank = (clampedPercentile / 100.0) * (sortedValues.size - 1)
        val lowerIndex = rank.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(sortedValues.lastIndex)
        val weight = rank - lowerIndex
        return sortedValues[lowerIndex] + weight * (sortedValues[upperIndex] - sortedValues[lowerIndex])
    }

    /**
     * Exports raw benchmark samples as machine-readable CSV format.
     */
    fun exportAsCsv(): String {
        val events = getAllEvents()
        val sb = StringBuilder()
        sb.append("timestamp_iso,timestamp_ms,operation_name,duration_ms,status\n")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        events.forEach { event ->
            val iso = dateFormat.format(Date(event.timestamp))
            val status = if (event.isSuccess) "SUCCESS" else "FAILURE"
            val escapedName = "\"" + event.operationName.replace("\"", "\"\"") + "\""
            sb.append(String.format(Locale.US, "%s,%d,%s,%.3f,%s\n", iso, event.timestamp, escapedName, event.durationMs, status))
        }
        return sb.toString()
    }

    /**
     * Exports aggregated summary metrics formatted as a GitHub Markdown table.
     */
    fun exportSummaryAsMarkdown(): String {
        val summaries = computeSummaries()
        val sb = StringBuilder()
        sb.append("| Benchmark Operation | Samples (N) | Mean (ms) | StdDev (ms) | Min (ms) | Max (ms) | P95 (ms) |\n")
        sb.append("| :--- | :---: | :---: | :---: | :---: | :---: | :---: |\n")
        summaries.forEach { s ->
            sb.append(
                String.format(
                    Locale.US,
                    "| %s | %d | %.2f | %.2f | %.2f | %.2f | %.2f |\n",
                    s.operationName,
                    s.sampleCount,
                    s.meanMs,
                    s.stdDevMs,
                    s.minMs,
                    s.maxMs,
                    s.p95Ms
                )
            )
        }
        return sb.toString()
    }

    /**
     * Exports a comprehensive benchmark report combining statistical summary and raw CSV samples.
     */
    fun exportFullReport(): String {
        val sb = StringBuilder()
        sb.append("# XMTP MLS & Hardware Keystore Benchmark Report\n\n")
        sb.append("## Statistical Aggregations (RFC 9420 & Android Keystore)\n\n")
        sb.append(exportSummaryAsMarkdown())
        sb.append("\n## Raw Telemetry Samples (CSV)\n\n")
        sb.append("```csv\n")
        sb.append(exportAsCsv())
        sb.append("```\n")
        return sb.toString()
    }

    /**
     * Measures synchronous operation execution time with millisecond precision.
     */
    inline fun <T> measureOperation(operationName: String, block: () -> T): T {
        val startNs = SystemClock.elapsedRealtimeNanos()
        var success = false
        return try {
            val result = block()
            success = true
            result
        } finally {
            val durationMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            Log.i(TAG, String.format(Locale.US, "[METRIC] %-38s : %8.2f ms", operationName, durationMs))
            recordMetric(operationName, durationMs, success)
        }
    }

    /**
     * Measures suspend operation execution time with millisecond precision.
     */
    suspend inline fun <T> measureSuspendOperation(operationName: String, block: suspend () -> T): T {
        val startNs = SystemClock.elapsedRealtimeNanos()
        var success = false
        return try {
            val result = block()
            success = true
            result
        } finally {
            val durationMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            Log.i(TAG, String.format(Locale.US, "[METRIC] %-38s : %8.2f ms", operationName, durationMs))
            recordMetric(operationName, durationMs, success)
        }
    }
}

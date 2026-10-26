package com.example.thesis.security

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
 * Performance benchmarking logger (Zero-PII) for thesis experimentation.
 * Tracks hardware Keystore latency, SQLCipher initialization, EIP-191 signing, MLS handshake, and GDPR Shred.
 */
object ThesisTelemetry {

    const val TAG = "THESIS_BENCHMARK"

    private val _eventsFlow = MutableSharedFlow<TelemetryEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val eventsFlow: SharedFlow<TelemetryEvent> = _eventsFlow.asSharedFlow()

    fun recordMetric(operationName: String, durationMs: Double, isSuccess: Boolean) {
        _eventsFlow.tryEmit(
            TelemetryEvent(
                timestamp = System.currentTimeMillis(),
                operationName = operationName,
                durationMs = durationMs,
                isSuccess = isSuccess
            )
        )
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
            Log.i(TAG, String.format("[METRIC] %-38s : %8.2f ms", operationName, durationMs))
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
            Log.i(TAG, String.format("[METRIC] %-38s : %8.2f ms", operationName, durationMs))
            recordMetric(operationName, durationMs, success)
        }
    }
}

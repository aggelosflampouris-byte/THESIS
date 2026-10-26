package com.example.thesis.security

import android.os.SystemClock
import android.util.Log

/**
 * Καταγραφέας επιδόσεων (Zero-PII) για την εξαγωγή πειραματικών μετρήσεων
 * της πτυχιακής εργασίας (Keystore latency, SQLCipher init, EIP-191 signing, MLS handshake, GDPR Shred).
 */
object ThesisTelemetry {

    const val TAG = "THESIS_BENCHMARK"

    /**
     * Μετράει τον χρόνο εκτέλεσης μιας σύγχρονης λειτουργίας με ακρίβεια χιλιοστού του δευτερολέπτου.
     */
    inline fun <T> measureOperation(operationName: String, block: () -> T): T {
        val startNs = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            val durationMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            Log.i(TAG, String.format("[METRIC] %-38s : %8.2f ms", operationName, durationMs))
        }
    }

    /**
     * Μετράει τον χρόνο εκτέλεσης μιας suspend λειτουργίας με ακρίβεια χιλιοστού του δευτερολέπτου.
     */
    suspend inline fun <T> measureSuspendOperation(operationName: String, block: suspend () -> T): T {
        val startNs = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            val durationMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            Log.i(TAG, String.format("[METRIC] %-38s : %8.2f ms", operationName, durationMs))
        }
    }
}

package com.example.thesis.diagnostics

import android.content.Context
import com.example.thesis.ui.ActionLogEntry

// Guarda la traza de cualquier fallo no capturado en el registro persistente antes de que el proceso termine.
object CrashLogger {
    private const val MAX_STACK_CHARS = 6_000

    fun install(context: Context) {
        val logFile = ActionLogStore.defaultFile(context)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                ActionLogStore.appendSync(
                    file = logFile,
                    entry = ActionLogEntry(
                        tag = "CRASH",
                        message = formatCrash(thread.name, throwable),
                        isError = true
                    )
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    internal fun formatCrash(threadName: String, throwable: Throwable): String {
        val header = "Uncaught ${throwable::class.java.name} on thread '$threadName': ${throwable.message}"
        return header + "\n" + throwable.stackTraceToString().take(MAX_STACK_CHARS)
    }
}

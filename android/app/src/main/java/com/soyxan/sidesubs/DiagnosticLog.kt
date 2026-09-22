package com.soyxan.sidesubs

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small private diagnostic log. Callers add structured events only; never put
 * PINs, JWTs, Plex tokens, request bodies or authenticated URLs here.
 */
class DiagnosticLog(context: Context) {
    private val file = File(context.filesDir, "diagnostics.log")

    @Synchronized
    fun add(event: String) {
        runCatching {
            if (file.exists() && file.length() > MAX_BYTES) {
                val tail = file.readText().takeLast(MAX_BYTES / 2)
                file.writeText(tail.substringAfter('\n', ""))
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("$timestamp  ${event.take(500)}\n")
        }
    }

    @Synchronized
    fun read(): String = runCatching {
        if (file.exists()) file.readText() else "No diagnostic events yet."
    }.getOrDefault("The diagnostic log could not be read.")

    companion object {
        private const val MAX_BYTES = 64 * 1024
    }
}

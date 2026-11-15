package com.inyogeshwar.codesathi.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash reporter (Phase 7, Step B3 — adapted from AgentMT's
 * `core/CrashReporter.kt`, the user's own code).
 *
 * An uncaught exception is written to a local file
 * (`getFilesDir()/last_crash.txt`) and shown on the next launch from
 * Home, with a Copy button and a Dismiss button. There is NO upload
 * and NO analytics anywhere — the file never leaves the device
 * unless the user copies it out themselves.
 *
 * Installed in [CodeSathiApp.onCreate] via the default
 * uncaught-exception handler, keeping the previous handler chained
 * so the process still dies normally after the file is written.
 * Secrets never appear in stack traces because they are never put
 * in exception messages (keys live only in EncryptedSharedPreferences
 * and are never interpolated into an error).
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val version = runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull()
                File(app.filesDir, FILE).writeText(buildString {
                    append("CodeSathi ").append(version)
                        .append(" · Android ").append(Build.VERSION.RELEASE)
                        .append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                    append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        .append(" · thread ").append(t.name).append("\n\n")
                    append(sw.toString().take(20_000))
                })
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** The last crash report, or null when the app did not crash last time. */
    fun pending(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    /** Forget the last crash (after the user dismisses it). */
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}

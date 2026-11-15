package com.inyogeshwar.codesathi.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs commands in the user's own Termux app through its public
 * RUN_COMMAND intent (the documented action, extras and result
 * bundle keys only — no Termux code, no terminal-view library, no
 * native code). Adapted from AgentMT's TermuxBridge to CodeSathi:
 * the bridge is an execution backend the agent's `run` tool can use
 * when the user turns it on (Terminal screen), and the stdio MCP
 * transport in [McpClient] runs through it too.
 *
 * What the user must do first (stated on the Terminal screen and in
 * the README — the app cannot do any of it for them):
 * - install Termux,
 * - set allow-external-apps=true in Termux (~/.termux/termux.properties,
 *   then termux-reload-settings),
 * - grant this app the com.termux.permission.RUN_COMMAND permission
 *   (declared in the manifest; Termux asks when the first command runs).
 * Without Termux the feature refuses with a plain message — nothing
 * is silently run in-app instead, so the model never believes a
 * command ran in Termux when it ran somewhere else.
 *
 * Termux can only see shared storage (after termux-setup-storage) or
 * its own home — never this app's private folder. So a `run` in
 * Termux only works when the project folder resolves to a shared-
 * storage path; the app-private fallback refuses.
 */
object TermuxBridge {
    const val PKG = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private val ids = AtomicInteger(1)
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun ctx(): Context = app ?: error("TermuxBridge not initialised")

    data class Result(val stdout: String, val stderr: String, val exitCode: Int, val err: Int, val errmsg: String) {
        val ok get() = err == -1 && exitCode == 0
        fun text(): String = buildString {
            if (stdout.isNotEmpty()) append(stdout.trimEnd()).append('\n')
            if (stderr.isNotEmpty()) append(stderr.trimEnd()).append('\n')
            if (errmsg.isNotBlank() && err != -1) {
                append("[termux error $err] ").append(errmsg.trim()).append('\n')
            }
            append("[exit $exitCode]")
        }
    }

    fun installed(c: Context): Boolean =
        runCatching { c.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    fun permitted(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when Termux is installed and this app may run commands in it. */
    fun ready(c: Context? = app): Boolean = c != null && installed(c) && permitted(c)

    /** Termux can only see shared storage or its own home. */
    fun canSee(path: String): Boolean =
        path.startsWith("/storage/") || path.startsWith("/sdcard") || path.startsWith(HOME)

    /** One-time setup the user pastes into Termux. */
    const val SETUP = "mkdir -p ~/.termux; sed -i '/allow-external-apps/d' ~/.termux/termux.properties 2>/dev/null; " +
        "echo 'allow-external-apps=true' >> ~/.termux/termux.properties; " +
        "termux-reload-settings; termux-setup-storage; pkg install -y nodejs python git"

    /**
     * Runs [command] with bash -lc in Termux's background and waits
     * for its result. Returns null on timeout (the command keeps
     * running inside Termux — Termux gives no way to kill it from
     * here, so the agent's Stop cannot stop a Termux command).
     */
    suspend fun run(command: String, workdir: String? = null, timeoutMs: Long = 60_000): Result? {
        val c = ctx()
        if (!installed(c)) throw IllegalStateException("Termux is not installed.")
        if (!permitted(c)) {
            throw IllegalStateException(
                "CodeSathi is not allowed to run commands in Termux yet — " +
                    "enable allow-external-apps in Termux and grant the permission when Termux asks."
            )
        }
        val n = ids.getAndIncrement()
        val action = c.packageName + ".TERMUX_RESULT." + n
        val done = CompletableDeferred<Result>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val b = intent.getBundleExtra("result")
                done.complete(
                    Result(
                        b?.getString("stdout").orEmpty(), b?.getString("stderr").orEmpty(),
                        b?.getInt("exitCode", -1) ?: -1, b?.getInt("err", -1) ?: -1,
                        b?.getString("errmsg").orEmpty()
                    )
                )
            }
        }
        ContextCompat.registerReceiver(c, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        try {
            val flags = PendingIntent.FLAG_ONE_SHOT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, n, Intent(action).setPackage(c.packageName), flags)
            val i = Intent().setClassName(PKG, SERVICE).setAction(ACTION)
                .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            workdir?.let { i.putExtra("com.termux.RUN_COMMAND_WORKDIR", it) }
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            return withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            runCatching { c.unregisterReceiver(receiver) }
        }
    }

    fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

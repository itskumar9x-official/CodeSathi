package com.inyogeshwar.codesathi.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.inyogeshwar.codesathi.CodeSathiApp
import com.inyogeshwar.codesathi.MainActivity
import com.inyogeshwar.codesathi.R
import com.inyogeshwar.codesathi.data.Message
import com.inyogeshwar.codesathi.data.Session
import com.inyogeshwar.codesathi.ui.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Phase 2, Step 7 — Remote mode foreground service.
 *
 * Started from the Remote screen's toggle; stopped by the same toggle
 * or by the notification's Stop action. While it runs, the phone serves
 * the small remote API on port [RemoteServer.PORT] (LAN only).
 *
 * The service never starts without a password: if none is saved in
 * KeyStore it stops itself at once. The password itself is never read
 * into a log or a notification — only the URL is shown.
 *
 * Turns run through the one shared [AppViewModel] (set by MainActivity),
 * so a remote turn is the same agent loop as an in-app turn, and a file
 * change it proposes waits for approval on the phone's diff card.
 */
class RemoteService : Service() {

    companion object {
        const val ACTION_STOP = "com.inyogeshwar.codesathi.remote.STOP"
        private const val CHANNEL_ID = "codesathi_remote"
        private const val NOTIFICATION_ID = 1

        /** Start the service (and the server) from the Remote screen. */
        fun start(context: Context) {
            val intent = Intent(context, RemoteService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // Foreground service could not start (e.g. background
                // restrictions) — the toggle stays off; the user retries
                // from the Remote screen while the app is in the foreground.
            }
        }

        /** Stop the service (and the server). */
        fun stop(context: Context) {
            val intent = Intent(context, RemoteService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: RemoteServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        // START_STICKY: if the system kills the service, it is recreated
        // and the server comes back up (the password is still in KeyStore).
        return START_STICKY
    }

    private fun startServer() {
        val app = application as CodeSathiApp
        val password = app.keyStore.getRemotePassword()
        if (password.isNullOrBlank()) {
            // No password set = the server refuses to start.
            serviceScope.launch { app.preferencesStore.setRemoteEnabled(false) }
            stopSelf()
            return
        }
        if (server?.isRunning == true) return
        // Reuse MainActivity's ViewModel when the app is open; otherwise
        // build one from the same factory (same app singletons inside).
        val vm: AppViewModel = app.appViewModel
            ?: AppViewModel.factory(app).create(AppViewModel::class.java)
        val handler = object : RemoteServer.RemoteHandler {
            override suspend fun listSessions(): List<Session> =
                app.database.sessionDao().observeSessions().first()

            override suspend fun getSessionMessages(sessionId: String): List<Message>? =
                vm.remoteSessionMessages(sessionId)

            override suspend fun runTurn(sessionId: String, message: String): RemoteTurnResult =
                vm.runRemoteTurn(sessionId, message, this@RemoteService)
        }
        val remoteServer = RemoteServer(handler) { app.keyStore.getRemotePassword() }
        if (!remoteServer.start()) {
            // Port already in use (or bind failed) — do not pretend to run.
            serviceScope.launch { app.preferencesStore.setRemoteEnabled(false) }
            stopSelf()
            return
        }
        server = remoteServer
        startInForeground(NOTIFICATION_ID, buildNotification())
        serviceScope.launch { app.preferencesStore.setRemoteEnabled(true) }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        val app = application as CodeSathiApp
        serviceScope.launch { app.preferencesStore.setRemoteEnabled(false) }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote mode",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "CodeSathi remote server is running on your Wi-Fi."
            manager.createNotificationChannel(channel)
        }
        val ip = RemoteServer.wifiIpAddress()
        val url = if (ip != null) "http://${ip}:${RemoteServer.PORT}" else "http://<wifi-ip>:${RemoteServer.PORT}"
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RemoteService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("CodeSathi Remote is on")
            .setContentText("Open $url on another device (same Wi-Fi)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build()
            )
            .build()
        return notification
    }

    /** startForeground with the dataSync type on Android 10+, plain before that. */
    private fun startInForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(id, notification)
        }
    }
}

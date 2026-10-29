package com.android.r0dumpmanager.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.r0dumpmanager.MainActivity
import com.android.r0dumpmanager.R
import com.android.r0dumpmanager.data.export.ArtifactExportRepository
import com.android.r0dumpmanager.data.packageinfo.PackageRepository
import com.android.r0dumpmanager.data.property.FusionPropertyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Foreground service that hosts the [McpServer] on an abstract-namespace unix
 * socket ("r0dump_mcp"). The `r0dump_mcp_bridge` native binary connects to it
 * and relays a host MCP client's stdio, so the pipeline can be driven without
 * touching the UI. Started/stopped by the MCP toggle; runs only while on.
 */
class McpBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: LocalServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            // Upstream uses FOREGROUND_SERVICE_TYPE_SPECIAL_USE, which only exists from API 34.
            // The dataSync type is the Android 13 equivalent for a long-lived local socket server
            // and must match the manifest's android:foregroundServiceType.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (serverSocket == null) {
            scope.launch { serve() }
        }
        return START_STICKY
    }

    private suspend fun serve() {
        val server = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "failed to bind @$SOCKET_NAME", e)
            stopSelf()
            return
        }
        serverSocket = server
        val mcp = McpServer(FusionPropertyRepository(), PackageRepository(this), ArtifactExportRepository())
        while (scope.isActive) {
            val client = try {
                runInterruptible { server.accept() }
            } catch (e: Exception) {
                if (scope.isActive) Log.w(TAG, "accept ended", e)
                break
            }
            Log.i(TAG, "client connected")
            try {
                handleClient(client, mcp)
            } catch (e: Exception) {
                Log.w(TAG, "client session error", e)
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private suspend fun handleClient(client: LocalSocket, mcp: McpServer) {
        val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
        val output: OutputStream = client.outputStream
        while (scope.isActive) {
            val line = runInterruptible { reader.readLine() } ?: break
            if (line.isBlank()) continue
            Log.i(TAG, "recv ${line.length} bytes")
            val response = mcp.handle(line)
            Log.i(TAG, "handled -> ${response?.length ?: -1} bytes")
            if (response != null) {
                runInterruptible {
                    output.write(response.toByteArray(Charsets.UTF_8))
                    output.write('\n'.code)
                    output.flush()
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.mcp_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_r0dump_manager)
            .setContentTitle(getString(R.string.mcp_notification_title))
            .setContentText(getString(R.string.mcp_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "R0dumpMcp"
        private const val SOCKET_NAME = "r0dump_mcp"
        private const val CHANNEL_ID = "r0dump_mcp_bridge"
        private const val NOTIFICATION_ID = 4200

        fun start(context: Context) {
            context.startForegroundService(Intent(context, McpBridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpBridgeService::class.java))
        }
    }
}

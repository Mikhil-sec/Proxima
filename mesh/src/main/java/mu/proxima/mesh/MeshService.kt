package mu.proxima.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the process (and so the Nearby links owned by [NearbyMeshEngine]) alive with the screen off.
 * The engine does the work; this service only holds the foreground notification.
 */
class MeshService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Proxima mesh relay", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Proxima mesh active")
            .setContentText("Relaying safety alerts to nearby phones")
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // Missing runtime permission or background-start restriction: mesh still runs while the app is open.
            Log.w(TAG, "mesh foreground service not started: ${it.message}")
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "Proxima"
        private const val CHANNEL_ID = "proxima_mesh"
        private const val NOTIFICATION_ID = 3001

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MeshService::class.java)) }
                .onFailure { Log.w(TAG, "mesh service start failed: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}

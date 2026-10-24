package id.harness.pipocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 后台提醒用的**前台服务**：只为把进程钉住、让到桥的 SSE 不断。
 *
 * 为什么必须有它：Android 在后台会收掉 socket（这也是以前「远端机器的通知到不了」的根因）。
 * 用户口径是「走 pocket 通道，不要 ntfy」⇒ 就必须让 app 自己在后台也不掉线：
 * 桥发一条 SSE `notify` 事件 → 这里所在进程里的 SSE 收到 → [NotifyReceiver.post] 弹 pocket 自己的通知。
 *
 * 代价：会有一条**低优先级、无声**的常驻通知（Android 的硬要求，不可隐藏）。
 * 用户可在「选择机器 → 后台提醒」里关掉；关掉后后台就收不到远端通知，只能回到 app 里看。
 */
class LinkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        AppState.init(applicationContext)
        startForeground(NOTIFY_ID, buildNotification())
        // 进程活着就够：SSE 由 AppState 负责（它自己有断线重连），这里不再另起一条。
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notify_channel_link),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = getString(R.string.notify_channel_link_desc)
                    setShowBadge(false)
                },
            )
        }
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val machine = AppState.baseUrl.ifBlank { "pi" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notify_link_title))
            .setContentText(machine)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "pi-pocket-link"
        const val NOTIFY_ID = 4242
        const val ACTION_STOP = "id.harness.pipocket.LINK_STOP"

        /** 开/关后台守护（幂等）。 */
        fun setRunning(context: Context, running: Boolean) {
            val intent = Intent(context, LinkService::class.java)
            runCatching {
                if (running) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                    else context.startService(intent)
                } else {
                    context.stopService(intent)
                }
            }
        }
    }
}

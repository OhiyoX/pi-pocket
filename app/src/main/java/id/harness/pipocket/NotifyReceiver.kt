package id.harness.pipocket

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 「会话停了/答完了」的通知。
 *
 * 为什么是**桥发广播**、不是 app 自己看 SSE：
 * app 进后台后 Android 会把 socket 收掉（我们没有常驻前台服务的打算），
 * 那时谁也收不到流式事件。所以由**桥**在 pi 说这一轮结束时发一条本地广播
 * （`am broadcast -a id.harness.pipocket.NOTIFY`），app 只负责按前后台决定表现：
 *
 *  · **在前台**（用户就在看）：不弹系统通知，只在对话里落一行提示（[AppState.addNotice]）；
 *  · **在后台**：发一条系统通知，点它回到对应的会话。
 *
 * 同一条会话连续通知只用同一个 id（覆盖，不堆一串）。
 */
class NotifyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND)?.takeIf { it.isNotBlank() } ?: "turn-end"
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: id.harness.pipocket.ui.notifyTitle(kind)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val file = intent.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() }
        post(context, kind = kind, title = title, text = text, file = file)
    }

    /**
     * 弹一条 pocket 自己的通知（见 companion 的 [post]）。
     *
     * 两条触发路共用：① 本机桥的 `am` 广播（本类）；② 远端机器桥的 SSE `notify` 事件
     * （[AppState] 在后台时调 `NotifyReceiver.post`）—— 后者需要 [LinkService] 把进程守住。
     */
    companion object {
        const val ACTION_NOTIFY = "id.harness.pipocket.NOTIFY"
        const val CHANNEL_ID = "pi-pocket-turns"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FILE = "file"
        const val EXTRA_KIND = "kind"

        /** 同会话互相覆盖（不会堆一串）；不同会话各占一条。 */
        fun notifyIdFor(file: String?): Int = id.harness.pipocket.ui.notifyIdFor(file)

        /**
         * 弹一条 pocket 自己的通知（前台只落提示，不打扰）。
         *
         * 两条触发路共用：① 本机桥的 `am` 广播（[onReceive]）；② 远端机器桥的 SSE `notify` 事件
         * （[AppState] 后台时调这里）—— 后者需要 [LinkService] 守住进程。
         */
        fun post(context: Context, kind: String, title: String, text: String, file: String?) {
            AppState.init(context)

            // 回合结束类（回答完成 / 已停止 / 出错了）默认**不发**：用户 2026-10-24
            // 「每次都显示且报错也显示」⇒ 设置 › 通知 › 回答完成提醒 打开才发。
            // 等人回应（needs-input）与 agent 主动发的（note）不受影响。
            if (!id.harness.pipocket.ui.turnEndNoticeAllowed(kind, AppState.notifyTurnEnd)) return

            // 前台：不打断，只落提示（与终端里看到的一致）
            if (AppState.appInForeground) {
                AppState.addNotice(title = title, detail = text, level = if (kind == "error") "error" else "info")
                return
            }

            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannel(context, manager)

            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_SESSION_FILE, file)
            }
            val pending = PendingIntent.getActivity(
                context,
                // 会话不同 → requestCode 不同，否则 PendingIntent 会复用上一条会话的 extra
                (file?.hashCode() ?: 0),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .apply { if (text.isNotBlank()) setContentText(text) }
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setOnlyAlertOnce(false)
                .build()

            // 没授权就安静退出（不弹通知不等于功能坏；用户可以在系统设置里补授权）
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            runCatching {
                NotificationManagerCompat.from(context).notify(notifyIdFor(file), notification)
            }
        }

        private fun ensureChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notify_channel_turns),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notify_channel_turns_desc)
                },
            )
        }
    }
}

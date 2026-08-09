package com.xposed.doupp.bookmark

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xposed.doupp.util.HookUtils

/**
 * 书签提醒通知。
 * 与 DYHelper 的 CommentBookmarkNotifier/ProbeNotifier 对齐：
 * - 评论书签有新回复
 * - 主页书签有新作品
 * 点击后打开抖音主界面。
 */
object BookmarkNotifier {

    private const val CHANNEL_ID = "doupp_bookmark"
    private const val CHANNEL_NAME = "书签提醒"
    private const val TAG = "BookmarkNotifier"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
        ensureChannel()
    }

    private fun ensureChannel() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "Dou+ 评论书签与主页书签提醒"
                    }
                    nm.createNotificationChannel(channel)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun launchIntent(context: Context): PendingIntent? {
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return null
            val pi = PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi
        } catch (_: Throwable) {
            null
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        val context = appContext ?: return
        try {
            ensureChannel()
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(launchIntent(context))
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (t: Throwable) {
            HookUtils.log("$TAG notify failed: ${t.message}")
        }
    }

    fun notifyNewCommentReply(record: CommentBookmarkRecord) {
        if (!record.notificationEnabled) return
        val text = buildString {
            val first = record.newReplyItems.firstOrNull()
            if (first != null) {
                val author = if (first.authorName.isEmpty()) "有人" else first.authorName
                append(author).append("：").append(first.content.take(36))
            } else {
                append("新增 ")
                append(if (record.newReplyCount < 1) 1 else record.newReplyCount)
                append(" 条回复")
                if (record.content.isNotEmpty()) {
                    append("：").append(record.content.take(36))
                }
            }
        }
        notify((record.commentId.hashCode() and 0xFFFF) + 13697024, "评论书签有新回复", text)
    }

    fun notifyNewProfileAweme(record: ProfileBookmarkRecord) {
        if (!record.notificationEnabled) return
        val first = record.newAwemeItems.firstOrNull()
        val text = if (first != null) {
            val desc = if (first.desc.isEmpty()) first.awemeId else first.desc
            "${record.displayName()} 发布了新作品：${desc.take(40)}"
        } else {
            "${record.displayName()} 有新作品"
        }
        notify((record.uniqueKey().hashCode() and 0xFFFF) + 13762560, "主页书签有新作品", text)
    }

    private object Compat
}
package com.xposed.doupp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.xposed.doupp.ui.DouSettings

/**
 * 保活前台服务。
 *
 * 小米(HyperOS)的 WakePathChecker 会拦截抖音"唤醒"本模块进程来服务 ContentProvider，
 * 表现为 "MIUILOG-AutoStart ... Reject ... com.xposed.doupp"。只要本模块进程一直存活，
 * provider 就已发布，抖音读取时无需唤醒，WakePathChecker 不会拦截。
 *
 * 因此本服务以前台服务形式常驻，保证 ContentProvider 始终可用，抖音能实时读取设置。
 *
 * 首次从抖音 hook 进程启动时（exported=true），立刻调用 DouSettings.init() 确保
 * prefs 文件存在并设为 world-readable。
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "doupp_keepalive"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.xposed.doupp.action.STOP_KEEPALIVE"

        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
            }
        }

        /**
         * 从抖音 hook 进程启动本模块的 KeepAliveService（跨 UID 启动 exported service）。
         * 使用显式 ComponentName 避免隐式 Intent 被 Android 11+ 限制。
         */
        fun startFromHook(context: Context) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(MODULE_PACKAGE, "$MODULE_PACKAGE.KeepAliveService")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                com.xposed.doupp.util.HookUtils.log("KeepAliveService: startFromHook 成功")
            } catch (t: Throwable) {
                com.xposed.doupp.util.HookUtils.log("KeepAliveService: startFromHook 失败: ${t.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }

        private const val MODULE_PACKAGE = "com.xposed.doupp"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 创建 prefs 文件并设为 world-readable（首次启动时由模块设置页调用 init，
        // 但若用户从未打开设置页，此处兜底创建）
        DouSettings.init(this)
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        // 被杀后系统会尝试拉起（前台服务优先级高，小米通常保留）
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Dou++ 保活",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            mgr.createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        @Suppress("DEPRECATION")
        return builder
            .setContentTitle("Dou++ 已激活")
            .setContentText("设置同步服务运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}

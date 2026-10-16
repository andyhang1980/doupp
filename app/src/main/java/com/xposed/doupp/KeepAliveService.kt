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

        /**
         * 从抖音 hook 进程额外建立一条绑定，作为保活主力。
         *
         * 实测 HyperOS 直接拒绝后台 FGS：
         *   W ActivityManager: Service.startForeground() not allowed due to bg restriction
         * 前台服务拿不到（startForegroundCount=0），本模块进程只能以普通后台服务身份存活，
         * 易被省电策略回收。而"被前台应用绑定"的服务进程优先级由客户端决定 ——
         * 抖音是前台应用，绑定后本模块进程不会被冻结/回收，ContentProvider 始终在线，
         * 抖音读设置时无需唤醒模块进程，WakePathChecker 的 AutoStart Reject 也就不会触发。
         *
         * 故意不解绑：连接随抖音进程死亡由系统自动释放，不会泄漏；多次调用也只是重复绑定同一连接。
         */
        fun bindFromHook(context: Context) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(MODULE_PACKAGE, "$MODULE_PACKAGE.KeepAliveService")
                }
                val ok = context.bindService(intent, keepAliveConn, Context.BIND_AUTO_CREATE)
                com.xposed.doupp.util.HookUtils.log("KeepAliveService: bindFromHook ok=$ok")
            } catch (t: Throwable) {
                com.xposed.doupp.util.HookUtils.log("KeepAliveService: bindFromHook 失败: ${t.message}")
            }
        }

        /** 故意常驻：绑定要一直维持到抖音进程死亡，不能解绑 */
        private val keepAliveConn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                com.xposed.doupp.util.HookUtils.log("KeepAliveService: 绑定建立，模块进程已挂靠前台应用")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                com.xposed.doupp.util.HookUtils.log("KeepAliveService: 绑定断开（模块进程被回收？）")
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

    /**
     * 返回非 null 的 Binder。
     *
     * 抖音 hook 进程会 bindService() 到本服务（见 companion.bindFromHook），
     * 借此把本模块进程钉在"被前台应用绑定"的优先级上。返回 null 会让绑定立即断开，
     * 保活效果归零，因此这里必须给出一个真实 Binder。
     */
    private val binding = android.os.Binder()

    override fun onBind(intent: Intent?): IBinder? = binding

    /** 是否已成功拿到前台身份 */
    @Volatile
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        // ★ 必须先拿前台身份，再碰任何可能阻塞的逻辑。
        // DouSettings.init 会做跨进程 prefs 探测（ContextHelper / ContentProvider 调用带 3s 超时），
        // 实测抖音进程内这一段耗时 3.2s；本服务是 startForegroundService 拉起的，
        // 超时 5s 仍未调用 startForeground，系统就抛
        // ForegroundServiceDidNotStartInTimeException 并强杀进程
        //（实测 15:21:49 KeepAliveService 正是这样挂的，还被 MiSight 当崩溃上报）。
        ensureForeground()
        // 创建 prefs 文件并设为 world-readable（首次启动时由模块设置页调用 init，
        // 但若用户从未打开设置页，此处兜底创建）
        DouSettings.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        // 系统以 START_STICKY 重启本服务时前台身份已丢失，需重新争取
        ensureForeground()
        // 被杀后系统会尝试拉起（前台服务优先级高，小米通常保留）
        return START_STICKY
    }

    /**
     * 争取前台身份。
     *
     * manifest 已把类型从 dataSync 换成 specialUse（Android 14+ 禁止 dataSync 型
     * 从 BOOT_COMPLETED 启动，startForeground 会抛 ForegroundServiceStartNotAllowedException，
     * 未捕获时整个进程 FATAL，实测 15 上开机必崩）。
     *
     * 但小米自启动/省电策略仍可能拒绝；此时必须 stopSelf()：本服务是经
     * startForegroundService 拉起的，若迟迟不调用 startForeground，ActivityManager
     * 会在 5s 后记 "Stop FGS timeout" 并强杀（实测 KeepAliveService 正是被这条掐掉）。
     * 主动停服务比被系统超时杀掉干净，也避免 ANR 记录。
     * 降级后模块进程仍可在设置页打开 / 抖音唤醒时提供 ContentProvider。
     */
    private fun ensureForeground() {
        // ★ 绝不能用 isForeground 提前 return！
        // 系统每次 startForegroundService() 都会把 ServiceRecord.fgRequired 置回 true，
        // 要求应用在 5s 内**再次**调用 startForeground()。首次启动已成功拿到前台身份后，
        // 若后续 startFromHook 触发的再次启动被 if (isForeground) return 吞掉，
        // 系统就判超时 → ForegroundServiceDidNotStartInTimeException → 整个模块进程被杀
        //（实测 15:25:04 就是这么挂的，ServiceRecord{ebcf6a}，pid 16942 主线程）。
        // startForeground() 幂等，重复调用只会刷新同一个通知，无副作用。
        try {
            startForeground(NOTIF_ID, buildNotification())
            isForeground = true
            com.xposed.doupp.util.HookUtils.log("KeepAliveService: startForeground 成功")
        } catch (t: Throwable) {
            com.xposed.doupp.util.HookUtils.log(
                "KeepAliveService: startForeground 被拒(${t.javaClass.simpleName})，停止服务避免 FGS 超时"
            )
            stopSelf()
        }
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

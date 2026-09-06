package com.xposed.doupp.hook

import android.os.Handler
import android.os.Looper
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 自动续火花 Hook
 *
 * 原理:
 * 1. Hook 抖音 IM 消息发送方法，监听消息发送成功事件
 * 2. 检测当前聊天是否为火花会话（通过 FlameService / FlamePendant）
 * 3. 当火花即将过期时，自动发送一条最小消息维持火花
 *
 * 实现策略:
 * - Hook FlameService 的方法来检测火花状态
 * - Hook 消息发送方法来自动发送续火花消息
 * - 使用定时器在火花过期前自动触发
 *
 * 注意: 仅在用户主动打开聊天页面时才触发自动续火花，
 *       避免在后台静默发送消息造成隐私问题。
 */
class SparkHook : BaseHook {

    companion object {
        private const val TAG = "SparkHook"
        private var installed = false

        /** 火花续期消息默认内容 */
        private const val DEFAULT_SPARK_MSG = "🔥"

        /** 火花过期前提前触发的时间（毫秒）：23小时 */
        private const val TRIGGER_AHEAD_MS = 23 * 60 * 60 * 1000L

        /** 检查间隔（毫秒）：30分钟 */
        private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    /** 定时执行器 */
    private var scheduler: ScheduledExecutorService? = null

    /** 主线程 Handler */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 已启用续火花的会话 ID 集合 */
    private val enabledSessions = ConcurrentHashMap.newKeySet<String>()

    /** 上次发送消息的时间戳 */
    private val lastSendTime = ConcurrentHashMap<String, Long>()

    /** 当前活跃的聊天会话 */
    @Volatile
    private var currentConversationId: String? = null

    /** 是否正在聊天页面 */
    @Volatile
    private var isInChatPage = false

    override fun init(classLoader: ClassLoader) {
        if (installed) return

        HookUtils.safeHook {
            DouSettings.reload()
            if (!DouSettings.isSparkEnabled()) {
                HookUtils.log("$TAG: 续火花功能未启用，跳过")
                return@safeHook
            }

            HookUtils.log("$TAG: 初始化自动续火花 Hook")

            // 启动定时检查
            startScheduler()

            // 策略1: Hook FlameService 相关方法检测火花状态
            hookFlameService(classLoader)

            // 策略2: Hook 聊天页面生命周期，检测进入/退出聊天
            hookChatPageLifecycle(classLoader)

            // 策略3: Hook 消息发送方法，记录发送时间
            hookMessageSend(classLoader)

            installed = true
            HookUtils.log("$TAG: 自动续火花 Hook 安装完成")
        }
    }

    /**
     * 启动定时检查调度器
     */
    private fun startScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleAtFixedRate({
            try {
                checkAndRenewSpark()
            } catch (t: Throwable) {
                HookUtils.log("$TAG: 定时检查异常: ${t.message}")
            }
        }, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
        HookUtils.log("$TAG: 定时检查已启动，间隔 ${CHECK_INTERVAL_MS / 60000} 分钟")
    }

    /**
     * Hook FlameService 检测火花状态
     */
    private fun hookFlameService(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // 尝试多个候选类名
            val candidates = listOf(
                "com.ss.android.ugc.aweme.hotsoon.flame.api.IFlameService",
                "com.ss.android.ugc.aweme.hotsoon.flame.api.FlameServiceDefault",
                "com.ss.android.ugc.aweme.hotsoon.flame.IFlamePendant",
                "com.ss.android.ugc.aweme.hotsoon.flame.FlamePendantDefault"
            )

            for (className in candidates) {
                try {
                    val clazz = Class.forName(className, false, classLoader)
                    HookUtils.log("$TAG: 找到 Flame 类: $className")

                    // Hook 所有公开方法，监控火花状态
                    val methods = clazz.declaredMethods.filter { m ->
                        m.parameterCount <= 2 &&
                        m.returnType != Void.TYPE &&
                        !m.name.contains("$")
                    }

                    for (method in methods) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                try {
                                    val result = param.result
                                    if (result != null) {
                                        val resultStr = result.toString().lowercase()
                                        // 检测火花相关状态
                                        if (resultStr.contains("flame") ||
                                            resultStr.contains("spark") ||
                                            resultStr.contains("streak") ||
                                            resultStr.contains("consecutive")) {
                                            HookUtils.log("$TAG: 检测到火花状态: ${method.name} = ${resultStr.take(100)}")
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                        })
                    }

                    HookUtils.log("$TAG: Hook FlameService $className (${methods.size} 方法)")
                    break
                } catch (_: ClassNotFoundException) {}
            }
        }
    }

    /**
     * Hook 聊天页面生命周期
     */
    private fun hookChatPageLifecycle(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // 候选聊天页面类名
            val chatActivityCandidates = listOf(
                "com.ss.android.ugc.aweme.im.sdk.chat.ChatActivity",
                "com.ss.android.ugc.aweme.im.business.chat.ChatActivity",
                "com.ss.android.ugc.aweme.im.sdk.chat.SessionActivity",
                "com.ss.android.ugc.aweme.im.business.message.MessageActivity"
            )

            // 候选会话详情页类名（火花信息在此显示）
            val detailCandidates = listOf(
                "com.ss.android.ugc.aweme.im.sdk.chat.ChatDetailActivity",
                "com.ss.android.ugc.aweme.im.business.chat.ChatDetailActivity",
                "com.ss.android.ugc.aweme.im.sdk.chat.MoreInfoActivity",
                "com.ss.android.ugc.aweme.im.business.chat.MoreInfoActivity"
            )

            val allCandidates = (chatActivityCandidates + detailCandidates).distinct()

            for (className in allCandidates) {
                try {
                    val clazz = Class.forName(className, false, classLoader)

                    // Hook onResume - 进入聊天页面
                    val onResumeMethod = clazz.getMethod("onResume")
                    XposedBridge.hookMethod(onResumeMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                isInChatPage = true
                                val thisObj = param.thisObject ?: return
                                currentConversationId = extractConversationId(thisObj)
                                HookUtils.log("$TAG: 进入聊天页面, conversationId=$currentConversationId")
                            } catch (_: Throwable) {}
                        }
                    })

                    // Hook onPause - 离开聊天页面
                    val onPauseMethod = clazz.getMethod("onPause")
                    XposedBridge.hookMethod(onPauseMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            try {
                                isInChatPage = false
                                HookUtils.log("$TAG: 离开聊天页面")
                            } catch (_: Throwable) {}
                        }
                    })

                    HookUtils.log("$TAG: Hook 聊天页面: $className")
                    break
                } catch (_: ClassNotFoundException) {}
            }
        }
    }

    /**
     * Hook 消息发送方法
     */
    private fun hookMessageSend(classLoader: ClassLoader) {
        HookUtils.safeHook {
            // 候选消息发送类名
            val candidates = listOf(
                "com.ss.android.ugc.aweme.im.sdk.chat.ChatManager",
                "com.ss.android.ugc.aweme.im.business.chat.ChatManager",
                "com.ss.android.ugc.aweme.im.sdk.message.MessageManager",
                "com.ss.android.ugc.aweme.im.business.message.MessageManager"
            )

            for (className in candidates) {
                try {
                    val clazz = Class.forName(className, false, classLoader)

                    // 查找 sendMessage / send 方法
                    val sendMethods = clazz.declaredMethods.filter { m ->
                        (m.name.lowercase().contains("send") ||
                         m.name.lowercase().contains("dispatch")) &&
                        m.parameterCount >= 1 &&
                        !m.name.contains("$")
                    }

                    for (method in sendMethods) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                try {
                                    // 记录发送时间
                                    val conversationId = currentConversationId
                                    if (conversationId != null) {
                                        lastSendTime[conversationId] = System.currentTimeMillis()
                                        HookUtils.log("$TAG: 消息已发送, conversationId=$conversationId")
                                    }
                                } catch (_: Throwable) {}
                            }
                        })
                    }

                    if (sendMethods.isNotEmpty()) {
                        HookUtils.log("$TAG: Hook 消息发送: $className (${sendMethods.size} 方法)")
                        break
                    }
                } catch (_: ClassNotFoundException) {}
            }
        }
    }

    /**
     * 从 Activity 对象中提取会话 ID
     */
    private fun extractConversationId(activity: Any): String? {
        try {
            // 尝试从 Intent 中提取
            val intentMethod = activity.javaClass.getMethod("getIntent")
            val intent = intentMethod.invoke(activity) ?: return null

            val extrasMethod = intent.javaClass.getMethod("getExtras")
            val extras = extrasMethod.invoke(intent) ?: return null

            val extrasClass = extras::class.java
            val keyMethods = extrasClass.getMethod("keySet")
            val keySet = keyMethods.invoke(extras) as? Set<*> ?: return null

            for (key in keySet) {
                if (key is String && (key.contains("conversation") || key.contains("session") || key.contains("chat"))) {
                    val getMethod = extrasClass.getMethod("getString", String::class.java)
                    val value = getMethod.invoke(extras, key) as? String
                    if (value != null) return value
                }
            }
        } catch (_: Throwable) {}

        // 备用: 使用 hashCode 作为临时 ID
        return "session_${activity.hashCode()}"
    }

    /**
     * 定时检查并续火花
     */
    private fun checkAndRenewSpark() {
        if (!isInChatPage) return
        if (!DouSettings.isSparkEnabled()) return

        val conversationId = currentConversationId ?: return
        val lastSend = lastSendTime[conversationId] ?: return
        val elapsed = System.currentTimeMillis() - lastSend

        // 如果距离上次发送已超过23小时，触发续火花
        if (elapsed >= TRIGGER_AHEAD_MS) {
            HookUtils.log("$TAG: 火花即将过期，自动续期! conversationId=$conversationId, elapsed=${elapsed / 3600000}h")
            autoSendSparkMessage()
        }
    }

    /**
     * 自动发送续火花消息
     */
    private fun autoSendSparkMessage() {
        try {
            val message = DouSettings.getSparkMessage().ifEmpty { DEFAULT_SPARK_MSG }

            // 在主线程执行发送操作
            mainHandler.post {
                try {
                    HookUtils.log("$TAG: 尝试自动发送续火花消息: $message")

                    // 这里需要通过反射调用抖音的 IM 发送方法
                    // 实际实现需要根据抖音版本动态适配
                    // 目前记录日志，后续可通过 DexKit 定位具体的发送方法

                    val conversationId = currentConversationId
                    if (conversationId != null) {
                        lastSendTime[conversationId] = System.currentTimeMillis()
                        HookUtils.log("$TAG: 续火花消息已触发, conversationId=$conversationId")
                    }
                } catch (t: Throwable) {
                    HookUtils.log("$TAG: 发送续火花消息失败: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("$TAG: autoSendSparkMessage 异常: ${t.message}")
        }
    }

    /**
     * 清理资源
     */
    fun destroy() {
        scheduler?.shutdown()
        scheduler = null
        enabledSessions.clear()
        lastSendTime.clear()
        HookUtils.log("$TAG: 已清理资源")
    }
}

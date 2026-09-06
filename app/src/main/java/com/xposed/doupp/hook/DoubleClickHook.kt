package com.xposed.doupp.hook

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import com.xposed.doupp.compat.XposedHelpers

/**
 * 双击行为 Hook — 自定义双击视频播放区域的行为
 *
 * 功能:
 * - 点赞（默认）
 * - 打开评论
 * - 分享
 * - 无操作
 *
 * 原理:
 * - Hook Activity 的 dispatchTouchEvent
 * - 检测双击手势（两次点击间隔 < 300ms）
 * - 根据设置执行对应操作
 */
class DoubleClickHook : BaseHook {

    companion object {
        private const val TAG = "DoubleClick"

        @Volatile
        private var installed = false

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var lastDownTime = 0L

        @Volatile
        private var lastDownX = 0f

        @Volatile
        private var lastDownY = 0f

        /** 上一次已处理的触摸序列 downTime，用于去重（抖音可能对同一次触摸派发多次 ACTION_DOWN） */
        @Volatile
        private var lastProcessedDownTime = -1L

        @Volatile
        private var lastPrefsDbg = 0L

        private const val DOUBLE_CLICK_INTERVAL_MS = 300L
        private const val DOUBLE_CLICK_TOLERANCE = 50f
    }

    override fun tag() = TAG
    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return

        HookUtils.safeHook {
            hookDispatchTouchEvent(classLoader)
            installed = true
            HookUtils.log("$TAG: 安装完成, action=${DouSettings.getDoubleClickAction()}")
            HookUtils.log("$TAG: [DBG] provider keys=${DouSettings.debugPrefsKeys()}")
        }
    }

    private fun hookDispatchTouchEvent(classLoader: ClassLoader) {
        // 主入口1：MainActivity.dispatchTouchEvent —— 抖音 39.8 覆写了该方法，
        // 且当 mMotionFilter 拦截事件时会直接 return true，不 invoke-super，
        // 因此框架 Activity.dispatchTouchEvent 的 hook 可能收不到事件。
        var mainHooked = false
        try {
            val mainActivityClass = XposedHelpers.findClass(
                "com.ss.android.ugc.aweme.main.MainActivity", classLoader
            )
            XposedBridge.hookAllMethods(mainActivityClass, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try { onTouch(param) } catch (_: Throwable) {}
                }
            })
            HookUtils.log("$TAG: Hook MainActivity.dispatchTouchEvent")
            mainHooked = true
        } catch (t: Throwable) {
            HookUtils.log("$TAG: Hook MainActivity 失败: ${t.message}")
        }

        // 主入口2：框架 Activity.dispatchTouchEvent 兜底（仅当 MainActivity hook 失败时，
        // 避免 invoke-super 链路对同一事件触发两次导致误判）
        if (!mainHooked) {
            try {
                val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)
                XposedBridge.hookAllMethods(activityClass, "dispatchTouchEvent", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try { onTouch(param) } catch (_: Throwable) {}
                    }
                })
                HookUtils.log("$TAG: Hook Activity.dispatchTouchEvent")
            } catch (t: Throwable) {
                HookUtils.log("$TAG: Hook dispatchTouchEvent 失败: ${t.message}")
            }
        }
    }

    private fun onTouch(param: XC_MethodHook.MethodHookParam) {
        val action = DouSettings.getDoubleClickAction()
        if (action == "like") {
            // 运行时每 3 秒打印一次 prefs 状态，排查设置同步
            val now = System.currentTimeMillis()
            if (now - lastPrefsDbg > 3000L) {
                lastPrefsDbg = now
                HookUtils.log("$TAG: action=like, ${DouSettings.debugPrefsKeys()}")
            }
            return
        }

        val ev = param.args[0] as? MotionEvent ?: return
        if (ev.action != MotionEvent.ACTION_DOWN) return

        // 去重：同一次物理触摸（相同 downTime）可能被 dispatchTouchEvent 多次派发，
        // 只处理第一个 ACTION_DOWN，避免误判为双击。
        if (ev.downTime == lastProcessedDownTime) return
        lastProcessedDownTime = ev.downTime

        val now = System.currentTimeMillis()
        val x = ev.x
        val y = ev.y

        HookUtils.log("$TAG: down x=$x y=$y dt=${now - lastDownTime}ms")
        if (now - lastDownTime < DOUBLE_CLICK_INTERVAL_MS &&
            Math.abs(x - lastDownX) < DOUBLE_CLICK_TOLERANCE &&
            Math.abs(y - lastDownY) < DOUBLE_CLICK_TOLERANCE
        ) {
            val activity = param.thisObject as? android.app.Activity
            if (activity != null && isMainFeedActivity(activity)) {
                HookUtils.log("$TAG: 命中双击，执行 action=${DouSettings.getDoubleClickAction()}")
                handleDoubleClick(activity, x, y)
                lastDownTime = 0L
                param.result = true
                return
            }
        }

        lastDownTime = now
        lastDownX = x
        lastDownY = y
    }

    private fun isMainFeedActivity(activity: android.app.Activity): Boolean {
        return try {
            val name = activity.javaClass.name
            name.contains("MainActivity") || name.contains("main")
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleDoubleClick(activity: android.app.Activity, x: Float, y: Float) {
        try {
            val action = DouSettings.getDoubleClickAction()
            HookUtils.log("$TAG: 双击检测到 action=$action")

            when (action) {
                "comment" -> openComment(activity)
                "share" -> openShare(activity)
                "none" -> {}
                "like" -> {}
            }
        } catch (_: Throwable) {}
    }

    private fun openComment(activity: android.app.Activity) {
        try {
            val decorView = activity.window.decorView
            clickViewByDescription(decorView, "评论")
            clickViewByDescription(decorView, "评论区")
            HookUtils.log("$TAG: 打开评论")
        } catch (_: Throwable) {}
    }

    private fun openShare(activity: android.app.Activity) {
        try {
            val decorView = activity.window.decorView
            clickViewByDescription(decorView, "分享")
            HookUtils.log("$TAG: 打开分享")
        } catch (_: Throwable) {}
    }

    private fun clickViewByDescription(root: View, text: String): Boolean {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && desc.contains(text) && child.isShown && child.isClickable) {
                        val location = IntArray(2)
                        child.getLocationOnScreen(location)
                        if (location[1] > 0) {
                            child.performClick()
                            return true
                        }
                    }
                    if (child is ViewGroup) {
                        if (clickViewByDescription(child, text)) return true
                    }
                }
            }
        } catch (_: Throwable) {}
        return false
    }
}

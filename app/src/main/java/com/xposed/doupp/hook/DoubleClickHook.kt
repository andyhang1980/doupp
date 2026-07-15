package com.xposed.doupp.hook

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

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
            HookUtils.log("$TAG: 安装完成")
        }
    }

    private fun hookDispatchTouchEvent(classLoader: ClassLoader) {
        try {
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)
            XposedBridge.hookAllMethods(activityClass, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val action = DouSettings.getDoubleClickAction()
                        if (action == "like") return

                        val ev = param.args[0] as? MotionEvent ?: return
                        if (ev.action == MotionEvent.ACTION_DOWN) {
                            val now = System.currentTimeMillis()
                            val x = ev.x
                            val y = ev.y

                            if (now - lastDownTime < DOUBLE_CLICK_INTERVAL_MS &&
                                Math.abs(x - lastDownX) < DOUBLE_CLICK_TOLERANCE &&
                                Math.abs(y - lastDownY) < DOUBLE_CLICK_TOLERANCE
                            ) {
                                val activity = param.thisObject as? android.app.Activity
                                if (activity != null && isMainFeedActivity(activity)) {
                                    handleDoubleClick(activity, x, y)
                                    lastDownTime = 0L
                                    return
                                }
                            }

                            lastDownTime = now
                            lastDownX = x
                            lastDownY = y
                        }
                    } catch (_: Throwable) {}
                }
            })
            HookUtils.log("$TAG: Hook Activity.dispatchTouchEvent")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: Hook dispatchTouchEvent 失败: ${t.message}")
        }
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

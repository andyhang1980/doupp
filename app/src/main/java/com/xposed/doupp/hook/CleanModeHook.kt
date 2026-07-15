package com.xposed.doupp.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 清爽模式 Hook — 隐藏界面元素，专注内容观看
 *
 * 功能:
 * - 隐藏引导提示
 * - 隐藏评论入口
 * - 隐藏分享入口
 * - 隐藏点赞按钮
 * - 隐藏底部导航栏
 * - 隐藏顶部导航栏
 * - 隐藏音乐唱片
 *
 * 原理:
 * - Hook View 添加/布局过程，遍历 View 树查找需要隐藏的元素
 * - 通过 contentDescription 特征识别目标控件
 */
class CleanModeHook : BaseHook {

    companion object {
        private const val TAG = "CleanMode"

        @Volatile
        private var installed = false

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var lastApplyTime = 0L

        private const val APPLY_INTERVAL_MS = 2000L

        @Volatile
        private var lastCleanModeEnabled = false

        @Volatile
        private var lastHideGuide = false
        @Volatile
        private var lastHideCommentEntry = false
        @Volatile
        private var lastHideShareEntry = false
        @Volatile
        private var lastHideLikeButton = false
        @Volatile
        private var lastHideBottomTab = false
        @Volatile
        private var lastHideTopBar = false
        @Volatile
        private var lastHideMusicDisc = false

        private const val SETTINGS_CHECK_INTERVAL_MS = 5000L
    }

    override fun tag() = TAG
    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return

        HookUtils.safeHook {
            hookViewAdd(classLoader)
            installed = true

            val settingsCheckRunnable = object : Runnable {
                override fun run() {
                    try {
                        checkSettingsChanged()
                    } catch (_: Throwable) {}
                    mainHandler.postDelayed(this, SETTINGS_CHECK_INTERVAL_MS)
                }
            }
            mainHandler.postDelayed(settingsCheckRunnable, SETTINGS_CHECK_INTERVAL_MS)

            HookUtils.log("$TAG: 安装完成")
        }
    }

    private fun hookViewAdd(classLoader: ClassLoader) {
        // 不再 Hook ViewGroup.addView（全局Hook会导致抖音启动时大量触发，引起闪退）
        // 只 Hook Activity.onResume，在页面切换时应用清爽模式
        try {
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)
            XposedBridge.hookAllMethods(activityClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!DouSettings.isCleanModeEnabled()) return
                        val activity = param.thisObject as? android.app.Activity ?: return
                        val name = activity.javaClass.name
                        // 只在抖音主Activity中应用，避免影响其他Activity
                        if (!name.contains("MainActivity") && !name.contains("main")) return
                        scheduleApplyCleanMode()
                    } catch (_: Throwable) {}
                }
            })
            HookUtils.log("$TAG: Hook Activity.onResume")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: Hook Activity.onResume 失败: ${t.message}")
        }
    }

    private fun checkSettingsChanged() {
        try {
            val enabled = DouSettings.isCleanModeEnabled()
            val hideGuide = DouSettings.isHideGuide()
            val hideCommentEntry = DouSettings.isHideCommentEntry()
            val hideShareEntry = DouSettings.isHideShareEntry()
            val hideLikeButton = DouSettings.isHideLikeButton()
            val hideBottomTab = DouSettings.isHideBottomTab()
            val hideTopBar = DouSettings.isHideTopBar()
            val hideMusicDisc = DouSettings.isHideMusicDisc()

            var changed = false
            if (enabled != lastCleanModeEnabled) changed = true
            if (enabled && hideGuide != lastHideGuide) changed = true
            if (enabled && hideCommentEntry != lastHideCommentEntry) changed = true
            if (enabled && hideShareEntry != lastHideShareEntry) changed = true
            if (enabled && hideLikeButton != lastHideLikeButton) changed = true
            if (enabled && hideBottomTab != lastHideBottomTab) changed = true
            if (enabled && hideTopBar != lastHideTopBar) changed = true
            if (enabled && hideMusicDisc != lastHideMusicDisc) changed = true

            if (changed) {
                lastCleanModeEnabled = enabled
                lastHideGuide = hideGuide
                lastHideCommentEntry = hideCommentEntry
                lastHideShareEntry = hideShareEntry
                lastHideLikeButton = hideLikeButton
                lastHideBottomTab = hideBottomTab
                lastHideTopBar = hideTopBar
                lastHideMusicDisc = hideMusicDisc

                HookUtils.log("$TAG: 设置变化，重新应用清爽模式")
                scheduleApplyCleanMode()
            }
        } catch (_: Throwable) {}
    }

    private fun scheduleApplyCleanMode() {
        val now = System.currentTimeMillis()
        if (now - lastApplyTime < APPLY_INTERVAL_MS) return
        lastApplyTime = now

        mainHandler.postDelayed({
            try {
                applyCleanMode()
            } catch (_: Throwable) {}
        }, 500)
    }

    private fun applyCleanMode() {
        try {
            val activity = com.xposed.doupp.util.ContextHelper.getCurrentActivity() ?: return
            val decorView = activity.window.decorView ?: return

            if (!DouSettings.isCleanModeEnabled()) {
                restoreAllViews(decorView)
                return
            }

            if (DouSettings.isHideCommentEntry()) {
                hideViewByDescription(decorView, "评论")
                hideViewByDescription(decorView, "评论区")
            } else {
                showViewByDescription(decorView, "评论")
                showViewByDescription(decorView, "评论区")
            }

            if (DouSettings.isHideShareEntry()) {
                hideViewByDescription(decorView, "分享")
            } else {
                showViewByDescription(decorView, "分享")
            }

            if (DouSettings.isHideLikeButton()) {
                hideViewByDescription(decorView, "点赞")
                hideViewByDescription(decorView, "取消点赞")
            } else {
                showViewByDescription(decorView, "点赞")
                showViewByDescription(decorView, "取消点赞")
            }

            if (DouSettings.isHideMusicDisc()) {
                hideMusicDisc(decorView)
            } else {
                showMusicDisc(decorView)
            }

            if (DouSettings.isHideBottomTab()) {
                hideBottomTab(decorView)
            } else {
                showBottomTab(decorView)
            }

            if (DouSettings.isHideTopBar()) {
                hideTopBar(decorView)
            } else {
                showTopBar(decorView)
            }

            if (DouSettings.isHideGuide()) {
                hideGuideTips(decorView)
            } else {
                showGuideTips(decorView)
            }
        } catch (_: Throwable) {}
    }

    private fun restoreAllViews(root: View) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    if (child.visibility != View.VISIBLE) {
                        child.visibility = View.VISIBLE
                    }
                    if (child is ViewGroup) {
                        restoreAllViews(child)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hideViewByDescription(root: View, text: String) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && desc.contains(text)) {
                        child.visibility = View.GONE
                    }
                    if (child is ViewGroup) {
                        hideViewByDescription(child, text)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hideMusicDisc(root: View) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && (desc.contains("唱片") || desc.contains("音乐") || desc.contains("原声"))) {
                        if (child is ViewGroup && child.childCount > 0) {
                            child.visibility = View.GONE
                        }
                    }
                    if (child is ViewGroup) {
                        hideMusicDisc(child)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hideBottomTab(root: View) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && (desc.contains("首页") || desc.contains("朋友") ||
                                desc.contains("消息") || desc.contains("我") ||
                                desc.contains("tab") || desc.contains("Tab"))) {
                        if (child is ViewGroup) {
                            val parent = child.parent
                            if (parent is ViewGroup && parent.childCount >= 3) {
                                parent.visibility = View.GONE
                            }
                        }
                    }
                    if (child is ViewGroup) {
                        hideBottomTab(child)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hideTopBar(root: View) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && (desc.contains("关注") || desc.contains("推荐") ||
                                desc.contains("同城") || desc.contains("发现"))) {
                        if (child is ViewGroup && child.childCount >= 2) {
                            val parent = child.parent
                            if (parent is ViewGroup) {
                                parent.visibility = View.GONE
                            }
                        }
                    }
                    if (child is ViewGroup) {
                        hideTopBar(child)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hideGuideTips(root: View) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && (desc.contains("引导") || desc.contains("提示") ||
                                desc.contains("上滑") || desc.contains("下滑") ||
                                desc.contains("双击") || desc.contains("新手"))) {
                        child.visibility = View.GONE
                    }
                    if (child is ViewGroup) {
                        hideGuideTips(child)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun showViewByDescription(root: View, text: String) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && desc.contains(text)) {
                        child.visibility = View.VISIBLE
                    }
                    if (child is ViewGroup) {
                        showViewByDescription(child, text)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun showMusicDisc(root: View) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && (desc.contains("唱片") || desc.contains("音乐") || desc.contains("原声"))) {
                        if (child is ViewGroup && child.childCount > 0) {
                            child.visibility = View.VISIBLE
                        }
                    }
                    if (child is ViewGroup) {
                        showMusicDisc(child)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun showBottomTab(root: View) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && (desc.contains("首页") || desc.contains("朋友") ||
                                desc.contains("消息") || desc.contains("我") ||
                                desc.contains("tab") || desc.contains("Tab"))) {
                        if (child is ViewGroup) {
                            val parent = child.parent
                            if (parent is ViewGroup && parent.childCount >= 3) {
                                parent.visibility = View.VISIBLE
                            }
                        }
                    }
                    if (child is ViewGroup) {
                        showBottomTab(child)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun showTopBar(root: View) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && (desc.contains("关注") || desc.contains("推荐") ||
                                desc.contains("同城") || desc.contains("发现"))) {
                        if (child is ViewGroup && child.childCount >= 2) {
                            val parent = child.parent
                            if (parent is ViewGroup) {
                                parent.visibility = View.VISIBLE
                            }
                        }
                    }
                    if (child is ViewGroup) {
                        showTopBar(child)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun showGuideTips(root: View) {
        try {
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val child = root.getChildAt(i) ?: continue
                    val desc = child.contentDescription
                    if (desc != null && (desc.contains("引导") || desc.contains("提示") ||
                                desc.contains("上滑") || desc.contains("下滑") ||
                                desc.contains("双击") || desc.contains("新手"))) {
                        child.visibility = View.VISIBLE
                    }
                    if (child is ViewGroup) {
                        showGuideTips(child)
                    }
                }
            }
        } catch (_: Throwable) {}
    }
}

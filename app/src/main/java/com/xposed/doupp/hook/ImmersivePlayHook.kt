package com.xposed.doupp.hook

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaCache
import com.xposed.doupp.util.MediaDownloader
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 沉浸式纯净播放 Hook（移植自 douyim 抖仙人模块）
 *
 * 功能:
 * - 播放视频时隐藏视频画面以外的抖音界面与系统栏，只保留视频画面（沉浸式）
 * - 暂停视频后立即恢复完整界面，并在右侧操作栏顶部显示半透明悬浮下载按钮，
 *   点击即可无水印保存当前视频
 * - 播放器状态跟踪：通过 TTVideoEngine 系列 play/pause/stop 与 VideoEngineListener
 *   回调识别真实的播放/暂停/完成状态
 * - 界面处理在运行时定位当前 Activity 的播放器视图与右侧操作栏，不依赖易变的资源 ID
 *
 * 独立开关: DouSettings.isImmersiveModeEnabled()（默认关闭）
 */
class ImmersivePlayHook : BaseHook {

    companion object {
        private const val TAG = "Immersive"
        private const val SCAN_INTERVAL_MS = 100L
        private const val DURATION_MS = 60 * 60 * 1000L

        @Volatile
        private var installed = false

        private val mainHandler = Handler(Looper.getMainLooper())

        // ==================== 活动 Activity 跟踪 ====================
        @Volatile
        private var activeActivity: Activity? = null

        // ==================== 播放状态 ====================
        @Volatile
        private var isPlaying = false
        @Volatile
        private var userPaused = false
        @Volatile
        private var lastStateUpdateAt = 0L

        // ==================== 沉浸式扫描 ====================
        private val hiddenViews = Collections.synchronizedMap(WeakHashMap<View, SavedView>())
        private val rootSystemUi = Collections.synchronizedMap(WeakHashMap<View, Int>())
        private val expandedViewports = Collections.synchronizedMap(WeakHashMap<View, ViewGroup.LayoutParams>())

        @Volatile
        private var scanScheduled = false

        // ==================== 悬浮下载按钮 ====================
        private var downloadButton: TextView? = null

        // ==================== 合成滑动 ====================
        @Volatile
        private var swipeRunning = false
        @Volatile
        private var lastSwipeAt = 0L

        private var videoMissingLogged = false
        private var transitionBoostUntil = 0L
        private var lastScanFailureAt = 0L

        // ==================== 触摸跟踪 ====================
        private var touchInProgress = false
        private var touchDownX = 0f
        private var touchDownY = 0f
        private var touchDownAt = 0L
        private var lastHandledTouchUpAt = 0L
    }

    override fun tag() = TAG
    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        HookUtils.safeHook {
            installActivityLifecycleHooks(classLoader)
            installPlayerHooks(classLoader)
            installed = true
            HookUtils.log("$TAG: 安装完成（开关=${DouSettings.isImmersiveModeEnabled()}）")
        }
    }

    // ==================== Activity 生命周期 ====================

    private fun installActivityLifecycleHooks(classLoader: ClassLoader) {
        val activityClass = try {
            com.xposed.doupp.compat.XposedHelpers.findClass("android.app.Activity", classLoader)
        } catch (_: Throwable) {
            android.app.Activity::class.java
        }

        XposedBridge.hookAllMethods(activityClass, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                mainHandler.post {
                    activeActivity = activity
                    if (DouSettings.isImmersiveModeEnabled()) {
                        scheduleScan(100L)
                    }
                }
            }
        })

        XposedBridge.hookAllMethods(activityClass, "onPause", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                mainHandler.post {
                    if (activeActivity === activity) {
                        restoreAll(activity, leaveActivity = true)
                        removeDownloadButton()
                        activeActivity = null
                    }
                }
            }
        })

        XposedBridge.hookAllMethods(activityClass, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val activity = param.thisObject as? Activity ?: return
                    val event = param.args.getOrNull(0) as? MotionEvent ?: return
                    beforeActivityTouch(activity, event)
                } catch (_: Throwable) {}
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val activity = param.thisObject as? Activity ?: return
                    val event = param.args.getOrNull(0) as? MotionEvent ?: return
                    onActivityTouch(activity, event)
                } catch (_: Throwable) {}
            }
        })

        HookUtils.log("$TAG: Activity 生命周期 Hook 已安装")
    }

    // ==================== 播放器状态跟踪 ====================

    private fun installPlayerHooks(classLoader: ClassLoader) {
        val engineClasses = listOf(
            "com.ss.ttvideoengine.TTVideoEngine",
            "com.ss.ttvideoengine.TTVideoEngineImpl",
            "com.ss.ttvideoengine.TTVideoEngineImplV2",
            "com.ss.android.ugc.aweme.player.sdk.impl.SimplifyPlayerImpl"
        )
        var hooked = 0
        for (className in engineClasses) {
            try {
                val clazz = com.xposed.doupp.compat.XposedHelpers.findClass(className, classLoader)
                hooked += hookEngineClass(clazz)
                HookUtils.log("$TAG: 播放器类挂载 $className")
            } catch (t: Throwable) {
                HookUtils.log("$TAG: 播放器类缺失 $className")
            }
        }
        if (hooked == 0) {
            // 兜底: 通过 DexKit 找播放器类
            try {
                val candidates = com.xposed.doupp.util.DexKitManager.findClassesByStrings(
                    listOf("play_addr")
                )
                for (name in candidates) {
                    if (!name.contains("ttvideoengine") && !name.contains("player")) continue
                    try {
                        val clazz = com.xposed.doupp.compat.XposedHelpers.findClass(name, classLoader)
                        hooked += hookEngineClass(clazz)
                        HookUtils.log("$TAG: DexKit 播放器类 $name")
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        HookUtils.log("$TAG: 播放器状态 Hook 完成，共 $hooked 个")
    }

    private fun hookEngineClass(clazz: Class<*>): Int {
        var count = 0
        for (method in clazz.declaredMethods) {
            val name = method.name
            val lower = name.lowercase()
            try {
                if (isPlayMethod(name, method)) {
                    hookState(method, true)
                    count++
                } else if (isPauseMethod(name, method)) {
                    hookState(method, false)
                    count++
                } else if (lower.contains("completion") || lower.contains("completed")) {
                    hookCompletion(method)
                    count++
                } else if (lower.startsWith("set") && lower.contains("listener")) {
                    hookListenerSetter(method)
                    count++
                }
            } catch (_: Throwable) {}
        }
        return count
    }

    private fun isPlayMethod(name: String, method: java.lang.reflect.Method): Boolean {
        return method.parameterCount == 0 &&
            (name == "play" || name == "start" || name == "resume")
    }

    private fun isPauseMethod(name: String, method: java.lang.reflect.Method): Boolean {
        return method.parameterCount == 0 &&
            (name.startsWith("pause") || name == "stop" || name == "release" || name == "releaseasync")
    }

    private fun hookState(method: java.lang.reflect.Method, playing: Boolean) {
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (playing) markPlaying() else markPaused()
                } catch (_: Throwable) {}
            }
        })
    }

    private fun hookCompletion(method: java.lang.reflect.Method) {
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try { onPlaybackCompleted() } catch (_: Throwable) {}
            }
        })
    }

    private val listenerOwners = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    private fun hookListenerSetter(method: java.lang.reflect.Method) {
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val owner = param.thisObject ?: return
                    for (arg in param.args) {
                        if (arg != null) {
                            listenerOwners[arg] = owner
                            hookListenerObject(arg)
                        }
                    }
                } catch (_: Throwable) {}
            }
        })
    }

    private fun hookListenerObject(listener: Any) {
        val clazz = listener.javaClass
        for (method in clazz.methods) {
            if (Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers)) continue
            val lower = method.name.lowercase()
            try {
                if (lower == "oncompletion" || lower == "oncompleted") {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try { onPlaybackCompleted() } catch (_: Throwable) {}
                        }
                    })
                } else if (lower == "onplaybackstatechanged") {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val state = lastInteger(param.args)
                                if (state != null) {
                                    when (state) {
                                        1 -> markPlaying()
                                        2 -> markPaused()
                                        3 -> onPlaybackError()
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    })
                }
            } catch (_: Throwable) {}
        }
    }

    private fun lastInteger(values: Array<Any?>): Int? {
        for (i in values.indices.reversed()) {
            val v = values[i]
            if (v is Int) return v
        }
        return null
    }

    // ==================== 播放状态回调 ====================

    private fun markPlaying() {
        mainHandler.post {
            val now = SystemClock.uptimeMillis()
            if (userPaused && now - lastStateUpdateAt > 1500) {
                // 用户暂停后同一视频恢复播放 → 视为用户主动继续
                userPaused = false
                removeDownloadButton()
            }
            isPlaying = true
            lastStateUpdateAt = now
            transitionBoostUntil = SystemClock.uptimeMillis() + 1200L
            onPlaybackChanged(true)
        }
    }

    private fun markPaused() {
        mainHandler.post {
            isPlaying = false
            lastStateUpdateAt = SystemClock.uptimeMillis()
            onPlaybackChanged(false)
        }
    }

    private fun onPlaybackCompleted() {
        mainHandler.post {
            if (!DouSettings.isImmersiveModeEnabled()) return@post
            isPlaying = false
            // 播放完成：自动滑动到下一个（仅沉浸模式开启时）
            val activity = activeActivity
            val decor = activity?.window?.decorView ?: return@post
            swipeToNext(decor, "播放完成")
        }
    }

    private fun onPlaybackError() {
        mainHandler.post {
            if (!DouSettings.isImmersiveModeEnabled()) return@post
            isPlaying = false
            val activity = activeActivity
            val decor = activity?.window?.decorView ?: return@post
            swipeToNext(decor, "播放错误")
        }
    }

    private fun onPlaybackChanged(playing: Boolean) {
        val activity = activeActivity
        val decor = activity?.window?.decorView
        if (decor == null) {
            scheduleScan(250L)
            return
        }
        if (playing) {
            removeDownloadButton()
            scheduleScan(0L)
        } else {
            mainHandler.postDelayed({
                val current = activeActivity
                val currentDecor = current?.window?.decorView
                if (!isPlaying && !userPaused) {
                    restoreAll(current, currentDecor)
                    showDownloadButton(current, currentDecor)
                }
            }, 500L)
        }
    }

    // ==================== 触摸处理 ====================

    private fun beforeActivityTouch(activity: Activity, event: MotionEvent) {
        if (swipeRunning || !touchInProgress) return
        if (event.actionMasked != MotionEvent.ACTION_UP) return
        if (!userPaused) return

        val decor = activeDecor(activity)
        val density = decor?.resources?.displayMetrics?.density ?: 1f
        val dx = event.rawX - touchDownX
        val dy = event.rawY - touchDownY
        if (decor != null && Math.abs(dy) > 72f * density && Math.abs(dy) > Math.abs(dx)) {
            // 用户滑动切换 → 结束暂停态
            userPaused = false
            isPlaying = true
        }
    }

    private fun onActivityTouch(activity: Activity, event: MotionEvent) {
        if (!DouSettings.isImmersiveModeEnabled()) return
        val action = event.actionMasked
        if (swipeRunning) {
            if (touchInProgress && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                touchInProgress = false
            }
            return
        }
        if (action == MotionEvent.ACTION_DOWN) {
            touchInProgress = true
            touchDownX = event.rawX
            touchDownY = event.rawY
            touchDownAt = event.eventTime
            return
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            touchInProgress = false
            return
        }
        if (action != MotionEvent.ACTION_UP) return
        touchInProgress = false

        // 仅在存在居中可见视频页（真正 feed 播放页面）时才接管触摸
        val decorCheck = activeDecor(activity)
        if (decorCheck == null || findVisibleVideoViews(decorCheck).isEmpty()) {
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastHandledTouchUpAt < 300L) return
        lastHandledTouchUpAt = now

        val dx = event.rawX - touchDownX
        val dy = event.rawY - touchDownY
        val elapsed = event.eventTime - touchDownAt
        val decor = activeDecor(activity)
        val density = decor?.resources?.displayMetrics?.density ?: 1f

        // 垂直滑动 → 切换视频，结束暂停
        if (decor != null && Math.abs(dy) > 72f * density && Math.abs(dy) > Math.abs(dx)) {
            userPaused = false
            isPlaying = true
            scheduleScan(0L)
            return
        }

        // 中心区域轻触 → 暂停/恢复
        if (decor == null ||
            dx * dx + dy * dy > 1600f ||
            elapsed > 500L ||
            event.rawX < decor.width * 0.18f ||
            event.rawX > decor.width * 0.82f ||
            event.rawY < decor.height * 0.12f ||
            event.rawY > decor.height * 0.86f) {
            return
        }

        if (userPaused) {
            // 恢复播放
            userPaused = false
            isPlaying = true
            removeDownloadButton()
            scheduleScan(0L)
            HookUtils.log("$TAG: 轻触恢复播放")
        } else {
            // 暂停
            userPaused = true
            isPlaying = false
            mainHandler.postDelayed({
                if (userPaused) {
                    val current = activeActivity
                    val currentDecor = current?.window?.decorView
                    restoreAll(current, currentDecor)
                    showDownloadButton(current, currentDecor)
                }
            }, 150L)
            HookUtils.log("$TAG: 轻触暂停")
        }
    }

    private fun activeDecor(activity: Activity?): View? {
        return activity?.window?.decorView
    }

    // ==================== 扫描 & 沉浸式 UI ====================

    private fun scheduleScan(delayMs: Long) {
        if (scanScheduled) return
        scanScheduled = true
        mainHandler.postDelayed({ scan() }, delayMs)
    }

    private fun scan() {
        scanScheduled = false
        if (!DouSettings.isImmersiveModeEnabled()) {
            restoreAll(activeActivity)
            removeDownloadButton()
            return
        }
        try {
            scanOnce()
        } catch (t: Throwable) {
            val now = SystemClock.uptimeMillis()
            if (now - lastScanFailureAt >= 2000L) {
                lastScanFailureAt = now
                HookUtils.log("$TAG: 沉浸式扫描失败: ${t.message}")
            }
        } finally {
            if (!scanScheduled) {
                scheduleScan(SCAN_INTERVAL_MS)
            }
        }
    }

    private fun scanOnce() {
        val activity = activeActivity
        val decor = activeDecor(activity)
        if (decor == null) {
            scheduleScan(250L)
            return
        }
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) return

        if (userPaused) {
            // 用户暂停：恢复完整界面并保留下载按钮，扫描不再重复隐藏
            restoreAll(activity, decor)
            scheduleNextScan()
            return
        }

        // 只保留视频画面
        val videos = findVisibleVideoViews(decor)
        if (videos.isNotEmpty()) {
            videoMissingLogged = false
            expandVideoViewport(decor, videos)
            restoreVideoPaths(videos)
            hideOutsideVideoPaths(decor, videos)
            hideSystemBars(activity, decor)
        } else {
            restoreAll(activity, decor)
            if (!videoMissingLogged) {
                videoMissingLogged = true
                HookUtils.log("$TAG: 未找到居中的可见视频 SurfaceView/TextureView")
            }
        }
        scheduleNextScan()
    }

    private fun scheduleNextScan() {
        val delay = if (SystemClock.uptimeMillis() < transitionBoostUntil) 16L else SCAN_INTERVAL_MS.toLong()
        scheduleScan(delay)
    }

    private fun findVisibleVideoViews(root: View): List<View> {
        val candidates = ArrayList<View>()
        collectVideoViews(root, candidates)
        return centeredVisibleVideoViews(root, candidates)
    }

    private fun collectVideoViews(view: View, out: MutableList<View>) {
        if (view is SurfaceView || view is TextureView) {
            out.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectVideoViews(view.getChildAt(i), out)
            }
        }
    }

    private fun centeredVisibleVideoViews(root: View, candidates: List<View>): List<View> {
        val rootVisible = Rect()
        if (!root.getGlobalVisibleRect(rootVisible)) return emptyList()
        val centerX = rootVisible.centerX()
        val centerY = rootVisible.centerY()
        val screenArea = Math.max(1L, rootVisible.width().toLong()) * Math.max(1L, rootVisible.height().toLong())

        val result = ArrayList<View>()
        val visible = Rect()
        for (candidate in candidates) {
            if (!candidate.isAttachedToWindow ||
                candidate.visibility != View.VISIBLE ||
                !candidate.isShown ||
                !candidate.getGlobalVisibleRect(visible) ||
                !visible.contains(centerX, centerY)) {
                continue
            }
            val area = visible.width().toLong() * visible.height().toLong()
            if (area >= screenArea / 5L && !result.contains(candidate)) {
                result.add(candidate)
            }
        }
        return result
    }

    private fun expandVideoViewport(decor: View, videos: List<View>) {
        for (video in videos) {
            if (expandVideoViewportFrom(decor, video)) return
        }
    }

    private fun expandVideoViewportFrom(decor: View, video: View): Boolean {
        var viewport: View = video
        while (viewport.parent is ViewGroup && (viewport.parent as View) != decor) {
            val parent = viewport.parent as View
            val targetViewport = "com.ss.android.ugc.aweme.common.widget.RTViewPager" == viewport.javaClass.name
            val missingHeight = parent.height - viewport.height
            val parentFillsWindow =
                parent.width >= decor.width * 0.9f &&
                    parent.height >= decor.height * 0.95f
            val viewportLeavesBottomSlot =
                viewport.width >= decor.width * 0.9f &&
                    missingHeight > 32 &&
                    missingHeight < decor.height / 3
            if (targetViewport && parentFillsWindow && viewportLeavesBottomSlot) {
                applyExpandedViewport(viewport)
                return true
            }
            viewport = parent
        }
        return false
    }

    private fun applyExpandedViewport(viewport: View) {
        if (expandedViewports.containsKey(viewport)) return
        val original = viewport.layoutParams ?: return
        if (original !is RelativeLayout.LayoutParams) return

        val expanded = RelativeLayout.LayoutParams(original)
        expanded.height = ViewGroup.LayoutParams.MATCH_PARENT
        expanded.topMargin = 0
        expanded.bottomMargin = 0
        expanded.removeRule(RelativeLayout.ABOVE)
        expanded.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        expanded.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        expandedViewports[viewport] = original
        viewport.layoutParams = expanded
        viewport.requestLayout()
    }

    private fun hideOutsideVideoPaths(node: View, videos: List<View>) {
        if (videos.contains(node)) return
        if (node !is ViewGroup || !containsAny(node, videos)) {
            hide(node)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i)
            if (videos.contains(child) || containsAny(child, videos)) {
                hideOutsideVideoPaths(child, videos)
            } else {
                hide(child)
            }
        }
    }

    private fun containsAny(node: View, targets: List<View>): Boolean {
        for (target in targets) {
            if (contains(node, target)) return true
        }
        return false
    }

    private fun contains(node: View, target: View): Boolean {
        if (node === target) return true
        if (node is ViewGroup) {
            for (i in 0 until node.childCount) {
                if (contains(node.getChildAt(i), target)) return true
            }
        }
        return false
    }

    private fun restoreVideoPaths(videos: List<View>) {
        synchronized(hiddenViews) {
            val entries = ArrayList(hiddenViews.entries)
            for ((view, saved) in entries) {
                if (view == null || saved == null) continue
                if (videos.contains(view) || containsAny(view, videos)) {
                    view.alpha = saved.alpha
                    view.visibility = saved.visibility
                    view.importantForAccessibility = saved.accessibility
                    hiddenViews.remove(view)
                }
            }
        }
    }

    private fun hide(view: View) {
        if (!hiddenViews.containsKey(view)) {
            hiddenViews[view] = SavedView(
                view.alpha,
                view.importantForAccessibility,
                view.visibility
            )
        }
        view.alpha = 0f
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun restoreAll(activity: Activity?, leaveActivity: Boolean = false) {
        restoreAll(activity, activeDecor(activity), leaveActivity)
    }

    private fun restoreAll(activity: Activity?, decor: View?, leaveActivity: Boolean = false) {
        if (leaveActivity) {
            restoreExpandedViewports()
        }
        synchronized(hiddenViews) {
            val entries = ArrayList(hiddenViews.entries)
            for ((view, saved) in entries) {
                if (view == null || saved == null) continue
                view.alpha = saved.alpha
                view.visibility = saved.visibility
                view.importantForAccessibility = saved.accessibility
            }
            hiddenViews.clear()
        }
        if (decor != null) {
            val systemUi = rootSystemUi.remove(decor)
            if (systemUi != null) {
                decor.systemUiVisibility = systemUi
            }
        }
    }

    private fun restoreExpandedViewports() {
        synchronized(expandedViewports) {
            val entries = ArrayList(expandedViewports.entries)
            for ((view, original) in entries) {
                if (view == null || original == null) continue
                view.layoutParams = original
                view.requestLayout()
            }
            expandedViewports.clear()
        }
    }

    private fun hideSystemBars(activity: Activity?, decor: View) {
        if (!rootSystemUi.containsKey(decor)) {
            rootSystemUi[decor] = decor.systemUiVisibility
        }
        decor.systemUiVisibility =
            decor.systemUiVisibility or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // ==================== 悬浮下载按钮 ====================

    private fun showDownloadButton(activity: Activity?, decor: View?) {
        if (activity == null || decor == null || !userPaused || decor !is FrameLayout) {
            removeDownloadButton()
            return
        }

        val current = downloadButton
        if (current != null && current.parent === decor) {
            current.visibility = View.VISIBLE
            current.bringToFront()
            return
        }
        removeDownloadButton()

        val density = decor.resources.displayMetrics.density
        val size = Math.round(52 * density)
        val verticalGap = Math.round(10 * density)
        val rightMargin = Math.round(4 * density)
        val button = TextView(activity)
        button.text = "↓\n下载"
        button.setTextColor(Color.WHITE)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        button.gravity = Gravity.CENTER
        button.includeFontPadding = false
        button.contentDescription = "下载无水印视频"
        button.isClickable = true
        button.isFocusable = true
        button.elevation = 6 * density

        val background = GradientDrawable()
        background.shape = GradientDrawable.OVAL
        background.setColor(0x73000000.toInt())
        background.setStroke(Math.round(1 * density), 0x66FFFFFF.toInt())
        button.background = background

        button.setOnClickListener {
            if (!userPaused) {
                removeDownloadButton()
                return@setOnClickListener
            }
            val currentActivity = activeActivity
            val currentDecor = currentActivity?.window?.decorView
            downloadCurrentVideo(currentActivity, currentDecor)
        }

        val params = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
        params.topMargin = resolveDownloadButtonTop(decor, size, verticalGap)
        params.rightMargin = rightMargin
        decor.addView(button, params)
        button.bringToFront()
        downloadButton = button
        HookUtils.log("$TAG: 暂停下载按钮已显示 top=${params.topMargin}")
    }

    private fun removeDownloadButton() {
        val button = downloadButton
        if (button != null) {
            hiddenViews.remove(button)
            button.setOnClickListener(null)
            val parent = button.parent
            if (parent is ViewGroup) {
                parent.removeView(button)
            }
        }
        downloadButton = null
    }

    private fun resolveDownloadButtonTop(decor: View, size: Int, gap: Int): Int {
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val rootLeft = location[0]
        val rootTop = location[1]
        var nativeTop = findRightMenuTop(decor, rootLeft, rootTop, decor.width, decor.height)
        val desired = if (nativeTop == Int.MAX_VALUE) {
            Math.round(decor.height * 0.38f)
        } else {
            nativeTop - rootTop - size - gap
        }
        val minimum = Math.round(decor.height * 0.20f)
        val maximum = Math.max(minimum, Math.round(decor.height * 0.60f))
        return Math.max(minimum, Math.min(maximum, desired))
    }

    private fun findRightMenuTop(view: View, rootLeft: Int, rootTop: Int, rootWidth: Int, rootHeight: Int): Int {
        var best = Int.MAX_VALUE
        if (view != downloadButton &&
            view.visibility == View.VISIBLE &&
            view.isShown &&
            view.alpha > 0.1f &&
            (view.isClickable || view.contentDescription != null)) {
            val visible = Rect()
            if (view.getGlobalVisibleRect(visible)) {
                val centerX = visible.centerX()
                val maxWidth = Math.min(rootWidth / 4, dp(view, 104))
                val maxHeight = dp(view, 128)
                if (centerX >= rootLeft + Math.round(rootWidth * 0.78f) &&
                    visible.top >= rootTop + Math.round(rootHeight * 0.30f) &&
                    visible.bottom <= rootTop + Math.round(rootHeight * 0.95f) &&
                    visible.width() in 1..maxWidth &&
                    visible.height() in 1..maxHeight) {
                    best = visible.top
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                best = Math.min(best, findRightMenuTop(view.getChildAt(i), rootLeft, rootTop, rootWidth, rootHeight))
            }
        }
        return best
    }

    private fun dp(view: View, value: Int): Int {
        return Math.round(value * view.resources.displayMetrics.density)
    }

    // ==================== 下载 ====================

    private fun downloadCurrentVideo(activity: Activity?, decor: View?) {
        try {
            val context: Context = activity?.applicationContext ?: run {
                HookUtils.log("$TAG: 无法获取 Context")
                return
            }
            val aweme = MediaCache.getCurrentAweme()
            if (aweme == null) {
                HookUtils.showToast(context, "当前视频地址暂不可用")
                return
            }
            val url = MediaCache.getVideoUrlFromAweme(aweme)
            if (url == null) {
                HookUtils.showToast(context, "当前视频地址暂不可用")
                return
            }
            val fileName = "douyin_${System.currentTimeMillis()}.mp4"
            HookUtils.showToast(context, "开始下载无水印视频")
            HookUtils.log("$TAG: 开始下载 ${url.take(120)}")
            MediaDownloader.download(
                context,
                url,
                fileName,
                onComplete = { uri ->
                    HookUtils.log("$TAG: 下载完成 $uri")
                    HookUtils.showToast(context, "下载完成")
                },
                onError = { t ->
                    HookUtils.log("$TAG: 下载失败 ${t.message}")
                    HookUtils.showToast(context, "下载失败，请稍后重试")
                }
            )
        } catch (t: Throwable) {
            HookUtils.log("$TAG: 下载异常 ${t.message}")
        }
    }

    // ==================== 合成滑动 ====================

    private fun swipeToNext(decor: View, reason: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (swipeRunning || !decor.isAttachedToWindow || now - lastSwipeAt < 1500L) return false
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) return false

        swipeRunning = true
        lastSwipeAt = now
        HookUtils.log("$TAG: 滑动到下一项: $reason")

        val x = width * 0.5f
        val startY = height * 0.72f
        val endY = height * 0.24f
        val downTime = SystemClock.uptimeMillis()
        try {
            dispatch(decor, downTime, downTime, MotionEvent.ACTION_DOWN, x, startY)
        } catch (t: Throwable) {
            swipeRunning = false
            return false
        }
        val steps = 8
        for (i in 1..steps) {
            val step = i
            mainHandler.postDelayed({
                if (!swipeRunning) return@postDelayed
                val fraction = step / steps.toFloat()
                val y = startY + (endY - startY) * fraction
                val action = if (step == steps) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                try {
                    dispatch(decor, downTime, SystemClock.uptimeMillis(), action, x, y)
                } catch (_: Throwable) {
                    swipeRunning = false
                }
                if (step == steps) {
                    mainHandler.postDelayed({ swipeRunning = false }, 500L)
                }
            }, (i * 22L))
        }
        return true
    }

    private fun dispatch(view: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private class SavedView(
        val alpha: Float,
        val accessibility: Int,
        val visibility: Int
    )
}

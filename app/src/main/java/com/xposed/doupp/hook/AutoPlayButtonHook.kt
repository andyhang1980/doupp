package com.xposed.doupp.hook

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class AutoPlayButtonHook : BaseHook {

    companion object {
        private const val TAG = "AutoPlayButton"
        private const val BTN_TAG = "dou_plus_autoplay_btn"
        private var installed = false
        private val mainHandler = Handler(Looper.getMainLooper())
        private const val PREFS_POS_X = "auto_play_btn_x"
        private const val PREFS_POS_Y = "auto_play_btn_y"
        private var periodicStarted = false
        private val longPressedViews = java.util.WeakHashMap<View, Boolean>()
        /** 仅本次 Activity 隐藏（不持久化，下次 onResume 自动恢复） */
        @Volatile
        private var sessionHide = false
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        HookUtils.safeHook {
            val activityClass = XposedHelpers.findClass("android.app.Activity", classLoader)
            XposedBridge.hookAllMethods(activityClass, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    mainHandler.postDelayed({ tryInject(activity) }, 600)
                    startPeriodicCheck(activity)
                }
            })
            installed = true
            HookUtils.log("$TAG: Activity.onResume Hook 已安装")
        }
    }

    private var periodicActivity: Activity? = null

    private fun startPeriodicCheck(activity: Activity) {
        if (periodicStarted) return
        periodicStarted = true
        periodicActivity = activity
        mainHandler.post(object : Runnable {
            override fun run() {
                val a = periodicActivity
                if (a == null || a.isFinishing) {
                    periodicStarted = false
                    return
                }
                tryInject(a)
                mainHandler.postDelayed(this, 3000)
            }
        })
        HookUtils.log("$TAG: 周期性检查已启动（每3秒）")
    }

    private fun isFeedActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name.lowercase()
        return name.contains("main") || name.contains("home") || name.contains("feed")
    }

    private fun tryInject(activity: Activity) {
        mainHandler.post {
            try {
                val decor = activity.window?.decorView as? ViewGroup ?: return@post
                val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return@post

                if (!isFeedActivity(activity)) {
                    content.findViewWithTag<View>(BTN_TAG)?.let {
                        content.removeView(it)
                    }
                    return@post
                }

                // 每次进入新 Activity 重置 session 隐藏（长按隐藏仅维持到当前 Activity 结束）
                sessionHide = false

                val hide = DouSettings.isAutoPlayHide()
                val existing = content.findViewWithTag<View>(BTN_TAG)
                HookUtils.log("$TAG: tryInject hide=$hide sessionHide=$sessionHide existing=${existing != null}")
                if (hide) {
                    if (existing != null) {
                        content.removeView(existing)
                        HookUtils.log("$TAG: 隐藏设置生效，移除按钮")
                    }
                    return@post
                }
                if (existing != null) return@post

                val density = activity.resources.displayMetrics.density
                val btnSize = (48 * density).toInt()
                val btn = createAutoPlayButton(activity, density)
                btn.tag = BTN_TAG

                val screenW = if (content.width > 0) content.width else decor.width
                val screenH = if (content.height > 0) content.height else decor.height

                val lp = FrameLayout.LayoutParams(btnSize, btnSize)
                if (DouSettings.isAutoPlayFloating()) {
                    val savedX = getSavedPosX(activity)
                    val savedY = getSavedPosY(activity)
                    if (savedX >= 0 && savedY >= 0 && savedX + btnSize <= screenW && savedY + btnSize <= screenH) {
                        lp.leftMargin = savedX
                        lp.topMargin = savedY
                        lp.gravity = Gravity.TOP or Gravity.START
                    } else {
                        val defaultX = (screenW * 0.92 - btnSize / 2).toInt()
                        val defaultY = (screenH * 0.44 - btnSize * 1.5).toInt()
                        lp.leftMargin = defaultX.coerceIn(0, screenW - btnSize)
                        lp.topMargin = defaultY.coerceIn(0, screenH - btnSize)
                        lp.gravity = Gravity.TOP or Gravity.START
                    }
                } else {
                    val headCenterX = 0.92
                    val headTopY = 0.44
                    val gap = (6 * density).toInt()
                    val btnX = (screenW * headCenterX - btnSize / 2).toInt()
                    val btnY = (screenH * headTopY - btnSize - gap).toInt()
                    lp.gravity = Gravity.TOP or Gravity.END
                    lp.topMargin = maxOf(0, btnY)
                    lp.marginEnd = maxOf(0, screenW - btnX - btnSize)
                }

                content.addView(btn, lp)
                HookUtils.log("$TAG: 已注入自动播放按钮 (floating=${DouSettings.isAutoPlayFloating()})")
            } catch (t: Throwable) {
                HookUtils.log("$TAG: tryInject 失败: ${t.message}")
            }
        }
    }

    private fun createAutoPlayButton(context: Context, density: Float): ImageView {
        return object : ImageView(context) {
            private var downX = 0f
            private var downY = 0f
            private var lastX = 0f
            private var lastY = 0f
            private val dragThreshold = (density * 20 + 0.5f).toInt()

            init {
                isClickable = true
                isFocusable = true
                isLongClickable = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = createCircleBg(context, DouSettings.isAutoPlayEnabled())
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                updateState(this)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!DouSettings.isAutoPlayFloating()) return super.onTouchEvent(event)

                val parent = parent as? ViewGroup ?: return super.onTouchEvent(event)
                val lp = layoutParams as? FrameLayout.LayoutParams ?: return super.onTouchEvent(event)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        lastX = event.rawX
                        lastY = event.rawY
                        longPressedViews.put(this, false)
                        parent.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        lp.leftMargin = (lp.leftMargin + dx).toInt().coerceIn(0, parent.width - width)
                        lp.topMargin = (lp.topMargin + dy).toInt().coerceIn(0, parent.height - height)
                        layoutParams = lp
                        lastX = event.rawX
                        lastY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val totalDx = event.rawX - downX
                        val totalDy = event.rawY - downY
                        if (Math.abs(totalDx) > dragThreshold || Math.abs(totalDy) > dragThreshold) {
                            savePos(context, lp.leftMargin, lp.topMargin)
                        } else if (longPressedViews.get(this) != true) {
                            performClick()
                        }
                        parent.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        parent.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
                return true
            }
        }.also { btn ->
            btn.setOnClickListener {
                DouSettings.setAutoPlay(!DouSettings.isAutoPlayEnabled())
                updateState(btn)
                val on = DouSettings.isAutoPlayEnabled()
                HookUtils.showToast(context, if (on) "自动播放: 开" else "自动播放: 关")
                HookUtils.log("$TAG: 自动播放切换为 $on")
            }
            btn.setOnLongClickListener {
                longPressedViews.put(btn, true)
                sessionHide = true
                HookUtils.showToast(context, "悬浮按钮已隐藏（切换视频后恢复）")
                HookUtils.log("$TAG: 长按 session 隐藏")
                btn.post {
                    val p = btn.parent as? ViewGroup
                    p?.removeView(btn)
                }
                true
            }
        }
    }

    private fun getSavedPosX(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences("auto_play_position", Context.MODE_PRIVATE)
            prefs.getInt(PREFS_POS_X, -1)
        } catch (_: Throwable) { -1 }
    }

    private fun getSavedPosY(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences("auto_play_position", Context.MODE_PRIVATE)
            prefs.getInt(PREFS_POS_Y, -1)
        } catch (_: Throwable) { -1 }
    }

    private fun savePos(context: Context, x: Int, y: Int) {
        try {
            context.getSharedPreferences("auto_play_position", Context.MODE_PRIVATE)
                .edit()
                .putInt(PREFS_POS_X, x)
                .putInt(PREFS_POS_Y, y)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun updateState(btn: ImageView) {
        val on = DouSettings.isAutoPlayEnabled()
        btn.background = createCircleBg(btn.context, on)
        btn.setImageDrawable(TriangleDrawable(if (on) Color.WHITE else 0x99FFFFFF.toInt()))
    }

    private fun createCircleBg(context: Context, on: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0x99000000.toInt() else 0x33000000.toInt())
        }
    }

    private class TriangleDrawable(private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val pad = w * 0.16f
            val path = Path().apply {
                moveTo(pad, pad)
                lineTo(w - pad, h / 2f)
                lineTo(pad, h - pad)
                close()
            }
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

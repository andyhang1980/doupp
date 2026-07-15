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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 播放界面自动播放按钮 Hook
 *
 * 在 Feed 播放界面注入一个「自动播放」开关按钮，点击切换自动播放状态
 * （写入 DouSettings，与设置页、AutoPlayControllerHook 共用同一状态）。
 *
 * 实现策略（不依赖具体 ViewPager / 不强制要求找到头像）:
 * 1. Hook android.app.Activity.onResume，进入界面后延迟注入。
 * 2. 通过 Activity 类名（main/home/feed）判断是否在播放页，避免误注入其它页面。
 * 3. 若能在视图树中找到作者头像（contentDescription 含「头像」、或资源名含 avatar、
 *    或右侧竖向图标列首个子 View），则把按钮定位到头像正上方；
 *    找不到头像时也按右上角默认位置注入，保证按钮一定出现。
 */
class AutoPlayButtonHook : BaseHook {

    companion object {
        private const val TAG = "AutoPlayButton"
        private const val BTN_TAG = "dou_plus_autoplay_btn"
        private var installed = false
        private val mainHandler = Handler(Looper.getMainLooper())
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
                    HookUtils.log("$TAG: onResume -> ${activity.javaClass.name}")
                    mainHandler.postDelayed({ tryInject(activity) }, 600)
                }
            })
            installed = true
            HookUtils.log("$TAG: Activity.onResume Hook 已安装")
        }
    }

    /** 通过 Activity 类名粗略判断是否处于主 Feed 播放页 */
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
                        HookUtils.log("$TAG: 非播放页，移除自动播放按钮")
                    }
                    return@post
                }

            if (content.findViewWithTag<View>(BTN_TAG) != null) return@post

            val density = activity.resources.displayMetrics.density
            val avatar = findAvatar(decor)
            // 按钮尺寸与头像差不多（36~72dp 区间内直接用头像尺寸，否则默认 48dp）
            val btnSize = if (avatar != null && avatar.width > 0) {
                val w = avatar.width
                if (w in (36 * density).toInt()..(72 * density).toInt()) w else (48 * density).toInt()
            } else {
                (48 * density).toInt()
            }

            val btn = createAutoPlayButton(activity)
            btn.tag = BTN_TAG

            // 头像检测失败时，回退到右侧竖向图标列的首个子 View（即头像）作为定位锚点
            val anchor = avatar ?: findRightColumnFirstChild(decor)
            if (anchor == null) {
                HookUtils.log("$TAG: 未定位到头像，按钮不注入（避免悬空）")
                return@post
            }

            val lp = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.TOP or Gravity.END
                // 居中悬于头像正上方，留小间隙
                val cLoc = IntArray(2)
                content.getLocationInWindow(cLoc)
                val aLoc = IntArray(2)
                anchor.getLocationInWindow(aLoc)
                val axRel = aLoc[0] - cLoc[0]
                val ayRel = aLoc[1] - cLoc[1]
                val anchorW = anchor.width
                val screenW = if (content.width > 0) content.width else decor.width
                val gap = (6 * density).toInt()
                topMargin = maxOf(0, ayRel - btnSize - gap)
                marginEnd = maxOf(0, screenW - (axRel + anchorW / 2) - btnSize / 2)
                HookUtils.log("$TAG: 定位到头像正上方 (anchorTop=$ayRel, btnSize=$btnSize)")
            }
            content.addView(btn, lp)
            HookUtils.log("$TAG: 已注入自动播放按钮 (container=content)")
            } catch (t: Throwable) {
                HookUtils.log("$TAG: tryInject 失败: ${t.message}")
            }
        }
    }

    /**
     * 查找作者头像（增强策略）
     * 
     * 抖音 39.5 界面特征:
     * - 头像在屏幕右侧中间偏上位置
     * - 头像通常是圆形或圆角矩形 ImageView
     * - 头像周围可能有白色边框/加号按钮
     * - 头像下方依次是: 点赞、评论、收藏、分享等图标
     * 
     * 策略（按优先级）:
     * 1. 右侧区域找圆形/圆角 ImageView（最可靠）
     * 2. 资源 entry name 含 "avatar"
     * 3. contentDescription 含「头像」
     * 4. 右侧竖向图标列的首个子 View
     */
    private fun findAvatar(root: View): View? {
        if (root !is ViewGroup) return null
        
        val width = root.width
        val density = root.resources.displayMetrics.density
        val rightThreshold = width * 0.65f // 右侧 35% 区域
        val screenH = root.height
        
        // 策略1: 在右侧区域找圆形/圆角 ImageView，优先最上方的
        val candidates = mutableListOf<Pair<View, Float>>() // (view, y坐标)
        collectAvatarCandidates(root, width, screenH, density, rightThreshold, candidates, 0)
        
        if (candidates.isNotEmpty()) {
            // 优先选择右侧区域最上方的圆形 ImageView
            candidates.sortBy { it.second }
            val best = candidates.first().first
            HookUtils.log("$TAG: 策略1找到头像: ${best.javaClass.name} @ (${best.x.toInt()}, ${best.y.toInt()})")
            return best
        }
        
        // 策略2: 资源名
        val byRes = findByResourceName(root, "avatar", 0)
        if (byRes != null) {
            HookUtils.log("$TAG: 策略2(资源名)找到头像")
            return byRes
        }
        
        // 策略3: contentDescription
        val byDesc = findByContentDesc(root, "头像", 0)
        if (byDesc != null) {
            HookUtils.log("$TAG: 策略3(描述)找到头像")
            return byDesc
        }
        
        // 策略4: 右侧竖向图标列
        val cols = mutableListOf<ViewGroup>()
        collectVerticalIconColumns(root, width, cols, 0)
        if (cols.isNotEmpty()) {
            cols.sortWith(compareBy({ -it.childCount }, { it.x.toInt() }))
            val col = cols.first()
            val child = col.getChildAt(0)
            HookUtils.log("$TAG: 策略4(图标列)找到头像候选")
            return child
        }
        
        return null
    }
    
    /**
     * 收集头像候选 View
     * 条件: 
     * - 在屏幕右侧 35% 区域内
     * - 是 ImageView（头像控件）
     * - 尺寸在 36dp-72dp 之间（典型头像大小）
     * - 优先圆形/圆角（通过 background 判断）
     */
    private fun collectAvatarCandidates(
        view: View, 
        screenW: Int, 
        screenH: Int, 
        density: Float,
        rightThreshold: Float,
        out: MutableList<Pair<View, Float>>,
        depth: Int
    ) {
        if (depth > 20) return
        
        // 只考虑右侧区域的 View
        if (view.x >= rightThreshold) {
            // 检查是否是 ImageView 或 ImageView 的子类
            if (view is ImageView) {
                val w = view.width
                val h = view.height
                val minSize = (36 * density).toInt()
                val maxSize = (72 * density).toInt()
                
                // 尺寸符合头像特征
                if (w in minSize..maxSize && h in minSize..maxSize) {
                    // 检查是否有圆角背景（圆形头像特征）
                    val hasRoundBg = hasRoundedBackground(view)
                    
                    // 检查是否在屏幕中间偏上区域（头像典型位置）
                    val y = view.y + view.height / 2f
                    val isInAvatarZone = y > screenH * 0.25f && y < screenH * 0.65f
                    
                    if (hasRoundBg || isInAvatarZone) {
                        val score = if (hasRoundBg) view.y - 1000 else view.y // 圆形优先
                        out.add(Pair(view, score))
                    }
                }
            }
        }
        
        // 递归遍历子 View
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectAvatarCandidates(view.getChildAt(i), screenW, screenH, density, rightThreshold, out, depth + 1)
            }
        }
    }
    
    /**
     * 检查 View 是否有圆角/圆形背景
     */
    private fun hasRoundedBackground(view: View): Boolean {
        return try {
            val bg = view.background
            when (bg) {
                is GradientDrawable -> bg.cornerRadius > 0
                is android.graphics.drawable.ShapeDrawable -> true
                else -> {
                    // 检查背景类名
                    val bgClass = bg?.javaClass?.name ?: ""
                    bgClass.contains("Circle") || 
                    bgClass.contains("Round") ||
                    bgClass.contains("Oval") ||
                    bgClass.contains("Corner")
                }
            }
        } catch (_: Throwable) { false }
    }

    private fun findByResourceName(view: View, kw: String, depth: Int): View? {
        if (depth > 16) return null
        try {
            val id = view.id
            if (id != View.NO_ID) {
                val entry = view.resources.getResourceEntryName(id)
                if (entry != null && entry.lowercase().contains(kw)) return view
            }
        } catch (_: Throwable) { }
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val r = findByResourceName(view.getChildAt(i), kw, depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun findByContentDesc(view: View, kw: String, depth: Int): View? {
        if (depth > 16) return null
        val desc = try { view.contentDescription?.toString() } catch (_: Throwable) { null }
        if (desc != null && desc.contains(kw)) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val r = findByContentDesc(view.getChildAt(i), kw, depth + 1)
            if (r != null) return r
        }
        return null
    }

    private fun collectVerticalIconColumns(view: View, width: Int, out: MutableList<ViewGroup>, depth: Int) {
        if (depth > 16 || view !is ViewGroup) return
        val isVertical = try {
            val o = XposedHelpers.callMethod(view, "getOrientation") as? Int
            o == 1
        } catch (_: Throwable) { false }
        if (isVertical && view.x > width * 0.5f) {
            var iconCount = 0
            for (i in 0 until view.childCount) {
                if (view.getChildAt(i) is ImageView) iconCount++
            }
            if (iconCount >= 3) out.add(view)
        }
        for (i in 0 until view.childCount) {
            collectVerticalIconColumns(view.getChildAt(i), width, out, depth + 1)
        }
    }

    /**
     * 头像检测失败时的兜底：找到右侧竖向图标列（点赞/评论/收藏/分享所在的列），
     * 返回其首个子 View（即作者头像）作为定位锚点。
     */
    private fun findRightColumnFirstChild(root: View): View? {
        try {
            val width = root.width
            val cols = mutableListOf<ViewGroup>()
            collectVerticalIconColumns(root, width, cols, 0)
            if (cols.isNotEmpty()) {
                cols.sortWith(compareBy({ -it.childCount }, { it.x.toInt() }))
                val col = cols.first()
                return col.getChildAt(0)
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun createAutoPlayButton(context: Context): ImageView {
        val density = context.resources.displayMetrics.density
        return ImageView(context).apply {
            isClickable = true
            isFocusable = true
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = createCircleBg(context, DouSettings.isAutoPlayEnabled())
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                DouSettings.setAutoPlay(!DouSettings.isAutoPlayEnabled())
                updateState(this)
                val on = DouSettings.isAutoPlayEnabled()
                HookUtils.showToast(context, if (on) "自动播放: 开" else "自动播放: 关")
                HookUtils.log("$TAG: 自动播放切换为 $on")
            }
        }.also { updateState(it) }
    }

    private fun updateState(btn: ImageView) {
        val on = DouSettings.isAutoPlayEnabled()
        btn.background = createCircleBg(btn.context, on)
        // 圆形 + 三角播放符号（开启为白色，关闭为半透明白）
        btn.setImageDrawable(TriangleDrawable(if (on) Color.WHITE else 0x99FFFFFF.toInt()))
    }

    /** 圆形半透明背景（开启时更深，关闭时更淡） */
    private fun createCircleBg(context: Context, on: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0x99000000.toInt() else 0x33000000.toInt())
        }
    }

    /** 居中三角播放符号（代码绘制，避免依赖模块资源在非宿主进程加载失败） */
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

package com.xposed.doupp.hook

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.HookUtils
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

/**
 * 屏蔽「新版本内测邀请」弹窗。
 *
 * ## 为什么按文案拦，而不是按类名
 * 40.7.0 实测：这些中文文案全在 resources.arsc 里（内测 / 抢先体验 / 升级 / 邀请 都能搜到），
 * dex 的字符串常量里反而没有 —— 也就是说承载它的类名会随版本变，文案相对稳定。
 * 所以这里不去猜「哪个类弹的内测窗」，而是拦 Dialog.show()，
 * 展开视图树看有没有命中关键词的 TextView，命中就不让它显示。
 * 这样换版本不用改代码，也顺带覆盖 DialogFragment / BottomSheetDialog 这些子类。
 *
 * ## 误伤控制
 * 只认「内测 / 抢先 / 测试资格」这类强特征词，单纯的「升级」「新版本」不作为触发条件，
 * 因为设置页、下载页里出现这些词是正常的。
 *
 * ## 修复：小黄车/直播购物面板空白问题
 * 原实现在 beforeHookedMethod 里强制遍历 decorView，会导致 Lynx/异步渲染的 Dialog 内容空白。
 * 现改为：
 * 1. beforeHookedMethod 只做类名白名单过滤（购物/直播/商城相关 Dialog 直接放行）
 * 2. afterHookedMethod 异步延迟扫描文本，不阻塞 show()
 * 3. 命中内测特征词时用 dismiss() 关闭，而非短路 show()
 */
class BetaInviteHook : BaseHook {

    companion object {
        private const val TAG = "BetaInvite"

        @Volatile
        private var installed = false

        /** 强特征词：命中即判定为内测邀请弹窗 */
        private val STRONG_KEYWORDS = listOf(
            "内测", "抢先体验", "抢先尝鲜", "抢先", "测试资格", "体验资格",
            "内测邀请", "邀请你体验", "新版本尝鲜", "灰度邀请"
        )

        /** 辅助词：需要和强特征词同现才算，降低误伤 */
        private val WEAK_KEYWORDS = listOf("新版本", "版本", "体验", "下载", "立即")

        /** 排除词：命中这些说明是正经更新流程，不拦 */
        private val EXCLUDE_KEYWORDS = listOf("设置", "关于抖音", "版本号", "更新日志")

        /** 电商/直播/购物相关 Dialog 类名前缀白名单 —— 命中直接放行，不扫描、不拦截 */
        private val SHOPPING_DIALOG_PREFIXES = listOf(
            "com.bytedance.android.livesdk.livecommerce",
            "com.bytedance.android.shopping",
            "com.bytedance.android.ec",
            "com.bytedance.android.live.commerce",
            "com.ss.android.ugc.aweme.commerce",
            "com.ss.android.ugc.aweme.shop",
            "com.ss.android.ugc.aweme.livecommerce",
            "com.ss.android.ugc.aweme.ecommerce"
        )

        private val suppressed = AtomicLong(0)
        private val scannedDialogs = AtomicLong(0)

        /** 供设置页/日志查看已拦次数 */
        fun suppressedCount(): Long = suppressed.get()

        /** 弹窗内容是否像内测邀请 */
        fun looksLikeBetaInvite(texts: List<String>): Boolean {
            if (texts.isEmpty()) return false
            if (texts.any { t -> EXCLUDE_KEYWORDS.any { t.contains(it) } }) return false
            val joined = texts.joinToString(" ")
            val strong = STRONG_KEYWORDS.any { joined.contains(it) }
            if (!strong) return false
            // 强特征词存在时，若同时出现「立即下载/立即体验」这类按钮文案，判定更稳
            return WEAK_KEYWORDS.any { joined.contains(it) } || texts.size <= 4
        }

        /** 收集 View 树里的所有文本 */
        fun collectTexts(v: View?, out: MutableList<String>, depth: Int = 0) {
            if (v == null || depth > 12) return
            if (v is TextView) {
                v.text?.toString()?.trim()?.let { if (it.isNotEmpty()) out.add(it) }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    collectTexts(v.getChildAt(i), out, depth + 1)
                }
            }
        }

        /** 判断是否为购物/直播/商城相关 Dialog —— 类名命中直接放行 */
        fun isShoppingDialog(dlg: Dialog): Boolean {
            val className = dlg::class.java.name
            return SHOPPING_DIALOG_PREFIXES.any { className.startsWith(it) }
        }
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        HookUtils.safeHook {
            // Dialog.show() 是虚方法，拦它即可覆盖 DialogFragment / BottomSheetDialog / AlertDialog 等
            val showMethods: List<Method> = Dialog::class.java.declaredMethods
                .filter { it.name == "show" && it.parameterCount == 0 }
            var hooked = 0
            for (m in showMethods) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!DouSettings.isBlockBetaInviteEnabled()) return
                            val dlg = param.thisObject as? Dialog ?: return
                            if (isHostDialog(dlg)) return
                            // 白名单：购物/直播/商城相关 Dialog 直接放行，不扫描、不拦截
                            if (isShoppingDialog(dlg)) {
                                HookUtils.log("$TAG: 电商/直播 Dialog 放行: ${dlg::class.java.name}")
                                return
                            }
                            // before 里不再强制 inflate/扫描，避免破坏异步渲染（如小黄车面板）
                        } catch (_: Throwable) { }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!DouSettings.isBlockBetaInviteEnabled()) return
                            val dlg = param.thisObject as? Dialog ?: return
                            if (isHostDialog(dlg)) return
                            if (isShoppingDialog(dlg)) return

                            // Dialog 已显示，异步延迟扫描，不阻塞 UI 线程
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    if (!dlg.isShowing) return@postDelayed
                                    val texts = ArrayList<String>()
                                    runCatching { collectTexts(dlg.window?.decorView, texts) }
                                    if (texts.isEmpty()) runCatching { collectTexts(dlg.findViewById(android.R.id.content), texts) }
                                    if (texts.isEmpty()) return@postDelayed
                                    scannedDialogs.incrementAndGet()

                                    if (!looksLikeBetaInvite(texts)) return@postDelayed
                                    suppressed.incrementAndGet()
                                    HookUtils.log("$TAG: 已拦截内测弹窗: ${texts.take(3)}")
                                    // 用 dismiss() 关闭已显示的弹窗，而非短路 show()
                                    dlg.dismiss()
                                } catch (_: Throwable) { }
                            }, 200) // 给 Lynx/异步渲染留 200ms 缓冲
                        } catch (_: Throwable) { }
                    }
                })
                hooked++
            }
            installed = hooked > 0
            HookUtils.log("$TAG: 安装完成，hook show 方法 $hooked 个（afterHookedMethod 异步扫描 + 电商白名单）")
        }
    }

    /** 模块自己的设置弹窗不拦 */
    private fun isHostDialog(dlg: Dialog): Boolean = runCatching {
        val pkg = dlg.context?.packageName ?: return@runCatching false
        pkg == DouSettings.MODULE_PACKAGE
    }.getOrDefault(false)
}
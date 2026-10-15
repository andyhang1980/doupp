package com.xposed.doupp.hook

import android.app.Dialog
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

                            // show() 之前 decorView 可能还没 inflate 完，两处都看一遍
                            val texts = ArrayList<String>()
                            runCatching { collectTexts(dlg.window?.decorView, texts) }
                            if (texts.isEmpty()) runCatching { collectTexts(dlg.findViewById(android.R.id.content), texts) }
                            if (texts.isEmpty()) return
                            scannedDialogs.incrementAndGet()

                            if (!looksLikeBetaInvite(texts)) return
                            suppressed.incrementAndGet()
                            HookUtils.log("$TAG: 已拦截内测弹窗: ${texts.take(3)}")
                            // 直接不显示：置 result 即短路原方法（show 返回 Unit，null 即可）
                            runCatching { dlg.setCanceledOnTouchOutside(false) }
                            param.result = null
                        } catch (_: Throwable) { }
                    }
                })
                hooked++
            }
            installed = hooked > 0
            HookUtils.log("$TAG: 安装完成，hook show 方法 $hooked 个")
        }
    }

    /** 模块自己的设置弹窗不拦 */
    private fun isHostDialog(dlg: Dialog): Boolean = runCatching {
        val pkg = dlg.context?.packageName ?: return@runCatching false
        pkg == DouSettings.MODULE_PACKAGE
    }.getOrDefault(false)
}
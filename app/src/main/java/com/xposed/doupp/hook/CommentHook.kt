package com.xposed.doupp.hook

import com.xposed.doupp.util.HookUtils

/**
 * 评论区 Hook — 最小化策略
 *
 * 不 Hook Comment 的所有 getter (会导致每次加载评论都递归扫描)
 * 只 Hook 长按/复制等用户主动操作，被动发现媒体 URL
 */
class CommentHook : BaseHook {

    companion object {
        private const val TAG = "CommentHook"
        private var installed = false
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return

        HookUtils.safeHook {
            // 只 Hook ClipboardManager.setPrimaryClip，拦截复制链接场景
            val clipboardClass = HookUtils.findClassOrNull(
                "android.content.ClipboardManager", classLoader) ?: return@safeHook

            // 借鉴 LSPilot hookAllMethodsBefore 的 lambda 风格
            HookUtils.hookAllBefore(clipboardClass, "setPrimaryClip") { param ->
                val clip = param.args.firstOrNull() as? android.content.ClipData
                    ?: return@hookAllBefore
                val text = clip.getItemAt(0)?.text?.toString() ?: return@hookAllBefore

                if (text.contains("playwm") || text.contains("douyin.com")) {
                    HookUtils.log("$TAG: 剪贴板中发现抖音链接")
                    // 不拦截，只记录
                }
            }

            HookUtils.log("$TAG: ClipboardManager Hook 已安装")
            installed = true
        }
    }
}

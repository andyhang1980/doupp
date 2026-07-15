package com.xposed.doupp.hook

import com.xposed.doupp.util.AdaptationManager
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaCache
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Feed 流 Aweme Hook — 缓存当前正在浏览的内容
 *
 * 同时 Hook getVideo() 和 getAwemeId()，确保所有类型内容
 * （视频/图文/视频条等）都能被正确缓存，供自动播放、下载等模块使用。
 *
 * 自动适配: 优先使用 AdaptationManager 缓存的类名
 */
class FeedHook : BaseHook {

    companion object {
        private const val TAG = "FeedHook"
        private var installed = false
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return

        HookUtils.safeHook {
            // 1. 优先使用 AdaptationManager 缓存的类名
            val cachedClasses = AdaptationManager.getAdaptedClasses("aweme")

            // 2. 回退到硬编码候选列表
            val fallbackClasses = listOf(
                "com.ss.android.ugc.aweme.feed.model.Aweme",
                "com.ss.ugc.aweme.Aweme",
                "com.ss.android.ugc.aweme.model.Aweme",
                "com.ss.android.ugc.aweme.feed.model.FeedModel",
                "com.ss.android.ugc.aweme.model.FeedItem"
            )

            val allCandidates = (cachedClasses + fallbackClasses).distinct()

            val awemeClass = ClassFinder.findClass(classLoader, allCandidates)

            if (awemeClass != null) {
                HookUtils.log("$TAG: 找到 Aweme 类: ${awemeClass.name}")

                var hooked = false

                // 查找所有无参 getter 方法
                val allMethods = awemeClass.declaredMethods
                    .filter { m ->
                        m.parameterCount == 0 &&
                            m.returnType != Void.TYPE &&
                            m.returnType != java.lang.Void.TYPE &&
                            !m.name.contains("$")
                    }

                // 优先 Hook getVideo() — 视频内容最可靠的指示器
                val videoMethod = allMethods.firstOrNull { m ->
                    val name = m.name.lowercase()
                    name == "getvideo" ||
                        name == "video" ||
                        name == "getmvideo" ||
                        name == "mvideo" ||
                        name == "getvideomodel"
                }

                if (videoMethod != null) {
                    XposedBridge.hookMethod(videoMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                // 无论 result 是否为 null 都缓存：
                                // 图文内容 getVideo 返回 null，但也需要缓存 Aweme
                                // 供自动播放、下载等模块判断内容类型
                                MediaCache.setCurrentAweme(param.thisObject)
                            } catch (_: Throwable) {}
                        }
                    })
                    HookUtils.log("$TAG: Hook Aweme.${videoMethod.name} (缓存当前 Aweme)")
                    hooked = true
                }

                // 同时 Hook getAwemeId() — 图文/视频条等内容也会调用
                // 作为 getVideo() 的补充，确保所有内容类型都被缓存
                val idMethod = allMethods.firstOrNull { m ->
                    val name = m.name.lowercase()
                    name == "getawemeid" || name == "awemeid" || name == "itemid"
                }

                if (idMethod != null && idMethod != videoMethod) {
                    XposedBridge.hookMethod(idMethod, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                MediaCache.setCurrentAweme(param.thisObject)
                            } catch (_: Throwable) {}
                        }
                    })
                    HookUtils.log("$TAG: Hook Aweme.${idMethod.name} (补充缓存)")
                    hooked = true
                }

                if (hooked) {
                    installed = true
                } else {
                    HookUtils.log("$TAG: 未找到任何可 Hook 的方法")
                }
            } else {
                HookUtils.log("$TAG: 未找到 Aweme 类")
            }
        }
    }
}

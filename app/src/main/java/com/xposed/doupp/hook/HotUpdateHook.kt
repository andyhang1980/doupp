package com.xposed.doupp.hook

import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.AdaptationManager
import com.xposed.doupp.util.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 热更新屏蔽 Hook
 *
 * 抖音使用字节跳动内部热更新框架（如 Tinker、Sophix 或自研框架），
 * 在后台静默下载补丁并替换 dex/so，可能导致：
 * 1. 模块 Hook 的类被新 dex 覆盖失效
 * 2. 抖音版本行为突变（如类名重新混淆）
 * 3. 增加流量和电量消耗
 *
 * 策略:
 * 1. Hook 常见热更新框架的入口类（检测/下载/应用补丁）。
 * 2. 当设置开启时，拦截这些调用，阻止热更新执行。
 * 3. 支持框架: 字节跳动自研、Tinker、Sophix、Qigsaw 等。
 */
class HotUpdateHook : BaseHook {

    companion object {
        private const val TAG = "HotUpdateHook"
        private var installed = false

        /** 热更新框架入口类名候选 */
        private val HOT_UPDATE_CLASSES = listOf(
            // 字节跳动自研热更新
            "com.bytedance.ies.patch.PatchManager",
            "com.bytedance.ugc.patch.PatchManager",
            "com.bytedance.boost.patch.PatchLoader",
            "com.bytedance.boost.multidex.BoostMultiDex",
            "com.bytedance.mira.MiraManager",
            "com.bytedance.mira.MiraManager\$MiraPatch",
            "com.bytedance.hotfix.HotfixManager",
            "com.bytedance.patch.PatchLoader",
            // Tinker
            "com.tencent.tinker.lib.tinker.Tinker",
            "com.tencent.tinker.lib.tinker.TinkerInstaller",
            "com.tencent.tinker.lib.service.AbstractResultService",
            "com.tencent.tinker.lib.patch.UpgradePatch",
            // Sophix (阿里云)
            "com.taobao.sophix.SophixManager",
            "com.taobao.sophix.a.a", // 内部类
            // 其他
            "com.meituan.robust.PatchExecutor",
            "com.meituan.robust.PatchManipulate",
            "com.qihoo360.replugin.RePlugin",
            "com.didi.virtualapk.PluginManager"
        )

        /** 热更新关键方法名 */
        private val HOT_UPDATE_METHODS = listOf(
            "installPatch", "loadPatch", "applyPatch", "install", "update",
            "fetchPatchList", "checkPatch", "downloadPatch", "apply",
            "load", "init", "start", "triggerDownload", "tryLoad",
            "syncPatch", "asyncPatch", "requestPatch", "handlePatch"
        )
    }

    override fun tag() = TAG

    override fun isInstalled(): Boolean = installed

    private fun isBlockEnabled(): Boolean {
        return try {
            DouSettings.isBlockHotUpdateEnabled()
        } catch (_: Throwable) {
            true // 默认屏蔽热更新
        }
    }

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        HookUtils.safeHook {
            HookUtils.log("$TAG: 开始安装热更新屏蔽 Hook")

            var blockedCount = 0

            // 优先使用 AdaptationManager 缓存的类名
            val cachedClasses = AdaptationManager.getAdaptedClasses("hotupdate")

            // 回退到硬编码候选列表
            val fallbackClasses = HOT_UPDATE_CLASSES

            val allCandidates = (cachedClasses + fallbackClasses).distinct()

            // 策略1: Hook 已知的热更新框架入口类
            for (className in allCandidates) {
                try {
                    val clazz = XposedHelpers.findClass(className, classLoader)
                    for (methodName in HOT_UPDATE_METHODS) {
                        try {
                            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (!isBlockEnabled()) return
                                    val method = param.method as? java.lang.reflect.Method
                                    val returnType = method?.returnType
                                    when {
                                        returnType == Void.TYPE || returnType == java.lang.Void.TYPE -> {
                                            param.result = null
                                        }
                                        returnType == Boolean::class.javaPrimitiveType -> {
                                            param.result = false
                                        }
                                        returnType == Int::class.javaPrimitiveType -> {
                                            param.result = 0
                                        }
                                        returnType == Long::class.javaPrimitiveType -> {
                                            param.result = 0L
                                        }
                                        returnType == Float::class.javaPrimitiveType -> {
                                            param.result = 0f
                                        }
                                        returnType == Double::class.javaPrimitiveType -> {
                                            param.result = 0.0
                                        }
                                        else -> {
                                            param.result = null
                                        }
                                    }
                                    HookUtils.log("$TAG: 拦截 $className.$methodName (return=$returnType)")
                                }
                            })
                            blockedCount++
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }

            // 策略2: Hook 网络请求相关类（阻止补丁下载）
            try {
                val cronetClass = XposedHelpers.findClass(
                    "com.ttnet.org.chromium.net.impl.CronetUrlRequest", classLoader
                )
                XposedBridge.hookAllMethods(cronetClass, "start", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isBlockEnabled()) return
                        try {
                            val request = param.thisObject
                            val urlField = request.javaClass.getDeclaredField("mUrl")
                            urlField.isAccessible = true
                            val url = urlField.get(request) as? String ?: ""
                            // 检查 URL 是否包含热更新/补丁特征
                            if (isPatchUrl(url)) {
                                HookUtils.log("$TAG: 拦截补丁下载请求: $url")
                                param.result = null
                            }
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}

            // 策略3: Hook OkHttp 拦截热更新下载
            try {
                val okHttpClient = XposedHelpers.findClass(
                    "okhttp3.OkHttpClient", classLoader
                )
                XposedBridge.hookAllMethods(okHttpClient, "newCall", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isBlockEnabled()) return
                        try {
                            val request = param.args[0]
                            val urlMethod = request.javaClass.getMethod("url")
                            val url = urlMethod.invoke(request).toString()
                            if (isPatchUrl(url)) {
                                HookUtils.log("$TAG: 拦截 OkHttp 补丁下载: $url")
                                param.result = null
                            }
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}

            installed = true
            HookUtils.log("$TAG: 安装完成，拦截了 $blockedCount 个热更新入口")
        }
    }

    /**
     * 判断 URL 是否为热更新/补丁下载
     *
     * 严格匹配策略:
     * - 路径中包含补丁相关关键词（patch/hotfix/tinker/sophix）
     * - 或路径以补丁文件扩展名结尾（.patch/.dex/.diff/.delta）
     * - 不拦截包含 update/.zip/.apk/.so 等常见词的正常 URL
     */
    private fun isPatchUrl(url: String): Boolean {
        val lower = url.lowercase()
        // 严格: 路径中包含补丁框架专属关键词
        val strictKeywords = listOf(
            "patch/", "/patch", "hotfix", "tinker", "sophix",
            "boost_patch", "mira_patch", "robust"
        )
        if (strictKeywords.any { lower.contains(it) }) return true
        // 严格: URL 路径以补丁文件扩展名结尾
        val patchExtensions = listOf(".patch", ".diff", ".delta")
        if (patchExtensions.any { lower.endsWith(it) }) return true
        // 路径中包含 dex/so 下载特征（带路径分隔符，避免误匹配普通域名）
        if (lower.contains("/dex/") || lower.contains("/so/") ||
            lower.contains("/tinker/") || lower.contains("/sophix/")
        ) return true
        return false
    }
}

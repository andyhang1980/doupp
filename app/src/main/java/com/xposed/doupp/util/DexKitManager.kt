package com.xposed.doupp.util

import com.xposed.doupp.util.HookUtils
import org.luckypray.dexkit.DexKitBridge

/**
 * DexKit 管理器
 *
 * 通过 DexKit 在运行时对抖音 apk 做离线 dex 搜索，按字符串/结构特征定位混淆后的类与方法，
 * 使模块不再依赖写死的 39.6 混淆名，从而抗抖音版本变动（类似逗音小能手的思路）。
 *
 * 用法:
 * 1. 在 MainHook.handleLoadPackage 中调用 [init] 传入宿主 apk 路径 (lpparam.appInfo.sourceDir)
 * 2. 各 Hook 通过 [findClassesByStrings] / [findMethodNameByStrings] 定位目标
 * 3. bridge 懒加载，进程存活期间保持打开（避免重复初始化开销）
 */
object DexKitManager {

    @Volatile
    private var bridge: DexKitBridge? = null

    @Volatile
    private var apkPath: String? = null

    @Volatile
    private var libLoaded = false

    /**
     * 传入宿主 apk 路径，只需调用一次
     */
    fun init(apkPath: String) {
        if (this.apkPath == null) {
            this.apkPath = apkPath
            HookUtils.log("DexKit: 初始化 apk 路径 = $apkPath")
        }
    }

    /**
     * 加载 DexKit 的 native 库。DexKit 2.x 不会自动加载 so，必须显式 loadLibrary，
     * 否则 DexKitBridge.create 会抛 UnsatisfiedLinkError (nativeInitDexKit not found)。
     * 在 Xposed 模块注入的宿主进程内，使用模块自身的 ClassLoader 即可定位 libdexkit.so。
     */
    private fun ensureNativeLib() {
        if (libLoaded) return
        synchronized(this) {
            if (libLoaded) return
            try {
                System.loadLibrary("dexkit")
                libLoaded = true
                HookUtils.log("DexKit: System.loadLibrary(dexkit) ok")
            } catch (t: Throwable) {
                HookUtils.log("DexKit: loadLibrary 失败: ${t.message}")
            }
        }
    }

    /**
     * 获取（懒创建）DexKitBridge
     */
    fun getBridge(): DexKitBridge? {
        bridge?.let { return it }
        val path = apkPath ?: run {
            HookUtils.log("DexKit: apk 路径未初始化")
            return null
        }
        ensureNativeLib()
        return try {
            val b = DexKitBridge.create(path)
            bridge = b
            HookUtils.log("DexKit: bridge 创建成功")
            b
        } catch (t: Throwable) {
            HookUtils.log("DexKit: bridge 创建失败: ${t.message}")
            null
        }
    }

    /**
     * 通过类中方法引用的字符串特征查找候选类名。
     *
     * @param strings 类中任一方法引用的字符串（全部出现才匹配，默认 contains）
     * @param packages 限定搜索包名前缀（默认搜索抖音主包 + yyds混淆包）
     * @return 命中的类名列表（可能多个，调用方需结合结构特征二次筛选）
     */
    fun findClassesByStrings(
        strings: List<String>,
        packages: List<String> = listOf("com.ss.android.ugc.aweme", "yyds")
    ): List<String> {
        val b = getBridge() ?: return emptyList()
        return try {
            b.findClass {
                searchPackages(*packages.toTypedArray())
                matcher {
                    usingStrings(*strings.toTypedArray())
                }
            }.map { it.name }
        } catch (t: Throwable) {
            HookUtils.log("DexKit: findClassesByStrings 失败: ${t.message}")
            emptyList()
        }
    }

    /**
     * 在指定类中，通过方法引用的字符串特征查找方法名。
     *
     * @param className 已确定的类名
     * @param strings 方法体引用的字符串特征
     * @return 命中的方法名（取首个），无结果返回 null
     */
    fun findMethodNameByStrings(
        className: String,
        strings: List<String>
    ): String? {
        val b = getBridge() ?: return null
        return try {
            b.findMethod {
                matcher {
                    declaredClass(className)
                    usingStrings(*strings.toTypedArray())
                }
            }.firstOrNull()?.name
        } catch (t: Throwable) {
            HookUtils.log("DexKit: findMethodNameByStrings 失败: ${t.message}")
            null
        }
    }

    /**
     * 释放 native 资源（一般无需调用，进程退出即回收）
     */
    fun close() {
        try {
            bridge?.close()
        } catch (_: Throwable) {
        }
        bridge = null
    }
}

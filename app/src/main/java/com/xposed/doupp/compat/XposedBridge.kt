package com.xposed.doupp.compat

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Compat 兼容层：模拟旧版 XposedBridge (de.robv.android.xposed) 的静态方法，
 * 底层支持两种后端：
 *   - LibXposed API 101（LSPosed 现代 API）
 *   - 传统 Xposed API 82（FPA/太极/LSPatch 等免root框架）
 *
 * 使用前必须调用 [attach]（LibXposed）或 [attachLegacy]（传统 API）注入后端。
 */
object XposedBridge {

    /** 当前挂载的 LibXposed 接口；模块进程（设置页等）内为 null */
    @Volatile
    private var xposed: XposedInterface? = null

    /** 是否为传统 Xposed API 后端（FPA 等免root框架） */
    @Volatile
    private var legacyAttached = false

    /** 注入 LibXposed 接口（由模块入口调用） */
    fun attach(api: XposedInterface) {
        xposed = api
    }

    /** 切换到传统 Xposed API 后端（由 FPA 入口调用） */
    fun attachLegacy() {
        legacyAttached = true
    }

    /** 是否已挂载（宿主进程内为 true） */
    fun isAttached(): Boolean = xposed != null || legacyAttached

    /** 是否传统 Xposed API 后端 */
    fun isLegacyMode(): Boolean = legacyAttached

    /**
     * 读取模块自身的 SharedPreferences。
     * - LibXposed 后端：由 LSPosed 框架跨进程桥接
     * - 传统 API 后端：FPA 等免root框架无此能力，返回 null，由调用方走本地文件兜底
     */
    fun getRemotePreferences(name: String): SharedPreferences? {
        if (legacyAttached) return null
        return xposed?.getRemotePreferences(name)
    }

    /**
     * Hook 单个方法/构造器。
     * @return Unhook 用于解绑；失败返回 null
     */
    fun hookMethod(member: Member?, hook: XC_MethodHook): XC_MethodHook.Unhook? {
        val exe = member as? Executable ?: return null
        if (legacyAttached) {
            return try {
                val legacyHook = hook.toLegacyHook() as de.robv.android.xposed.XC_MethodHook
                val unhook = de.robv.android.xposed.XposedBridge.hookMethod(member, legacyHook)
                XC_MethodHook.Unhook(unhook)
            } catch (t: Throwable) {
                log("hookMethod 失败: ${member} - ${t.message}")
                null
            }
        }
        val api = xposed ?: return null
        return try {
            val handle = api.hook(exe).intercept(hook.toHooker())
            XC_MethodHook.Unhook(handle)
        } catch (t: Throwable) {
            log("hookMethod 失败: ${member} - ${t.message}")
            null
        }
    }

    /**
     * Hook 类及其父类中所有同名方法（模拟旧 API 语义）。
     * @return 成功挂载的 Unhook 集合
     */
    fun hookAllMethods(clazz: Class<*>?, methodName: String, hook: XC_MethodHook): Set<XC_MethodHook.Unhook> {
        if (clazz == null) return emptySet()
        val unhooks = mutableSetOf<XC_MethodHook.Unhook>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (method in c.declaredMethods) {
                if (method.name == methodName) {
                    hookMethod(method, hook)?.let { unhooks.add(it) }
                }
            }
            c = c.superclass
        }
        return unhooks
    }

    /**
     * Hook 类中所有构造器。
     */
    fun hookAllConstructors(clazz: Class<*>?, hook: XC_MethodHook): Set<XC_MethodHook.Unhook> {
        if (clazz == null) return emptySet()
        val unhooks = mutableSetOf<XC_MethodHook.Unhook>()
        for (ctor in clazz.declaredConstructors) {
            hookMethod(ctor, hook)?.let { unhooks.add(it) }
        }
        return unhooks
    }

    /**
     * 输出日志到框架日志（传统 API 用 XposedBridge.log）并回退 android.util.Log。
     */
    fun log(message: String) {
        if (legacyAttached) {
            try {
                de.robv.android.xposed.XposedBridge.log("Dou+ $message")
                return
            } catch (_: Throwable) {}
        }
        val api = xposed
        if (api != null) {
            try {
                api.log(Log.INFO, "Dou+", message)
                return
            } catch (_: Throwable) {}
        }
        try {
            Log.i("Dou+", message)
        } catch (_: Throwable) {}
    }

    /** 反射调用静态方法（XposedHelpers.callStaticMethod 兼容） */
    fun callStaticMethod(clazz: Class<*>?, methodName: String, vararg args: Any?): Any? {
        if (clazz == null) return null
        return try {
            val method = findMethod(clazz, methodName, args.size, isStatic = true)
                ?: return null
            method.isAccessible = true
            method.invoke(null, *args)
        } catch (t: Throwable) {
            log("callStaticMethod 失败: ${clazz.name}.$methodName - ${t.message}")
            null
        }
    }

    /** 反射调用实例方法（XposedHelpers.callMethod 兼容） */
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        if (obj == null) return null
        return try {
            val method = findMethod(obj.javaClass, methodName, args.size, isStatic = false)
                ?: return null
            method.isAccessible = true
            method.invoke(obj, *args)
        } catch (t: Throwable) {
            log("callMethod 失败: ${obj.javaClass.name}.$methodName - ${t.message}")
            null
        }
    }

    /** 按名称+参数个数查找方法（含父类，isStatic 过滤） */
    private fun findMethod(clazz: Class<*>, methodName: String, argCount: Int, isStatic: Boolean): Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == methodName && m.parameterCount == argCount && java.lang.reflect.Modifier.isStatic(m.modifiers) == isStatic) {
                    return m
                }
            }
            c = c.superclass
        }
        return null
    }
}

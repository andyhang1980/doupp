package com.xposed.doupp.util

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
import com.xposed.doupp.compat.XposedHelpers
import java.lang.reflect.Member

/** hook 回调参数类型别名，等价于 LSPilot �?HookParam */
typealias HookParam = XC_MethodHook.MethodHookParam

/**
 * Hook 通用工具
 *
 * 功能:
 * - safeHook: 安全执行 hook，捕获异�?
 * - log: 统一日志
 * - showToast: �?Toast
 * - 反射工具方法
 */
object HookUtils {

    /** 统一日志 TAG */
    private const val TAG = "Dou+"

    /** 主线�?Handler，用于弹 Toast */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 安全执行 hook 操作
     * 捕获所有异常，确保不影响宿主应�?
     *
     * @param block 要执行的操作
     */
    inline fun safeHook(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("Hook 异常: ${t.message}")
            log("堆栈: ${t.stackTraceToString().take(500)}")
        }
    }

    /**
     * 输出日志
     * 宿主进程内由 compat 层转发到 LSPosed 日志；
     * 模块自身进程（设置页等，非 Xposed 环境）下回退 android.util.Log。
     *
     * @param message 日志内容
     */
    fun log(message: String) {
        XposedBridge.log(message)
    }

    /**
     * 显示 Toast
     * 必须在主线程执行
     *
     * @param context Android Context
     * @param message Toast 消息
     * @param duration 显示时长，默�?SHORT
     */
    fun showToast(
        context: android.content.Context,
        message: String,
        duration: Int = Toast.LENGTH_SHORT
    ) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(context, message, duration).show()
            } else {
                mainHandler.post {
                    Toast.makeText(context, message, duration).show()
                }
            }
        } catch (t: Throwable) {
            log("显示Toast失败: ${t.message}")
        }
    }

    /**
     * 反射获取字段�?
     *
     * @param obj 目标对象
     * @param fieldName 字段�?
     * @return 字段值，失败返回 null
     */
    fun getField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        return try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj)
        } catch (t: Throwable) {
            log("反射获取字段失败: ${obj.javaClass.name}.$fieldName - ${t.message}")
            null
        }
    }

    /**
     * 反射设置字段�?
     *
     * @param obj 目标对象
     * @param fieldName 字段�?
     * @param value 要设置的�?
     */
    fun setField(obj: Any?, fieldName: String, value: Any?) {
        if (obj == null) return
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(obj, value)
        } catch (t: Throwable) {
            log("反射设置字段失败: ${obj.javaClass.name}.$fieldName - ${t.message}")
        }
    }

    /**
     * 反射调用方法
     *
     * @param obj 目标对象
     * @param methodName 方法�?
     * @param paramTypes 参数类型数组
     * @param args 参数值数�?
     * @return 方法返回值，失败返回 null
     */
    fun callMethod(
        obj: Any?,
        methodName: String,
        paramTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any?
    ): Any? {
        if (obj == null) return null
        return try {
            val method = obj.javaClass.getDeclaredMethod(methodName, *paramTypes)
            method.isAccessible = true
            method.invoke(obj, *args)
        } catch (t: Throwable) {
            log("反射调用方法失败: ${obj.javaClass.name}.$methodName - ${t.message}")
            null
        }
    }

    /**
     * 反射获取父类字段�?
     * 递归向上查找字段
     *
     * @param obj 目标对象
     * @param fieldName 字段�?
     * @return 字段值，失败返回 null
     */
    fun getFieldDeep(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null

        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            } catch (t: Throwable) {
                log("反射获取字段失败: $clazz.$fieldName - ${t.message}")
                return null
            }
        }

        // @SerializedName 注解反查（适配 40.0.0 全字段混淆：字段名变单字母，
        // 但 Gson 注解保留 JSON 名，如 play_addr_h264 / cid / text 等）
        return getFieldBySerializedName(obj, fieldName)
    }

    /**
     * 通过 @SerializedName 注解反查字段值（适配 40.0.0 全字段混淆）。
     *
     * 40.0.0 中模型字段被 R8 混淆为单字母，但 Gson 的 @SerializedName 注解
     * 保留了原始 JSON 名。遍历类的全部字段（含父类），读取 @SerializedName
     * 注解值并与目标 JSON 名匹配。
     *
     * 注解类通过宿主 classloader 加载（避免模块编译期依赖 gson）。
     */
    @JvmStatic
    fun getFieldBySerializedName(obj: Any?, jsonName: String): Any? {
        if (obj == null) return null

        val variants = buildNameVariants(jsonName)
        val annClassName = "com.google.gson.annotations.SerializedName"
        var annClass: Class<*>? = null
        try {
            annClass = Class.forName(annClassName, false, obj.javaClass.classLoader)
        } catch (_: Throwable) {}

        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                try {
                    val annValue = if (annClass != null) {
                        try {
                            val ann = annClass.getMethod("value")
                            // 用 Java 反射遍历注解，避免 Kotlin 平台类型问题
                            var matched: Any? = null
                            for (a in field.annotations) {
                                if (annClass.isInstance(a)) { matched = a; break }
                            }
                            if (matched != null) ann.invoke(matched) as? String else null
                        } catch (_: Throwable) { null }
                    } else {
                        field.declaredAnnotations.firstOrNull { a ->
                            try { a.javaClass.name == annClassName } catch (_: Throwable) { false }
                        }?.let { ann ->
                            try {
                                ann.javaClass.getMethod("value").invoke(ann) as? String
                            } catch (_: Throwable) { null }
                        }
                    }
                    if (annValue != null && variants.any { it.equals(annValue, ignoreCase = true) }) {
                        field.isAccessible = true
                        val value = field.get(obj)
                        if (value != null) return value
                    }
                } catch (_: Throwable) {}
            }
            clazz = clazz.superclass
        }
        return null
    }

    /** 生成字段名的各种命名变体（camelCase / snake_case / 大写） */
    @JvmStatic
    fun buildNameVariants(name: String): Set<String> {
        val variants = mutableSetOf(name, name.lowercase(), name.uppercase())
        // camelCase -> snake_case
        val snake = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
        variants.add(snake)
        variants.add(snake.uppercase())
        // snake_case -> camelCase
        if (name.contains('_')) {
            val camel = name.split("_").filter { it.isNotEmpty() }
                .mapIndexed { i, part -> if (i == 0) part.lowercase() else part.replaceFirstChar { it.uppercase() } }
                .joinToString("")
            variants.add(camel)
            variants.add(camel.lowercase())
        }
        return variants
    }

    /**
     * 检查类是否存在
     *
     * @param classLoader 类加载器
     * @param className 类名
     * @return true 如果类存�?
     */
    fun classExists(classLoader: ClassLoader, className: String): Boolean {
        return try {
            Class.forName(className, false, classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    /**
     * 获取对象的所有字段名
     *
     * @param obj 目标对象
     * @return 字段名列�?
     */
    fun getFieldNames(obj: Any?): List<String> {
        if (obj == null) return emptyList()

        val names = mutableListOf<String>()
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredFields.forEach { names.add(it.name) }
            clazz = clazz.superclass
        }
        return names
    }

    /**
     * 获取对象的所有方法名
     *
     * @param obj 目标对象
     * @return 方法名列�?
     */
    fun getMethodNames(obj: Any?): List<String> {
        if (obj == null) return emptyList()

        val names = mutableListOf<String>()
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredMethods.forEach { names.add(it.name) }
            clazz = clazz.superclass
        }
        return names
    }

    /**
     * 格式化字节数组为十六进制字符�?(用于调试)
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ============================================================
    // Hook DSL —�?借鉴 LSPilot �?hook 机制，用 Kotlin lambda 封装
    //
    // 目标：把散落各处�?
    //     XposedBridge.hookMethod(m, object : XC_MethodHook() {
    //         override fun beforeHookedMethod(param) { ... }
    //     })
    // 简化为�?
    //     hookBefore(m) { param -> ... }
    //
    // 所有回调自�?safeHook 包裹，回调抛异常不会波及宿主�?
    // 保持与现�?98 处裸调写法完全兼容，可渐进迁移�?
    // ============================================================

    /**
     * 前置 hook（借鉴 LSPilot hookBefore�?
     * @return XC_MethodHook.Unhook，可用于后续 unhook
     */
    inline fun hookBefore(
        member: Member?,
        crossinline block: (HookParam) -> Unit
    ): XC_MethodHook.Unhook? {
        if (member == null) return null
        return try {
            XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safeHook { block(param) }
                }
            })
        } catch (t: Throwable) {
            log("hookBefore 失败: ${member} - ${t.message}"); null
        }
    }

    /**
     * 后置 hook（借鉴 LSPilot hookAfter�?
     */
    inline fun hookAfter(
        member: Member?,
        crossinline block: (HookParam) -> Unit
    ): XC_MethodHook.Unhook? {
        if (member == null) return null
        return try {
            XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    safeHook { block(param) }
                }
            })
        } catch (t: Throwable) {
            log("hookAfter 失败: ${member} - ${t.message}"); null
        }
    }

    /**
     * 替换 hook：在 before 中跳过原方法（借鉴 LSPilot hookReplace + skipWith�?
     * block 返回值作为方法结果；返回 null 时原方法仍返�?null 并被跳过�?
     */
    inline fun hookReplace(
        member: Member?,
        crossinline block: (HookParam) -> Any?
    ): XC_MethodHook.Unhook? {
        if (member == null) return null
        return try {
            XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safeHook { param.result = block(param) }
                }
            })
        } catch (t: Throwable) {
            log("hookReplace 失败: ${member} - ${t.message}"); null
        }
    }

    /**
     * hook 类中所有同名方法的前置回调（借鉴 LSPilot hookAllMethodsBefore�?
     * @return 成功挂载�?Unhook 集合
     */
    inline fun hookAllBefore(
        clazz: Class<*>?,
        methodName: String,
        crossinline block: (HookParam) -> Unit
    ): Set<XC_MethodHook.Unhook> {
        if (clazz == null) return emptySet()
        return try {
            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safeHook { block(param) }
                }
            })
        } catch (t: Throwable) {
            log("hookAllBefore 失败: ${clazz.name}.$methodName - ${t.message}"); emptySet()
        }
    }

    /**
     * hook 类中所有同名方法的后置回调（借鉴 LSPilot hookAllMethodsAfter�?
     */
    inline fun hookAllAfter(
        clazz: Class<*>?,
        methodName: String,
        crossinline block: (HookParam) -> Unit
    ): Set<XC_MethodHook.Unhook> {
        if (clazz == null) return emptySet()
        return try {
            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    safeHook { block(param) }
                }
            })
        } catch (t: Throwable) {
            log("hookAllAfter 失败: ${clazz.name}.$methodName - ${t.message}"); emptySet()
        }
    }

    /**
     * 跳过原方法并指定返回值（借鉴 LSPilot param.skipWith�?
     * 仅在 before 回调中调用有效�?
     */
    fun HookParam.skipWith(value: Any?) {
        this.result = value
    }

    /**
     * 安全查找类，找不到返�?null（借鉴 LSPilot findClassOrNull�?
     */
    fun findClassOrNull(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            XposedHelpers.findClass(className, classLoader)
        } catch (_: Throwable) {
            null
        }
    }
}

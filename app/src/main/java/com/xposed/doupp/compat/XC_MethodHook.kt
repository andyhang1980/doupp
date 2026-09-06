package com.xposed.doupp.compat

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Member

/**
 * Compat 兼容层：模拟旧版 XposedBridge (de.robv.android.xposed) API 82 的
 * XC_MethodHook / MethodHookParam 语义，底层支持两种后端：
 *   - LibXposed API 101（LSPosed 现代 API）
 *   - 传统 Xposed API 82（FPA/太极/LSPatch 等免root框架）
 *
 * LibXposed 后端：API 101 采用 OkHttp 风格拦截器链（Hooker.intercept(Chain)），
 * 这里把 before+after 合并到单个 intercept 中模拟：
 *   1. 先执行 beforeHookedMethod
 *   2. 若 before 中设置了 result（param.result = ... 或 setResult）→ 跳过原方法返回该值
 *   3. 否则调用 chain.proceed(args) 执行原方法（参数可被 before 修改）
 *   4. 执行 afterHookedMethod（可再次修改 result 覆盖返回值）
 *
 * 传统 Xposed 后端：直接把 hook 适配成 de.robv.android.xposed.XC_MethodHook，
 * 其原生 before/after 语义与本抽象完全一致（before 设 result 即跳原方法）。
 *
 * 兼容目标：让项目其余 hook 文件只改 import，不改业务逻辑。
 */
abstract class XC_MethodHook {

    /** LibXposed 后端 before/after 阶段标记：result 赋值语义依赖当前相位 */
    internal enum class Phase { BEFORE, AFTER }

    /**
     * 前置回调：在原始方法执行前调用。
     * 如需跳过原始方法，在 before 中设置 param.result 即可。
     */
    open fun beforeHookedMethod(param: MethodHookParam) {}

    /**
     * 后置回调：在原始方法执行后调用，可读取/修改 param.result。
     */
    open fun afterHookedMethod(param: MethodHookParam) {}

    /**
     * 把本 Hook 适配成 LibXposed API 101 的 Hooker。
     */
    internal fun toHooker(): XposedInterface.Hooker {
        return XposedInterface.Hooker { chain ->
            val param = LibXposedParam(chain)
            beforeHookedMethod(param)
            if (param._resultSetByBefore) {
                // before 中显式设置了 result → 跳过原方法
                return@Hooker param.result
            }
            // 执行原方法（若 before 中改过 args，已体现在 param.args 中）
            param._phase = Phase.AFTER
            param.result = chain.proceed(param.args)
            afterHookedMethod(param)
            return@Hooker param.result
        }
    }

    /**
     * 把本 Hook 适配成传统 Xposed API 82 的 XC_MethodHook。
     * 返回类型为 Any（避免编译期直接依赖 de.robv.android.xposed 强类型，
     * 实际实例是 de.robv.android.xposed.XC_MethodHook）。
     */
    internal fun toLegacyHook(): Any {
        val hook = this
        return object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                try { hook.beforeHookedMethod(LegacyParam(param)) } catch (_: Throwable) {}
            }

            override fun afterHookedMethod(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
                try { hook.afterHookedMethod(LegacyParam(param)) } catch (_: Throwable) {}
            }
        }
    }

    /**
     * 与旧 API 的 XC_MethodHook.Unhook 兼容：可解绑 Hook。
     * handle 可能是 LibXposed 的 HookHandle 或传统 API 的 Unhook。
     */
    class Unhook internal constructor(
        private val handle: Any?
    ) {
        fun unhook() {
            try {
                if (handle == null) return
                val m = handle.javaClass.methods.firstOrNull {
                    it.name == "unhook" && it.parameterCount == 0
                } ?: return
                m.isAccessible = true
                m.invoke(handle)
            } catch (_: Throwable) {}
        }
    }

    /**
     * 与旧 API 的 XC_MethodHook.MethodHookParam 兼容的抽象参数对象。
     * 两种后端各自实现。
     */
    abstract class MethodHookParam {
        /** 被 Hook 的方法/构造器 */
        abstract val method: Member

        /** this 指针；静态方法为 null */
        abstract val thisObject: Any?

        /** 参数数组。before 中修改元素或整体替换后，会用于调用原方法 */
        abstract var args: Array<Any?>

        /** 返回值。before 中设置 → 跳过原方法；after 中设置 → 覆盖返回值 */
        abstract var result: Any?

        /** 获取第 index 个参数 */
        open fun getArg(index: Int): Any? {
            return if (index in args.indices) args[index] else null
        }

        /** 原始调用抛出的异常（传统 API 可读取；LibXposed 链式模型由 proceed 抛出） */
        open var throwable: Throwable? = null
    }

    /**
     * LibXposed 后端参数实现。
     */
    internal class LibXposedParam internal constructor(
        private val chain: XposedInterface.Chain
    ) : MethodHookParam() {

        override val method: Member
            get() = chain.getExecutable()

        override val thisObject: Any?
            get() = chain.getThisObject()

        private var _args: Array<Any?> = chain.getArgs().toTypedArray()

        override var args: Array<Any?>
            get() = _args
            set(value) {
                _args = value
            }

        internal var _phase = Phase.BEFORE

        internal var _resultSetByBefore = false

        override var result: Any? = null
            set(value) {
                field = value
                if (_phase == Phase.BEFORE) {
                    _resultSetByBefore = true
                }
            }
    }

    /**
     * 传统 Xposed 后端参数实现：直接包装 de.robv.android.xposed 的 MethodHookParam。
     */
    internal class LegacyParam internal constructor(
        private val legacy: de.robv.android.xposed.XC_MethodHook.MethodHookParam
    ) : MethodHookParam() {

        override val method: Member
            get() = legacy.method

        override val thisObject: Any?
            get() = legacy.thisObject

        override var args: Array<Any?>
            get() = legacy.args
            set(value) { legacy.args = value }

        override var result: Any?
            get() = legacy.result
            set(value) { legacy.result = value }

        override var throwable: Throwable?
            get() = legacy.throwable
            set(value) { legacy.throwable = value }
    }
}

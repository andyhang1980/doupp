package com.xposed.doupp.hook

import android.os.Handler
import android.os.Looper
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.DexKitManager
import com.xposed.doupp.util.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 官方 AutoPlayController 自动连播（运行时 DexKit 自适应）
 *
 * 原理（已逆向确认，见 39.6 AutoPlayViewModel）:
 * 抖音主 feed 的官方自动连播由 AutoPlayViewModel 控制，其自动连播有两个门控:
 *   - hN1(): Boolean  —— 用户开关（读取 Keva 键 "auto_play_key"）
 *   - jN1(): Boolean  —— 功能总开关（AutoPlayConfig.main_switch && inherent_need，服务端下发）
 * AutoPlayComponent 在所有关键路径（playableFinished / awemeCompleted / moveToNext）均先判断这两个方法，
 * 都为 true 时调用 triggerAutoPlayTask -> canMoveToNext -> 自动滑到下一视频（官方机制，无模拟滑动）。
 *
 * 本 Hook 通过 DexKit 在运行时按字符串 "auto_play_key" 定位混淆后的 AutoPlayViewModel，
 * 再按结构（static ()Z / ()Z / ()LiveData + LiveData 字段）识别 hN1/jN1/gN1/字段 e，
 * 不再写死 39.6 混淆名。当模块自动播放开关关闭时不干预（保留官方行为）。
 *
 * triggerMoveToNext() 沿用官方机制：向 AutoPlayViewModel.e (QLiveData) postValue，
 * 其 moveToNextActionObserver 会执行 feedViewPagerContext.LJIILIIL("AutoPlayComponent", true)。
 */
class AutoPlayControllerHook : BaseHook {

    companion object {
        private const val TAG = "AutoPlayCtrl"

        // 39.6 兜底类名（DexKit 命中失败时使用）
        private const val VM_CLASS_FALLBACK =
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayViewModel"

        @Volatile
        private var installed = false

        @Volatile
        private var currentAutoPlayVM: Any? = null

        // 动态识别到的字段 e 名（triggerMoveToNext 使用），默认 39.6 的 "e"
        @Volatile
        private var eFieldName = "e"

        private val mainHandler = Handler(Looper.getMainLooper())

        private val hooked = HashSet<String>()

        private fun enabled(): Boolean = DouSettings.isAutoPlayEnabled()

        /**
         * 走官方机制跳到下一个视频（不模拟触摸）。
         */
        @JvmStatic
        fun triggerMoveToNext() {
            val vm = currentAutoPlayVM ?: return
            try {
                val eField = vm.javaClass.getDeclaredField(eFieldName)
                eField.isAccessible = true
                val liveData = eField.get(vm) ?: return
                mainHandler.post {
                    try {
                        val post = liveData.javaClass.getMethod("postValue", Any::class.java)
                        post.invoke(liveData, "")
                    } catch (t: Throwable) {
                        HookUtils.log("$TAG: triggerMoveToNext postValue fail: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                HookUtils.log("$TAG: triggerMoveToNext fail: ${t.message}")
            }
        }
    }

    override fun tag() = TAG
    override fun isInstalled(): Boolean = installed

    override fun init(classLoader: ClassLoader) {
        if (installed) return
        HookUtils.safeHook {
            val clazz = findAutoPlayViewModelClass(classLoader) ?: run {
                HookUtils.log("$TAG: 未找到 AutoPlayViewModel")
                return@safeHook
            }
            HookUtils.log("$TAG: 找到 AutoPlayViewModel=${clazz.name}")

            val structure = analyzeStructure(clazz) ?: run {
                HookUtils.log("$TAG: 结构识别失败，放弃")
                return@safeHook
            }
            HookUtils.log("$TAG: 结构识别 hN1=${structure.hN1}, jN1=${structure.jN1}, gN1=${structure.gN1}, e=${structure.eField}")
            eFieldName = structure.eField

            // hN1(): isAutoPlayEnabled
            hookBooleanMethod(clazz, structure.hN1)
            // jN1(): static 功能总开关
            hookBooleanMethod(clazz, structure.jN1)
            // 构造后把开关 LiveData 置为 true
            hookConstructor(clazz, structure.gN1)

            if (hooked.isNotEmpty()) {
                installed = true
                HookUtils.log("$TAG: 安装完成, hooks=${hooked.joinToString()}")
            } else {
                HookUtils.log("$TAG: 未挂上任何方法")
            }
        }
    }

    /**
     * 通过 DexKit 字符串定位候选类，再用结构特征确定 AutoPlayViewModel。
     * 找不到时回退到 39.6 硬编码类名。
     */
    private fun findAutoPlayViewModelClass(classLoader: ClassLoader): Class<*>? {
        val candidates = DexKitManager.findClassesByStrings(listOf("auto_play_key"))
        HookUtils.log("$TAG: DexKit 候选类: $candidates")
        for (name in candidates) {
            try {
                val c = Class.forName(name, false, classLoader)
                if (analyzeStructure(c) != null) {
                    HookUtils.log("$TAG: 结构匹配 -> $name")
                    return c
                }
            } catch (_: Throwable) {
            }
        }
        HookUtils.log("$TAG: DexKit 无果，回退硬编码类名")
        return try {
            Class.forName(VM_CLASS_FALLBACK, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 识别 AutoPlayViewModel 的关键成员:
     * - hN1: 引用字符串 "auto_play_key" 的 ()Z 非 static 方法（用户开关）
     * - jN1: static ()Z 方法（功能总开关）
     * - gN1: () 返回类型名含 LiveData 的非 static 方法（开关 LiveData）
     * - e:   类型名含 LiveData 的字段（moveToNext 触发器）
     */
    private fun analyzeStructure(clazz: Class<*>): VMStructure? {
        val methods = clazz.declaredMethods

        val dexHN1 = DexKitManager.findMethodNameByStrings(clazz.name, listOf("auto_play_key"))
        val hN1 = if (dexHN1 != null && isBoolZero(clazz, dexHN1)) {
            dexHN1
        } else {
            methods.firstOrNull {
                !Modifier.isStatic(it.modifiers) &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.isEmpty()
            }?.name
        } ?: return null

        val jN1 = methods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.isEmpty()
        }?.name ?: return null

        val gN1 = methods.firstOrNull {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() &&
                it.returnType.name.contains("LiveData")
        }?.name ?: return null

        val eField = clazz.declaredFields.firstOrNull {
            it.type.name.contains("LiveData")
        }?.name ?: return null

        return VMStructure(hN1, jN1, gN1, eField)
    }

    private fun isBoolZero(clazz: Class<*>, name: String): Boolean {
        return try {
            val m = clazz.getDeclaredMethod(name)
            !Modifier.isStatic(m.modifiers) &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterTypes.isEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private data class VMStructure(
        val hN1: String,
        val jN1: String,
        val gN1: String,
        val eField: String
    )

    private fun hookBooleanMethod(clazz: Class<*>, name: String) {
        try {
            val m: Method = clazz.getDeclaredMethod(name)
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enabled()) {
                        param.result = true
                    }
                }
            })
            hooked.add(name)
            HookUtils.log("$TAG: hook $name ok")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: hook $name fail: ${t.message}")
        }
    }

    private fun hookConstructor(clazz: Class<*>, gN1: String) {
        try {
            val ctor = clazz.declaredConstructors.firstOrNull() ?: return
            ctor.isAccessible = true
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val vm = param.thisObject ?: return
                        currentAutoPlayVM = vm
                        if (!enabled()) return
                        val gMethod = clazz.getDeclaredMethod(gN1)
                        gMethod.isAccessible = true
                        val liveData = gMethod.invoke(vm) ?: return
                        val post = liveData.javaClass.getMethod("postValue", Any::class.java)
                        post.invoke(liveData, java.lang.Boolean.TRUE)
                    } catch (_: Throwable) {
                    }
                }
            })
            hooked.add("<init>")
            HookUtils.log("$TAG: hook <init> ok")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: hook <init> fail: ${t.message}")
        }
    }
}

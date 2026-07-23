package com.xposed.doupp.hook

import android.os.Handler
import android.os.Looper
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.DexKitManager
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaCache
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

        // Keva 同步缓存（避免每次反射）
        @Volatile private var kevaContainer: Any? = null
        @Volatile private var kevaKeys: Any? = null
        @Volatile private var kevaPutMethod: Method? = null
        @Volatile private var kevaAutoPlayKey: Any? = null
        /** 上一次同步到 Keva 的值，避免反复写入 */
        @Volatile private var lastKevaSync: Boolean? = null
        /** enabled() 结果缓存 TTL */
        @Volatile private var lastEnabled: Boolean = true
        @Volatile private var lastEnabledTime: Long = 0L
        private const val ENABLED_CACHE_MS = 200L

        // triggerMoveToNext 供视频过滤（VideoFilterHook）跳过使用
    private fun enabled(): Boolean {
        val now = System.currentTimeMillis()
        // 200ms 内复用上一个结果，避免高频反射开销
        if (now - lastEnabledTime < ENABLED_CACHE_MS) return lastEnabled
        val autoPlay = DouSettings.isAutoPlayEnabled()
        if (!autoPlay) {
            lastEnabled = false
            lastEnabledTime = now
            HookUtils.log("$TAG: enabled=false (autoPlay=$autoPlay)")
            syncKevaIfNeeded(false)
            return false
        }
        val live = isCurrentAwemeLive()
        val result = !live
        lastEnabled = result
        lastEnabledTime = now
        syncKevaIfNeeded(result)
        return result
    }

    private fun syncKevaIfNeeded(value: Boolean) {
        if (lastKevaSync == value) return
        lastKevaSync = value
        val put = kevaPutMethod
        val container = kevaContainer
        val key = kevaAutoPlayKey
        if (put == null || container == null || key == null) return
        try {
            put.invoke(container, key, value)
            HookUtils.log("$TAG: syncKeva -> $value ok")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: syncKeva fail: ${t.message}")
        }
    }

    /**
     * 运行时扫描 C1714 类，自动查找 Keva 容器（类型含 m4078 方法的静态字段）
     * 和键数组（InterfaceC1354[] 静态字段）及 auto-play 键（索引 22）。
     */
    @JvmStatic
    fun initKevaCache(classLoader: ClassLoader) {
        if (kevaContainer != null) return
        try {
            val c1714 = Class.forName("yyds.C1714", false, classLoader)
            // 找键数组: 找到 InterfaceC1354[] 静态字段（取索引 22 = auto-play 键）
            val iface1354Class = try { Class.forName("yyds.InterfaceC1354", false, classLoader) } catch (_: Throwable) { null }
            for (f in c1714.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type.isArray) {
                    try {
                        val arrObj = f.get(null) ?: continue
                        val len = java.lang.reflect.Array.getLength(arrObj)
                        if (len == 0) continue
                        val elem = java.lang.reflect.Array.get(arrObj, 0) ?: continue
                        // 判断元素是否为 InterfaceC1354 实例
                        if (iface1354Class == null || iface1354Class.isInstance(elem)) {
                            f.isAccessible = true
                            val arr = arrObj as? Array<*> ?: continue
                            kevaKeys = arr
                            if (len > 22) {
                                kevaAutoPlayKey = arr[22]
                                HookUtils.log("$TAG: Keva 键数组=${f.name}, size=$len")
                            }
                            break
                        }
                    } catch (_: Throwable) {}
                }
            }
            val ifaceKey = kevaAutoPlayKey?.javaClass ?: run {
                HookUtils.log("$TAG: Keva 未找到键类型")
                return
            }
            // 找 Keva 容器: 静态字段，其类型含 put(ifaceKey, Object) 方法
            for (f in c1714.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) && !f.type.isArray) {
                    for (m in f.type.declaredMethods) {
                        if (m.parameterCount == 2 && m.returnType == Void.TYPE &&
                            m.parameterTypes[0] == ifaceKey && m.parameterTypes[1] == Any::class.java) {
                            f.isAccessible = true
                            kevaContainer = f.get(null)
                            kevaPutMethod = m
                            HookUtils.log("$TAG: Keva 容器=${f.name}.${m.name}(${m.parameterTypes[0].simpleName},${m.parameterTypes[1].simpleName})")
                            break
                        }
                    }
                    if (kevaContainer != null) break
                }
            }
            if (kevaContainer == null) {
                // 兜底: 只在 C2429 类中找 put(InterfaceC1354, Object) 方法
                val c2429 = try { Class.forName("yyds.C2429", false, classLoader) } catch (_: Throwable) { null }
                if (c2429 != null) {
                    for (m in c2429.declaredMethods) {
                        if (m.parameterCount == 2 && m.returnType == Void.TYPE &&
                            m.parameterTypes[1] == Any::class.java && m.parameterTypes[0].name.contains("InterfaceC")) {
                            for (f in c1714.declaredFields) {
                                if (java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == c2429) {
                                    f.isAccessible = true
                                    kevaContainer = f.get(null)
                                    kevaPutMethod = m
                                    HookUtils.log("$TAG: Keva 容器 兜底=${f.name}.${m.name}()")
                                    break
                                }
                            }
                            if (kevaContainer != null) break
                        }
                    }
                }
            }
            if (kevaContainer == null) HookUtils.log("$TAG: Keva 容器未找到")
            if (kevaAutoPlayKey == null) HookUtils.log("$TAG: Keva auto-play key 未找到")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: initKevaCache 失败: ${t.message}")
        }
    }


    private fun isCurrentAwemeLive(): Boolean {
        val aweme = MediaCache.getCurrentAweme() ?: return false
        return try {
            val cls = aweme.javaClass
            for (f in listOf("live", "mLive", "liveRoom", "roomInfo")) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    if (field.get(aweme) != null) return true
                } catch (_: Throwable) {}
            }
            for (f in listOf("liveId", "roomId", "mLiveId")) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    val v = field.get(aweme)
                    if (v is String && v.isNotEmpty()) return true
                } catch (_: Throwable) {}
            }
            try {
                val m = cls.declaredMethods.firstOrNull {
                    it.parameterCount == 0 && it.name.equals("isLive", ignoreCase = true)
                }
                if (m != null) {
                    val r = m.invoke(aweme)
                    if (r is Boolean && r) return true
                }
            } catch (_: Throwable) {}
            false
        } catch (_: Throwable) {
            false
        }
    }

        /**
         * 走官方机制跳到下一个视频（不模拟触摸）。
         */
        @JvmStatic
        fun triggerMoveToNext() {
            if (!DouSettings.isAutoPlayEnabled()) {
                HookUtils.log("$TAG: triggerMoveToNext blocked (autoPlay off)")
                return
            }
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

            // 只 Hook hN1() 用户开关——不 Hook 构造器（避免 postValue 触发过早自动播放）
            // 不 Hook jN1()（服务端总开关，保留对时机的控制）
            // 不 Hook 其他候选类（避免误改播放进度检测方法）
            hookBooleanMethod(clazz, structure.hN1)
            // hookBooleanMethod(clazz, structure.jN1)
            // hookConstructor(clazz, structure.gN1)

            // 尝试初始化 Keva 缓存（仅用于兜底同步，不依赖其成功）
            initKevaCache(classLoader)

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
                    val v = enabled()
                    if (!v) HookUtils.log("$TAG: $name beforeHook -> false")
                    param.result = v
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = enabled()
                    if (param.result != v) HookUtils.log("$TAG: $name afterHook override ${param.result} -> $v")
                    param.result = v
                }
            })
            hooked.add(name)
            HookUtils.log("$TAG: hook $name ok")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: hook $name fail: ${t.message}")
        }
    }

    /**
     * 对 DexKit 找到的其它 auto_play_key 候选类做防御性 Hook：
     * - hook 所有 ()Z 方法返回 enabled()
     * - hook 所有 ()V 方法并判断是否屏蔽
     * - 记录构造器调用
     */
    private fun hookOtherAutoPlayCandidates(classLoader: ClassLoader, viewModelName: String) {
        val candidates = DexKitManager.findClassesByStrings(listOf("auto_play_key"))
        for (cName in candidates) {
            if (cName == viewModelName) continue
            val clazz = try { Class.forName(cName, false, classLoader) } catch (_: Throwable) { null }
            if (clazz == null) {
                HookUtils.log("$TAG: 候选类 $cName 未加载")
                continue
            }
            HookUtils.log("$TAG: 候选类 $cName 已加载，防御性 Hook")

            // 使用 DexKit 查找引用 "auto_play_key" 的方法（最相关）
            val keyMethod = DexKitManager.findMethodNameByStrings(cName, listOf("auto_play_key"))
            if (keyMethod != null) {
                try {
                    val km = clazz.getDeclaredMethod(keyMethod)
                    if (km.returnType == Boolean::class.javaPrimitiveType && km.parameterTypes.isEmpty()) {
                        hookBooleanMethod(clazz, keyMethod)
                        HookUtils.log("$TAG: $cName.$keyMethod (auto_play_key) hook ok")
                    }
                } catch (_: Throwable) {}
            }

            // 不 Hook 其他 ()Z 方法 —— 只 Hook 明确引用 auto_play_key 的方法，
            // 避免误改播放进度检测等方法的返回值导致未播完就跳转
            // Hook 所有构造器（诊断）
            for (ctor in clazz.declaredConstructors) {
                try {
                    ctor.isAccessible = true
                    XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val sig = ctor.parameterTypes.joinToString(",")
                            HookUtils.log("$TAG: $cName.<init>($sig) 实例化")
                        }
                    })
                } catch (_: Throwable) {}
            }
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
                        val gMethod = clazz.getDeclaredMethod(gN1)
                        gMethod.isAccessible = true
                        val liveData = gMethod.invoke(vm) ?: return
                        val post = liveData.javaClass.getMethod("postValue", Any::class.java)
                        post.invoke(liveData, enabled())
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

package com.xposed.doupp.hook

import android.os.Handler
import android.os.Looper
import com.xposed.doupp.ui.DouSettings
import com.xposed.doupp.util.ClassFinder
import com.xposed.doupp.util.DexKitManager
import com.xposed.doupp.util.HookUtils
import com.xposed.doupp.util.MediaCache
import com.xposed.doupp.compat.XC_MethodHook
import com.xposed.doupp.compat.XposedBridge
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
 *
 * ## 40.7.0 实测（dexdump classes43.dex）
 * - 载体类 AutoPlayViewModel / AutoPlayComponent 在 feed.plato 包下**没被混淆**，
 *   方法名变成 Xz(功能总开关 static)/Wz(用户开关)/Vz(开关 LiveData)，字段 e 是 QLiveData。
 *   所以按结构识别成员（static ()Z / ()Z / ()QLiveData / QLiveData 字段）而不是写死名字。
 * - 开关值存在 Keva 里，键名就是字面量 "auto_play_key"：
 *     读 LX/1QnY;.LIZ(String)Z  -> Keva.getBoolean(String, boolean)
 *     写 LX/1QnY;.LIZIZ()Keva   -> Keva.storeBoolean(String, boolean)
 *   载体 LX/1QnY 每版本会变，Keva（com.bytedance.keva.Keva）是稳定库，所以拦在 Keva 层。
 */
class AutoPlayControllerHook : BaseHook {

    companion object {
        private const val TAG = "AutoPlayCtrl"

        // 39.6 兜底类名（DexKit 命中失败时使用）
        private const val VM_CLASS_FALLBACK =
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayViewModel"

        /** 抖音主包：种子表按包过滤时用 */
        private const val AWEME_ROOT = "com.ss.android.ugc.aweme"

        @Volatile
        private var installed = false

        @Volatile
        private var currentAutoPlayVM: Any? = null

        // 动态识别到的字段 e 名（triggerMoveToNext 使用），默认 39.6 的 "e"
        @Volatile
        private var eFieldName = "e"

        private val mainHandler = Handler(Looper.getMainLooper())

        private val hooked = HashSet<String>()

        // Keva 通道：40.7.0 实测抖音把自动连播开关存在 Keva 里，键名就是字面量 "auto_play_key"
        @Volatile private var kevaHooked = 0
        private val kevaReads = java.util.concurrent.atomic.AtomicLong(0)
        private val kevaWrites = java.util.concurrent.atomic.AtomicLong(0)

        /** 抖音 Keva 里自动连播的键名（dexdump classes43.dex 实证） */
        private const val AUTO_PLAY_KEY = "auto_play_key"

        /** 40.7.0 上 Keva 的真实包名（字节自研，托管在 com.bytedance.keva） */
        private const val KEVA_CLASS = "com.bytedance.keva.Keva"

        // triggerMoveToNext 供视频过滤（VideoFilterHook）跳过使用

    private fun enabled(): Boolean {
        val autoPlay = DouSettings.isAutoPlayEnabled()
        if (!autoPlay) {
            HookUtils.log("$TAG: enabled=false (autoPlay=false)")
            return false
        }
        val live = isCurrentAwemeLive()
        if (live) {
            HookUtils.log("$TAG: enabled=false (live=true)")
            return false
        }
        // 官方仅在 playableFinished / awemeCompleted（视频播完）时读取 rL1/tL1 决定是否连播，
        // 播完时进度必然是 100%，因此这里不需要进度门控 —— 之前的 70% 进度检查会在视频
        // 开头把用户开关读到 false，导致官方 uL1() 直接 postValue FALSE 取消整个连播调度。
        return true
    }

    /**
     * 拦 Keva 里的 auto_play_key 读写，让官方的自动连播开关跟着模块走。
     *
     * 40.7.0 实测（dexdump classes43.dex）：
     *   读  LX/1QnY;.LIZ("auto_play_key")Z -> Keva.getBoolean(String, boolean)
     *   写  LX/1QnY;.LIZIZ()Keva -> Keva.storeBoolean("auto_play_key", v)
     * 载体 LX/1QnY 每个版本都会变，Keva 是稳定库 —— 所以拦在 Keva 这一层，
     * 不去猜「哪个混淆类持有容器」（39.x 那种反射 C1714 静态字段的做法在新版本上全是死代码）。
     */
    private fun hookKevaChannel(classLoader: ClassLoader) {
        val hookedClasses = HashSet<String>()
        var total = 0

        fun hookOn(cls: Class<*>): Int {
            if (!hookedClasses.add(cls.name)) return 0
            var n = 0
            for (m in cls.declaredMethods) {
                if (Modifier.isStatic(m.modifiers)) continue
                val p0 = m.parameterTypes.getOrNull(0)
                val p1 = m.parameterTypes.getOrNull(1)
                val isBoolSlot =
                    m.parameterCount == 2 && p0 == String::class.java && p1 == Boolean::class.javaPrimitiveType

                // 读：键命中就返回模块开关，官方后面所有判断自动跟着变
                if (isBoolSlot && m.name == "getBoolean" &&
                    m.returnType == Boolean::class.javaPrimitiveType
                ) {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.getArg(0) as? String != AUTO_PLAY_KEY) return
                            val want = enabled()
                            param.result = want
                            val n = kevaReads.incrementAndGet()
                            if (n <= 2L || n % 100L == 0L) {
                                HookUtils.log("$TAG: Keva 读 $AUTO_PLAY_KEY -> $want（第 $n 次）")
                            }
                        }
                    })
                    n++
                }

                // 写：把官方写进去的值改成模块开关。
                // 不吞掉写入 —— 吞了会让官方 UI 显示和实际状态对不上，用户会以为没生效。
                if (isBoolSlot && m.name == "storeBoolean" && m.returnType == Void.TYPE) {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.getArg(0) as? String != AUTO_PLAY_KEY) return
                            val want = enabled()
                            val origin = param.getArg(1)
                            if (origin != want) {
                                param.args[1] = want
                                val n = kevaWrites.incrementAndGet()
                                HookUtils.log("$TAG: Keva 写 $AUTO_PLAY_KEY 改写 $origin -> $want（第 $n 次）")
                            }
                        }
                    })
                    n++
                }
            }
            total += n
            return n
        }

        val keva = try {
            Class.forName(KEVA_CLASS, false, classLoader)
        } catch (t: Throwable) {
            HookUtils.log("$TAG: Keva 未加载(${t.message})，仅靠 ViewModel 门控")
            return
        }
        // Keva 是 PUBLIC ABSTRACT，getBoolean/storeBoolean 由实现类（40.7.0 是 KevaImpl）实现。
        // 只 hook 抽象基类不会生效 —— invoke-virtual 直接分派到实现类，这就是之前一次读取日志都没有的原因。
        val n = hookOn(keva)
        for (implName in listOf("com.bytedance.keva.KevaImpl", "com.tencent.keva.Keva")) {
            runCatching { Class.forName(implName, false, classLoader) }
                .onSuccess { hookOn(it) }
        }
        if (n == 0) {
            HookUtils.log("$TAG: 基类上没有可 hook 的读写方法（实现类已另挂 ${hookedClasses.size} 个）")
        }

        // 工厂兜底：任何实现类的实例都从 getRepo 过，拿到实例就把它的 class 也挂上，
        // 这样换了实现类名也不用改代码。
        var repoHooked = 0
        for (m in keva.declaredMethods) {
            if (!Modifier.isStatic(m.modifiers)) continue
            if (!m.name.startsWith("getRepo")) continue
            if (m.returnType != keva) continue
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val impl = param.result?.javaClass ?: return
                    val before = total
                    if (hookOn(impl) > 0 && total > before) {
                        HookUtils.log("$TAG: 从 getRepo 反推实现类 ${impl.name}")
                    }
                }
            })
            repoHooked++
        }
        kevaHooked = total
        HookUtils.log(
            "$TAG: Keva 通道 hook $total 个读写方法，挂载 ${hookedClasses.size} 个类，" +
                "getRepo 工厂 $repoHooked 个"
        )
    }


    private fun isCurrentAwemeLive(): Boolean {
        val aweme = MediaCache.getCurrentAweme() ?: return false
        return try {
            val cls = aweme.javaClass
            // 39.8 的 Aweme.isLive() 语义反直觉：awemeType==101 才是直播，
            // 其它类型 isLive() 返回 true，因此不能调用该方法（会把普通视频误判为直播，
            // 导致 enabled() 返回 false、官方取消连播调度）。
            // 正确判断：awemeType == 101。
            try {
                val typeField = cls.getDeclaredField("awemeType")
                typeField.isAccessible = true
                val tv = typeField.get(aweme)
                if (tv is Number) {
                    val live = tv.toInt() == 101
                    if (live) HookUtils.log("$TAG: isCurrentAwemeLive=true (awemeType=${tv})")
                    return live
                }
            } catch (_: Throwable) {}
            // 明确直播标志兜底
            for (f in listOf("isDetailLive", "isEcomLive", "isLiveReplay")) {
                try {
                    val field = cls.getDeclaredField(f)
                    field.isAccessible = true
                    val v = field.get(aweme)
                    if (v is Boolean && v) {
                        HookUtils.log("$TAG: isCurrentAwemeLive=true (字段 $f=true)")
                        return true
                    }
                } catch (_: Throwable) {}
            }
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
            doMoveToNext()
        }

        /**
         * 强制跳转到下一个视频。
         * 供视频过滤（直播/图文/广告/购物视频跳过）使用。
         * 受自动播放开关控制：关闭自动播放时任何自动切换到下一个的行为都停止。
         */
        @JvmStatic
        fun triggerMoveToNextForce() {
            if (!DouSettings.isAutoPlayEnabled()) {
                HookUtils.log("$TAG: triggerMoveToNextForce blocked (autoPlay off)")
                return
            }
            doMoveToNext()
        }

        private fun doMoveToNext() {
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

            // Hook hN1() 和 jN1()（用户开关 + 功能总开关）。官方只在视频播完后读取它们
            // 决定是否连播，因此这里直接返回用户开关状态即可，无需进度门控。
            hookBooleanMethod(clazz, structure.hN1)
            hookBooleanMethod(clazz, structure.jN1)

            // Keva 通道：官方的 auto_play_key 读写在这里拦（40.7.0 上有效）
            hookKevaChannel(classLoader)

            if (hooked.isNotEmpty()) {
                installed = true
                HookUtils.log("$TAG: 安装完成, hooks=${hooked.joinToString()}")
            } else {
                HookUtils.log("$TAG: 未挂上任何方法")
            }

            hookAutoPlayComponent(classLoader)
        }
    }

    /**
     * [39.8] hook AutoPlayComponent.triggerAutoPlayTask —— 官方自动连播的真正执行入口。
     *
     * 背景: 39.8 中 AutoPlayViewModel.rL1()（用户开关）返回值被我们强制 false 后，
     * 官方连播仍会发生 —— 因为 triggerAutoPlayTask 内部直接读 Keva 的 auto_play_key
     * （Keva 容器类在 39.8 已改名，模块无法写入），不经过 rL1()。因此必须在此拦截。
     */
    private fun hookAutoPlayComponent(classLoader: ClassLoader) {
        try {
            val compClass = try {
                Class.forName(
                    "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayComponent",
                    false, classLoader
                )
            } catch (_: Throwable) {
                HookUtils.log("$TAG: AutoPlayComponent 未加载")
                return
            }
            var hookedAny = false
            for (m in compClass.declaredMethods) {
                if (m.name == "triggerAutoPlayTask" && m.parameterCount == 2) {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!DouSettings.isAutoPlayEnabled()) {
                                HookUtils.log("$TAG: triggerAutoPlayTask blocked (autoPlay off)")
                                param.result = null
                            }
                        }
                    })
                    HookUtils.log("$TAG: hook AutoPlayComponent.triggerAutoPlayTask ok")
                    hookedAny = true
                }
            }
            if (!hookedAny) HookUtils.log("$TAG: AutoPlayComponent.triggerAutoPlayTask 未找到")
        } catch (t: Throwable) {
            HookUtils.log("$TAG: hookAutoPlayComponent fail: ${t.message}")
        }
    }

    /**
     * 定位 AutoPlayViewModel，三级候选，逐个用结构特征验证：
     *
     *  1. 种子表（ClassFinder → DexStringSeed）：离线扫好的 关键词->类 映射，秒级、无重扫
     *  2. 语义类名：40.7.0 上 feed.plato 的 autoplay 包没被混淆，类名是稳定的
     *  3. DexKit：全量扫 437MB，只在前两级都没命中时兜底
     *
     * 40.7.0 实测 auto_play_key 命中 5 个类，其中两个是真身：
     *   ...contentconsumption.autoplay.AutoPlayComponent
     *   ...contentconsumption.autoplay.AutoPlayViewModel
     */
    private fun findAutoPlayViewModelClass(classLoader: ClassLoader): Class<*>? {
        val tried = LinkedHashSet<String>()

        // 1. 种子表
        val seeded = runCatching {
            ClassFinder.findClassNamesByStrings(classLoader, listOf(AWEME_ROOT), listOf(AUTO_PLAY_KEY))
        }.getOrDefault(emptyList())
        HookUtils.log("$TAG: 种子表候选 ${seeded.size} 个")

        // 2. 语义类名（多个版本的包路径都试一遍）
        val semantic = listOf(
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.autoplay.AutoPlayViewModel",
            "com.ss.android.ugc.aweme.contentconsumption.autoplay.AutoPlayViewModel",
            VM_CLASS_FALLBACK
        )

        // 3. DexKit 兜底
        val dexKit = runCatching {
            DexKitManager.findClassesByStrings(listOf(AUTO_PLAY_KEY))
        }.getOrDefault(emptyList())

        for (name in (seeded + semantic + dexKit).distinct()) {
            if (!tried.add(name)) continue
            val c = try {
                Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                null
            }
            if (c == null) continue
            if (analyzeStructure(c) != null) {
                HookUtils.log("$TAG: 结构匹配 -> $name")
                return c
            }
        }
        HookUtils.log("$TAG: 三级候选都没匹配上（试了 ${tried.size} 个）")
        return null
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
                    // 捕获 AutoPlayViewModel 实例（用于 triggerMoveToNextForce 跳转），
                    // 替代已移除的构造器 hook —— 构造器 postValue 会导致过早跳转，
                    // 这里仅记录实例，不改动任何值。
                    val vm = param.thisObject
                    if (vm != null && !Modifier.isStatic(m.modifiers)) {
                        currentAutoPlayVM = vm
                    }
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
}

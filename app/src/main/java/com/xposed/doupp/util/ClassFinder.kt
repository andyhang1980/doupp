package com.xposed.doupp.util

import java.util.concurrent.ConcurrentHashMap

/**
 * 类查找工具。
 *
 * 这一版修掉了两个把适配直接废掉的问题：
 *
 * 1. 原来用 `scannedClassLoaders` 标记「这个 ClassLoader 扫过了」，
 *    结果第一次扫完某个包之后，**所有后续包扫描都直接返回空**。
 *    现在改成按 ClassLoader 缓存完整的类名列表，任意包都能反复查。
 *
 * 2. 原来对整个包做 `Class.forName`，`com.ss.android.ugc.aweme` 是十万级类，
 *    会把宿主内存和启动时间一起拖垮。
 *    现在默认只按**类名**过滤，需要时才懒加载 Class，并且有上限。
 *
 * 字符串特征搜索改走 [DexStringIndex]（真读 dex 字符串池和 const-string 指令），
 * 之前那套只读注解和 String 静态字段，方法体里的字符串一个也搜不到。
 */
object ClassFinder {

    /** 单次 Class.forName 加载上限，防止把宿主拖爆 */
    @Volatile
    var classLoadLimit: Int = 500

    /** 索引的类名总数超过这个量就说明宿主超大，日志里提示一下 */
    @Volatile
    var indexWarnSize: Int = 300_000

    private val resolved = ConcurrentHashMap<String, Class<*>>()

    /** ClassLoader -> 全部类名（懒建，每个 CL 只建一次） */
    private val nameIndex = ConcurrentHashMap<ClassLoader, List<String>>()

    /** ClassLoader -> 混淆包根（懒发现） */
    private val rootsCache = ConcurrentHashMap<ClassLoader, List<String>>()

    // ------------------------------------------------------------------
    // 类名索引
    // ------------------------------------------------------------------

    /**
     * 拿到宿主 ClassLoader 里的全部类名（建索引，只遍历 dex 条目，不加载类）。
     */
    fun classNames(classLoader: ClassLoader): List<String> {
        nameIndex[classLoader]?.let { return it }
        val names = ArrayList<String>(200_000)
        collectNames(classLoader, names, HashSet())
        HookUtils.log("ClassFinder: 类名索引 ${names.size} 个")
        if (names.size > indexWarnSize) {
            HookUtils.log("ClassFinder: 宿主类数量偏大 (${names.size})，已限制单次加载 $classLoadLimit")
        }
        nameIndex[classLoader] = names
        return names
    }

    private fun collectNames(classLoader: ClassLoader, out: MutableList<String>, seen: MutableSet<ClassLoader>) {
        if (!seen.add(classLoader)) return
        try {
            val pathListField = findFieldInHierarchy(classLoader.javaClass, "pathList") ?: return
            pathListField.isAccessible = true
            val pathList = pathListField.get(classLoader) ?: return
            val dexElementsField = findFieldInHierarchy(pathList.javaClass, "dexElements") ?: return
            dexElementsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return
            for (el in dexElements) {
                if (el == null) continue
                try {
                    val dexFile = findFieldInHierarchy(el.javaClass, "dexFile")?.apply { isAccessible = true }?.get(el)
                        ?: continue
                    val entries = dexFile.javaClass.getMethod("entries").invoke(dexFile)
                        as? java.util.Enumeration<String> ?: continue
                    while (entries.hasMoreElements()) out.add(entries.nextElement())
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            HookUtils.log("ClassFinder: dex 条目遍历失败 ${t.message}")
        }
        // delegate / parent 兜底（部分定制 ClassLoader）
        try {
            val f = findFieldInHierarchy(classLoader.javaClass, "delegate")
            if (f != null) {
                f.isAccessible = true
                val d = f.get(classLoader) as? ClassLoader
                if (d != null && d !== classLoader) collectNames(d, out, seen)
            }
        } catch (_: Throwable) { }
        try {
            val p = classLoader.parent
            if (p != null && p !== classLoader && p !== ClassLoader.getSystemClassLoader()) {
                collectNames(p, out, seen)
            }
        } catch (_: Throwable) { }
    }

    private fun findFieldInHierarchy(start: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null) {
            try { return c.getDeclaredField(name) } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 混淆包根发现
    // ------------------------------------------------------------------

    /**
     * 发现被 R8 混淆出来的包根。
     *
     * 40.7.0 实测：抖音应用类并不在 `yyds` 包下（该包 0 命中），
     * 而是被打散进 X / Y / O / m / ms / j6 ... 这类单双字母根。
     * 所以这里按「一级包名长度 <= 2 且不是常见库前缀」来识别，
     * 同时保留 yyds 以兼容旧版本。
     */
    fun obfuscatedRoots(classLoader: ClassLoader): List<String> {
        rootsCache[classLoader]?.let { return it }
        val knownLibRoots = setOf(
            "com", "org", "io", "java", "kotlin", "kotlinx", "android", "androidx", "dalvik",
            "javax", "okhttp3", "okio", "retrofit2", "bolts", "bytedance", "me", "cn", "secondary"
        )
        val counts = HashMap<String, Int>()
        for (n in classNames(classLoader)) {
            if (n.startsWith("yyds.")) { counts["yyds"] = (counts["yyds"] ?: 0) + 1; continue }
            val i = n.indexOf('.')
            if (i <= 0) continue
            val top = n.substring(0, i)
            if (top in knownLibRoots) continue
            if (top.length <= 2) counts[top] = (counts[top] ?: 0) + 1
        }
        val roots = counts.entries.sortedByDescending { it.value }.map { it.key }
        rootsCache[classLoader] = roots
        HookUtils.log("ClassFinder: 混淆包根 = $roots")
        return roots
    }

    // ------------------------------------------------------------------
    // 按包查类
    // ------------------------------------------------------------------

    /** 只返回类名，不加载类 */
    fun classNamesInPackage(
        classLoader: ClassLoader,
        packageName: String,
        includeInner: Boolean = false,
        limit: Int = 4000
    ): List<String> {
        val prefix = if (packageName.endsWith(".")) packageName else "$packageName."
        val out = ArrayList<String>(256)
        for (n in classNames(classLoader)) {
            if (!n.startsWith(prefix)) continue
            if (!includeInner && n.contains('$')) continue
            out.add(n)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * 兼容旧接口：返回 Class 列表。
     * 现在按类名过滤后**懒加载**，并受 [classLoadLimit] 限制。
     */
    fun getClassesInPackage(
        classLoader: ClassLoader,
        packageName: String,
        includeInner: Boolean = false
    ): List<Class<*>> {
        val out = ArrayList<Class<*>>(64)
        for (n in classNamesInPackage(classLoader, packageName, includeInner, classLoadLimit)) {
            loadClass(n, classLoader)?.let { out.add(it) }
        }
        return out
    }

    private fun loadClass(name: String, classLoader: ClassLoader): Class<*>? {
        resolved[name]?.let { return it }
        return try {
            val c = Class.forName(name, false, classLoader)
            resolved[name] = c
            c
        } catch (_: Throwable) { null }
    }

    /** 按候选名列表找类 */
    fun findClass(classLoader: ClassLoader, candidates: List<String>): Class<*>? {
        for (name in candidates) {
            loadClass(name, classLoader)?.let { return it }
        }
        return null
    }

    fun findClass(classLoader: ClassLoader, className: String): Class<*>? =
        findClass(classLoader, listOf(className))

    // ------------------------------------------------------------------
    // 特征查找
    // ------------------------------------------------------------------

    /** 按方法签名特征找类（在候选类名上过滤，不做全量 Class.forName） */
    fun findByMethodSignature(
        classLoader: ClassLoader,
        packageName: String,
        methodSignatures: List<String>,
        nameHints: List<String> = emptyList()
    ): List<Class<*>> {
        val parsed = methodSignatures.map { sig ->
            val parts = sig.split(":")
            Triple(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" },
                parts.getOrElse(2) { "" }.split(",").filter { it.isNotEmpty() })
        }
        val pool = poolClassNames(classLoader, packageName, nameHints)
        val out = ArrayList<Class<*>>(8)
        for (n in pool) {
            val c = loadClass(n, classLoader) ?: continue
            runCatching {
                val ok = parsed.all { (mn, rt, pts) ->
                    c.declaredMethods.any { m ->
                        (m.name == mn || m.name.lowercase().contains(mn.lowercase())) &&
                            (rt.isEmpty() || m.returnType.name.contains(rt)) &&
                            (pts.isEmpty() || m.parameterTypes.size == pts.size)
                    }
                }
                if (ok) out.add(c)
            }
        }
        return out
    }

    /** 按字段签名特征找类 */
    fun findByFieldSignature(
        classLoader: ClassLoader,
        packageName: String,
        fieldSignatures: List<String>,
        nameHints: List<String> = emptyList()
    ): List<Class<*>> {
        val parsed = fieldSignatures.map { sig ->
            val parts = sig.split(":")
            parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
        }
        val pool = poolClassNames(classLoader, packageName, nameHints)
        val out = ArrayList<Class<*>>(8)
        for (n in pool) {
            val c = loadClass(n, classLoader) ?: continue
            runCatching {
                val ok = parsed.all { (fn, ft) ->
                    c.declaredFields.any { f ->
                        (f.name == fn || f.name.lowercase().contains(fn.lowercase())) &&
                            (ft.isEmpty() || f.type.name.contains(ft))
                    }
                }
                if (ok) out.add(c)
            }
        }
        return out
    }

    /**
     * 候选类名池：先按名字特征缩到很小，再考虑整包。
     * 这是让特征查找在十万级宿主上还能跑的关键。
     */
    private fun poolClassNames(
        classLoader: ClassLoader,
        packageName: String,
        nameHints: List<String>
    ): List<String> {
        if (nameHints.isNotEmpty()) {
            val hit = classNamesInPackage(classLoader, packageName, includeInner = true, limit = 60_000)
                .filter { n -> nameHints.any { n.contains(it, ignoreCase = true) } }
                .take(400)
            if (hit.isNotEmpty()) return hit
        }
        // 退一步：混淆包根下名字带关键词的类
        if (nameHints.isNotEmpty()) {
            val roots = obfuscatedRoots(classLoader)
            val hit = ArrayList<String>()
            for (r in roots) {
                hit.addAll(
                    classNamesInPackage(classLoader, r, includeInner = true, limit = 60_000)
                        .filter { n -> nameHints.any { n.contains(it, ignoreCase = true) } }
                )
                if (hit.size >= 400) break
            }
            if (hit.isNotEmpty()) return hit.take(400)
        }
        return classNamesInPackage(classLoader, packageName, includeInner = true, limit = 400)
    }

    /**
     * 按字符串特征找类。
     *
     * 之前这个方法只读注解 / String 静态字段 / 方法名，像 `auto_play_key` 这种写在
     * 方法体里的字符串常量一个也搜不到，所以自动播放、分享面板的字符串适配从来没生效。
     *
     * 现在两级：
     *   1) 项目里其实已经集成了 DexKit（org.luckypray:dexkit 2.1.0），优先用它
     *   2) DexKit 拿不到时退到 [DexStringIndex]（自己解析 dex 字符串池 + const-string 指令）。
     *      Xposed 注入的宿主进程里 libdexkit.so 加载失败很常见，这层兜底是必要的。
     */
    fun findByStringConstants(
        classLoader: ClassLoader,
        packageName: String,
        keywords: List<String>
    ): List<Class<*>> {
        val names = findClassNamesByStrings(classLoader, listOf(packageName), keywords)
        val out = ArrayList<Class<*>>(8)
        for (n in names) loadClass(n, classLoader)?.let { out.add(it) }
        return out
    }

    /** 资源名特征（share_panel 之类）本质也是字符串常量 */
    fun findByResourceRefs(classLoader: ClassLoader, resourceNames: List<String>): List<Class<*>> {
        val names = findClassNamesByStrings(classLoader, emptyList(), resourceNames)
        val out = ArrayList<Class<*>>(8)
        for (n in names) loadClass(n, classLoader)?.let { out.add(it) }
        return out
    }

    /**
     * 字符串特征 -> 类名列表（只出名字，不加载类）。
     * @param packages 限定包前缀，空表示不限定
     */
    fun findClassNamesByStrings(
        classLoader: ClassLoader,
        packages: List<String>,
        keywords: List<String>
    ): List<String> {
        if (keywords.isEmpty()) return emptyList()

        val viaDexKit = DexKitManager.findClassesByStrings(
            keywords,
            packages.ifEmpty { listOf("com.ss.android.ugc.aweme", "yyds", "X", "Y", "O") }
        )
        if (viaDexKit.isNotEmpty()) {
            HookUtils.log("ClassFinder: DexKit 命中 [$keywords] -> ${viaDexKit.size} 个类")
            return viaDexKit
        }
        HookUtils.log("ClassFinder: DexKit 未命中 [$keywords]，退回自带 dex 索引")

        val roots = obfuscatedRoots(classLoader).toSet()
        val out = LinkedHashSet<String>()
        for (d in DexStringIndex.classesUsingStrings(classLoader, keywords)) {
            val n = descriptorToName(d)
            when {
                packages.isEmpty() -> out.add(n)
                packages.any { n.startsWith(it) } -> out.add(n)
                roots.contains(n.substringBefore('.')) -> out.add(n)
            }
        }
        HookUtils.log("ClassFinder: dex 索引命中 [$keywords] -> ${out.size} 个类")
        return out.toList()
    }

    fun descriptorToName(desc: String): String = desc.trim('/').replace('/', '.').let {
        if (it.startsWith("L") && it.endsWith(";")) it.substring(1, it.length - 1) else it
    }

    fun nameToDescriptor(name: String): String = "L" + name.replace('.', '/') + ";"

    fun clearCache() {
        resolved.clear()
        nameIndex.clear()
        rootsCache.clear()
    }

    fun cacheSize(): Int = resolved.size
}

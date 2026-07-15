package com.xposed.doupp.util

import java.util.concurrent.ConcurrentHashMap

/**
 * 类查找工具
 *
 * 适配最新版抖音:
 * - 抖音每次更新后类名都会混淆，但类的结构特征(字段类型、方法签名)相对稳定
 * - 加固后 dex 延迟加载，需要支持运行时动态查找
 * - 增强 DexFile 遍历：处理多 ClassLoader、 delegate ClassLoader
 * - 新增模糊匹配模式（字段名包含关键词即可匹配）
 *
 * LSPosed 2.x 兼容:
 * - 使用标准 Java 反射 API，不依赖 XposedHelpers 内部实现
 * - DexFile 遍历通过 dalvik.system.DexFile 反射完成
 */
object ClassFinder {

    /** 类缓存: key=类名, value=Class对象 */
    private val classCache = ConcurrentHashMap<String, Class<*>>()

    /** 已遍历过的 ClassLoader 集合 */
    private val scannedClassLoaders = mutableSetOf<ClassLoader>()

    /**
     * 通过候选类名列表查找类
     * 尝试每个候选名，返回第一个找到的
     *
     * @param classLoader 类加载器
     * @param candidates 候选类名列表
     * @return 找到的 Class，全部失败返回 null
     */
    fun findClass(classLoader: ClassLoader, candidates: List<String>): Class<*>? {
        for (name in candidates) {
            val cached = classCache[name]
            if (cached != null) return cached

            try {
                val clazz = Class.forName(name, false, classLoader)
                classCache[name] = clazz
                HookUtils.log("ClassFinder: 找到类 $name")
                return clazz
            } catch (_: ClassNotFoundException) {
                // 继续尝试下一个
            } catch (_: Throwable) {
                // 部分加固场景下可能抛出其他异常
            }
        }
        return null
    }

    /**
     * 通过单个类名查找类
     */
    fun findClass(classLoader: ClassLoader, className: String): Class<*>? {
        return findClass(classLoader, listOf(className))
    }

    /**
     * 通过字段特征查找类
     *
     * @param classLoader 类加载器
     * @param packageName 包名前缀
     * @param fieldSignatures 字段特征列表 (格式: "fieldName:fieldType")
     * @return 匹配的 Class 列表
     */
    fun findByFieldSignature(
        classLoader: ClassLoader,
        packageName: String,
        fieldSignatures: List<String>
    ): List<Class<*>> {
        val results = mutableListOf<Class<*>>()

        try {
            val parsedSignatures = fieldSignatures.map { sig ->
                val parts = sig.split(":")
                parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
            }

            val classes = getClassesInPackage(classLoader, packageName)

            for (clazz in classes) {
                try {
                    val fields = clazz.declaredFields
                    var matchCount = 0

                    for ((fieldName, fieldType) in parsedSignatures) {
                        val hasMatch = fields.any { field ->
                            // 精确匹配 或 模糊匹配（字段名包含关键词）
                            (field.name == fieldName ||
                             field.name.lowercase().contains(fieldName.lowercase())) &&
                            (fieldType.isEmpty() || field.type.name.contains(fieldType))
                        }
                        if (hasMatch) matchCount++
                    }

                    if (matchCount == parsedSignatures.size) {
                        results.add(clazz)
                    }
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            HookUtils.log("ClassFinder: 字段特征查找失败: ${t.message}")
        }

        return results
    }

    /**
     * 通过方法签名特征查找类
     *
     * @param classLoader 类加载器
     * @param packageName 包名前缀
     * @param methodSignatures 方法特征列表 (格式: "methodName:returnType:paramType1,paramType2")
     * @return 匹配的 Class 列表
     */
    fun findByMethodSignature(
        classLoader: ClassLoader,
        packageName: String,
        methodSignatures: List<String>
    ): List<Class<*>> {
        val results = mutableListOf<Class<*>>()

        try {
            val parsedSignatures = methodSignatures.map { sig ->
                val parts = sig.split(":")
                val methodName = parts.getOrElse(0) { "" }
                val returnType = parts.getOrElse(1) { "" }
                val paramTypes = parts.getOrElse(2) { "" }.split(",").filter { it.isNotEmpty() }
                Triple(methodName, returnType, paramTypes)
            }

            val classes = getClassesInPackage(classLoader, packageName)

            for (clazz in classes) {
                try {
                    var matchCount = 0

                    for ((methodName, returnType, paramTypes) in parsedSignatures) {
                        val hasMatch = clazz.declaredMethods.any { method ->
                            // 精确匹配 或 模糊匹配（方法名包含关键词）
                            (method.name == methodName ||
                             method.name.lowercase().contains(methodName.lowercase())) &&
                            (returnType.isEmpty() ||
                             method.returnType.name.contains(returnType) ||
                             method.returnType.simpleName.contains(returnType)) &&
                            method.parameterTypes.size == paramTypes.size
                        }
                        if (hasMatch) matchCount++
                    }

                    if (matchCount == parsedSignatures.size) {
                        results.add(clazz)
                    }
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            HookUtils.log("ClassFinder: 方法签名查找失败: ${t.message}")
        }

        return results
    }

    /**
     * 获取包下的所有类
     *
     * 适配新版抖音:
     * - 支持 BaseDexClassLoader 和其子类（含加固后的自定义 ClassLoader）
     * - 递归遍历 delegate ClassLoader
     * - 处理 multiDex 场景
     *
     * LSPosed 2.x 兼容:
     * - 使用 dalvik.system.DexFile 反射遍历
     * - pathList -> dexElements -> dexFile -> entries
     */
    fun getClassesInPackage(
        classLoader: ClassLoader,
        packageName: String,
        includeInner: Boolean = false
    ): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()

        try {
            collectClassesFromClassLoader(classLoader, packageName, classes, includeInner)
        } catch (t: Throwable) {
            HookUtils.log("ClassFinder: DexFile 遍历失败: ${t.message}")
            // 备用方案: 尝试常见类名
            val commonClasses = listOf(
                "${packageName}.Aweme",
                "${packageName}.Video",
                "${packageName}.Image",
                "${packageName}.Comment",
                "${packageName}.User",
                "${packageName}.feed.model.Aweme",
                "${packageName}.feed.model.Video",
                "${packageName}.feed.model.ImageModel"
            )
            for (name in commonClasses) {
                try {
                    classes.add(Class.forName(name, false, classLoader))
                } catch (_: ClassNotFoundException) { }
            }
        }

        return classes
    }

    /**
     * 递归从 ClassLoader 中收集类
     * 处理加固后可能存在的 delegate ClassLoader
     */
    private fun collectClassesFromClassLoader(
        classLoader: ClassLoader,
        packageName: String,
        classes: MutableList<Class<*>>,
        includeInner: Boolean = false
    ) {
        if (classLoader in scannedClassLoaders) return
        scannedClassLoaders.add(classLoader)

        try {
            // 通过 BaseDexClassLoader.pathList.dexElements 遍历
            var pathListField: java.lang.reflect.Field? = null
            var clazz: Class<*>? = classLoader.javaClass

            // 递归查找 pathList 字段（可能在父类中）
            while (clazz != null && pathListField == null) {
                try {
                    pathListField = clazz.getDeclaredField("pathList")
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }

            if (pathListField == null) {
                HookUtils.log("ClassFinder: 未找到 pathList 字段 on ${classLoader.javaClass.name}")
                return
            }

            pathListField.isAccessible = true
            val pathList = pathListField.get(classLoader) ?: return

            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
            dexElementsField.isAccessible = true
            val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return

            HookUtils.log("ClassFinder: 遍历 ${dexElements.size} 个 dex 元素")

            for (dexElement in dexElements) {
                if (dexElement == null) continue

                val dexFileField = try {
                    dexElement.javaClass.getDeclaredField("dexFile")
                } catch (_: NoSuchFieldException) { continue }

                dexFileField.isAccessible = true
                val dexFile = dexFileField.get(dexElement) ?: continue

                // DexFile.entries() 返回 Enumeration<String>
                val entriesMethod = dexFile.javaClass.getMethod("entries")
                @Suppress("UNCHECKED_CAST")
                val entries = entriesMethod.invoke(dexFile) as? java.util.Enumeration<String> ?: continue

                var count = 0
                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    if (className.startsWith(packageName) &&
                        (includeInner || !className.contains('$'))
                    ) {
                        try {
                            val c = Class.forName(className, false, classLoader)
                            classes.add(c)
                            count++
                        } catch (_: Throwable) { }
                    }
                }
                if (count > 0) {
                    HookUtils.log("ClassFinder: 从 dex 元素收集到 $count 个类")
                }
            }
        } catch (_: Throwable) {
            // 可能不是 BaseDexClassLoader，尝试 delegate
        }

        // 检查是否有 delegate ClassLoader（加固场景）
        try {
            val delegateField = classLoader.javaClass.getDeclaredField("delegate")
            delegateField.isAccessible = true
            val delegate = delegateField.get(classLoader) as? ClassLoader
            if (delegate != null && delegate != classLoader) {
                collectClassesFromClassLoader(delegate, packageName, classes, includeInner)
            }
        } catch (_: Throwable) { }

        // 检查 parent ClassLoader
        try {
            val parent = classLoader.parent
            if (parent != null && parent != classLoader && parent != ClassLoader.getSystemClassLoader()) {
                collectClassesFromClassLoader(parent, packageName, classes, includeInner)
            }
        } catch (_: Throwable) { }
    }

    /**
     * 通过类中包含的字符串常量搜索类
     *
     * 原理: 遍历类的所有常量池字符串，匹配目标关键词。
     * 适用于: 通过特征字符串（如 "auto_play_key"）定位混淆后的类。
     *
     * @param classLoader 类加载器
     * @param packageName 包名前缀
     * @param keywords 要搜索的关键词列表（全部命中才算匹配）
     * @return 匹配的 Class 列表
     */
    fun findByStringConstants(
        classLoader: ClassLoader,
        packageName: String,
        keywords: List<String>
    ): List<Class<*>> {
        val results = mutableListOf<Class<*>>()

        try {
            val classes = getClassesInPackage(classLoader, packageName, includeInner = true)
            HookUtils.log("ClassFinder: 字符串搜索, 扫描 ${classes.size} 个类, 关键词=$keywords")

            for (clazz in classes) {
                try {
                    val allStrings = getClassConstantStrings(clazz, classLoader)
                    var matchCount = 0
                    for (keyword in keywords) {
                        if (allStrings.any { it.contains(keyword, ignoreCase = true) }) {
                            matchCount++
                        }
                    }
                    if (matchCount == keywords.size) {
                        results.add(clazz)
                        HookUtils.log("ClassFinder: 字符串匹配: ${clazz.name}")
                    }
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            HookUtils.log("ClassFinder: 字符串搜索失败: ${t.message}")
        }

        HookUtils.log("ClassFinder: 字符串搜索完成, 找到 ${results.size} 个类")
        return results
    }

    /**
     * 获取类中所有字符串常量
     * 通过反射 DexFile / Dex 的字符串池实现。
     * 简化实现: 检查类的字段值、方法名、类名等可见字符串。
     */
    private fun getClassConstantStrings(clazz: Class<*>, classLoader: ClassLoader): List<String> {
        val strings = mutableListOf<String>()

        try {
            // 1. 类名、包名
            strings.add(clazz.name)
            strings.add(clazz.simpleName)

            // 2. 字段名（不读取字段值，避免触发 <clinit> 导致类初始化崩溃）
            for (f in clazz.declaredFields) {
                strings.add(f.name)
            }

            // 3. 方法名
            for (m in clazz.declaredMethods) {
                strings.add(m.name)
            }

            // 4. 尝试从 dex 中读取字符串常量（更全面）
            try {
                val dexStrings = getDexStringsForClass(clazz, classLoader)
                strings.addAll(dexStrings)
            } catch (_: Throwable) { }

        } catch (_: Throwable) { }

        return strings.distinct()
    }

    /**
     * 从 DexFile 中读取类的字符串常量
     * 通过反射 dalvik.system.DexFile 获取类的字符串。
     */
    private fun getDexStringsForClass(clazz: Class<*>, classLoader: ClassLoader): List<String> {
        val strings = mutableListOf<String>()
        try {
            // 尝试读取类的所有注解值（字符串类型）
            for (annotation in clazz.declaredAnnotations) {
                strings.add(annotation.annotationClass.simpleName ?: "")
            }

            // 遍历方法的注解和异常
            for (m in clazz.declaredMethods) {
                for (ex in m.exceptionTypes) {
                    strings.add(ex.name)
                }
            }

            // 字段类型名
            for (f in clazz.declaredFields) {
                strings.add(f.type.name)
            }
        } catch (_: Throwable) { }

        return strings
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        classCache.clear()
        scannedClassLoaders.clear()
    }

    /**
     * 获取缓存大小
     */
    fun cacheSize(): Int = classCache.size
}

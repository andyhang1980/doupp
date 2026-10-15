package com.xposed.doupp.util

import java.io.File
import java.util.zip.ZipFile

/**
 * DexStringIndex —— 轻量 dex 字符串索引。
 *
 * 背景：模块声称支持「DexKit 字符串特征定位」，但之前的实现只读注解和 String 静态字段，
 * 方法体里的字符串常量（比如 auto_play_key）根本读不到，所以基于字符串特征的适配全部落空。
 * 这里直接解析宿主 APK 的 dex：
 *   string_ids -> string_data          拿到字符串池
 *   class_defs -> class_data -> code_item 扫描 const-string 指令，把字符串归属到类
 * 之后就能回答「哪个类里出现了 auto_play_key / share_panel」这类问题。
 *
 * 只索引关心的关键词，避免把 437MB dex 的全部字符串塞进内存。
 */
object DexStringIndex {

    /** 关键词 -> 使用该字符串的类描述符 (形如 Lcom/ss/.../AutoPlayViewModel;) */
    private val keywordToClasses = HashMap<String, MutableSet<String>>()

    /** 类描述符 -> 该类里出现的关键词 */
    private val classToKeywords = HashMap<String, MutableSet<String>>()

    private val indexed = HashSet<String>()   // 已建索引的 dex 标识
    @Volatile private var building = false

    val keywordCount: Int get() = keywordToClasses.size
    val classCount: Int get() = classToKeywords.size

    /**
     * 为给定关键词建索引。
     * @param apkPath 宿主 APK 路径 (ApplicationInfo.sourceDir)
     * @param keywords 关心的关键词
     * @return 关键词 -> 类描述符列表
     */
    @Synchronized
    fun build(apkPath: String, keywords: List<String>): Map<String, List<String>> {
        if (keywords.isEmpty()) return emptyMap()
        building = true
        return try {
            val kws = keywords.toSet()
            val fresh = kws.filter { !keywordToClasses.containsKey(it) }
            if (fresh.isEmpty()) return snapshot()

            val apk = File(apkPath)
            if (!apk.exists() || apk.length() < 1024) {
                HookUtils.log("DexStringIndex: APK 不存在 $apkPath")
                return snapshot()
            }
            ZipFile(apk).use { zf ->
                val dexEntries = zf.entries().toList()
                    .filter { it.name.matches(Regex("classes(\\d*)\\.dex")) }
                    .map { it.name }
                    .sortedWith(compareBy({ if (it == "classes.dex") 0 else 1 }, { it }))
                HookUtils.log("DexStringIndex: ${dexEntries.size} 个 dex, 关键词 ${fresh.size} 个")

                for (name in dexEntries) {
                    if (name in indexed) continue
                    zf.getInputStream(zf.getEntry(name)!!).use { input ->
                        val bytes = input.readBytes()
                        try {
                            scanDex(bytes, fresh.toSet())
                        } catch (t: Throwable) {
                            HookUtils.log("DexStringIndex: $name 解析失败 ${t.message}")
                        }
                    }
                    indexed.add(name)
                }
            }
            snapshot()
        } catch (t: Throwable) {
            HookUtils.log("DexStringIndex: 建索引失败 ${t.message}")
            snapshot()
        } finally {
            building = false
        }
    }

    private fun snapshot(): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        keywordToClasses.forEach { (k, v) -> if (v.isNotEmpty()) out[k] = v.toList() }
        return out
    }

    /** 查某关键词命中的类 */
    fun classesFor(keyword: String): List<String> = keywordToClasses[keyword]?.toList() ?: emptyList()

    /** 查某类里出现过的关键词 */
    fun keywordsOf(classDescriptor: String): List<String> = classToKeywords[classDescriptor]?.toList() ?: emptyList()

    /** 便捷入口：给定宿主 ClassLoader 和关键词，返回「关键词 -> 类描述符」 */
    fun classesUsingStrings(
        classLoader: ClassLoader,
        keywords: List<String>
    ): List<String> {
        val apk = runCatching { ContextHelper.getContext()?.applicationInfo?.sourceDir }.getOrNull()
        if (apk.isNullOrEmpty()) {
            HookUtils.log("DexStringIndex: 拿不到宿主 APK 路径")
            return emptyList()
        }
        val out = LinkedHashSet<String>()
        build(apk, keywords).forEach { (_, classes) -> out.addAll(classes) }
        return out.toList()
    }

    // ------------------------------------------------------------------
    // dex 解析
    // ------------------------------------------------------------------

    private fun scanDex(b: ByteArray, keywords: Set<String>) {
        if (b.size < 112) return
        if (!(b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() && b[2] == 'x'.code.toByte())) return

        val stringIdsSize = readInt(b, 56)
        val stringIdsOff = readInt(b, 60)
        val typeIdsSize = readInt(b, 64)
        val typeIdsOff = readInt(b, 68)
        val classDefsSize = readInt(b, 96)
        val classDefsOff = readInt(b, 100)
        if (stringIdsSize <= 0 || stringIdsOff <= 0 || classDefsSize <= 0 || classDefsOff <= 0) return

        // 1) 字符串池: 只解码我们关心的关键词，省内存
        val wanted = HashMap<Int, String>()   // string index -> 关键词
        for (i in 0 until stringIdsSize) {
            val strOff = readInt(b, stringIdsOff + i * 4)
            if (strOff <= 0 || strOff >= b.size) continue
            val s = readDexString(b, strOff) ?: continue
            if (s.length < 4 || s.length > 80) continue
            val hit = keywords.firstOrNull { s.equals(it, true) || s.contains(it) }
            if (hit != null) wanted[i] = hit
        }
        if (wanted.isEmpty()) return

        // 2) class_defs: 逐个类扫 code_item 里的 const-string
        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            if (base + 32 > b.size) break
            val classIdx = readInt(b, base)
            val classDataOff = readInt(b, base + 24)
            if (classDataOff <= 0 || classDataOff >= b.size) continue
            val desc = typeDescriptor(b, classIdx, typeIdsOff, stringIdsOff) ?: continue
            val found = scanClassData(b, classDataOff, wanted) ?: continue
            if (found.isEmpty()) continue
            for (kw in found) {
                keywordToClasses.getOrPut(kw) { mutableSetOf() }.add(desc)
                classToKeywords.getOrPut(desc) { mutableSetOf() }.add(kw)
            }
        }
    }

    /** dex 字节游标（uleb128 友好） */
    private class Cursor(val b: ByteArray, var p: Int) {
        fun uleb(): Int {
            var result = 0
            var shift = 0
            while (p < b.size) {
                val byte = b[p].toInt() and 0xFF
                result = result or ((byte and 0x7F) shl shift)
                p++
                if (byte and 0x80 == 0) break
                shift += 7
                if (shift > 28) break
            }
            return result
        }
    }

    /** 解析 class_data_item，返回该类里出现的关键词 */
    private fun scanClassData(b: ByteArray, off: Int, wanted: Map<Int, String>): Set<String>? {
        if (off <= 0 || off >= b.size) return null
        val c = Cursor(b, off)
        val staticFields = c.uleb()
        val instanceFields = c.uleb()
        val directMethods = c.uleb()
        val virtualMethods = c.uleb()

        repeat(staticFields + instanceFields) {
            c.uleb()   // field_idx_diff
            c.uleb()   // access_flags
        }

        val out = HashSet<String>()
        repeat(directMethods + virtualMethods) {
            c.uleb()        // method_idx_diff
            c.uleb()        // access_flags
            val codeOff = c.uleb()
            if (codeOff > 0 && codeOff < b.size) scanCodeItem(b, codeOff, wanted, out)
        }
        return out
    }

    /** 扫 code_item 里的 const-string / const-string-jumbo */
    private fun scanCodeItem(b: ByteArray, codeOff: Int, wanted: Map<Int, String>, out: MutableSet<String>) {
        if (codeOff + 16 > b.size) return
        val insnsSize = readInt(b, codeOff + 12)
        val insnsOff = codeOff + 16
        if (insnsSize <= 0 || insnsSize > 2_000_000) return
        var u = 0
        var guard = 0
        while (u < insnsSize && guard++ < 400_000) {
            val op = b[insnsOff + u * 2].toInt() and 0xFF
            when (op) {
                0x1a -> {   // const-string vAA, string@BBBB
                    val sIdx = (b[insnsOff + (u + 1) * 2].toInt() and 0xFF) or
                            ((b[insnsOff + (u + 1) * 2 + 1].toInt() and 0xFF) shl 8)
                    wanted[sIdx]?.let { out.add(it) }
                    u += 1
                }
                0x1b -> {   // const-string/jumbo vAA, string@BBBBBBBB
                    val lo = (b[insnsOff + (u + 1) * 2].toInt() and 0xFF) or
                            ((b[insnsOff + (u + 1) * 2 + 1].toInt() and 0xFF) shl 8)
                    val hi = (b[insnsOff + (u + 2) * 2].toInt() and 0xFF) or
                            ((b[insnsOff + (u + 2) * 2 + 1].toInt() and 0xFF) shl 8)
                    wanted[lo or (hi shl 16)]?.let { out.add(it) }
                    u += 2
                }
                else -> u += instructionWidth(op)
            }
            if (u < 0) return
        }
    }

    /** 指令宽度 (code unit 数)。0x1a/0x1b 在 scanCodeItem 里单独处理，这里不重复计入。 */
    private fun instructionWidth(op: Int): Int = when {
        op == 0x00 -> 1                                  // nop
        op == 0x14 || op == 0x17 -> 3                    // const-wide/16, const-wide/64
        op == 0x26 || op == 0x2b || op == 0x2c -> 3     // fill-array-data, packed-switch, sparse-switch
        op == 0xfa || op == 0xfb -> 4                    // invoke-polymorphic(/range)
        op == 0xfc || op == 0xfd -> 3                    // invoke-custom(/range)
        op == 0x18 || op == 0x19 -> 2
        op in 0x02..0x0e -> 2
        op in 0x21..0x25 -> 2
        op == 0x27 || op == 0x28 -> 2
        op in 0x44..0x6d -> 2
        op in 0x7b..0x8f -> 2
        op in 0x90..0xaf -> 2
        op in 0xb0..0xcf -> 2
        op in 0xd0..0xe2 -> 2
        op == 0xfe || op == 0xff -> 2
        else -> 1
    }

    private fun readDexString(b: ByteArray, off: Int): String? {
        try {
            var p = off
            // utf16_size uleb128 (只需跳过一个字节的常见情况)
            while (p < b.size && (b[p].toInt() and 0x80) != 0 && p < off + 5) p++
            p++
            val sb = StringBuilder()
            while (p < b.size) {
                val c = b[p].toInt() and 0xFF
                if (c == 0) break
                if (c < 0x80) { sb.append(c.toChar()); p++ }
                else if (c and 0xE0 == 0xC0) { sb.append(((c and 0x1F) shl 6) or (b[p + 1].toInt() and 0x3F)); p += 2 }
                else if (c and 0xF0 == 0xE0) {
                    sb.append(((c and 0x0F) shl 12) or ((b[p + 1].toInt() and 0x3F) shl 6) or (b[p + 2].toInt() and 0x3F))
                    p += 3
                } else { p++ }
                if (sb.length > 200) return null
            }
            return sb.toString()
        } catch (_: Throwable) { return null }
    }

    /** type_idx -> 类描述符 (Lcom/ss/.../Aweme;) */
    private fun typeDescriptor(b: ByteArray, typeIdx: Int, typeIdsOff: Int, stringIdsOff: Int): String? {
        if (typeIdx < 0) return null
        val p = typeIdsOff + typeIdx * 4
        if (p + 4 > b.size) return null
        val strIdx = readInt(b, p)
        if (strIdx < 0) return null
        val sp = stringIdsOff + strIdx * 4
        if (sp + 4 > b.size) return null
        val strOff = readInt(b, sp)
        if (strOff <= 0 || strOff >= b.size) return null
        return readDexString(b, strOff)
    }

    private fun readInt(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > b.size) return -1
        return (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    fun clear() {
        keywordToClasses.clear()
        classToKeywords.clear()
        indexed.clear()
    }
}
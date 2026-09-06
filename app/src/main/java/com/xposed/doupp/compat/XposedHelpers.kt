package com.xposed.doupp.compat

/**
 * Compat 兼容层：模拟旧版 XposedHelpers (de.robv.android.xposed) 的静态工具方法。
 * 全部为纯反射实现，不依赖 LibXposed。
 */
object XposedHelpers {

    /** 在指定 ClassLoader 下查找类；找不到抛 ClassNotFoundException（与旧 API 一致） */
    @Throws(ClassNotFoundException::class)
    fun findClass(className: String, classLoader: ClassLoader): Class<*> {
        return Class.forName(className, false, classLoader)
    }

    /** 查找类，找不到返回 null */
    fun findClassIfExists(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(className, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    /** 反射读取对象字段值（含父类递归） */
    fun getObjectField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    /** 反射写入对象字段值（含父类递归） */
    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        if (obj == null) return
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            } catch (_: Throwable) {
                return
            }
        }
    }

    /** 反射调用静态方法（兼容旧 API） */
    fun callStaticMethod(clazz: Class<*>?, methodName: String, vararg args: Any?): Any? {
        return XposedBridge.callStaticMethod(clazz, methodName, *args)
    }

    /** 反射调用实例方法（兼容旧 API） */
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        return XposedBridge.callMethod(obj, methodName, *args)
    }
}
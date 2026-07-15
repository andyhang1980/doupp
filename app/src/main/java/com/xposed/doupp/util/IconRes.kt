package com.xposed.doupp.util

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * 模块自带资源加载器
 *
 * 模块注入的 View 运行在抖音(宿主)进程内，宿主的 Resources 不含本模块的 drawable。
 * 通过 PackageManager 获取本模块(apk)的 Resources，再按资源名加载 drawable，
 * 从而可以在宿主进程里使用模块自带的图标（如逗音小能手的图标）。
 */
object IconRes {

    private const val MODULE_PKG = "com.xposed.doupp"

    fun getDrawable(context: Context, name: String): Drawable? {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(MODULE_PKG, 0)
            val res = pm.getResourcesForApplication(appInfo)
            val id = res.getIdentifier(name, "drawable", MODULE_PKG)
            if (id != 0) res.getDrawable(id, null) else null
        } catch (t: Throwable) {
            null
        }
    }
}

package com.xposed.doupp.hook

/**
 * Hook 模块基础接口
 *
 * 所有 Hook 模块都需要实现此接口，
 * 提供统一的初始化入口和类加载器引用。
 *
 * 适配新版抖音:
 * - 增加 isInstalled 标志，防止延迟安装时重复 Hook
 * - BaseHook 可感知安装状态
 */
interface BaseHook {
    /**
     * 初始化 Hook 模块
     *
     * @param classLoader 抖音应用的类加载器，用于反射查找目标类
     */
    fun init(classLoader: ClassLoader)

    /**
     * 检查此 Hook 是否已安装
     * 用于防止延迟安装时重复 Hook 同一方法
     */
    fun isInstalled(): Boolean

    /**
     * Hook 模块名称，用于日志
     */
    fun tag(): String
}

package com.xposed.doupp.util

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Context 获取工具
 *
 * 功能:
 * - 从各种途径获取 Application Context
 * - 用于下载、通知等需要 Context 的操作
 *
 * 在 Xposed 模块中，Context 不是直接可用的，
 * 需要通过 Hook 来获取。
 *
 * 适配最新版抖音:
 * - 抖音使用加固 + multiDex，类延迟加载
 * - 必须通过 Hook Application.attach(Context) 获取正确时机
 * - attach 是 Application 生命周期最早可 Hook 的方法，此时 ClassLoader 已就绪
 * - 提供回调让其他 Hook 模块在正确时机安装
 *
 * 兼容 LSPosed 2.0.x (API 101) / LSPosed 1.x (API 93+)
 */
object ContextHelper {

    /** 缓存的 Application Context */
    @Volatile
    private var applicationContext: Context? = null

    /** 是否已初始化 */
    private var initialized = false

    /** LSPosed 2.x 中 Application 的完整类名 */
    private const val APPLICATION_CLASS = "android.app.Application"
    private const val ACTIVITY_CLASS = "android.app.Activity"
    private const val ACTIVITY_THREAD_CLASS = "android.app.ActivityThread"

    /** 当 Application.attach 完成后回调，用于延迟 Hook 安装 */
    private val onReadyCallbacks = mutableListOf<(ClassLoader, Context) -> Unit>()

    /** 是否已触发回调 */
    @Volatile
    private var callbacksFired = false

    /**
     * 初始化 Context 获取
     *
     * 适配策略:
     * 1. 优先 Hook Application.attach(Context) — 抖音加固后最早可用时机
     * 2. 备用 Hook Application.onCreate — 传统方案
     * 3. 备用 Hook Activity.onCreate — 最后兜底
     * 4. 立即尝试 ActivityThread 反射
     *
     * @param lpparam 加载包参数
     */
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (initialized) return
        initialized = true

        hookApplicationAttach(lpparam)
        hookApplication(lpparam)
        hookActivity(lpparam)

        // LSPosed 2.x 备用方案: 尝试立即通过反射获取
        tryGetContextByReflection()
    }

    /**
     * 注册回调: 当 Application 就绪后执行
     * 如果 Application 已就绪，立即执行
     *
     * @param callback 回调函数，参数为 ClassLoader 和 Context
     */
    fun onApplicationReady(callback: (ClassLoader, Context) -> Unit) {
        synchronized(onReadyCallbacks) {
            val context = applicationContext
            if (callbacksFired && context != null) {
                // 已就绪，立即执行
                try {
                    callback(context.classLoader, context)
                } catch (t: Throwable) {
                    HookUtils.log("ContextHelper: onApplicationReady 立即执行失败: ${t.message}")
                }
            } else {
                onReadyCallbacks.add(callback)
            }
        }
    }

    /**
     * Hook Application.attach(Context)
     *
     * 这是抖音加固后最早能拿到正确 ClassLoader 的时机。
     * attach 在 onCreate 之前调用，此时:
     * - multiDex 已安装完毕
     * - 加固壳已解密主 dex
     * - 真实的 ClassLoader 已替换到 Application 上
     *
     * 适配新版抖音的关键: 之前只 Hook onCreate，
     * 但加固后 onCreate 时部分类可能尚未加载，
     * 导致 ClassFinder 找不到 Aweme 等类。
     */
    private fun hookApplicationAttach(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.safeHook {
            val appClass = XposedHelpers.findClass(APPLICATION_CLASS, lpparam.classLoader)

            XposedBridge.hookAllMethods(appClass, "attach", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val context = param.args.firstOrNull() as? Context ?: return
                        applicationContext = context.applicationContext ?: context

                        val appClassLoader = context.classLoader
                        HookUtils.log("ContextHelper: 通过 Application.attach 获取 Context + ClassLoader")
                        HookUtils.log("ContextHelper: ClassLoader 类型: ${appClassLoader?.javaClass?.name}")

                        fireReadyCallbacks(appClassLoader, applicationContext!!)
                    } catch (t: Throwable) {
                        HookUtils.log("ContextHelper: Application.attach Hook 失败: ${t.message}")
                    }
                }
            })

            HookUtils.log("ContextHelper: Application.attach Hook 已安装")
        }
    }

    /**
     * 触发所有就绪回调
     */
    private fun fireReadyCallbacks(classLoader: ClassLoader, context: Context) {
        synchronized(onReadyCallbacks) {
            if (callbacksFired) return
            callbacksFired = true

            for (callback in onReadyCallbacks) {
                try {
                    callback(classLoader, context)
                } catch (t: Throwable) {
                    HookUtils.log("ContextHelper: 就绪回调执行失败: ${t.message}")
                }
            }
            onReadyCallbacks.clear()
        }
    }

    /**
     * Hook Application.onCreate
     * 作为备用方案，某些情况下 attach 可能未被调用
     */
    private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.safeHook {
            val appClass = XposedHelpers.findClass(APPLICATION_CLASS, lpparam.classLoader)

            XposedBridge.hookAllMethods(appClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val app = param.thisObject as Application
                        val context = app.applicationContext ?: app as Context
                        if (applicationContext == null) {
                            applicationContext = context
                            HookUtils.log("ContextHelper: 通过 Application.onCreate 获取 Context")
                        }
                        // 如果 attach 没触发回调，这里兜底
                        if (!callbacksFired) {
                            fireReadyCallbacks(context.classLoader, context)
                        }
                    } catch (t: Throwable) {
                        HookUtils.log("ContextHelper: Application Hook 失败: ${t.message}")
                    }
                }
            })
        }
    }

    /**
     * Hook Activity.onCreate
     * 作为最后兜底方案
     */
    private fun hookActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.safeHook {
            val activityClass = XposedHelpers.findClass(ACTIVITY_CLASS, lpparam.classLoader)

            XposedBridge.hookAllMethods(activityClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (applicationContext == null) {
                            val activity = param.thisObject as android.app.Activity
                            applicationContext = activity.applicationContext
                            HookUtils.log("ContextHelper: 通过 Activity.onCreate 获取 Context")
                        }
                        // 最后兜底触发回调
                        if (!callbacksFired && applicationContext != null) {
                            fireReadyCallbacks(applicationContext!!.classLoader, applicationContext!!)
                        }
                    } catch (_: Throwable) { }
                }
            })
        }
    }

    /**
     * 获取 Application Context
     *
     * @return Context，如果尚未获取则返回 null
     */
    fun getContext(): Context? {
        return applicationContext ?: tryGetContextByReflection()
    }

    /**
     * 获取 Application Context (非空版本)
     * 如果尚未获取，尝试通过反射获取
     *
     * @return Context
     * @throws IllegalStateException 如果无法获取 Context
     */
    fun requireContext(): Context {
        return applicationContext ?: tryGetContextByReflection()
            ?: throw IllegalStateException("Context 尚未初始化，请确保抖音已启动")
    }

    /**
     * 通过反射尝试获取当前 Application
     *
     * LSPosed 2.x 兼容:
     * - ActivityThread.currentActivityThread() 在 Android 9+ 仍然可用
     * - getApplication() 返回当前 Application 实例
     */
    private fun tryGetContextByReflection(): Context? {
        if (applicationContext != null) return applicationContext

        return try {
            val activityThreadClass = Class.forName(ACTIVITY_THREAD_CLASS)
            val currentActivityThread = XposedHelpers.callStaticMethod(
                activityThreadClass, "currentActivityThread"
            )
            if (currentActivityThread != null) {
                val app = XposedHelpers.callMethod(currentActivityThread, "getApplication") as? Application
                if (app != null) {
                    applicationContext = app.applicationContext
                    HookUtils.log("ContextHelper: 通过反射 ActivityThread 获取 Context")
                    return applicationContext
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 检查 Context 是否已就绪
     */
    fun isReady(): Boolean = applicationContext != null

    /**
     * 获取当前 Activity
     * 通过 ActivityThread 反射获取
     */
    @Volatile
    private var currentActivity: android.app.Activity? = null

    fun getCurrentActivity(): android.app.Activity? {
        // 先用缓存
        currentActivity?.let { if (!it.isFinishing) return it }

        // 反射获取
        return try {
            val activityThreadClass = Class.forName(ACTIVITY_THREAD_CLASS)
            val currentActivityThread = XposedHelpers.callStaticMethod(
                activityThreadClass, "currentActivityThread"
            )
            if (currentActivityThread != null) {
                // 获取 mActivities 字段
                val activitiesField = activityThreadClass.getDeclaredField("mActivities")
                activitiesField.isAccessible = true
                val activities = activitiesField.get(currentActivityThread) as? Map<*, *>
                if (activities != null) {
                    for (value in activities.values) {
                        try {
                            val activityRecord = value ?: continue
                            // ActivityClientRecord.activity
                            val activityField = activityRecord.javaClass.getDeclaredField("activity")
                            activityField.isAccessible = true
                            val activity = activityField.get(activityRecord) as? android.app.Activity
                            if (activity != null && !activity.isFinishing) {
                                currentActivity = activity
                                return activity
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 检查回调是否已触发（即 Application.attach 已执行）
     */
    fun isCallbacksFired(): Boolean = callbacksFired

    /**
     * 重置 (用于测试)
     */
    fun reset() {
        applicationContext = null
        initialized = false
        callbacksFired = false
        synchronized(onReadyCallbacks) {
            onReadyCallbacks.clear()
        }
    }
}

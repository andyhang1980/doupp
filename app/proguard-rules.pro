# DouyinHelper ProGuard Rules

# Keep Xposed entry point
-keep class com.xposed.doupp.MainHook {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

# Keep all hook classes
-keep class com.xposed.doupp.hook.** { *; }

# Keep settings
-keep class com.xposed.doupp.ui.** { *; }

# Xposed API
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

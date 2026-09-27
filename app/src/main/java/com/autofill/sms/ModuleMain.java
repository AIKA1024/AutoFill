package com.autofill.sms;

import android.util.Log;

import com.autofill.sms.hook.AppHooks;
import com.autofill.sms.hook.HookConfig;
import com.autofill.sms.hook.SmsHooks;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 模块入口（META-INF/xposed/java_init.list 中声明）。
 * <p>
 * 使用 LSPosed v2.x 现代的 libxposed API（io.github.libxposed.api）。
 */
public class ModuleMain extends XposedModule {

    public static final String TAG = "AutoFillSms";

    /** 当前进程的模块实例，用于把日志同时写进 LSPosed 自己的日志系统。 */
    private static volatile XposedModule sModule;

    public ModuleMain() {
        super();
    }

    /**
     * 统一日志出口。
     * <p>
     * 必须同时写 {@link android.util.Log} 和 {@code XposedModule#log}：
     * LSPosed 2.x 会把模块日志收进自己的日志系统（只在管理器「日志」页可见），
     * 不一定出现在 logcat 里，所以只调 {@code XposedModule#log} 会导致
     * {@code adb logcat -s AutoFillSms:*} 一条都抓不到。
     */
    public static void log(int priority, String message) {
        android.util.Log.println(priority, TAG, message);
        XposedModule m = sModule;
        if (m != null) {
            try {
                m.log(priority, TAG, message);
            } catch (Throwable ignored) {
                // 写框架日志失败不影响 logcat，也不该让 Hook 逻辑挂掉
            }
        }
    }

    public static void log(int priority, String message, Throwable t) {
        android.util.Log.println(priority, TAG, message + ": " + t);
        if (t != null) {
            android.util.Log.println(priority, TAG, android.util.Log.getStackTraceString(t));
        }
        XposedModule m = sModule;
        if (m != null) {
            try {
                m.log(priority, TAG, message, t);
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        sModule = this;
        HookConfig.init(this);
        ModuleMain.log(Log.INFO, "onModuleLoaded | process=" + param.getProcessName()
                + " | framework=" + getFrameworkName()
                + " " + getFrameworkVersion()
                + " | api=" + getApiVersion());
        ModuleMain.log(Log.INFO, "remote supported=" + hasProp(PROP_CAP_REMOTE)
                + " | system supported=" + hasProp(PROP_CAP_SYSTEM));
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        // 包刚加载，类加载器尚未就绪，真正的 Hook 放在 onPackageReady
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (pkg == null || "com.autofill.sms".equals(pkg)) {
            return;
        }
        try {
            // 短信拦截（只有加载了 InboundSmsHandler 的进程，通常是 com.android.phone）
            SmsHooks.install(this, param);
            // 应用侧：接收验证码、复制、Toast、通知、自动填入
            AppHooks.install(this, param);
        } catch (Throwable t) {
            ModuleMain.log(Log.ERROR, "install hooks failed in " + pkg, t);
        }
    }

    private boolean hasProp(long prop) {
        return (getFrameworkProperties() & prop) != 0;
    }
}

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

    public ModuleMain() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        HookConfig.init(this);
        log(Log.INFO, TAG, "onModuleLoaded | process=" + param.getProcessName()
                + " | framework=" + getFrameworkName()
                + " " + getFrameworkVersion()
                + " | api=" + getApiVersion());
        log(Log.INFO, TAG, "remote supported=" + hasProp(PROP_CAP_REMOTE)
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
            log(Log.ERROR, TAG, "install hooks failed in " + pkg, t);
        }
    }

    private boolean hasProp(long prop) {
        return (getFrameworkProperties() & prop) != 0;
    }
}

package com.autofill.sms.hook;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;

import com.autofill.sms.CodeParser;
import com.autofill.sms.ModuleMain;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 应用侧 Hook：
 * <ol>
 *   <li>Application#onCreate —— 拿到 Context 并动态注册验证码广播接收器</li>
 *   <li>Activity#onResume/onPause —— 判断本进程是否处于前台</li>
 *   <li>TextView#onFocusChanged —— 短信先到、输入框后获得焦点时补填</li>
 * </ol>
 */
public final class AppHooks {

    private static final String TAG = ModuleMain.TAG;

    /** 验证码在内存中保留多久，用于"先收到短信、后点输入框"的场景 */
    private static final long PENDING_TTL = 120_000L;
    /** 收到短信 30 秒内，任何获得焦点的输入框都尝试填入 */
    private static final long FRESH_TTL = 30_000L;

    private static volatile boolean sInstalled;
    private static volatile boolean sReceiverRegistered;
    private static volatile Context sAppContext;
    private static volatile String sPackageName;
    private static volatile WeakReference<Activity> sResumed = new WeakReference<>(null);

    private static volatile String sPendingCode;
    private static volatile long sPendingMillis;

    private AppHooks() {
    }

    public static synchronized void install(XposedModule module, PackageReadyParam param) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        sPackageName = param.getPackageName();
        hookApplication(module);
        hookActivityLifecycle(module);
        hookTextFocus(module);
    }

    public static Context getAppContext() {
        return sAppContext;
    }

    public static Activity getResumedActivity() {
        Activity a = sResumed.get();
        return (a != null && !a.isFinishing()) ? a : null;
    }

    public static boolean isForeground() {
        return getResumedActivity() != null;
    }

    private static void hookApplication(XposedModule module) {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.deoptimize(onCreate);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Application app = (Application) chain.getThisObject();
                    Context ctx = app.getApplicationContext();
                    sAppContext = ctx;
                    registerReceiver(module, ctx);
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "application init failed", t);
                }
                return result;
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook Application#onCreate failed", t);
        }
    }

    private static void hookActivityLifecycle(XposedModule module) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            module.deoptimize(onResume);
            module.hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    sResumed = new WeakReference<>((Activity) chain.getThisObject());
                } catch (Throwable ignored) {
                }
                return result;
            });

            Method onPause = Activity.class.getDeclaredMethod("onPause");
            module.deoptimize(onPause);
            module.hook(onPause).intercept(chain -> {
                sResumed = new WeakReference<>(null);
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook Activity lifecycle failed", t);
        }
    }

    private static void hookTextFocus(XposedModule module) {
        try {
            Method onFocusChanged = TextView.class
                    .getDeclaredMethod("onFocusChanged", boolean.class, int.class, Rect.class);
            module.deoptimize(onFocusChanged);
            module.hook(onFocusChanged).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    if (Boolean.TRUE.equals(chain.getArg(0))
                            && chain.getThisObject() instanceof EditText) {
                        fillOnFocus(module, (EditText) chain.getThisObject());
                    }
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "focus fill failed", t);
                }
                return result;
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "hook TextView#onFocusChanged failed", t);
        }
    }

    private static void fillOnFocus(XposedModule module, EditText editText) {
        String code = sPendingCode;
        if (code == null) {
            return;
        }
        long age = System.currentTimeMillis() - sPendingMillis;
        if (age > PENDING_TTL) {
            sPendingCode = null;
            return;
        }
        if (!HookConfig.enabled() || !HookConfig.autoFill() || HookConfig.isBlacklisted(sPackageName)) {
            return;
        }
        CharSequence hint = editText.getHint();
        boolean hintMatch = hint != null
                && CodeParser.matchKeyword(hint.toString(), HookConfig.keywords());
        boolean numeric = (editText.getInputType() & InputType.TYPE_MASK_CLASS)
                == InputType.TYPE_CLASS_NUMBER;
        // hint 命中关键词、输入框特征像验证码框、或刚收到短信（30s 内）才填入
        if (hintMatch || numeric || Actions.isLikelyCodeField(editText) || age <= FRESH_TTL) {
            Actions.setText(editText, code);
            sPendingCode = null;
            module.log(Log.INFO, TAG, "filled on focus in " + sPackageName);
        }
    }

    private static void registerReceiver(XposedModule module, Context ctx) {
        if (sReceiverRegistered || SmsHooks.isInstalled()) {
            // 短信进程自身不需要接收广播，避免重复处理
            return;
        }
        sReceiverRegistered = true;
        try {
            IntentFilter filter = new IntentFilter(SmsBridge.ACTION_CODE);
            BroadcastReceiver receiver = new CodeReceiver(module);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            module.log(Log.INFO, TAG, "code receiver registered in " + ctx.getPackageName());
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "register receiver failed", t);
        }
    }

    /** 接收短信进程广播出来的验证码 */
    static final class CodeReceiver extends BroadcastReceiver {

        private final XposedModule module;

        CodeReceiver(XposedModule module) {
            this.module = module;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !SmsBridge.ACTION_CODE.equals(intent.getAction())) {
                return;
            }
            final String code = intent.getStringExtra(SmsBridge.EXTRA_CODE);
            if (code == null || code.isEmpty()) {
                return;
            }
            final String sender = intent.getStringExtra(SmsBridge.EXTRA_SENDER);
            final String body = intent.getStringExtra(SmsBridge.EXTRA_BODY);

            if (!HookConfig.enabled() || HookConfig.isBlacklisted(sPackageName)) {
                return;
            }

            // 存下来，便于之后输入框获得焦点时补填
            sPendingCode = code;
            sPendingMillis = System.currentTimeMillis();

            boolean foreground = isForeground();
            module.log(Log.INFO, TAG, "code received in " + sPackageName
                    + " | foreground=" + foreground
                    + " | autoFill=" + HookConfig.autoFill());

            if (!foreground) {
                // 不在前台就不抢着处理，交给真正的前台 App
                return;
            }

            boolean needNotify = HookConfig.showNotification() && !Actions.canNotify(context);
            new Handler(Looper.getMainLooper()).post(() -> Actions.onCode(
                    module, context.getApplicationContext(), sPackageName, code, sender, body));

            if (isOrderedBroadcast()) {
                Bundle out = new Bundle();
                out.putBoolean(SmsBridge.EXTRA_NEED_NOTIFY, needNotify);
                setResultCode(SmsBridge.RESULT_HANDLED);
                setResultExtras(out);
            }
        }
    }
}

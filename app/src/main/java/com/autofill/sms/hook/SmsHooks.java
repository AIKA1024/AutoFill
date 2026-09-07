package com.autofill.sms.hook;

import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import com.autofill.sms.CodeParser;
import com.autofill.sms.ModuleMain;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 短信拦截：Hook com.android.internal.telephony.InboundSmsHandler#dispatchIntent。
 * <p>
 * 这里是短信入站后分发广播的最后一站，拿到 Intent 中的 pdu 即可解析出正文；
 * 需要拦截时直接不调用 chain.proceed()，短信就不会再分发给任何 App（也不会入短信库）。
 */
public final class SmsHooks {

    private static final String TAG = ModuleMain.TAG;
    private static final String CLASS_INBOUND = "com.android.internal.telephony.InboundSmsHandler";
    private static final String METHOD_DISPATCH = "dispatchIntent";

    private static volatile boolean sInstalled;
    private static volatile String sLastBody;
    private static volatile long sLastTime;

    private SmsHooks() {
    }

    public static boolean isInstalled() {
        return sInstalled;
    }

    public static synchronized void install(XposedModule module, PackageReadyParam param) {
        if (sInstalled) {
            return;
        }
        Class<?> inbound;
        try {
            inbound = Class.forName(CLASS_INBOUND, false, param.getClassLoader());
        } catch (Throwable t) {
            // 不是电话进程，正常现象
            return;
        }
        sInstalled = true;

        int hooked = 0;
        for (Method m : inbound.getDeclaredMethods()) {
            if (!METHOD_DISPATCH.equals(m.getName())) {
                continue;
            }
            try {
                m.setAccessible(true);
                module.deoptimize(m);
                module.hook(m).intercept(chain -> onDispatch(module, chain));
                hooked++;
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "hook " + METHOD_DISPATCH + " failed: " + m, t);
            }
        }
        module.log(Log.INFO, TAG, "InboundSmsHandler hooked: " + hooked + " overload(s) in "
                + param.getPackageName());
    }

    private static Object onDispatch(XposedModule module,
                                    io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
        Intent intent = null;
        for (Object arg : chain.getArgs()) {
            if (arg instanceof Intent) {
                intent = (Intent) arg;
                break;
            }
        }
        boolean block = false;
        if (intent != null) {
            try {
                block = process(module, intent);
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "process sms failed", t);
            }
        }
        if (block) {
            module.log(Log.INFO, TAG, "SMS blocked (verification code intercepted)");
            // void 方法，不调用 proceed 即拦截
            return null;
        }
        return chain.proceed();
    }

    /** @return true 表示需要拦截该短信 */
    private static boolean process(XposedModule module, Intent intent) {
        String action = intent.getAction();
        if (action == null || !action.endsWith("SMS_RECEIVED")) {
            return false;
        }
        Bundle extras = intent.getExtras();
        Object pdusObj = extras == null ? null : extras.get("pdus");
        if (!(pdusObj instanceof Object[]) || ((Object[]) pdusObj).length == 0) {
            return false;
        }
        Object first = ((Object[]) pdusObj)[0];
        if (!(first instanceof byte[])) {
            return false;
        }

        SmsMessage sms = createFromPdu((byte[]) first, intent.getStringExtra("format"));
        if (sms == null) {
            return false;
        }
        String body = sms.getMessageBody();
        if (body == null || body.isEmpty()) {
            return false;
        }
        if (!HookConfig.enabled()) {
            return false;
        }

        String code = CodeParser.extract(body, HookConfig.regex(), HookConfig.keywords());
        if (code == null) {
            return false;
        }

        // 同一条短信可能被多次分发，做 10 秒去重
        long now = System.currentTimeMillis();
        if (body.equals(sLastBody) && now - sLastTime < 10_000L) {
            return HookConfig.blockSms();
        }
        sLastBody = body;
        sLastTime = now;

        String sender = sms.getOriginatingAddress();
        module.log(Log.INFO, TAG, "code detected: " + code + " from " + sender);

        SmsBridge.dispatch(module, code, sender, body);
        return HookConfig.blockSms();
    }

    private static SmsMessage createFromPdu(byte[] pdu, String format) {
        if (format != null) {
            try {
                Method m = SmsMessage.class.getMethod("createFromPdu", byte[].class, String.class);
                return (SmsMessage) m.invoke(null, pdu, format);
            } catch (Throwable ignored) {
            }
        }
        try {
            Method m = SmsMessage.class.getMethod("createFromPdu", byte[].class);
            return (SmsMessage) m.invoke(null, pdu);
        } catch (Throwable ignored) {
        }
        return null;
    }
}

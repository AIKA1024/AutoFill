package com.autofill.sms.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.autofill.sms.ModuleMain;

import io.github.libxposed.api.XposedModule;

/**
 * 短信进程 → 应用进程的验证码传输通道。
 * <p>
 * 短信在 com.android.phone 进程被拦截解析，而复制/Toast/通知/填入需要在目标 App 进程执行，
 * 因此这里用一条有序广播把验证码广播给所有被 Hook 的进程：
 * 处于前台的进程处理并把结果码置为 HANDLED；无人处理时，短信进程自己做兜底。
 */
public final class SmsBridge {

    private static final String TAG = ModuleMain.TAG;

    public static final String ACTION_CODE = "com.autofill.sms.CODE_RECEIVED";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_SENDER = "sender";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_NEED_NOTIFY = "need_notify";

    public static final int RESULT_NONE = 0;
    public static final int RESULT_HANDLED = 1;

    private SmsBridge() {
    }

    public static void dispatch(XposedModule module, String code, String sender, String body) {
        Context ctx = AppHooks.getAppContext();
        if (ctx == null) {
            module.log(Log.WARN, TAG, "no context in sms process, skip dispatch");
            return;
        }
        Intent intent = new Intent(ACTION_CODE)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_BODY, body)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            ctx.sendOrderedBroadcast(intent, null,
                    new FallbackReceiver(module, ctx, code, sender, body),
                    null, RESULT_NONE, null, new Bundle());
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "sendOrderedBroadcast failed", t);
        }
    }

    /** 有序广播的终点：判断是否有前台 App 处理过 */
    static final class FallbackReceiver extends BroadcastReceiver {

        private final XposedModule module;
        private final Context ctx;
        private final String code;
        private final String sender;
        private final String body;

        FallbackReceiver(XposedModule module, Context ctx, String code, String sender, String body) {
            this.module = module;
            this.ctx = ctx;
            this.code = code;
            this.sender = sender;
            this.body = body;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            boolean handled = getResultCode() == RESULT_HANDLED;
            Bundle extras = getResultExtras(false);
            boolean needNotify = extras != null && extras.getBoolean(EXTRA_NEED_NOTIFY, false);

            Handler main = new Handler(Looper.getMainLooper());
            if (!handled) {
                // 没有任何前台应用处理（例如在桌面/息屏时收到短信），由本进程兜底
                main.post(() -> Actions.onCode(module, ctx, ctx.getPackageName(), code, sender, body));
            } else if (needNotify) {
                // 前台应用没有通知权限，这里代发通知
                main.post(() -> Actions.notifyCode(module, ctx, code, sender, body));
            }
        }
    }
}

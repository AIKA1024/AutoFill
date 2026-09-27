package com.autofill.sms.hook;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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


    /** 模块 App 自身的包名，跨进程拉起注入服务时用（本文件运行在被 Hook 的进程里，不引用模块类） */
    private static final String MODULE_PKG = "com.autofill.sms";

    public static final String ACTION_CODE = "com.autofill.sms.CODE_RECEIVED";
    /**
     * 请求 root 全局注入的 action（值与 {@code RootFillReceiver.ACTION} 相同）。
     * 这里用字面量而不是引用该类：本文件运行在被 Hook 的进程里，而 RootFillReceiver
     * 是模块 App 自己的组件，避免产生不必要的类依赖。
     */
    public static final String ACTION_ROOT_FILL = "com.autofill.sms.ROOT_FILL";
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
            ModuleMain.log(Log.WARN, "no context in sms process, skip dispatch");
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
            ModuleMain.log(Log.ERROR, "sendOrderedBroadcast failed", t);
        }
    }

    /**
     * 让模块自己的 App 用 root 执行 {@code input text} 注入验证码。
     * <p>
     * 两条路都发，靠模块 App 侧的 5 秒去重保证只填一次——因为它们各有各的坑：
     * <ul>
     *   <li><b>广播</b>：App 处于 stopped（装完 / 开机后一次都没打开过）时
     *       <b>完全收不到</b>，实测显式 {@code setPackage} 加
     *       {@code FLAG_INCLUDE_STOPPED_PACKAGES}、用 root 发送也送不进去；</li>
     *   <li><b>绑定</b>：不受 stopped 限制，但会被 Thanox 之类的后台管理模块拦截
     *       （真机日志 {@code Thanox-Core: bindServiceLocked block ...}）。</li>
     * </ul>
     * 两条都发，任意一条通就能填；都通时后到的那条被去重吞掉。
     */
    private static void requestRootFill(String code) {
        if (!HookConfig.autoFill() || !HookConfig.rootFill()) {
            return;
        }
        Context ctx = AppHooks.getAppContext();
        if (ctx == null) {
            return;
        }
        try {
            Intent bind = new Intent()
                    .setComponent(new ComponentName(MODULE_PKG, MODULE_PKG + ".RootFillService"))
                    .putExtra(EXTRA_CODE, code);
            boolean bound = ctx.bindService(bind, new FillConnection(ctx), Context.BIND_AUTO_CREATE);
            ModuleMain.log(Log.INFO, "root fill: bind requested to " + MODULE_PKG
                    + " | accepted=" + bound);
        } catch (Throwable t) {
            ModuleMain.log(Log.WARN, "root fill: bindService failed: " + t);
        }
        try {
            Intent intent = new Intent(ACTION_ROOT_FILL)
                    .putExtra(EXTRA_CODE, code)
                    .setPackage(MODULE_PKG)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                            | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(intent);
            ModuleMain.log(Log.INFO, "root fill requested (broadcast)");
        } catch (Throwable t) {
            ModuleMain.log(Log.ERROR, "root fill request failed", t);
        }
    }

    /** 绑定成功后立刻解绑：注入已经在服务的 onBind 里同步做完了 */
    private static final class FillConnection implements ServiceConnection {

        private final Context ctx;

        FillConnection(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                ctx.unbindService(this);
            } catch (Throwable ignored) {
                // 解绑失败不影响已经完成的注入
            }
            ModuleMain.log(Log.INFO, "root fill: service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // 不做任何事：一次性调用
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // 不做任何事：一次性调用
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
                // 没有任何应用进程接走验证码。正常情况（桌面/息屏）会走到这里；
                // 若此刻明明有 App 在前台，说明该 App 没被勾进作用域 —— 这时改用 root 注入兜底：
                // 按键由系统 InputDispatcher 分发给当前焦点窗口，与目标 App 是否被注入无关。
                ModuleMain.log(Log.WARN, "no app process handled the code, fallback in "
                        + ctx.getPackageName());
                requestRootFill(code);
                main.post(() -> Actions.onCode(module, ctx, ctx.getPackageName(), code, sender, body));
            } else if (needNotify) {
                // 前台应用没有通知权限，这里代发通知
                main.post(() -> Actions.notifyCode(module, ctx, code, sender, body));
            }
        }
    }
}

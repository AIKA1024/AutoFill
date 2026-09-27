package com.autofill.sms;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * 供短信进程（com.android.phone）绑定的验证码注入服务。
 * <p>
 * 为什么不直接用广播：Android 会给「从未启动过」的应用打上 stopped 标记，实测该状态下
 * <b>任何广播都送不进去</b>（显式 {@code setPackage} 甚至加上
 * {@code FLAG_INCLUDE_STOPPED_PACKAGES} 也一样），但显式启动组件不受限制。
 * 绑定本服务会把模块 App 的进程拉起来并顺带解除 stopped，于是装完 / 重启后
 * 一次都没打开过本应用，root 注入依然可用。
 * <p>
 * 真正执行注入的是 {@link RootFillReceiver#inject}，本类只负责被调用的入口。
 */
public class RootFillService extends Service {

    public static final String EXTRA_CODE = "code";

    private final IBinder stub = new Binder();

    @Override
    public IBinder onBind(Intent intent) {
        // 同步执行：调用方在 onServiceConnected 里才解绑，注入期间进程不会被回收
        RootFillReceiver.inject(codeOf(intent));
        return stub;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        RootFillReceiver.inject(codeOf(intent));
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private static String codeOf(Intent intent) {
        return intent == null ? null : intent.getStringExtra(EXTRA_CODE);
    }
}

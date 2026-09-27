package com.autofill.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 应用更新后把自己「唤醒」一次。
 * <p>
 * 目的很单一：Android 会给从未启动过的应用打上 stopped 标记，该状态下
 * <b>任何广播都收不到</b>（root 注入的广播通道会静默失效）。收到一次系统广播就能解除
 * 这个标记，于是开机后即使没手动打开过本应用，root 注入依然可用。
 * <p>
 * 这里不做任何业务：注入逻辑在 {@link RootFillReceiver} / {@link RootFillService} 里。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoFillSms";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "boot receiver: " + (intent == null ? "?" : intent.getAction()));
    }
}

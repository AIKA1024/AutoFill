package com.autofill.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 用 root 执行 {@code input text} 把验证码注入到当前焦点输入框。
 * <p>
 * 为什么需要它：LSPosed 的作用域只把模块注入到勾选的包，要往某个 App 的 {@code EditText}
 * 里写字，那个 App 必须被勾进作用域。而按键注入是走系统输入通道的——事件由
 * system_server 的 InputDispatcher 分发给当前焦点窗口，<b>与目标 App 是否被注入无关</b>，
 * 因此只需本模块自己的 App 拿到 root，就能给任何 App 填入，连 Compose / Flutter 自绘控件
 * （同样接收按键）也一并覆盖。
 * <p>
 * 由短信进程在没有其他进程接管验证码时，通过显式广播唤醒本组件。
 */
public class RootFillReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoFillSms";

    public static final String ACTION = "com.autofill.sms.ROOT_FILL";
    public static final String EXTRA_CODE = "code";

    /**
     * 允许拼进 shell 命令的验证码字符集：仅数字与字母。
     * <b>这不是为了校验验证码格式，而是安全底线</b>——验证码来自短信正文，若不做白名单，
     * 一条恶意短信就能让它以 root 身份执行任意命令。
     */
    private static final Pattern SAFE_CODE = Pattern.compile("[0-9A-Za-z]{3,16}");

    private static final String[] SU_CANDIDATES = {"su", "/system/bin/su", "/system/xbin/su"};

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        String code = intent.getStringExtra(EXTRA_CODE);
        if (code == null || !SAFE_CODE.matcher(code).matches()) {
            Log.w(TAG, "root fill refused, code not in safe charset: " + code);
            return;
        }
        // goAsync：su 需要时间，否则 onReceive 一返回进程就可能被回收
        final PendingResult pending = goAsync();
        final String finalCode = code;
        new Thread(() -> {
            try {
                boolean ok = inject(finalCode);
                Log.i(TAG, "root fill " + (ok ? "sent: " : "FAILED: ") + finalCode);
            } finally {
                pending.finish();
            }
        }, "root-fill").start();
    }

    /** @return 注入命令是否执行成功（不代表真的落到了输入框上） */
    static boolean inject(String code) {
        String cmd = "input text " + code;
        for (String su : SU_CANDIDATES) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{su, "-c", cmd});
                String err = readAll(p);
                boolean finished = p.waitFor(8, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroy();
                    Log.w(TAG, "root fill timeout via " + su);
                    continue;
                }
                if (p.exitValue() == 0) {
                    return true;
                }
                Log.w(TAG, "root fill exit=" + p.exitValue() + " via " + su
                        + (err.isEmpty() ? "" : " | " + err));
            } catch (Throwable t) {
                Log.w(TAG, "root fill error via " + su + ": " + t);
            }
        }
        return false;
    }

    /** 读一点错误输出，便于判断是权限被拒还是命令不存在 */
    private static String readAll(Process p) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 20) {
                sb.append(line).append("; ");
            }
        } catch (Throwable ignored) {
            // 读不到错误信息不影响主流程
        }
        return sb.toString().trim();
    }
}

package com.autofill.sms.hook;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import com.autofill.sms.ModuleMain;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 收到验证码后执行的动作：复制、Toast、通知、自动填入文本框。
 * <p>
 * 注意：本类的所有方法都必须在主线程调用。
 */
public final class Actions {

    private static final String TAG = ModuleMain.TAG;
    private static final String CHANNEL_ID = "sms_code";
    private static final String CHANNEL_NAME = "验证码";
    private static final int NOTIFY_ID = 20240906;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Actions() {
    }

    /** 在当前进程执行完整动作（复制 / Toast / 通知 / 填入） */
    public static void onCode(XposedModule module, Context ctx, String pkg,
                              String code, String sender, String body) {
        if (!HookConfig.enabled() || HookConfig.isBlacklisted(pkg)) {
            return;
        }
        if (HookConfig.autoCopy()) {
            copy(module, ctx, HookConfig.copyFull() && body != null ? body : code);
        }
        if (HookConfig.showToast()) {
            toast(ctx, "验证码 " + code + (TextUtils.isEmpty(sender) ? "" : "（" + sender + "）"));
        }
        if (HookConfig.showNotification()) {
            notifyCode(module, ctx, code, sender, body);
        }
        if (HookConfig.autoFill()) {
            fill(module, code);
        }
    }

    /** 只发通知（前台应用没有通知权限时的兜底） */
    public static void notifyCode(XposedModule module, Context ctx,
                                  String code, String sender, String body) {
        if (!HookConfig.showNotification()) {
            return;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("收到验证码时提醒");
                nm.createNotificationChannel(channel);
            }
            String title = "验证码 " + code;
            String text = (TextUtils.isEmpty(sender) ? "" : sender + "：")
                    + (body == null ? "" : body);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(ctx, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(ctx);
            }
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setDefaults(Notification.DEFAULT_LIGHTS);
            nm.notify(NOTIFY_ID, builder.build());
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "notify failed", t);
        }
    }

    public static void copy(XposedModule module, Context ctx, String text) {
        try {
            ClipboardManager cm =
                    (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("sms_code", text));
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "copy failed", t);
        }
    }

    public static void toast(Context ctx, String text) {
        try {
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    /** 当前进程是否允许发通知 */
    public static boolean canNotify(Context ctx) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 把验证码填到当前前台 Activity 的输入框 */
    public static void fill(XposedModule module, String code) {
        Activity activity = AppHooks.getResumedActivity();
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                View focused = activity.getCurrentFocus();
                if (focused instanceof EditText) {
                    setText((EditText) focused, code);
                    return;
                }
                // 没有焦点时，若界面上只有一个可见输入框，也尝试填入
                View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                List<EditText> editors = collect(decor);
                if (editors.size() == 1) {
                    setText(editors.get(0), code);
                }
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "fill failed", t);
            }
        });
    }

    public static void setText(EditText editText, String code) {
        MAIN.post(() -> {
            try {
                editText.setText(code);
                editText.setSelection(code.length());
            } catch (Throwable ignored) {
            }
        });
    }

    private static List<EditText> collect(View view) {
        List<EditText> result = new ArrayList<>();
        collectInternal(view, result);
        return result;
    }

    private static void collectInternal(View view, List<EditText> out) {
        if (view == null) {
            return;
        }
        if (view instanceof EditText) {
            if (view.isShown() && view.isEnabled()) {
                out.add((EditText) view);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectInternal(group.getChildAt(i), out);
            }
        }
    }
}

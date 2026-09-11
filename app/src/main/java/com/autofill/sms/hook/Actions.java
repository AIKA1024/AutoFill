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
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.Toast;

import com.autofill.sms.CodeParser;
import com.autofill.sms.ModuleMain;

import java.lang.reflect.Field;
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

    /**
     * 填入的重试时间点（毫秒）。收到短信时目标界面可能还在创建/动画中，
     * 输入框此时并不存在，所以需要多次尝试。
     */
    private static final long[] FILL_RETRIES = {0L, 300L, 700L, 1500L, 3000L};

    /** 输入框特征里出现的英文验证码关键词 */
    private static final String[] CODE_WORDS = {
            "code", "otp", "verify", "verif", "captcha", "sms", "auth", "pin"
    };
    /** 输入框特征里出现的中文验证码关键词 */
    private static final String[] CODE_WORDS_CN = {
            "验证码", "校验码", "动态码", "短信码", " Code", "口令"
    };

    /** 最近一次成功填入的验证码，用于让重试尽快收敛 */
    private static volatile String sLastFilled;

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

    // ------------------------------------------------------------------
    // 自动填入
    // ------------------------------------------------------------------

    /** 把验证码填到当前前台 Activity 的输入框（带重试） */
    public static void fill(XposedModule module, String code) {
        sLastFilled = null;
        for (long delay : FILL_RETRIES) {
            MAIN.postDelayed(() -> tryFill(module, code), delay);
        }
    }

    private static void tryFill(XposedModule module, String code) {
        if (code.equals(sLastFilled)) {
            return;
        }
        try {
            Activity activity = AppHooks.getResumedActivity();
            if (activity == null) {
                module.log(Log.WARN, TAG, "fill skipped: no resumed activity");
                return;
            }
            View decor = activity.getWindow() == null
                    ? null : activity.getWindow().getDecorView();
            if (decor == null) {
                return;
            }

            // 1) 已经聚焦在输入框上 —— 最常见也最可靠的情况
            View focused = activity.getCurrentFocus();
            if (focused instanceof EditText) {
                if (commit((EditText) focused, code)) {
                    done(module, code, "focused EditText");
                }
                return;
            }

            // 2) H5 / WebView 登录页（WebView 里没有 EditText，只能注入 JS）
            WebView webView = findWebView(decor);
            if (webView != null) {
                if (fillWebView(module, webView, code)) {
                    done(module, code, "webview");
                }
                return;
            }

            List<EditText> editors = collect(decor);
            module.log(Log.INFO, TAG, "candidate inputs: " + editors.size());

            // 3) 多格 OTP（6 个单字符框那种）
            if (fillSplitBoxes(editors, code)) {
                done(module, code, "split boxes");
                return;
            }

            // 4) 按特征打分挑一个最像验证码框的
            EditText best = pickBest(editors);
            if (best != null) {
                best.requestFocus();
                if (commit(best, code)) {
                    done(module, code, "scored EditText");
                }
                return;
            }

            module.log(Log.WARN, TAG,
                    "no input found — 可能是 Compose / Flutter / 自绘控件，无法填入");
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "fill failed", t);
        }
    }

    private static void done(XposedModule module, String code, String how) {
        sLastFilled = code;
        module.log(Log.INFO, TAG, "filled via " + how);
    }

    /**
     * 写入文本。优先走 {@code InputConnection#commitText}，它等价于"输入法输入"，
     * 会正常触发 TextWatcher / InputFilter；失败再退回 setText。
     */
    private static boolean commit(EditText editText, String code) {
        try {
            CharSequence old = editText.getText();
            if (old != null && old.length() > 0) {
                editText.selectAll();
            }
            EditorInfo info = new EditorInfo();
            InputConnection ic = editText.onCreateInputConnection(info);
            if (ic != null) {
                ic.beginBatchEdit();
                ic.commitText(code, 1);
                ic.endBatchEdit();
            } else {
                editText.setText(code);
            }
            CharSequence now = editText.getText();
            if (now != null && now.length() > 0) {
                try {
                    editText.setSelection(now.length());
                } catch (Throwable ignored) {
                }
                return true;
            }
        } catch (Throwable primary) {
            // 退回最朴素的方式
            try {
                editText.setText(code);
                editText.setSelection(code.length());
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 外部（焦点补填）使用，直接写文本 */
    public static void setText(EditText editText, String code) {
        MAIN.post(() -> commit(editText, code));
    }

    // ------------------------------------------------------------------
    // 候选输入框挑选
    // ------------------------------------------------------------------

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

    private static EditText pickBest(List<EditText> editors) {
        if (editors.isEmpty()) {
            return null;
        }
        EditText best = null;
        int bestScore = 0;
        for (EditText et : editors) {
            int score = score(et);
            if (score > bestScore) {
                bestScore = score;
                best = et;
            }
        }
        // 没有命中任何特征时，只有唯一输入框才敢填（避免填错地方）
        if (bestScore <= 0) {
            return editors.size() == 1 ? editors.get(0) : null;
        }
        return best;
    }

    private static int score(EditText et) {
        int score = 0;
        CharSequence hint = et.getHint();
        String hintStr = hint == null ? null : hint.toString();
        if (hintStr != null) {
            if (CodeParser.matchKeyword(hintStr, HookConfig.keywords())) {
                score += 5;
            }
            if (looksLikeCodeField(hintStr)) {
                score += 5;
            }
        }
        CharSequence desc = et.getContentDescription();
        if (desc != null && looksLikeCodeField(desc.toString())) {
            score += 3;
        }
        String idName = resourceName(et);
        if (idName != null && looksLikeCodeField(idName)) {
            score += 3;
        }
        if ((et.getInputType() & android.text.InputType.TYPE_MASK_CLASS)
                == android.text.InputType.TYPE_CLASS_NUMBER) {
            score += 2;
        }
        int max = maxLength(et);
        if (max >= 4 && max <= 8) {
            score += 3;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                String[] hints = et.getAutofillHints();
                if (hints != null) {
                    for (String h : hints) {
                        if (h != null && (h.toLowerCase().contains("sms")
                                || h.toLowerCase().contains("otp"))) {
                            score += 5;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (et.getText() == null || et.getText().length() == 0) {
            score += 1;
        }
        return score;
    }

    /** 该输入框是否"看起来像验证码框"：供焦点补填时判断要不要填 */
    public static boolean isLikelyCodeField(EditText et) {
        return score(et) >= 3;
    }

    private static boolean looksLikeCodeField(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String w : CODE_WORDS) {
            if (lower.contains(w)) {
                return true;
            }
        }
        for (String w : CODE_WORDS_CN) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String resourceName(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID || id <= 0) {
                return null;
            }
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取 android:maxLength（InputFilter.LengthFilter 的私有字段 mMax） */
    private static int maxLength(EditText et) {
        try {
            InputFilter[] filters = et.getFilters();
            if (filters == null) {
                return -1;
            }
            for (InputFilter f : filters) {
                if (f instanceof InputFilter.LengthFilter) {
                    Field field = InputFilter.LengthFilter.class.getDeclaredField("mMax");
                    field.setAccessible(true);
                    return (Integer) field.get(f);
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 6 格独立输入框的 OTP 界面：每格填一个字符 */
    private static boolean fillSplitBoxes(List<EditText> editors, String code) {
        if (editors.size() < 4 || code.length() < 4 || editors.size() < code.length()) {
            return false;
        }
        for (EditText et : editors) {
            if (maxLength(et) != 1) {
                return false;
            }
        }
        for (int i = 0; i < code.length(); i++) {
            if (!commit(editors.get(i), String.valueOf(code.charAt(i)))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // WebView（H5 登录页）
    // ------------------------------------------------------------------

    private static WebView findWebView(View view) {
        if (view == null) {
            return null;
        }
        if (view instanceof WebView) {
            return view.isShown() ? (WebView) view : null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 往 H5 页面里填验证码。优先匹配 autocomplete="one-time-code" 或
     * name/id/placeholder 含验证码关键词的输入框，找不到就退到第一个可见的
     * tel/number/text 输入框。
     */
    private static boolean fillWebView(XposedModule module, WebView webView, String code) {
        try {
            String js = "(function(c){"
                    + "var ins=document.querySelectorAll('input,textarea');"
                    + "var cand=null,i,el,sig,st;"
                    + "for(i=0;i<ins.length;i++){el=ins[i];"
                    + "if(el.disabled||el.readOnly)continue;"
                    + "st=window.getComputedStyle(el);"
                    + "if(!st||st.display==='none'||st.visibility==='hidden')continue;"
                    + "sig=((el.name||'')+' '+(el.id||'')+' '+(el.placeholder||'')+' '"
                    + "+(el.getAttribute('autocomplete')||'')+' '+(el.type||'')).toLowerCase();"
                    + "if(sig.indexOf('code')>=0||sig.indexOf('otp')>=0||sig.indexOf('verify')>=0"
                    + "||sig.indexOf('\\u9a8c\\u8bc1\\u7801')>=0"
                    + "||el.getAttribute('autocomplete')==='one-time-code'){cand=el;break;}}"
                    + "if(!cand){for(i=0;i<ins.length;i++){el=ins[i];"
                    + "if(el.disabled||el.readOnly)continue;"
                    + "st=window.getComputedStyle(el);"
                    + "if(!st||st.display==='none'||st.visibility==='hidden')continue;"
                    + "if(el.type==='tel'||el.type==='number'||el.type==='text'){cand=el;break;}}}"
                    + "if(!cand)return 'no-input';"
                    + "cand.focus();"
                    + "var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value')"
                    + "||Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value');"
                    + "if(d&&d.set){d.set.call(cand,c);}else{cand.value=c;}"
                    + "cand.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "cand.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "cand.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));"
                    + "return cand.value;})('" + code + "');";

            boolean original = webView.getSettings().getJavaScriptEnabled();
            if (!original) {
                webView.getSettings().setJavaScriptEnabled(true);
            }
            webView.evaluateJavascript(js, value ->
                    module.log(Log.INFO, TAG, "webview fill result: " + value));
            if (!original) {
                // 填完把 JS 开关恢复原样，避免长期改变应用行为
                MAIN.postDelayed(() -> {
                    try {
                        webView.getSettings().setJavaScriptEnabled(false);
                    } catch (Throwable ignored) {
                    }
                }, 1500L);
            }
            return true;
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "webview fill failed", t);
            return false;
        }
    }
}

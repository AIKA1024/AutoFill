package com.autofill.sms.hook;

import android.content.SharedPreferences;

import com.autofill.sms.Config;

import io.github.libxposed.api.XposedModule;

/**
 * 在被 Hook 的进程里读取远程配置（只读）。
 * <p>
 * 任何一次读取失败都退回默认值，保证不会因为配置问题影响目标进程。
 */
public final class HookConfig {

    private static volatile XposedModule sModule;
    private static volatile SharedPreferences sPrefs;

    private HookConfig() {
    }

    public static void init(XposedModule module) {
        sModule = module;
    }

    private static SharedPreferences prefs() {
        SharedPreferences p = sPrefs;
        if (p == null) {
            synchronized (HookConfig.class) {
                if (sPrefs == null) {
                    try {
                        sPrefs = sModule.getRemotePreferences(Config.GROUP);
                    } catch (Throwable t) {
                        return null;
                    }
                }
                p = sPrefs;
            }
        }
        return p;
    }

    private static boolean bool(String key, boolean def) {
        try {
            SharedPreferences p = prefs();
            return p == null ? def : p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String str(String key, String def) {
        try {
            SharedPreferences p = prefs();
            String v = p == null ? null : p.getString(key, def);
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean enabled() {
        return bool(Config.KEY_ENABLED, Config.DEF_ENABLED);
    }

    public static boolean autoCopy() {
        return bool(Config.KEY_AUTO_COPY, Config.DEF_AUTO_COPY);
    }

    public static boolean copyFull() {
        return bool(Config.KEY_COPY_FULL, Config.DEF_COPY_FULL);
    }

    public static boolean autoFill() {
        return bool(Config.KEY_AUTO_FILL, Config.DEF_AUTO_FILL);
    }

    public static boolean showToast() {
        return bool(Config.KEY_SHOW_TOAST, Config.DEF_SHOW_TOAST);
    }

    public static boolean showNotification() {
        return bool(Config.KEY_SHOW_NOTIFICATION, Config.DEF_SHOW_NOTIFICATION);
    }

    public static boolean blockSms() {
        return bool(Config.KEY_BLOCK_SMS, Config.DEF_BLOCK_SMS);
    }

    public static String regex() {
        return str(Config.KEY_REGEX, Config.DEF_REGEX);
    }

    public static String keywords() {
        return str(Config.KEY_KEYWORDS, Config.DEF_KEYWORDS);
    }

    public static String blacklist() {
        return str(Config.KEY_BLACKLIST, Config.DEF_BLACKLIST);
    }

    /** 包名是否命中黑名单（黑名单以逗号/分号/空格分隔） */
    public static boolean isBlacklisted(String packageName) {
        if (packageName == null) {
            return false;
        }
        String list = blacklist();
        if (list == null || list.trim().isEmpty()) {
            return false;
        }
        for (String item : list.split("[,，;；\\s]+")) {
            if (item.isEmpty()) {
                continue;
            }
            if (item.equals(packageName)) {
                return true;
            }
        }
        return false;
    }
}

package com.autofill.sms;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置页读写配置：优先写远程配置（下发给 Hook 进程），同时写一份本地兜底。
 */
public final class ConfigStore {

    private ConfigStore() {
    }

    private static SharedPreferences local(Context c) {
        return c.getSharedPreferences(Config.PREFS_BACKUP, Context.MODE_PRIVATE);
    }

    public static boolean getBoolean(Context c, String key, boolean def) {
        SharedPreferences remote = App.getRemotePrefs();
        if (remote != null) {
            return remote.getBoolean(key, def);
        }
        return local(c).getBoolean(key, def);
    }

    public static String getString(Context c, String key, String def) {
        SharedPreferences remote = App.getRemotePrefs();
        if (remote != null) {
            return remote.getString(key, def);
        }
        return local(c).getString(key, def);
    }

    public static void putBoolean(Context c, String key, boolean value) {
        local(c).edit().putBoolean(key, value).apply();
        SharedPreferences remote = App.getRemotePrefs();
        if (remote != null) {
            remote.edit().putBoolean(key, value).apply();
        }
    }

    public static void putString(Context c, String key, String value) {
        local(c).edit().putString(key, value).apply();
        SharedPreferences remote = App.getRemotePrefs();
        if (remote != null) {
            remote.edit().putString(key, value).apply();
        }
    }
}

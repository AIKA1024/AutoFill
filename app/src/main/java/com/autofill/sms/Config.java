package com.autofill.sms;

/**
 * Hook 进程与模块 App 共享的配置键与默认值。
 * <p>
 * 配置实际存放在 LSPosed 的 Remote Preferences（数据库）中：
 * 模块 App 通过 XposedService 写入，被 Hook 的进程通过 XposedInterface 读取。
 */
public final class Config {

    private Config() {
    }

    /** 远程配置组名 */
    public static final String GROUP = "autofill_sms";

    /** 未连接框架时的本地兜底存储文件名 */
    public static final String PREFS_BACKUP = "autofill_sms_local";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_AUTO_COPY = "auto_copy";
    public static final String KEY_COPY_FULL = "copy_full";
    public static final String KEY_AUTO_FILL = "auto_fill";
    public static final String KEY_SHOW_TOAST = "show_toast";
    public static final String KEY_SHOW_NOTIFICATION = "show_notification";
    public static final String KEY_BLOCK_SMS = "block_sms";
    public static final String KEY_REGEX = "regex";
    public static final String KEY_KEYWORDS = "keywords";
    public static final String KEY_BLACKLIST = "blacklist";

    public static final boolean DEF_ENABLED = true;
    public static final boolean DEF_AUTO_COPY = true;
    public static final boolean DEF_COPY_FULL = false;
    public static final boolean DEF_AUTO_FILL = true;
    public static final boolean DEF_SHOW_TOAST = true;
    public static final boolean DEF_SHOW_NOTIFICATION = true;
    public static final boolean DEF_BLOCK_SMS = false;

    /** 默认正则：4~8 位纯数字，且前后不能再接数字（避免命中订单号/手机号片段） */
    public static final String DEF_REGEX = "(?<![0-9])[0-9]{4,8}(?![0-9])";

    /** 默认关键词：命中其一才认为是验证码短信；留空表示不过滤 */
    public static final String DEF_KEYWORDS =
            "验证码,校验码,动态码,激活码,确认码,登录码,安全码,验证码为,验证,code,Code,CODE,verification,verify,OTP,otp";

    public static final String DEF_BLACKLIST = "";
}

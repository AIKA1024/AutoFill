package com.autofill.sms;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验证码提取。模块 App（设置页测试）与 Hook 进程共用同一套逻辑。
 */
public final class CodeParser {

    private CodeParser() {
    }

    /**
     * 判断文本是否命中关键词。关键词为空表示不过滤。
     */
    public static boolean matchKeyword(String text, String keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (keywords == null || keywords.trim().isEmpty()) {
            return true;
        }
        String lower = text.toLowerCase();
        for (String k : keywords.split("[,，;；\\s]+")) {
            if (k.isEmpty()) {
                continue;
            }
            if (lower.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从短信正文中提取验证码。
     * <p>
     * 规则：先过关键词，再用正则匹配；正则含捕获组时优先取第 1 组，否则取整条匹配。
     *
     * @return 验证码，未命中返回 null
     */
    public static String extract(String body, String regex, String keywords) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        if (!matchKeyword(body, keywords)) {
            return null;
        }
        String re = (regex == null || regex.trim().isEmpty()) ? Config.DEF_REGEX : regex.trim();
        try {
            Matcher matcher = Pattern.compile(re).matcher(body);
            if (matcher.find()) {
                if (matcher.groupCount() >= 1) {
                    String group = matcher.group(1);
                    if (group != null && !group.isEmpty()) {
                        return group;
                    }
                }
                return matcher.group();
            }
        } catch (Throwable ignored) {
            // 正则非法时静默失败，避免影响 Hook 进程
        }
        return null;
    }
}

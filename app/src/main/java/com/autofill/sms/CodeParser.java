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
     * 按可靠性从高到低依次尝试：
     * <ol>
     *   <li><b>关键词之后</b>的第一个匹配 —— 验证码基本都紧跟在"验证码/码是/code"这类
     *       提示词后面（{@code 【4399】验证码：798759} 应取 798759，而不是签名里的 4399）；</li>
     *   <li><b>屏蔽短信签名</b>（{@code 【4399】}、{@code [4399]} 等方括号内容）后的第一个匹配；</li>
     *   <li>整条短信的第一个匹配（兜底）。</li>
     * </ol>
     * 正则含捕获组时优先取第 1 组，否则取整条匹配。
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
        Pattern pattern;
        try {
            pattern = Pattern.compile(re);
        } catch (Throwable ignored) {
            // 正则非法时静默失败，避免影响 Hook 进程
            return null;
        }
        String nearKeyword = firstAfterKeyword(body, pattern, keywords);
        if (nearKeyword != null) {
            return nearKeyword;
        }
        String masked = maskSignatures(body);
        String inMasked = firstMatch(pattern, masked);
        if (inMasked != null) {
            return inMasked;
        }
        return firstMatch(pattern, body);
    }

    /** 短信签名：【4399】、[4399]、《4399》、『4399』、<4399> 之类，长度 0~20 */
    private static final Pattern SIGNATURE = Pattern.compile(
            "【[^】]{0,20}】|\\[[^\\]]{0,20}\\]|《[^》]{0,20}》|『[^』]{0,20}』|<[^>]{0,20}>");

    /** 把签名括号连同内容替换成等长空格，保持后面的字符下标不变 */
    private static String maskSignatures(String body) {
        Matcher m = SIGNATURE.matcher(body);
        StringBuilder out = new StringBuilder(body.length());
        int last = 0;
        while (m.find()) {
            out.append(body, last, m.start());
            for (int i = m.start(); i < m.end(); i++) {
                out.append(' ');
            }
            last = m.end();
        }
        out.append(body, last, body.length());
        return out.toString();
    }

    /** 关键词在短信里每次出现的位置之后，取最近的匹配；关键词为空时返回 null */
    private static String firstAfterKeyword(String body, Pattern pattern, String keywords) {
        if (keywords == null || keywords.trim().isEmpty()) {
            return null;
        }
        String lower = body.toLowerCase();
        for (String k : keywords.split("[,，;；\\s]+")) {
            if (k.isEmpty()) {
                continue;
            }
            String key = k.toLowerCase();
            int from = 0;
            int idx;
            while ((idx = lower.indexOf(key, from)) >= 0) {
                String tail = body.substring(idx + k.length());
                String found = firstMatch(pattern, maskSignatures(tail));
                if (found != null) {
                    return found;
                }
                from = idx + k.length();
            }
        }
        return null;
    }

    /** 取第一个匹配；正则含捕获组时优先第 1 组 */
    private static String firstMatch(Pattern pattern, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        if (m.groupCount() >= 1) {
            String group = m.group(1);
            if (group != null && !group.isEmpty()) {
                return group;
            }
        }
        return m.group();
    }
}

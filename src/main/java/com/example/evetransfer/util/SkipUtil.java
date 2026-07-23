package com.example.evetransfer.util;

public class SkipUtil {

    public static boolean skip(String text) {
        return containsChinese(text) || isHyperNet(text);
    }

    private static boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '一' && c <= '龥') {
                return true;
            }
        }
        return false;
    }

    private static boolean isHyperNet(String text) {
        return text.contains("HyperNet offer");
    }


}

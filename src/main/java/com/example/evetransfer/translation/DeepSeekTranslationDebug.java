package com.example.evetransfer.translation;

import java.io.Console;
import java.util.Scanner;

/**
 * 独立调试 DeepSeek 翻译接口。
 * 运行时通过控制台手动输入 API Key，然后循环输入待翻译文本。
 * 输入空行或 exit/quit 退出。
 */
public class DeepSeekTranslationDebug {

    public static void main(String[] args) throws Exception {
        System.setProperty("deepseek.api.key", "sk-");

        DeepSeekTranslationService service = new DeepSeekTranslationService();

        String line = "-Zirnitra 5/8POP win Marshal 8/16Buy a node, win the jackpot!";

        long t0 = System.currentTimeMillis();
        String result = "";
//        String result = service.translate(line, "zh").join();
        long cost = System.currentTimeMillis() - t0;
        System.out.println("译文(" + cost + "ms): " + result);
    }

    private static String readApiKey() {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("请输入 DeepSeek API Key: ");
            return chars == null ? null : new String(chars);
        }
        System.out.print("请输入 DeepSeek API Key: ");
        Scanner scanner = new Scanner(System.in);
        return scanner.hasNextLine() ? scanner.nextLine() : null;
    }
}

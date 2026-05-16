package com.example.evetransfer.translation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用 DeepSeek API 进行翻译。
 *
 * API key 读取优先级：
 * 1. 环境变量 DEEPSEEK_API_KEY
 * 2. 系统属性 deepseek.api.key（mvn javafx:run -Ddeepseek.api.key=xxx）
 *
 * 使用 JDK 内置 HttpClient，不需要额外 HTTP 库。
 * JSON 响应用正则提取 choices[0].message.content，不需要 Jackson/Gson。
 */
public class DeepSeekTranslationService implements TranslationService {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-v4-flash";

    // 正则提取 JSON 里 "content" : "xxx" 的值（支持转义引号）
    private static final Pattern CONTENT_PATTERN = Pattern.compile(
            "\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
    );

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiKey;

    public DeepSeekTranslationService() {
        this.apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[DeepSeek] 警告：未配置 API Key。请设置环境变量 DEEPSEEK_API_KEY " +
                    "或启动参数 -Ddeepseek.api.key=xxx");
        }
    }

    private String resolveApiKey() {
        String env = System.getenv("DEEPSEEK_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String prop = System.getProperty("deepseek.api.key");
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        return null;
    }

    @Override
    public CompletableFuture<String> translate(String text, String targetLanguage) {
        if (containsChinese(text)) {
            return CompletableFuture.completedFuture(text);
        }
        // 超网不翻译
        if (isHyperNet(text)) {
            return CompletableFuture.completedFuture(text);
        }

        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture("[未配置 API Key]");
        }

        String systemPrompt = buildSystemPrompt();
        String jsonBody = buildRequestBody(systemPrompt, text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return "[HTTP " + response.statusCode() + ": " + response.body().substring(0, Math.min(100, response.body().length())) + "]";
                    }
                    String body = response.body();
                    Matcher matcher = CONTENT_PATTERN.matcher(body);
                    // 第一个 content 是 system message（如果有），第二个是 assistant 的回复
                    // DeepSeek 响应里 choices[0].message.content 就是我们要的
                    String lastContent = null;
                    while (matcher.find()) {
                        lastContent = matcher.group(1);
                    }
                    return lastContent != null ? unescapeJson(lastContent) : "[无翻译结果]";
                })
                .exceptionally(ex -> "[翻译异常: " + ex.getMessage() + "]");
    }

    private String buildSystemPrompt() {
        return "你是一个EVE Online游戏聊天翻译器。用户将发送外语聊天消息，你需要：\n" +
                "1. 快速翻译成中文，只输出译文，不加任何解释、标点或格式标记。\n" +
                "2. 保留所有EVE特有名词（如舰船名、势力名、物品名、星系名）的英文原文或通用简称（例如：Tengu、Amarr、PLEX、Jita）。\n" +
                "3. 对简单问候或单个单词（o7, gf, brb）使用玩家常用译法（例如：o7→致敬，gf→好局，brb→马上回）。\n" +
                "4. 不翻译表情符号（:D, :(, o/）和常见的游戏缩写（FC, DPS, ISK, WH）。\n" +
                "5. 直接输出译文，严禁输出其他内容";
    }

    private String buildRequestBody(String systemPrompt, String userText) {
        return "{"
                + "\"model\":\"" + MODEL + "\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + escapeJson(systemPrompt) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(userText) + "\"}"
                + "],"
                + "\"stream\":false,"
                + "\"max_tokens\":4096,"
                + "\"temperature\":1"
                + "}";
    }

    private boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FA5') {
                return true;
            }
        }
        return false;
    }

    private boolean isHyperNet(String text) {
        return text.contains("HyperNet offer");
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i++; }
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

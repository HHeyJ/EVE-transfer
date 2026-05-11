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
 * 调用本地 Ollama 大模型接口进行翻译。
 *
 * 使用 JDK 自带的 HttpClient 发异步 POST 请求，不需要额外引入 HTTP 库。
 * JSON 响应结构非常简单，直接用正则提取 "response" 字段的值，
 * 不需要额外引入 Jackson / Gson 等 JSON 解析库。
 */
public class OllamaTranslationService implements TranslationService {

    // Ollama generate API 地址
    private static final String API_URL = "http://localhost:11434/api/generate";

    // 你本地拉取的模型名
    private static final String MODEL = "huihui_ai/hy-mt1.5-abliterated:1.8b";

    // 正则：从 JSON 字符串里提取 "response" 后面的值
    // 匹配 "response" : "xxx"，支持字符串里的转义引号 \"
    private static final Pattern RESPONSE_PATTERN = Pattern.compile(
            "\"response\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
    );

    // HttpClient 是线程安全的，整个应用复用一个实例即可
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public CompletableFuture<String> translate(String text, String targetLanguage) {
        // 如果原文已经是中文，直接返回原文，不走翻译接口
        if (containsChinese(text)) {
            return CompletableFuture.completedFuture(text);
        }

        // 把 zh/en/ja 等语言代码映射成人类可读的语言名，拼进 prompt
        String langName = mapLanguage(targetLanguage);

        // 构造 prompt，和你 postman 里的一样
        String prompt = "将以下文本翻译为" + langName + "，注意只需要输出翻译后的结果，不要额外解释：" + text;

        // 手动拼 JSON 请求体。先把 prompt 里的特殊字符做 JSON 转义。
        String jsonBody = "{"
                + "\"model\":\"" + MODEL + "\","
                + "\"prompt\":\"" + escapeJson(prompt) + "\","
                + "\"stream\":false"
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        // sendAsync 返回 CompletableFuture，不会阻塞主线程
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return "[HTTP " + response.statusCode() + "]";
                    }
                    String body = response.body();
                    Matcher matcher = RESPONSE_PATTERN.matcher(body);
                    if (matcher.find()) {
                        // 提取到的是 JSON 字符串，需要把转义字符还原
                        return unescapeJson(matcher.group(1));
                    }
                    return "[无翻译结果]";
                })
                .exceptionally(ex -> "[翻译异常: " + ex.getMessage() + "]");
    }

    /**
     * 检测文本是否包含中文字符（CJK 统一表意文字）。
     * 如果原文已经是中文，就不需要再翻译了。
     */
    private boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FA5') {
                return true;
            }
        }
        return false;
    }

    // 语言代码 -> 语言名，拼进 prompt
    private String mapLanguage(String code) {
        return switch (code.toLowerCase()) {
            case "zh", "zh-cn", "chinese" -> "Chinese";
            case "en", "english" -> "English";
            case "ja", "japanese" -> "Japanese";
            case "ko", "korean" -> "Korean";
            case "fr", "french" -> "French";
            case "de", "german" -> "German";
            case "ru", "russian" -> "Russian";
            default -> "Chinese";
        };
    }

    // JSON 字符串转义：把 \ 和 " 前面加上反斜杠，换行符转成 \n
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // JSON 字符串反转义：把 \" 还原成 "，\n 还原成换行，\\ 还原成 \
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

package com.example.evetransfer.translation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 调用 DeepSeek API 进行翻译。
 *
 * API key 读取优先级：
 * 1. 环境变量 DEEPSEEK_API_KEY
 * 2. 系统属性 deepseek.api.key
 */
@Service
public class DeepSeekTranslationService implements TranslationService {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-v4-flash";
    private static final int MAX_TOKENS = 4096;
    private static final double TEMPERATURE = 1.0;

    private static final String SYSTEM_PROMPT =
            "你是一个EVE Online游戏聊天翻译器。用户将发送外语聊天消息，你需要：\n" +
            "1. 快速翻译成中文，只输出译文，不加任何解释、标点或格式标记。\n" +
            "2. 保留所有EVE特有名词（如舰船名、势力名、物品名、星系名）的英文原文或通用简称（例如：Tengu、Amarr、PLEX、Jita）。\n" +
            "3. 对简单问候或单个单词（o7, gf, brb）使用玩家常用译法（例如：o7→致敬，gf→好局，brb→马上回）。\n" +
            "4. 不翻译表情符号（:D, :(, o/）和常见的游戏缩写（FC, DPS, ISK, WH）。\n" +
            "5. 直接输出译文，严禁输出其他内容";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
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
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        if (containsChinese(text)) {
            return CompletableFuture.completedFuture(text);
        }
        if (isHyperNet(text)) {
            return CompletableFuture.completedFuture(text);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        return doRequest(SYSTEM_PROMPT, text);
    }

    private CompletableFuture<String> doRequest(String systemPrompt, String userText) {
        ChatRequest requestBody = new ChatRequest(
                MODEL,
                List.of(
                        new Message("system", systemPrompt),
                        new Message("user", userText)
                ),
                false,
                new Thinking("disabled"),
                MAX_TOKENS,
                TEMPERATURE
        );

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            return CompletableFuture.completedFuture("[请求序列化异常: " + e.getMessage() + "]");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        String snippet = response.body().substring(0, Math.min(100, response.body().length()));
                        return "[HTTP " + response.statusCode() + ": " + snippet + "]";
                    }
                    try {
                        ChatResponse parsed = objectMapper.readValue(response.body(), ChatResponse.class);
                        if (parsed.choices() == null || parsed.choices().isEmpty()) {
                            return "[无翻译结果]";
                        }
                        Message message = parsed.choices().get(0).message();
                        if (message == null || message.content() == null || message.content().isBlank()) {
                            return "[无翻译结果]";
                        }
                        return message.content();
                    } catch (JsonProcessingException e) {
                        return "[响应解析异常: " + e.getMessage() + "]";
                    }
                })
                .exceptionally(ex -> "[翻译异常: " + ex.getMessage() + "]");
    }

    private boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '一' && c <= '龥') {
                return true;
            }
        }
        return false;
    }

    private boolean isHyperNet(String text) {
        return text.contains("HyperNet offer");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ChatRequest(
            String model,
            List<Message> messages,
            boolean stream,
            Thinking thinking,
            @JsonProperty("max_tokens") int maxTokens,
            double temperature
    ) {}

    private record Message(String role, String content) {}

    private record Thinking(String type) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            Usage usage,
            @JsonProperty("system_fingerprint") String systemFingerprint
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(
            int index,
            Message message,
            Object logprobs,
            @JsonProperty("finish_reason") String finishReason
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens,
            @JsonProperty("prompt_tokens_details") PromptTokensDetails promptTokensDetails,
            @JsonProperty("prompt_cache_hit_tokens") int promptCacheHitTokens,
            @JsonProperty("prompt_cache_miss_tokens") int promptCacheMissTokens
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record PromptTokensDetails(
            @JsonProperty("cached_tokens") int cachedTokens
    ) {}
}

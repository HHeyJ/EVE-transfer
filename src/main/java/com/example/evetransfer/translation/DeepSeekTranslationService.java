package com.example.evetransfer.translation;

import com.example.evetransfer.model.ChatMessage;
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
import java.util.ArrayList;
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
            "你是一位精通中英双语、熟悉 EVE Online 游戏术语和社区文化的专业翻译。你的任务是将用户逐条提供的英文聊天记录（包含时间戳、角色名和发言内容）准确、流畅地翻译成中文。\n" +
                    "\n" +
                    "翻译规则：\n" +
                    "1. **保留格式**：每条消息的 `发言者 > 内容` 结构必须原样保留，仅翻译发言内容部分。\n" +
                    "2. **术语一致**：使用 EVE 中文玩家社区通用译名，例如：\n" +
                    "   - NPE → 新手引导体验（New Player Experience）\n" +
                    "   - toon → 角色/人物\n" +
                    "   - training queue → 技能训练队列\n" +
                    "   - ISK → 伊甸币（或保留 ISK）\n" +
                    "   - o7 → 保持“o7”（玩家敬礼表情）\n" +
                    "3. **风格自然**：翻译应保留原文的口语化、轻松或求助语气，避免生硬直译。例如 “nice one” → “干得漂亮”，“woot” → “哇哦”。\n" +
                    "4. **上下文连贯**：由于是多轮对话，需参考之前轮次的翻译结果，确保人名、事件、术语前后一致。如果某条消息指代了之前的发言（如“Yeah, maybe I did something like that”），要结合历史语境译出合理的指代。\n" +
                    "5. **特殊标记**：时间戳和角色名保持原样（不翻译），仅在内容中出现的游戏内物品、地点、舰船名等按社区习惯翻译。\n" +
                    "6. **无额外解释**：仅输出翻译后的`内容`，不要附加注释、说明或元评论。\n" +
                    "7. ***绝对禁止***：绝对禁止返回输出`发言者`！！！\n" +
                    "\n" +
                    "示例输入：\n" +
                    "Gilli Anne Dakken > Eagle is Airborne o7\n" +
                    "\n" +
                    "示例输出：\n" +
                    "雄鹰已经起飞 o7\n" +
                    "\n" +
                    "现在，请开始逐条翻译用户提供的聊天记录，每条独立但保持整体连贯。";

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
    public CompletableFuture<String> translate(ChatMessage msg, List<ChatMessage> history) {
        String text = msg.getOriginal();
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        return doRequest(SYSTEM_PROMPT, history, msg);
    }

    /**
     * 手动翻译：把用户输入（一般是中文）翻译成指定目标语言。
     * 无历史上下文，纯文本进出。
     */
    public CompletableFuture<String> translateTo(String text, String targetLanguage) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture("[未配置 API Key]");
        }
        String lang = (targetLanguage == null || targetLanguage.isBlank()) ? "English" : targetLanguage;
        return doPlainRequest(buildOutboundPrompt(lang), text);
    }

    private String buildOutboundPrompt(String targetLanguage) {
        return "你是一个EVE Online游戏聊天翻译器。用户将发送一段中文聊天消息，你需要：\n" +
                "1. 将内容翻译成 " + targetLanguage + "，只输出译文，不加任何解释、标点或格式标记。\n" +
                "2. 保留所有EVE特有名词（如舰船名、势力名、物品名、星系名）的英文原文或通用简称（例如：Tengu、Amarr、PLEX、Jita）。\n" +
                "3. 常用玩家缩写保持原样（o7, gf, brb, FC, DPS, ISK, WH 等）。\n" +
                "4. 不翻译表情符号（:D, :(, o/）。\n" +
                "5. 直接输出译文，严禁输出其他内容。";
    }

    /**
     * 简化版：无历史、无发言者前缀，只做单条文本翻译。给 translateTo 用。
     */
    private CompletableFuture<String> doPlainRequest(String systemPrompt, String userText) {
        List<Message> messages = List.of(
                new Message("system", systemPrompt),
                new Message("user", userText)
        );
        return sendChat(messages);
    }

    private CompletableFuture<String> doRequest(String systemPrompt, List<ChatMessage> history, ChatMessage msg) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        if (history != null) {
            for (ChatMessage turn : history) {
                if (turn == null) continue;
                String original = turn.getOriginal();
                String translated = turn.getTranslated();
                String player = turn.getPlayer();
                if (original == null || original.isBlank()) continue;
                if (translated == null || translated.isBlank()) continue;
                if (player == null || player.isBlank()) continue;
                messages.add(new Message("user", player + " > " + original));
                messages.add(new Message("assistant", translated));
            }
        }
        messages.add(new Message("user", msg.getPlayer() + " > " + msg.getOriginal()));
        return sendChat(messages);
    }

    private CompletableFuture<String> sendChat(List<Message> messages) {
        ChatRequest requestBody = new ChatRequest(
                MODEL,
                messages,
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

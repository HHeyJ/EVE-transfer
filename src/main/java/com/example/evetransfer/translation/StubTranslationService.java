package com.example.evetransfer.translation;

import java.util.concurrent.CompletableFuture;

/**
 * 占位翻译实现，仅用于演示和测试。
 *
 * 实际接入本地大模型时，把这个类替换掉，
 * 让 translate() 里调用你的 HTTP 接口（如 Ollama / LM Studio）。
 */
public class StubTranslationService implements TranslationService {
    @Override
    public CompletableFuture<String> translate(String text, String targetLanguage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 模拟网络延迟 5 毫秒
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "[翻译] " + text;
        });
    }
}

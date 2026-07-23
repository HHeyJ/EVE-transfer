package com.example.evetransfer.translation;

import com.example.evetransfer.model.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface TranslationService {

    /**
     * 带历史上下文的翻译：history 中每条已翻译的 ChatMessage
     * 会被展开为一次 user(原文)/assistant(译文) 对话。
     */
    CompletableFuture<String> translate(ChatMessage msg, List<ChatMessage> history);
}

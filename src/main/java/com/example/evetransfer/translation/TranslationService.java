package com.example.evetransfer.translation;

import java.util.concurrent.CompletableFuture;

/**
 * 翻译服务接口。
 *
 * 这里用接口（interface）而不是直接写死实现，
 * 是为了以后接入本地大模型 API 时，只需要写一个新的类实现这个接口，
 * 其他地方完全不用改。
 *
 * 类比 web：相当于定义了一个翻译 API 的接口文件，
 * 不同提供商（OpenAI、Ollama、本地模型）各自实现。
 */
public interface TranslationService {

    /**
     * 翻译文本。
     *
     * @param text           要翻译的原文
     * @param targetLanguage 目标语言代码，如 "zh"（中文）、"en"（英文）
     * @return CompletableFuture<String>，异步返回翻译结果。
     *         为什么用 CompletableFuture？因为翻译可能要几百毫秒甚至几秒，
     *         不能阻塞主线程（否则 UI 会卡死）。
     *         类比 JS：相当于 async function translate(text) { return await fetch(...) }
     */
    CompletableFuture<String> translate(String text, String targetLanguage);
}

package com.example.evetransfer.translation;

import java.util.concurrent.CompletableFuture;

public interface TranslationService {

    CompletableFuture<String> translate(String text, String targetLanguage);
}

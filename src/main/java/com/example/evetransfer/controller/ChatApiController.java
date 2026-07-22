package com.example.evetransfer.controller;

import com.example.evetransfer.model.ChatMessage;
import com.example.evetransfer.service.ChatService;
import com.example.evetransfer.translation.DeepSeekTranslationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class ChatApiController {

    private final ChatService chatService;
    private final DeepSeekTranslationService translationService;

    public ChatApiController(ChatService chatService, DeepSeekTranslationService translationService) {
        this.chatService = chatService;
        this.translationService = translationService;
    }

    @PostMapping("/directory")
    public ResponseEntity<?> setDirectory(@RequestBody Map<String, String> body) {
        String dir = body.get("path");
        if (dir == null || dir.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "路径不能为空"));
        }
        try {
            chatService.setLogDirectory(dir);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/channels")
    public Set<String> getChannels() {
        return chatService.getChannels();
    }

    @GetMapping("/channels/{channel}/listening")
    public Map<String, Boolean> isListening(@PathVariable String channel) {
        return Map.of("listening", chatService.isListening(channel));
    }

    @PostMapping("/channels/{channel}/listening")
    public ResponseEntity<?> setListening(@PathVariable String channel, @RequestBody Map<String, Boolean> body) {
        Boolean listening = body.get("listening");
        if (listening == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "参数错误"));
        }
        chatService.setListening(channel, listening);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/channels/{channel}/messages")
    public List<ChatMessage> getMessages(@PathVariable String channel) {
        return chatService.getMessages(channel);
    }

    @PostMapping("/translate")
    public ResponseEntity<?> translate(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        String targetLanguage = body.get("targetLanguage");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文本不能为空"));
        }
        try {
            String result = translationService.translateTo(text, targetLanguage).join();
            return ResponseEntity.ok(Map.of("translated", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}

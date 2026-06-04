package com.example.evetransfer.controller;

import com.example.evetransfer.service.ChatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class SseController {

    private final ChatService chatService;

    public SseController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/sse/chat")
    public SseEmitter subscribe() {
        return chatService.subscribe();
    }
}

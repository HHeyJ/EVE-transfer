package com.example.evetransfer.controller;

import com.example.evetransfer.service.ChatService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    private final ChatService chatService;

    public PageController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("dirSet", chatService.isDirectorySet());
        model.addAttribute("dirPath", chatService.getLogDirPath());
        return "index";
    }
}

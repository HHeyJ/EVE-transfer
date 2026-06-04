package com.example.evetransfer.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条聊天消息的数据模型。
 */
public class ChatMessage {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final long id;
    private final LocalDateTime timestamp;
    private final String channel;
    private final String player;
    private final String original;
    private String translated;

    public ChatMessage(LocalDateTime timestamp, String channel, String player, String original) {
        this.id = ID_GENERATOR.incrementAndGet();
        this.timestamp = timestamp;
        this.channel = channel;
        this.player = player;
        this.original = original;
        // 第一版不接 AI 翻译 API，默认返回原文
        this.translated = original;
    }

    public long getId() { return id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getChannel() { return channel; }
    public String getPlayer() { return player; }
    public String getOriginal() { return original; }
    public String getTranslated() { return translated; }
    public void setTranslated(String translated) { this.translated = translated; }

    public String getTimeStr() {
        return timestamp.format(TIME_FMT);
    }
}

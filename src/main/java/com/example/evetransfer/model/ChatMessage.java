package com.example.evetransfer.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条聊天消息的数据模型。
 */
@Getter
public class ChatMessage {

    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final long id;
    private final LocalDateTime timestamp;
    private final String channel;
    private final String player;
    private final String original;

    @Setter
    private String translated;

    public ChatMessage(LocalDateTime timestamp, String channel, String player, String original) {
        this.id = ID_GENERATOR.incrementAndGet();
        this.timestamp = timestamp;
        this.channel = channel;
        this.player = player;
        this.original = original;
    }

    public String getTimeStr() {
        return timestamp.format(TIME_FMT);
    }
}

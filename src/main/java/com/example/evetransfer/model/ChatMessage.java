package com.example.evetransfer.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条聊天消息的数据模型，相当于前端里的一个 JS 对象：
 * { id, timestamp, channel, player, original, translated }
 *
 * 用 JavaFX 的 StringProperty 包装 translated，
 * 这样翻译完成后 UI 能自动收到通知并刷新。
 *
 * 类比 web：相当于 Vue 的 reactive({ translated: '' }) 或 React 的 useState。
 */
public class ChatMessage {

    // 自增 ID，避免不同消息时间戳相同时分不清
    private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

    private final long id;                  // 唯一标识
    private final LocalDateTime timestamp;  // 消息时间
    private final String channel;           // 频道名（Alliance / Local / Corp...）
    private final String player;            // 玩家名
    private final String original;          // 原文
    // translated 用 StringProperty 而不是普通 String，
    // 这样外部可以通过 addListener() 监听变化，类似 Vue 的 watch / React 的 useEffect
    private final StringProperty translated = new SimpleStringProperty("");

    public ChatMessage(LocalDateTime timestamp, String channel, String player, String original) {
        this.id = ID_GENERATOR.incrementAndGet();
        this.timestamp = timestamp;
        this.channel = channel;
        this.player = player;
        this.original = original;
    }

    public long getId() { return id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getChannel() { return channel; }
    public String getPlayer() { return player; }
    public String getOriginal() { return original; }

    // 翻译结果的 getter / setter / property 访问器
    public String getTranslated() { return translated.get(); }
    public void setTranslated(String value) { translated.set(value); }
    public StringProperty translatedProperty() { return translated; }

    @Override
    public String toString() {
        return String.format("[%s] %s: %s", channel, player, original);
    }
}

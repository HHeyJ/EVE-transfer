package com.example.evetransfer.service;

import com.example.evetransfer.model.ChatMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 管理 SSE 连接并向所有客户端广播消息。
 */
@Component
public class SseBroadcaster {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * 新增一个 SSE 订阅。返回的 SseEmitter 会挂在当前 HTTP 长连接上，
     * 连接关闭 / 超时 / 出错时自动从列表移除，避免向死连接推送。
     * 超时设为 0 表示不主动断开，由客户端决定生命周期。
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /**
     * 把一条聊天消息序列化成 JSON 后广播给所有订阅者。
     * 手写 JSON 是为了避免额外引入序列化配置；字段与前端约定字段名对齐。
     */
    public void pushMessage(ChatMessage msg) {
        String json = String.format(
                "{\"id\":%d,\"channel\":\"%s\",\"player\":\"%s\",\"time\":\"%s\",\"original\":\"%s\",\"translated\":\"%s\"}",
                msg.getId(),
                escapeJson(msg.getChannel()),
                escapeJson(msg.getPlayer()),
                escapeJson(msg.getTimeStr()),
                escapeJson(msg.getOriginal()),
                escapeJson(msg.getTranslated())
        );
        broadcast(json);
    }

    /**
     * 通知前端"频道列表发生了变化"，前端收到后会重新拉一次 /api/channels。
     * 用于新频道被自动发现的场景。
     */
    public void pushChannelListUpdate() {
        broadcast("{\"type\":\"channels\"}");
    }

    /**
     * 向所有存活的 SseEmitter 发一段字符串。
     * 发送失败一般意味着连接已经断了，直接从列表移除。
     */
    private void broadcast(String json) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    /**
     * 转义 JSON 字符串里的特殊字符（反斜杠、双引号、控制字符），
     * 保证拼接出的 JSON 合法。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

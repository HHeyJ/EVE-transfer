package com.example.evetransfer.service;

import com.example.evetransfer.model.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存频道监听状态和每个频道的消息缓存。
 * 逻辑集中在这里，避免消息与频道状态散在各处。
 */
@Component
public class ChannelStore {

    private static final int MAX_MESSAGES_PER_CHANNEL = 200;

    private final Map<String, List<ChatMessage>> channelMessages = new ConcurrentHashMap<>();
    private final Map<String, Boolean> channelListening = new ConcurrentHashMap<>();

    /**
     * 切换日志目录时调用，清空所有频道数据，恢复到初始状态。
     */
    public void reset() {
        channelMessages.clear();
        channelListening.clear();
    }

    /**
     * 幂等地登记一个频道：不存在就创建监听条目和空的消息列表；已存在则忽略。
     * 消息列表用 synchronizedList 包一层，配合外部 synchronized 块使用。
     */
    public void ensureChannel(String channel) {
        channelListening.putIfAbsent(channel, false);
        channelMessages.computeIfAbsent(channel, k -> Collections.synchronizedList(new ArrayList<>()));
    }

    /**
     * 登记频道并告诉调用方是不是首次见到这个频道。
     * true = 之前不存在（可用于触发前端刷新频道列表），false = 已经登记过。
     */
    public boolean registerIfAbsent(String channel) {
        boolean isNew = !channelListening.containsKey(channel);
        ensureChannel(channel);
        return isNew;
    }

    /**
     * 返回按字典序排好的当前所有频道名快照。
     */
    public Set<String> getChannels() {
        return new TreeSet<>(channelListening.keySet());
    }

    /**
     * 查询指定频道是否处于监听中。未登记的频道视为未监听。
     */
    public boolean isListening(String channel) {
        return channelListening.getOrDefault(channel, false);
    }

    /**
     * 设置频道的监听开关。上层拿到 true 时通常还要触发一次历史扫描。
     */
    public void setListening(String channel, boolean listening) {
        channelListening.put(channel, listening);
    }

    /**
     * 往指定频道的缓存里追加一条消息；超出上限时丢弃最早那条。
     * 用消息列表本身作为锁，保证 append + trim 的原子性。
     */
    public void appendMessage(String channel, ChatMessage msg) {
        List<ChatMessage> list = channelMessages.get(channel);
        if (list == null) return;
        synchronized (list) {
            list.add(msg);
            if (list.size() > MAX_MESSAGES_PER_CHANNEL) {
                list.removeFirst();
            }
        }
    }

    /**
     * 拿到某频道的消息快照供前端渲染。
     * 未监听的频道返回空列表（业务约定：不显示历史消息，只有开始监听后才可见）。
     * 返回的是新 ArrayList，调用方可以安全遍历。
     */
    public List<ChatMessage> getMessages(String channel) {
        if (!isListening(channel)) {
            return Collections.emptyList();
        }
        List<ChatMessage> list = channelMessages.get(channel);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }
}

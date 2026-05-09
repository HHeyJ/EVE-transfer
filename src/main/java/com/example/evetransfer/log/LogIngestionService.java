package com.example.evetransfer.log;

import com.example.evetransfer.model.ChatMessage;
import com.example.evetransfer.model.LogFileState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 日志摄取服务：协调"读取文件 → 解析行 → 产生消息"的流水线。
 *
 * 相当于前端的一个数据处理中间件：
 * 输入：文件路径，输出：ChatMessage 对象，交给 UI 显示和翻译队列。
 */
public class LogIngestionService {

    private final LogParser parser = new LogParser();    // 行解析器
    private final LogReader reader = new LogReader();    // 增量读取器
    // 每个文件对应一个 LogFileState，ConcurrentHashMap 保证多线程安全
    private final Map<Path, LogFileState> fileStates = new ConcurrentHashMap<>();
    private final Consumer<ChatMessage> messageConsumer; // 解析出消息后推给谁

    public LogIngestionService(Consumer<ChatMessage> messageConsumer) {
        this.messageConsumer = messageConsumer;
    }

    /**
     * 在监控开始前先登记文件，防止重复创建状态对象。
     */
    public void registerFile(Path path) {
        fileStates.computeIfAbsent(path, LogFileState::new);
    }

    /**
     * 启动时的批量初始扫描。
     *
     * 逻辑：先把所有文件全部读一遍，解析出所有消息，
     * 然后按频道分组，每个频道只保留时间最近的 limitPerChannel 条，
     * 最后按时间顺序统一推给 consumer。
     *
     * 这样即使某个频道有 10 个历史文件，也只会翻译 20 条，而不是 10×20 条。
     *
     * @param paths           要扫描的文件列表
     * @param limitPerChannel 每个频道保留的最大消息数
     */
    public void handleInitialScan(List<Path> paths, int limitPerChannel) {
        List<ChatMessage> allMessages = new ArrayList<>();

        // 第一步：读取所有文件，解析出全部消息（不推给 consumer，先收集）
        for (Path path : paths) {
            final LogFileState state = fileStates.computeIfAbsent(path, LogFileState::new);
            try {
                List<String> lines = reader.readNewLines(path, state);
                if (!lines.isEmpty()) {
                    System.out.println("[ingest] 初始扫描 " + path.getFileName() + " -> " + lines.size() + " 行");
                }
                for (String line : lines) {
                    parser.parseLine(line).ifPresent(msg -> {
                        if (state.getChannelName() == null) {
                            state.setChannelName(msg.getChannel());
                        }
                        allMessages.add(msg);
                    });
                }
            } catch (IOException e) {
                System.err.println("[ingest] 初始扫描失败 " + path + ": " + e.getMessage());
            }
        }

        if (allMessages.isEmpty()) {
            System.out.println("[ingest] 初始扫描未读到任何消息");
            return;
        }

        // 第二步：按频道分组，每个频道按时间排序，只保留最近的 limitPerChannel 条
        Map<String, List<ChatMessage>> byChannel = allMessages.stream()
                .collect(Collectors.groupingBy(ChatMessage::getChannel));

        List<ChatMessage> toKeep = new ArrayList<>();
        for (Map.Entry<String, List<ChatMessage>> entry : byChannel.entrySet()) {
            List<ChatMessage> list = entry.getValue();
            // 按时间戳排序，时间相同按 id 排序（id 是自增的，保证稳定）
            list.sort(Comparator.comparing(ChatMessage::getTimestamp)
                    .thenComparingLong(ChatMessage::getId));
            // 取最后 limitPerChannel 条
            int fromIndex = Math.max(0, list.size() - limitPerChannel);
            toKeep.addAll(list.subList(fromIndex, list.size()));
        }

        // 第三步：把所有保留的消息再按时间顺序统一发送给 consumer
        toKeep.sort(Comparator.comparing(ChatMessage::getTimestamp)
                .thenComparingLong(ChatMessage::getId));

        System.out.println("[ingest] 初始扫描完成，共 " + allMessages.size()
                + " 条消息，保留 " + toKeep.size() + " 条送入翻译队列");

        for (ChatMessage msg : toKeep) {
            messageConsumer.accept(msg);
        }
    }

    /**
     * 实时监控时的增量读取：有新内容就全部消费。
     */
    public void handleFileChange(Path path) {
        final LogFileState state = fileStates.computeIfAbsent(path, LogFileState::new);

        try {
            List<String> lines = reader.readNewLines(path, state);
            if (!lines.isEmpty()) {
                System.out.println("[ingest] " + path.getFileName() + " -> " + lines.size() + " 行新内容");
            }
            for (String line : lines) {
                parser.parseLine(line).ifPresentOrElse(msg -> {
                    if (state.getChannelName() == null) {
                        state.setChannelName(msg.getChannel());
                    }
                    messageConsumer.accept(msg);
                }, () -> System.out.println("[ingest] 解析失败: " + line.substring(0, Math.min(60, line.length()))));
            }
        } catch (IOException e) {
            System.err.println("[ingest] 读取失败 " + path + ": " + e.getMessage());
        }
    }

    public Map<Path, LogFileState> getFileStates() {
        return fileStates;
    }
}

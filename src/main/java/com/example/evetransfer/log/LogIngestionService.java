package com.example.evetransfer.log;

import com.example.evetransfer.model.ChatMessage;
import com.example.evetransfer.model.LogFileState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
     * 文件发生变化时调用：增量读取新内容，逐行解析，把有效消息推给 consumer。
     *
     * @param path 发生变化的文件
     */
    /**
     * 启动时的初始读取：只保留每个文件最后 limit 条消息，
     * 防止应用启动时一次性把所有历史日志全部塞进 UI。
     */
    public void handleInitialFile(Path path, int limit) {
        final LogFileState state = fileStates.computeIfAbsent(path, LogFileState::new);

        try {
            List<String> lines = reader.readNewLines(path, state);
            if (lines.isEmpty()) return;

            System.out.println("[ingest] 初始读取 " + path.getFileName() + " 共 " + lines.size() + " 行，保留最后 " + limit + " 条");

            // 只取最后 limit 行
            int fromIndex = Math.max(0, lines.size() - limit);
            List<String> recentLines = lines.subList(fromIndex, lines.size());

            for (String line : recentLines) {
                parser.parseLine(line).ifPresent(msg -> {
                    if (state.getChannelName() == null) {
                        state.setChannelName(msg.getChannel());
                    }
                    messageConsumer.accept(msg);
                });
            }
        } catch (IOException e) {
            System.err.println("[ingest] 初始读取失败 " + path + ": " + e.getMessage());
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

package com.example.evetransfer.service;

import com.example.evetransfer.log.LogDirectoryMonitor;
import com.example.evetransfer.log.LogIngestionService;
import com.example.evetransfer.model.ChatMessage;
import com.example.evetransfer.model.LogFileState;
import com.example.evetransfer.translation.QueuedTranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 门面：串联日志目录、消息缓存、翻译、SSE 广播。
 * 具体的连接管理放在 SseBroadcaster；频道数据放在 ChannelStore。
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final QueuedTranslationService translationService;
    private final ChannelStore channelStore;
    private final SseBroadcaster sseBroadcaster;

    private LogDirectoryMonitor monitor;
    private LogIngestionService ingestionService;
    private Path logDir;

    /**
     * 判断用户是否已经选好了日志目录。用于首屏决定是显示设置面板还是聊天页。
     */
    public boolean isDirectorySet() {
        return logDir != null;
    }

    /**
     * 拿到当前日志目录的字符串路径，未设置时返回空串。
     */
    public String getLogDirPath() {
        return logDir != null ? logDir.toString() : "";
    }

    /**
     * 切换日志目录：
     * 1) 停掉旧监控；2) 清空频道数据；3) 重建 ingestion 管道；
     * 4) 快速扫描一次目录内所有 txt 头部提取频道名；5) 启动目录监控。
     * 切换后所有频道默认都是"未监听"，等用户手动开启。
     */
    public void setLogDirectory(String dirPath) throws Exception {
        stopMonitoring();
        this.logDir = Paths.get(dirPath).toAbsolutePath().normalize();

        channelStore.reset();
        ingestionService = new LogIngestionService(this::onNewMessage);

        List<Path> allFiles = scanTxtFiles(logDir);
        Set<String> channels = ingestionService.discoverChannels(allFiles);
        for (String ch : channels) {
            channelStore.ensureChannel(ch);
        }

        monitor = new LogDirectoryMonitor(logDir, path -> {
            ingestionService.registerFile(path);
            ingestionService.handleFileChange(path, channelStore.getListeningView());
        });
        monitor.start();
    }

    /**
     * 返回当前所有已知频道的名字（含未监听的），供前端渲染 tab 列表。
     */
    public Set<String> getChannels() {
        return channelStore.getChannels();
    }

    /**
     * 查询某个频道是否正在监听。前端根据这个值决定要不要显示"开始监听"横幅。
     */
    public boolean isListening(String channel) {
        return channelStore.isListening(channel);
    }

    /**
     * 切换某频道的监听开关。
     * 打开监听时，回补一次历史扫描（每个频道保留最近 20 条），
     * 让用户开箱就能看到近期消息而不用等新增。
     */
    public void setListening(String channel, boolean listening) {
        channelStore.setListening(channel, listening);
        if (listening && logDir != null) {
            List<Path> files = scanTxtFiles(logDir).stream()
                    .filter(p -> {
                        LogFileState st = ingestionService.getFileState(p);
                        return st != null && channel.equals(st.getChannelName());
                    })
                    .collect(Collectors.toList());
            ingestionService.handleInitialScan(files, 20);
        }
    }

    /**
     * 拿到指定频道当前缓存的消息（未监听则返回空列表）。
     */
    public List<ChatMessage> getMessages(String channel) {
        return channelStore.getMessages(channel);
    }

    /**
     * SSE 订阅入口，直接委派给广播器。
     */
    public SseEmitter subscribe() {
        return sseBroadcaster.subscribe();
    }

    /**
     * ingestion 管道解析出一条新消息后的回调：
     * 1) 遇到新频道就登记并通知前端刷新频道列表；
     * 2) 消息永远进入频道缓存（哪怕未监听也保留，方便后续开启监听时展示）；
     * 3) 只对正在监听的频道触发翻译；翻译完成后推给所有 SSE 客户端。
     */
    private void onNewMessage(ChatMessage msg) {
        String ch = msg.getChannel();
        if (channelStore.registerIfAbsent(ch)) {
            sseBroadcaster.pushChannelListUpdate();
        }
        channelStore.appendMessage(ch, msg);
        if (channelStore.isListening(ch)) {
            translationService.offer(msg, sseBroadcaster::pushMessage);
        }
    }

    /**
     * 罗列目录下所有 .txt 日志文件。目录不存在或不可读时返回空列表，
     * 保证上层逻辑不用处理 IOException。
     */
    private List<Path> scanTxtFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        if (java.nio.file.Files.exists(dir)) {
            try (var stream = java.nio.file.Files.newDirectoryStream(dir, "*.txt")) {
                for (Path p : stream) files.add(p);
            } catch (Exception ignored) {}
        }
        return files;
    }

    /**
     * 关闭当前的目录监控（如果有）。切换目录或应用退出时调用，防止 WatchService 泄漏。
     */
    private void stopMonitoring() {
        if (monitor != null) {
            monitor.close();
            monitor = null;
        }
    }
}

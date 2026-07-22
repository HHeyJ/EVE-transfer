package com.example.evetransfer.service;

import com.example.evetransfer.log.LogDirectoryMonitor;
import com.example.evetransfer.log.LogIngestionService;
import com.example.evetransfer.model.ChatMessage;
import com.example.evetransfer.model.LogFileState;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final int MAX_MESSAGES_PER_CHANNEL = 200;

    private final Map<String, List<ChatMessage>> channelMessages = new ConcurrentHashMap<>();
    private final Map<String, Boolean> channelListening = new ConcurrentHashMap<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private LogDirectoryMonitor monitor;
    private LogIngestionService ingestionService;
    private Path logDir;

    public boolean isDirectorySet() {
        return logDir != null;
    }

    public String getLogDirPath() {
        return logDir != null ? logDir.toString() : "";
    }

    public void setLogDirectory(String dirPath) throws Exception {
        stopMonitoring();
        this.logDir = Paths.get(dirPath).toAbsolutePath().normalize();

        channelMessages.clear();
        channelListening.clear();

        ingestionService = new LogIngestionService(this::onNewMessage);

        List<Path> allFiles = scanTxtFiles(logDir);
        Set<String> channels = ingestionService.discoverChannels(allFiles);
        for (String ch : channels) {
            channelListening.put(ch, false);
            channelMessages.put(ch, Collections.synchronizedList(new ArrayList<>()));
        }

        // 启动监控（先不监听任何频道，等用户手动开启）
        monitor = new LogDirectoryMonitor(logDir, path -> {
            ingestionService.registerFile(path);
            ingestionService.handleFileChange(path,channelListening);
        });
        monitor.start();
    }

    public Set<String> getChannels() {
        return new TreeSet<>(channelListening.keySet());
    }

    public boolean isListening(String channel) {
        return channelListening.getOrDefault(channel, false);
    }

    public void setListening(String channel, boolean listening) {
        channelListening.put(channel, listening);

        // 如果开启监听，扫描一次该频道的历史消息
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

    public List<ChatMessage> getMessages(String channel) {
        if (channelListening.getOrDefault(channel,false)) {
            List<ChatMessage> list = channelMessages.get(channel);
            return list == null ? Collections.emptyList() : new ArrayList<>(list);
        }
        return Collections.emptyList();
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    private void onNewMessage(ChatMessage msg) {
        String ch = msg.getChannel();
        boolean isNewChannel = !channelListening.containsKey(ch);
        if (isNewChannel) {
            channelListening.put(ch, false);
            channelMessages.put(ch, Collections.synchronizedList(new ArrayList<>()));
            pushChannelListUpdate();
        }
        List<ChatMessage> list = channelMessages.get(ch);
        synchronized (list) {
            list.add(msg);
            if (list.size() > MAX_MESSAGES_PER_CHANNEL) {
                list.remove(0);
            }
        }
        // 推送最新订阅消息
        if (channelListening.getOrDefault(ch,false)) {
            pushToClients(msg);
        }
    }

    private void pushToClients(ChatMessage msg) {
        String json = String.format(
                "{\"id\":%d,\"channel\":\"%s\",\"player\":\"%s\",\"time\":\"%s\",\"original\":\"%s\",\"translated\":\"%s\"}",
                msg.getId(),
                escapeJson(msg.getChannel()),
                escapeJson(msg.getPlayer()),
                escapeJson(msg.getTimeStr()),
                escapeJson(msg.getOriginal()),
                escapeJson(msg.getTranslated())
        );
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    private List<Path> scanTxtFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        if (java.nio.file.Files.exists(dir)) {
            try (var stream = java.nio.file.Files.newDirectoryStream(dir, "*.txt")) {
                for (Path p : stream) files.add(p);
            } catch (Exception ignored) {}
        }
        return files;
    }

    private void pushChannelListUpdate() {
        String json = "{\"type\":\"channels\"}";
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    private void stopMonitoring() {
        if (monitor != null) {
            monitor.close();
            monitor = null;
        }
    }

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

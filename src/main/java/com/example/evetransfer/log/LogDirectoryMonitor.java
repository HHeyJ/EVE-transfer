package com.example.evetransfer.log;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 监控日志目录的变化。
 *
 * Java 提供了 WatchService（文件系统监听服务），
 * 但 macOS 的实现是"轮询式"的（默认 5~10 秒才触发一次），太慢了。
 *
 * 所以我们用"双保险"方案：
 * 1. WatchService：系统级监听，有变化立刻知道（Windows/Linux 效果好）
 * 2. 自己每 500ms 扫描一遍目录：兜底，macOS 也能近实时发现新文件
 *
 * 类比 web：相当于同时用了 WebSocket + setInterval 轮询做实时推送。
 */
public class LogDirectoryMonitor implements AutoCloseable {

    private final Path logDir;                    // 要监控的目录
    private final Consumer<Path> onModified;      // 回调函数：文件变化时执行什么
    private final ScheduledExecutorService poller; // 定时轮询的线程池
    private final ExecutorService watcherExecutor; // WatchService 的事件循环线程
    private WatchService watchService;
    // 记录每个文件上次的大小，用于轮询时判断是否新增了内容
    private final Map<Path, Long> trackedFileSizes = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public LogDirectoryMonitor(Path logDir, Consumer<Path> onModified) {
        this.logDir = logDir;
        this.onModified = onModified;
        // 单线程定时器，名字设为 "log-poller"，方便在 IDE 里调试时辨认
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-poller");
            t.setDaemon(true); // 设为守护线程，主程序退出时自动结束
            return t;
        });
        this.watcherExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "log-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动监控。分三步：
     * 1. 先扫一遍目录，处理已存在的文件（防止漏掉启动前的日志）
     * 2. 注册 WatchService
     * 3. 启动定时轮询
     */
    public void start() throws IOException {
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
        }

        // 第一步：初始扫描
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*.txt")) {
            for (Path path : stream) {
                trackFile(path);
                onModified.accept(path);
            }
        }

        // 第二步：注册系统级监听
        this.watchService = FileSystems.getDefault().newWatchService();
        logDir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
        watcherExecutor.submit(this::watchLoop);

        // 第三步：每 500 毫秒扫一次目录做兜底
        poller.scheduleAtFixedRate(this::pollDirectory, 0, 500, TimeUnit.MILLISECONDS);
    }

    // 记录文件当前大小
    private void trackFile(Path path) {
        try {
            trackedFileSizes.put(path, Files.size(path));
        } catch (IOException e) {
            trackedFileSizes.put(path, 0L);
        }
    }

    /**
     * 轮询逻辑：遍历目录里所有 .txt 文件，
     * 如果发现新文件，或者已知文件变大了，就触发回调。
     */
    private void pollDirectory() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*.txt")) {
            for (Path path : stream) {
                try {
                    long currentSize = Files.size(path);
                    Long lastSize = trackedFileSizes.get(path);
                    if (lastSize == null) {
                        // 新文件
                        System.out.println("[poll] 发现新文件: " + path);
                        trackFile(path);
                        onModified.accept(path);
                    } else if (currentSize > lastSize) {
                        // 文件变大了
                        System.out.println("[poll] 文件更新: " + path + " (" + lastSize + " -> " + currentSize + ")");
                        trackedFileSizes.put(path, currentSize);
                        onModified.accept(path);
                    }
                } catch (IOException e) {
                    System.err.println("[poll] 检查文件失败 " + path + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[poll] 目录扫描失败: " + e.getMessage());
        }
    }

    /**
     * WatchService 事件循环：阻塞等待系统通知，有事件就处理。
     * 类比 web：相当于一个 while(true) 的 WebSocket 消息接收循环。
     */
    @SuppressWarnings("unchecked")
    private void watchLoop() {
        while (running) {
            try {
                // 最多等 1 秒，超时返回 null，然后继续循环（这样 running = false 时能被及时感知）
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == OVERFLOW) continue; // 系统丢事件了，跳过

                    // 取出文件名（相对路径），拼接成完整路径
                    Path filename = ((WatchEvent<Path>) event).context();
                    Path fullPath = logDir.resolve(filename);

                    if (kind == ENTRY_CREATE) {
                        System.out.println("[watch] 新文件: " + fullPath);
                        trackFile(fullPath);
                        onModified.accept(fullPath);
                    } else if (kind == ENTRY_MODIFY) {
                        if (!trackedFileSizes.containsKey(fullPath)) {
                            System.out.println("[watch] 未跟踪的文件被修改: " + fullPath);
                            trackFile(fullPath);
                        }
                        onModified.accept(fullPath);
                    }
                }
                key.reset(); // 重置 key，让它能继续接收下一个事件
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 停止监控，释放资源。
     * 类比 web：组件卸载时 clearInterval + close WebSocket。
     */
    @Override
    public void close() {
        running = false;
        poller.shutdownNow();
        watcherExecutor.shutdownNow();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {}
        }
    }
}

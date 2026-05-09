package com.example.evetransfer.translation;

import com.example.evetransfer.AppConfig;
import com.example.evetransfer.model.ChatMessage;
import javafx.application.Platform;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 带队列的翻译服务包装器。
 *
 * 作用：把收到的翻译请求排队，用单线程逐个消费。
 * 为什么不用多线程并发？因为本地大模型显卡显存有限，
 * 同时翻译 20 条消息可能会 OOM（显存爆炸）。
 *
 * 类比 web：相当于一个前端请求队列 + 单 worker 线程池，
 * 保证同时只发一个请求给后端。
 */
public class QueuedTranslationService implements AutoCloseable {

    private final TranslationService translationService; // 真正的翻译实现
    private final ArrayBlockingQueue<ChatMessage> queue; // 有界阻塞队列
    private final ExecutorService executor;              // 单线程执行器
    private final Consumer<ChatMessage> onTranslated;   // 翻译完成后的回调
    private volatile boolean running = true;

    public QueuedTranslationService(TranslationService translationService, Consumer<ChatMessage> onTranslated) {
        this.translationService = translationService;
        this.onTranslated = onTranslated;
        this.queue = new ArrayBlockingQueue<>(AppConfig.MAX_TRANSLATION_QUEUE);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "translation-worker");
            t.setDaemon(true);
            return t;
        });
        this.executor.submit(this::processLoop);
    }

    /**
     * 把消息加入翻译队列。
     * 如果队列满了，删掉最老的一条，防止无限堆积。
     */
    public void offer(ChatMessage message) {
        if (!running) return;
        if (!queue.offer(message)) {
            queue.poll();      // 踢掉队首
            queue.offer(message);
        }
    }

    /**
     * 工作线程的主循环：从队列里取消息，调用翻译接口，完成后通知 UI 刷新。
     *
     * 关键点：translationService.translate() 返回 CompletableFuture，
     * 所以翻译在后台执行，不会阻塞这个 worker 线程。
     * 但 thenAccept 里的 setTranslated 和 onTranslated 回调必须用 Platform.runLater
     * 包起来，因为 JavaFX 的 UI 操作只能在主线程执行。
     */
    private void processLoop() {
        while (running) {
            try {
                ChatMessage message = queue.poll(1, TimeUnit.SECONDS);
                if (message == null) continue;

                translationService.translate(message.getOriginal(), AppConfig.TARGET_LANGUAGE)
                        .thenAccept(result -> Platform.runLater(() -> {
                            // 必须在 JavaFX 主线程修改数据，否则 UI 不会刷新
                            message.setTranslated(result);
                            if (onTranslated != null) {
                                onTranslated.accept(message);
                            }
                        }))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> {
                                message.setTranslated("[翻译失败]");
                                if (onTranslated != null) {
                                    onTranslated.accept(message);
                                }
                            });
                            return null;
                        })
                        .get(); // 阻塞等待这次翻译完成，保证单线程顺序执行
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 某个消息翻译异常不要打断整个循环，继续处理下一条
            }
        }
    }

    @Override
    public void close() {
        running = false;
        executor.shutdownNow();
    }
}

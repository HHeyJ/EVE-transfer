package com.example.evetransfer.translation;

import com.example.evetransfer.model.ChatMessage;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 带队列的翻译服务包装器。
 *
 * 用单线程串行消费翻译请求，避免同时打爆远端 API 或本地大模型显存。
 * 队列满时丢弃最老的一条。
 */
@Service
public class QueuedTranslationService {

    private final TranslationService translationService;
    private final ArrayBlockingQueue<Task> queue;
    private final ExecutorService executor;
    private volatile boolean running = true;
    private final String targetLanguage = "zh";

    public QueuedTranslationService(TranslationService translationService) {
        this.translationService = translationService;
        this.queue = new ArrayBlockingQueue<>(200);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "translation-worker");
            t.setDaemon(true);
            return t;
        });
        this.executor.submit(this::processLoop);
    }

    public void offer(ChatMessage message, Consumer<ChatMessage> onTranslated) {
        if (!running) return;
        Task task = new Task(message, onTranslated);
        if (!queue.offer(task)) {
            queue.poll();
            queue.offer(task);
        }
    }

    private void processLoop() {
        while (running) {
            try {
                Task task = queue.poll(1, TimeUnit.SECONDS);
                if (task == null) continue;

                translationService.translate(task.message.getOriginal(), targetLanguage)
                        .thenAccept(result -> {
                            task.message.setTranslated(result);
                            if (task.onTranslated != null) {
                                task.onTranslated.accept(task.message);
                            }
                        })
                        .exceptionally(ex -> {
                            task.message.setTranslated("[翻译失败]");
                            if (task.onTranslated != null) {
                                task.onTranslated.accept(task.message);
                            }
                            return null;
                        })
                        .get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
    }

    private record Task(ChatMessage message, Consumer<ChatMessage> onTranslated) {}
}

package com.example.evetransfer.translation;

import com.example.evetransfer.model.ChatMessage;
import com.example.evetransfer.service.ChannelStore;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 带队列的翻译服务包装器。
 *
 * 用单线程串行消费翻译请求，避免同时打爆远端 API 或本地大模型显存。
 * 队列满时丢弃最老的一条。
 *
 * 翻译前会从 ChannelStore 拉取该频道最近若干条已完成翻译的消息，
 * 组装成 user/assistant 多轮对话，保证上下文语意通畅。
 */
@Service
public class QueuedTranslationService {

    /**
     * 作为多轮对话上下文注入的历史消息条数。
     */
    private static final int CONTEXT_TURNS = 10;

    private final TranslationService translationService;
    private final ChannelStore channelStore;
    private final ArrayBlockingQueue<Task> queue;
    private final ExecutorService executor;
    private volatile boolean running = true;

    public QueuedTranslationService(TranslationService translationService, ChannelStore channelStore) {
        this.translationService = translationService;
        this.channelStore = channelStore;
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

                List<ChatMessage> history = buildHistory(task.message);
                translationService.translate(task.message, history)
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

    /**
     * 从当前消息所在频道回捞最近若干条已翻译消息，作为 user(原文)/assistant(译文) 的多轮对话上下文。
     * 会跳过当前正在翻译的这一条本身，避免出现"自己回答自己"。
     */
    private List<ChatMessage> buildHistory(ChatMessage current) {
        List<ChatMessage> recent = channelStore.getRecentTranslated(current.getChannel(), CONTEXT_TURNS + 1);
        List<ChatMessage> turns = new ArrayList<>(recent.size());
        for (ChatMessage m : recent) {
            if (m.getId() == current.getId()) continue;
            turns.add(m);
        }
        while (turns.size() > CONTEXT_TURNS) {
            turns.removeFirst();
        }
        return turns;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
    }

    private record Task(ChatMessage message, Consumer<ChatMessage> onTranslated) {}
}

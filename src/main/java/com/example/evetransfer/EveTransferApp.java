package com.example.evetransfer;

import com.example.evetransfer.log.LogDirectoryMonitor;
import com.example.evetransfer.log.LogIngestionService;
import com.example.evetransfer.translation.QueuedTranslationService;
import com.example.evetransfer.translation.DeepSeekTranslationService;
import com.example.evetransfer.translation.TranslationService;
import com.example.evetransfer.ui.ChannelSelectionDialog;
import com.example.evetransfer.ui.OverlayController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 程序入口。JavaFX 的 Application 类比做网页里的 "根组件"，
 * start() 是页面加载完成后执行的方法，类似 Vue 的 mounted / React 的 useEffect。
 */
public class EveTransferApp extends Application {

    private LogDirectoryMonitor monitor;
    private QueuedTranslationService translationService;
    private LogIngestionService ingestionService;
    private Path logDir;
    private int initialLimitPerChannel = 5; // 运行时首次读取新文件的限流条数（0 表示不限流）

    @Override
    public void start(Stage primaryStage) {
        // 禁止 JavaFX 自动退出（否则 primaryStage.hide() 会导致整个应用退出）
        Platform.setImplicitExit(false);

        // 1. 创建悬浮窗
        OverlayController overlay = new OverlayController();
        overlay.setOnChannelSelectRequested(() -> openChannelSelector(overlay));

        // 2. 翻译服务
        TranslationService translator = new DeepSeekTranslationService();
        translationService = new QueuedTranslationService(translator, overlay::refreshMessage);

        // 3. 日志解析服务
        ingestionService = new LogIngestionService(msg -> {
            overlay.addMessage(msg);
            translationService.offer(msg);
        });
        ingestionService.setInitialLimitPerChannel(initialLimitPerChannel);

        // 4. 日志目录
        String logDirArg = getParameters().getRaw().stream().findFirst().orElse("./logs");
        logDir = Paths.get(logDirArg).toAbsolutePath().normalize();
        System.out.println("监控日志目录: " + logDir);

        // 5. 扫描目录，收集所有文件
        List<Path> allFiles = new ArrayList<>();
        if (Files.exists(logDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*.txt")) {
                for (Path path : stream) {
                    allFiles.add(path);
                }
            } catch (IOException e) {
                System.err.println("目录扫描失败: " + e.getMessage());
            }
        }

        // 6. 快速发现所有频道名（只读文件头，不消费消息）
        Set<String> allChannels = ingestionService.discoverChannels(allFiles);
        System.out.println("发现频道: " + allChannels);

        // 7. 弹出频道选择对话框
        Set<String> selectedChannels;
        if (allChannels.isEmpty()) {
            selectedChannels = new HashSet<>();
        } else {
            Set<String> result = ChannelSelectionDialog.showAndWait(allChannels, allChannels);
            if (result == null) {
                // 用户取消了，直接退出程序
                Platform.exit();
                return;
            }
            selectedChannels = result;
            System.out.println("用户选择监听: " + selectedChannels);
        }
        ingestionService.setMonitoredChannels(selectedChannels);

        // 8. 初始扫描：只加载选中频道的历史消息
        List<Path> selectedFiles = allFiles.stream()
                .filter(p -> {
                    var state = ingestionService.getFileState(p);
                    return state != null && state.getChannelName() != null
                            && selectedChannels.contains(state.getChannelName());
                })
                .collect(Collectors.toList());
        ingestionService.handleInitialScan(selectedFiles, initialLimitPerChannel);

        // 9. 启动实时监控（所有文件都进入监控，但摄取服务内部会过滤）
        monitor = new LogDirectoryMonitor(logDir, path -> {
            ingestionService.registerFile(path);
            ingestionService.handleFileChange(path);
        });

        try {
            monitor.start();
        } catch (Exception e) {
            System.err.println("启动监控失败: " + e.getMessage());
            e.printStackTrace();
        }

        // 10. 显示悬浮窗
        overlay.show();

        // primaryStage 是 JavaFX 自动创建的主舞台，我们不需要显示它。
        // 悬浮窗 overlay 是唯一可见窗口。关闭按钮会调用 Platform.exit() 真正退出。
    }

    /**
     * 运行时重新打开频道选择对话框。
     */
    private void openChannelSelector(OverlayController overlay) {
        List<Path> allFiles = new ArrayList<>();
        if (Files.exists(logDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*.txt")) {
                for (Path path : stream) {
                    allFiles.add(path);
                }
            } catch (IOException e) {
                System.err.println("目录扫描失败: " + e.getMessage());
            }
        }

        Set<String> allChannels = ingestionService.discoverChannels(allFiles);
        Set<String> currentlySelected = ingestionService.getMonitoredChannels();
        Set<String> newSelection = ChannelSelectionDialog.showAndWait(allChannels, currentlySelected);

        // null 表示用户取消了，不做任何操作；空集合表示用户确定但清空了所有选择
        if (newSelection != null) {
            ingestionService.setMonitoredChannels(newSelection);
            System.out.println("更新监听频道: " + newSelection);
        }
    }

    @Override
    public void stop() {
        if (monitor != null) {
            monitor.close();
        }
        if (translationService != null) {
            translationService.close();
        }
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

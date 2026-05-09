package com.example.evetransfer;

import com.example.evetransfer.log.LogDirectoryMonitor;
import com.example.evetransfer.log.LogIngestionService;
import com.example.evetransfer.model.ChatMessage;
import com.example.evetransfer.translation.QueuedTranslationService;
import com.example.evetransfer.translation.StubTranslationService;
import com.example.evetransfer.translation.TranslationService;
import com.example.evetransfer.ui.OverlayController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.*;

/**
 * 程序入口。JavaFX 的 Application 类比做网页里的 "根组件"，
 * start() 是页面加载完成后执行的方法，类似 Vue 的 mounted / React 的 useEffect。
 */
public class EveTransferApp extends Application {

    // 这三个对象需要在窗口关闭时手动释放资源，所以声明为成员变量
    private LogDirectoryMonitor monitor;           // 监控日志目录变化的"观察者"
    private QueuedTranslationService translationService; // 翻译队列
    private LogIngestionService ingestionService;  // 日志读取与解析

    /**
     * JavaFX 启动后自动调用的方法，相当于网页的初始化逻辑。
     * primaryStage 是 JavaFX 自动创建的第一个窗口，我们这里不用它，
     * 而是自己创建了一个悬浮窗口（OverlayController）。
     */
    @Override
    public void start(Stage primaryStage) {
        // 1. 创建悬浮窗（置顶、透明、可拖动）
        OverlayController overlay = new OverlayController();

        // 2. 创建翻译服务（目前是占位实现，之后换成你的本地大模型接口）
        TranslationService translator = new StubTranslationService();

        // 3. 用队列包装翻译服务，防止一次性发送太多请求压垮接口
        //    onTranslated 回调：翻译完成后刷新 UI 对应的那一行
        translationService = new QueuedTranslationService(translator, overlay::refreshMessage);

        // 4. 日志解析服务：读到新消息后，
        //    先加到 UI 列表，再送进翻译队列
        ingestionService = new LogIngestionService(msg -> {
            overlay.addMessage(msg);        // 立刻显示原文
            translationService.offer(msg);  // 排队等待翻译
        });

        // 5. 从命令行参数读取日志目录，默认用项目里的 logs/ 文件夹
        String logDirArg = getParameters().getRaw().stream().findFirst().orElse("./logs");
        Path logDir = Paths.get(logDirArg).toAbsolutePath().normalize();
        System.out.println("监控日志目录: " + logDir);

        // 6. 启动前先手动扫描目录，每个文件只加载最近 20 条消息
        //    防止启动时把所有历史日志全部塞进 UI。
        if (Files.exists(logDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*.txt")) {
                for (Path path : stream) {
                    ingestionService.registerFile(path);
                    ingestionService.handleInitialFile(path, 20);
                }
            } catch (IOException e) {
                System.err.println("初始扫描失败: " + e.getMessage());
            }
        }

        // 7. 启动实时监控（WatchService + 轮询），后续新内容全部读取
        monitor = new LogDirectoryMonitor(logDir, path -> {
            ingestionService.registerFile(path);     // 登记文件
            ingestionService.handleFileChange(path); // 读取新增内容
        });

        try {
            monitor.start();
        } catch (Exception e) {
            System.err.println("启动监控失败: " + e.getMessage());
            e.printStackTrace();
        }

        // 7. 显示悬浮窗
        overlay.show();

        // 8. 把 JavaFX 自带的 primaryStage "藏"掉，只显示我们的悬浮窗
        primaryStage.setOpacity(0);
        primaryStage.setWidth(1);
        primaryStage.setHeight(1);
        primaryStage.show();
        primaryStage.hide();
    }

    /**
     * 窗口关闭时调用，类似网页的 beforeunload。
     * 用来关闭文件监控和翻译线程，防止内存泄漏。
     */
    @Override
    public void stop() {
        if (monitor != null) {
            monitor.close();
        }
        if (translationService != null) {
            translationService.close();
        }
        Platform.exit(); // 彻底结束 JavaFX 程序
    }

    public static void main(String[] args) {
        launch(args); // JavaFX 程序的标准入口
    }
}

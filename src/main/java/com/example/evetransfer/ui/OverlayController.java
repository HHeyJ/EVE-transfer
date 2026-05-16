package com.example.evetransfer.ui;

import com.example.evetransfer.AppConfig;
import com.example.evetransfer.model.ChatMessage;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 悬浮窗口控制器。相当于前端 Vue/React 组件里的 setup() 或组件逻辑。
 *
 * 负责搭建整个 UI 结构：
 * - 顶部标题栏（可拖动、透明度滑块、原文开关、关闭按钮）
 * - 中间消息列表（ListView，类似网页的 ul/li 列表）
 * - 底部频道过滤条 + 右下角缩放手柄
 */
public class OverlayController {

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final ListView<ChatMessage> messageList = new ListView<>();

    // ObservableList：JavaFX 的可观察列表，添加/删除元素时 UI 自动刷新。
    // 类比 web：相当于 Vue 的 ref([]) 或 React 的 useState([])。
    private final ObservableList<ChatMessage> messages = FXCollections.observableArrayList();

    // FilteredList：在 ObservableList 基础上加过滤条件，不改变原列表。
    // 类比 web：相当于 computed(() => messages.filter(m => activeChannels.includes(m.channel)))
    private final FilteredList<ChatMessage> filteredMessages = new FilteredList<>(messages);

    private final FlowPane channelFilterBar = new FlowPane(6, 4);
    private final Set<String> activeChannels = ConcurrentHashMap.newKeySet();
    private final Set<String> knownChannels = ConcurrentHashMap.newKeySet();

    // 是否显示原文。BooleanProperty 是 JavaFX 的可观察布尔值，
    // 改这个值会自动触发所有监听它的地方更新 UI。
    private final BooleanProperty showOriginal = new SimpleBooleanProperty(true);

    // 拖动窗口时记录鼠标偏移量
    private double dragOffsetX;
    private double dragOffsetY;

    // 缩放窗口时记录的初始状态
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartWidth;
    private double resizeStartHeight;

    private Runnable onChannelSelectRequested;
    // 翻译回调：(text, targetLang) -> CompletableFuture<String>
    private BiFunction<String, String, CompletableFuture<String>> translateCallback;

    // 翻译面板相关组件
    private VBox translatePanel;
    private TextArea translateInput;
    private ComboBox<String> langSelector;
    private Label translateResult;
    private Button translateBtn;

    public OverlayController() {
        this.stage = OverlayStageFactory.create(root, 420, 520);
        setupUI();
        applyRoundedClip();
    }

    /**
     * 给整个窗口裁剪成圆角。JavaFX 默认矩形没有圆角，
     * 所以用一个带圆角的 Rectangle 作为 "遮罩"（clip）盖在 root 上。
     * 类比 CSS：clip-path: inset(0 round 16px);
     */
    private void applyRoundedClip() {
        root.layoutBoundsProperty().addListener((obs, old, bounds) -> {
            var rect = new javafx.scene.shape.Rectangle(bounds.getWidth(), bounds.getHeight());
            rect.setArcWidth(16);
            rect.setArcHeight(16);
            root.setClip(rect);
        });
    }

    /**
     * 搭建 UI。BorderPane 是 JavaFX 的布局容器，把界面分成上/下/左/右/中五个区域。
     * 类比 web：相当于一个 flex 容器，分 header / main / footer。
     */
    private void setupUI() {
        root.getStyleClass().add("overlay-root");
        root.setStyle("-fx-background-color: rgba(22,22,30,0.92); -fx-background-radius: 16;");

        // 顶部区域：标题栏 + 可折叠翻译面板
        VBox topBox = new VBox(0);
        HBox header = createHeader();
        translatePanel = createTranslatePanel();
        translatePanel.setVisible(false);
        translatePanel.setManaged(false);
        topBox.getChildren().addAll(header, translatePanel);
        root.setTop(topBox);

        // 中间消息列表
        messageList.setItems(filteredMessages);
        // cellFactory：自定义每一行怎么渲染。类比 web：相当于 <ul> 里每个 <li> 的 renderItem 函数。
        messageList.setCellFactory(lv -> new MessageListCell(showOriginal));
        messageList.setFocusTraversable(false);
        messageList.getStyleClass().add("message-list");
        messageList.setStyle("-fx-background-color: transparent; -fx-padding: 0 0 4 0;");
        // fixedCellSize = -1 表示每行高度根据内容自适应（wrapText 才能正确换行撑开高度）
        messageList.setFixedCellSize(-1);
        // 当 ListView 宽度变化时，文本换行会导致每行高度变化，必须刷新列表重新计算
        messageList.widthProperty().addListener((obs, oldW, newW) -> messageList.refresh());
        VBox.setVgrow(messageList, Priority.ALWAYS);

        StackPane centerPane = new StackPane(messageList);
        centerPane.setStyle("-fx-background-color: transparent;");
        root.setCenter(centerPane);

        // 底部区域（频道过滤 + 缩放手柄）
        VBox bottomBox = new VBox(0);
        bottomBox.setPadding(new Insets(4, 10, 6, 10));
        bottomBox.setStyle("-fx-background-color: rgba(18,18,26,0.95); -fx-background-radius: 0 0 16 16;");

        HBox bottomRow = new HBox(8);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        // FlowPane 会占满剩余宽度，当按钮放不下时自动换到下一行
        channelFilterBar.setPrefWrapLength(0);
        HBox.setHgrow(channelFilterBar, Priority.ALWAYS);

        StackPane resizeHandle = createResizeHandle();
        bottomRow.getChildren().addAll(channelFilterBar, resizeHandle);
        bottomBox.getChildren().add(bottomRow);
        root.setBottom(bottomBox);
    }

    /**
     * 创建顶部标题栏。
     * 包含：蓝色小圆点、标题、原文开关、透明度滑块、关闭按钮。
     */
    private HBox createHeader() {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 10, 14));
        header.setStyle("-fx-background-color: rgba(28,28,38,0.95); -fx-background-radius: 16 16 0 0;");
        header.getStyleClass().add("overlay-header");

        Circle dot = new Circle(4, Color.web("#00d2ff"));
        Label title = new Label("EVE Translator");
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #f1f2f6;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 频道选择按钮
        Button channelSelectBtn = new Button("⚙");
        channelSelectBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; -fx-font-size: 13px; " +
                "-fx-padding: 0 4 0 4; -fx-cursor: hand;"
        );
        channelSelectBtn.setOnMouseEntered(e -> channelSelectBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #00d2ff; -fx-font-size: 13px; " +
                "-fx-padding: 0 4 0 4; -fx-cursor: hand;"
        ));
        channelSelectBtn.setOnMouseExited(e -> channelSelectBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; -fx-font-size: 13px; " +
                "-fx-padding: 0 4 0 4; -fx-cursor: hand;"
        ));
        channelSelectBtn.setOnAction(e -> {
            if (onChannelSelectRequested != null) {
                onChannelSelectRequested.run();
            }
        });

        // 手动翻译按钮（展开/折叠翻译面板）
        Button translateToggleBtn = new Button("译");
        translateToggleBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; -fx-font-size: 12px; " +
                "-fx-padding: 0 6 0 6; -fx-cursor: hand;"
        );
        translateToggleBtn.setOnMouseEntered(e -> translateToggleBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #00d2ff; -fx-font-size: 12px; " +
                "-fx-padding: 0 6 0 6; -fx-cursor: hand;"
        ));
        translateToggleBtn.setOnMouseExited(e -> translateToggleBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; -fx-font-size: 12px; " +
                "-fx-padding: 0 6 0 6; -fx-cursor: hand;"
        ));
        translateToggleBtn.setOnAction(e -> {
            boolean visible = !translatePanel.isVisible();
            translatePanel.setVisible(visible);
            translatePanel.setManaged(visible);
            if (visible) {
                translateInput.clear();
                translateResult.setText("");
                translateResult.setVisible(false);
            }
        });

        // 原文显示开关。ToggleButton = 可切换状态的按钮，类似 checkbox。
        ToggleButton originalToggle = new ToggleButton("原文");
        originalToggle.setSelected(true);
        originalToggle.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; -fx-font-size: 11px; " +
                "-fx-padding: 2 8 2 8; -fx-background-radius: 6; -fx-cursor: hand;" +
                "-fx-border-color: rgba(160,160,176,0.3); -fx-border-radius: 6;"
        );
        originalToggle.selectedProperty().addListener((obs, old, val) -> {
            showOriginal.set(val); // 改属性，MessageListCell 里会监听这个变化
            if (val) {
                originalToggle.setStyle(
                        "-fx-background-color: rgba(0,210,255,0.15); -fx-text-fill: #00d2ff; -fx-font-size: 11px; " +
                        "-fx-padding: 2 8 2 8; -fx-background-radius: 6; -fx-cursor: hand;" +
                        "-fx-border-color: rgba(0,210,255,0.4); -fx-border-radius: 6;"
                );
            } else {
                originalToggle.setStyle(
                        "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; -fx-font-size: 11px; " +
                        "-fx-padding: 2 8 2 8; -fx-background-radius: 6; -fx-cursor: hand;" +
                        "-fx-border-color: rgba(160,160,176,0.3); -fx-border-radius: 6;"
                );
            }
        });

        // 透明度滑块。Slider 值变化时实时改窗口 opacity。
        Slider opacitySlider = new Slider(AppConfig.MIN_OPACITY, AppConfig.MAX_OPACITY, AppConfig.DEFAULT_OPACITY);
        opacitySlider.setPrefWidth(70);
        opacitySlider.setStyle("-fx-control-inner-background: rgba(255,255,255,0.1);");
        opacitySlider.valueProperty().addListener((obs, oldVal, newVal) ->
                stage.setOpacity(newVal.doubleValue()));

        // 关闭按钮：彻底退出程序
        Button closeBtn = new Button("×");
        closeBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; " +
                "-fx-font-size: 16px; -fx-padding: 0 4 0 4; -fx-cursor: hand;"
        );
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #ff6b6b; " +
                "-fx-font-size: 16px; -fx-padding: 0 4 0 4; -fx-cursor: hand;"
        ));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; " +
                "-fx-font-size: 16px; -fx-padding: 0 4 0 4; -fx-cursor: hand;"
        ));
        closeBtn.setOnAction(e -> Platform.exit());

        header.getChildren().addAll(dot, title, channelSelectBtn, translateToggleBtn, spacer, originalToggle, opacitySlider, closeBtn);

        // 拖动支持：在标题栏按下鼠标时记录位置，拖动时更新窗口坐标
        header.setOnMousePressed(this::onMousePressed);
        header.setOnMouseDragged(this::onMouseDragged);

        return header;
    }

    /**
     * 创建手动翻译面板。
     * 包含：输入框、目标语言选择、翻译按钮、结果显示、复制按钮。
     * 默认折叠，点击标题栏"译"按钮展开/收起。
     */
    private VBox createTranslatePanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10, 14, 10, 14));
        panel.setStyle("-fx-background-color: rgba(32,32,44,0.95);");

        // 输入框
        translateInput = new TextArea();
        translateInput.setPromptText("输入要翻译的中文...");
        translateInput.setPrefRowCount(2);
        translateInput.setWrapText(true);
        translateInput.setStyle(
                "-fx-control-inner-background: rgba(40,40,52,0.9); -fx-text-fill: #e0e0e0; " +
                "-fx-font-size: 12px; -fx-background-radius: 8; -fx-padding: 6;"
        );

        // 语言选择 + 翻译按钮 一行
        HBox ctrlRow = new HBox(8);
        ctrlRow.setAlignment(Pos.CENTER_LEFT);

        langSelector = new ComboBox<>();
        langSelector.getItems().addAll("English", "Japanese", "Korean", "French", "German", "Russian");
        langSelector.setValue("English");
        langSelector.setStyle(
                "-fx-background-color: rgba(40,40,52,0.9); -fx-text-fill: #e0e0e0; " +
                "-fx-font-size: 11px; -fx-background-radius: 6;"
        );

        translateBtn = new Button("翻译");
        translateBtn.setStyle(
                "-fx-background-color: #00d2ff; -fx-text-fill: #1e1e2e; -fx-font-weight: bold; " +
                "-fx-font-size: 12px; -fx-padding: 4 14 4 14; -fx-background-radius: 8; -fx-cursor: hand;"
        );
        translateBtn.setOnAction(e -> doTranslate());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ctrlRow.getChildren().addAll(langSelector, spacer, translateBtn);

        // 结果显示
        translateResult = new Label();
        translateResult.setWrapText(true);
        translateResult.setStyle(
                "-fx-text-fill: #00d2ff; -fx-font-size: 13px; -fx-padding: 4 2 4 2;"
        );
        translateResult.setVisible(false);

        // 复制按钮
        Button copyBtn = new Button("复制结果");
        copyBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; " +
                "-fx-font-size: 11px; -fx-padding: 3 10 3 10; -fx-cursor: hand; " +
                "-fx-border-color: rgba(160,160,176,0.3); -fx-border-radius: 6;"
        );
        copyBtn.setOnMouseEntered(e -> copyBtn.setStyle(
                "-fx-background-color: rgba(0,210,255,0.1); -fx-text-fill: #00d2ff; " +
                "-fx-font-size: 11px; -fx-padding: 3 10 3 10; -fx-cursor: hand; " +
                "-fx-border-color: rgba(0,210,255,0.4); -fx-border-radius: 6;"
        ));
        copyBtn.setOnMouseExited(e -> copyBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; " +
                "-fx-font-size: 11px; -fx-padding: 3 10 3 10; -fx-cursor: hand; " +
                "-fx-border-color: rgba(160,160,176,0.3); -fx-border-radius: 6;"
        ));
        copyBtn.setOnAction(e -> {
            String text = translateResult.getText();
            if (text != null && !text.isBlank()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
                copyBtn.setText("已复制");
                // 2 秒后恢复文字
                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                    Platform.runLater(() -> copyBtn.setText("复制结果"));
                }).start();
            }
        });

        panel.getChildren().addAll(translateInput, ctrlRow, translateResult, copyBtn);
        return panel;
    }

    private void doTranslate() {
        String text = translateInput.getText();
        if (text == null || text.isBlank()) {
            translateResult.setText("请输入内容");
            translateResult.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
            translateResult.setVisible(true);
            return;
        }
        if (translateCallback == null) {
            translateResult.setText("翻译服务未就绪");
            translateResult.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
            translateResult.setVisible(true);
            return;
        }
        String lang = langSelector.getValue();
        translateBtn.setText("翻译中...");
        translateBtn.setDisable(true);
        translateResult.setText("");
        translateResult.setVisible(true);

        translateCallback.apply(text, lang).thenAccept(result -> {
            Platform.runLater(() -> {
                translateResult.setText(result);
                translateResult.setStyle("-fx-text-fill: #00d2ff; -fx-font-size: 13px;");
                translateBtn.setText("翻译");
                translateBtn.setDisable(false);
            });
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                translateResult.setText("翻译失败: " + ex.getMessage());
                translateResult.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
                translateBtn.setText("翻译");
                translateBtn.setDisable(false);
            });
            return null;
        });
    }

    public void setTranslateCallback(BiFunction<String, String, CompletableFuture<String>> callback) {
        this.translateCallback = callback;
    }

    /**
     * 创建右下角缩放手柄。鼠标按住拖拽可以改变窗口大小。
     */
    private StackPane createResizeHandle() {
        // SVGPath 画一个小三角形
        SVGPath path = new SVGPath();
        path.setContent("M 12 4 L 4 12 L 12 12 Z");
        path.setFill(Color.web("#606070"));
        path.setRotate(180);

        StackPane handle = new StackPane(path);
        handle.setPrefSize(18, 18);
        handle.setCursor(Cursor.SE_RESIZE); // 鼠标变成右下角拖拽形状
        handle.setStyle("-fx-background-color: transparent;");

        handle.setOnMousePressed(e -> {
            resizeStartX = e.getScreenX();
            resizeStartY = e.getScreenY();
            resizeStartWidth = stage.getWidth();
            resizeStartHeight = stage.getHeight();
        });
        handle.setOnMouseDragged(e -> {
            double deltaX = e.getScreenX() - resizeStartX;
            double deltaY = e.getScreenY() - resizeStartY;
            stage.setWidth(Math.max(300, resizeStartWidth + deltaX));   // 最小宽 300
            stage.setHeight(Math.max(200, resizeStartHeight + deltaY)); // 最小高 200
        });

        return handle;
    }

    private void onMousePressed(MouseEvent e) {
        dragOffsetX = e.getScreenX() - stage.getX();
        dragOffsetY = e.getScreenY() - stage.getY();
    }

    private void onMouseDragged(MouseEvent e) {
        stage.setX(e.getScreenX() - dragOffsetX);
        stage.setY(e.getScreenY() - dragOffsetY);
    }

    /**
     * 添加一条新消息到列表，按时间戳有序插入。
     * 这样即使不同频道的文件到达顺序不同，整体列表也是按时间排列的。
     *
     * 类比 web：相当于 Vue 的 nextTick(() => messages.splice(insertIndex, 0, msg))
     */
    public void addMessage(ChatMessage message) {
        Platform.runLater(() -> {
            // 1. 按时间戳找到正确的插入位置，保证整体有序
            int insertIndex = findInsertIndex(message);
            messages.add(insertIndex, message);

            // 2. 每个频道最多保留 50 条（防止内存无限增长）
            String ch = message.getChannel();
            long channelCount = messages.stream()
                    .filter(m -> m.getChannel().equals(ch))
                    .count();
            if (channelCount > 50) {
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i).getChannel().equals(ch)) {
                        messages.remove(i); // 删掉该频道最早的一条
                        break;
                    }
                }
            }

            // 3. 全局最多保留 500 条
            while (messages.size() > 500) {
                messages.remove(0);
            }

            updateChannelFilter(ch);
            // 自动滚动到新插入的位置
            messageList.scrollTo(insertIndex);
        });
    }

    /**
     * 二分查找找到按时间戳排序的插入位置。
     * 时间戳相同则按 id 排序（id 是自增的，保证绝对顺序）。
     */
    private int findInsertIndex(ChatMessage message) {
        int left = 0;
        int right = messages.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            ChatMessage midMsg = messages.get(mid);
            int cmp = midMsg.getTimestamp().compareTo(message.getTimestamp());
            if (cmp == 0) {
                // 时间戳相同，按 id 排序
                cmp = Long.compare(midMsg.getId(), message.getId());
            }
            if (cmp <= 0) {
                left = mid + 1;
            } else {
                right = mid;
            }
        }
        return left;
    }

    /**
     * 翻译完成后刷新列表。
     * messageList.refresh() 会强制所有 ListCell 重新渲染，确保翻译文本显示出来。
     */
    public void refreshMessage(ChatMessage message) {
        Platform.runLater(() -> messageList.refresh());
    }

    /**
     * 当发现新频道时，在底部添加一个彩色胶囊按钮，用来过滤显示/隐藏该频道。
     */
    private void updateChannelFilter(String channel) {
        if (knownChannels.add(channel)) {
            activeChannels.add(channel);

            ToggleButton btn = new ToggleButton(channel);
            btn.setSelected(true);
            btn.setStyle(channelToggleStyle(channel, true));
            btn.selectedProperty().addListener((obs, oldVal, newVal) -> {
                btn.setStyle(channelToggleStyle(channel, newVal));
                if (newVal) {
                    activeChannels.add(channel);
                } else {
                    activeChannels.remove(channel);
                }
                updateFilter();
            });
            channelFilterBar.getChildren().add(btn);
        }
    }

    // 生成胶囊按钮的 CSS 样式
    private String channelToggleStyle(String channel, boolean active) {
        String color = channelColor(channel);
        if (active) {
            return String.format(
                    "-fx-background-color: %s22; -fx-text-fill: %s; -fx-font-size: 10px; " +
                    "-fx-padding: 3 10 3 10; -fx-background-radius: 12; -fx-cursor: hand; " +
                    "-fx-border-color: %s44; -fx-border-radius: 12;",
                    color, color, color
            );
        } else {
            return String.format(
                    "-fx-background-color: transparent; -fx-text-fill: #606070; -fx-font-size: 10px; " +
                    "-fx-padding: 3 10 3 10; -fx-background-radius: 12; -fx-cursor: hand; " +
                    "-fx-border-color: rgba(96,96,112,0.25); -fx-border-radius: 12;"
            );
        }
    }

    // 每个频道固定一种颜色，方便一眼区分
    private String channelColor(String channel) {
        return switch (channel.toLowerCase()) {
            case "local" -> "#ff9f43";   // 橙
            case "corp" -> "#54a0ff";    // 蓝
            case "alliance" -> "#1dd1a1"; // 绿
            case "fleet" -> "#5f27cd";   // 紫
            default -> "#00d2ff";        // 青
        };
    }

    // 根据 activeChannels 更新过滤条件
    private void updateFilter() {
        filteredMessages.setPredicate(msg -> activeChannels.contains(msg.getChannel()));
    }

    public Stage getStage() {
        return stage;
    }

    public void setOnChannelSelectRequested(Runnable callback) {
        this.onChannelSelectRequested = callback;
    }

    public void show() {
        stage.show();
    }
}

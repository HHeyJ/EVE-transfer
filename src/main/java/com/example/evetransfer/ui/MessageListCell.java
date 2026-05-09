package com.example.evetransfer.ui;

import com.example.evetransfer.model.ChatMessage;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 自定义消息列表的每一行（ListCell）。
 *
 * JavaFX 的 ListView 会自动回收和复用 ListCell（类似虚拟列表），
 * 所以同一行 cell 可能会显示不同的消息。
 * 因此每次 updateItem 都要把旧监听器清掉、再绑新的，防止监听错乱。
 *
 * 类比 web：相当于给 <ul> 写了一个自定义的 renderItem 组件，
 * 并且用 watch 监听数据变化来更新 DOM。
 */
public class MessageListCell extends ListCell<ChatMessage> {

    // 布局容器，类似一个 div
    private final VBox container = new VBox(3);
    private final HBox header = new HBox(8);
    private final Label channelBadge = new Label();
    private final Label playerLabel = new Label();
    private final Label timeLabel = new Label();
    private final Label translatedLabel = new Label();
    private final Label originalLabel = new Label();

    // 是否显示原文，从外部传入
    private final BooleanProperty showOriginal;

    // 当前这条 cell 正在显示的消息对象
    private ChatMessage currentItem;

    // 翻译结果变化时的监听器
    private final ChangeListener<String> translatedListener = (obs, old, val) -> updateTranslated(val);

    // 原文开关变化时的监听器
    private final ChangeListener<Boolean> showOriginalListener = (obs, old, val) -> {
        originalLabel.setVisible(val);
        originalLabel.setManaged(val);
    };

    public MessageListCell(BooleanProperty showOriginal) {
        this.showOriginal = showOriginal;

        // 整个消息卡片：圆角半透明背景
        container.setPadding(new Insets(6, 12, 6, 12));
        container.setStyle("-fx-background-color: rgba(35,35,48,0.6); -fx-background-radius: 10;");

        // 频道标签（胶囊样式）
        channelBadge.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: white; " +
                "-fx-padding: 1 8 1 8; -fx-background-radius: 10;");

        playerLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #c8c8d8;");
        timeLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #505060;");

        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(channelBadge, playerLabel, timeLabel);

        // 翻译结果：白色大字体
        translatedLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #f1f2f6; -fx-wrap-text: true;");
        translatedLabel.setWrapText(true);

        // 原文：灰色小字体
        originalLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #7a7a8a; -fx-wrap-text: true;");
        originalLabel.setWrapText(true);

        container.getChildren().addAll(header, translatedLabel, originalLabel);
        VBox.setVgrow(translatedLabel, Priority.ALWAYS);
    }

    /**
     * 每次 cell 被复用或数据变化时调用。
     * 参数 msg：要显示的新消息；empty：true 表示这一行是空的（列表末尾的空白行）。
     */
    @Override
    protected void updateItem(ChatMessage msg, boolean empty) {
        // 第一步：先把旧监听器拆掉，防止监听上一个消息
        if (currentItem != null) {
            currentItem.translatedProperty().removeListener(translatedListener);
        }
        showOriginal.removeListener(showOriginalListener);

        super.updateItem(msg, empty);

        if (empty || msg == null) {
            setGraphic(null); // 不显示任何内容
            setStyle("-fx-background-color: transparent; -fx-padding: 2 0 2 0;");
            currentItem = null;
            return;
        }

        // 第二步：绑定新消息
        currentItem = msg;
        currentItem.translatedProperty().addListener(translatedListener);
        showOriginal.addListener(showOriginalListener);

        // 渲染频道标签
        String color = channelColor(msg.getChannel());
        channelBadge.setText(msg.getChannel().toUpperCase());
        channelBadge.setStyle(String.format(
                "-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: white; " +
                "-fx-padding: 1 8 1 8; -fx-background-radius: 10; " +
                "-fx-background-color: %s55;", color));

        playerLabel.setText(msg.getPlayer());
        timeLabel.setText(msg.getTimestamp().toLocalTime().toString());

        // 渲染翻译结果
        updateTranslated(msg.getTranslated());

        // 渲染原文，并根据开关控制显示/隐藏
        originalLabel.setText(msg.getOriginal());
        originalLabel.setVisible(showOriginal.get());
        originalLabel.setManaged(showOriginal.get());

        setGraphic(container);
        setStyle("-fx-background-color: transparent; -fx-padding: 2 0 2 0;");
    }

    private void updateTranslated(String translated) {
        if (translated != null && !translated.isEmpty()) {
            translatedLabel.setText(translated);
            translatedLabel.setVisible(true);
            translatedLabel.setManaged(true);
        } else {
            translatedLabel.setVisible(false);
            translatedLabel.setManaged(false);
        }
    }

    private String channelColor(String channel) {
        return switch (channel.toLowerCase()) {
            case "local" -> "#ff9f43";
            case "corp" -> "#54a0ff";
            case "alliance" -> "#1dd1a1";
            case "fleet" -> "#5f27cd";
            default -> "#00d2ff";
        };
    }
}

package com.example.evetransfer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.*;

/**
 * 频道选择对话框。
 * 启动时或运行时弹出，让用户勾选要监听的频道。
 */
public class ChannelSelectionDialog {

    /**
     * 显示频道选择对话框并等待用户确认。
     *
     * @param allChannels        所有可用频道
     * @param previouslySelected 之前已选中的频道（默认勾选）
     * @return 用户选中的频道集合；如果用户点了确定但什么都没选，返回空集合；
     *         如果用户取消了（点了取消或关闭窗口），返回 null
     */
    public static Set<String> showAndWait(Set<String> allChannels, Set<String> previouslySelected) {
        final Set<String> selected = new HashSet<>();
        final boolean[] confirmed = {false};

        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.UTILITY);
        dialog.setTitle("选择监听频道");
        dialog.setResizable(false);

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.setAlignment(Pos.TOP_CENTER);
        root.setStyle("-fx-background-color: #1e1e2e;");

        Label title = new Label("选择要监听的频道");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #f1f2f6;");

        VBox channelBox = new VBox(8);
        List<CheckBox> checkBoxes = new ArrayList<>();

        if (allChannels.isEmpty()) {
            Label empty = new Label("目录中未发现频道文件");
            empty.setStyle("-fx-text-fill: #a0a0b0;");
            channelBox.getChildren().add(empty);
        } else {
            // 全选 / 全不选 按钮
            HBox btnRow = new HBox(8);
            btnRow.setAlignment(Pos.CENTER);
            Button selectAllBtn = new Button("全选");
            Button selectNoneBtn = new Button("全不选");
            styleSmallBtn(selectAllBtn);
            styleSmallBtn(selectNoneBtn);
            selectAllBtn.setOnAction(e -> checkBoxes.forEach(cb -> cb.setSelected(true)));
            selectNoneBtn.setOnAction(e -> checkBoxes.forEach(cb -> cb.setSelected(false)));
            btnRow.getChildren().addAll(selectAllBtn, selectNoneBtn);
            root.getChildren().add(btnRow);

            for (String channel : new TreeSet<>(allChannels)) {
                CheckBox cb = new CheckBox(channel);
                cb.setSelected(previouslySelected.contains(channel));
                cb.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 13px;");
                checkBoxes.add(cb);
                channelBox.getChildren().add(cb);
            }
        }

        ScrollPane scroll = new ScrollPane(channelBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(Math.min(300, Math.max(100, allChannels.size() * 32)));
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        // 按钮行：确定 + 取消
        HBox btnRow = new HBox(12);
        btnRow.setAlignment(Pos.CENTER);

        Button confirmBtn = new Button("确定");
        confirmBtn.setStyle(
                "-fx-background-color: #00d2ff; -fx-text-fill: #1e1e2e; " +
                "-fx-font-weight: bold; -fx-padding: 6 20 6 20; " +
                "-fx-background-radius: 8; -fx-cursor: hand;"
        );
        confirmBtn.setOnAction(e -> {
            for (CheckBox cb : checkBoxes) {
                if (cb.isSelected()) {
                    selected.add(cb.getText());
                }
            }
            confirmed[0] = true;
            dialog.close();
        });

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #a0a0b0; " +
                "-fx-font-weight: bold; -fx-padding: 6 20 6 20; " +
                "-fx-background-radius: 8; -fx-cursor: hand;" +
                "-fx-border-color: rgba(160,160,176,0.3); -fx-border-radius: 8;"
        );
        cancelBtn.setOnAction(e -> dialog.close());

        btnRow.getChildren().addAll(cancelBtn, confirmBtn);

        root.getChildren().addAll(title, scroll, btnRow);

        double height = allChannels.isEmpty() ? 180 : Math.min(440, 140 + allChannels.size() * 32);
        Scene scene = new Scene(root, 300, height);
        dialog.setScene(scene);

        dialog.showAndWait();

        return confirmed[0] ? selected : null;
    }

    private static void styleSmallBtn(Button btn) {
        btn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: #e0e0e0; " +
                "-fx-font-size: 11px; -fx-padding: 3 10 3 10; " +
                "-fx-background-radius: 6; -fx-cursor: hand;"
        );
    }
}

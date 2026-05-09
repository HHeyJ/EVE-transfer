package com.example.evetransfer.ui;

import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 悬浮窗口工厂。
 *
 * JavaFX 的 Stage 相当于浏览器窗口（window），
 * Scene 相当于网页的 document，Region 就是网页里的一个 div。
 *
 * 这里创建一个没有系统边框、始终置顶、背景透明的窗口。
 */
public class OverlayStageFactory {

    public static Stage create(Region root, double width, double height) {
        Stage stage = new Stage();

        // TRANSPARENT：连标题栏都没有，完全自己做 UI
        stage.initStyle(StageStyle.TRANSPARENT);

        // 始终浮在其他窗口上方（游戏窗口之上）
        stage.setAlwaysOnTop(true);

        // 默认 90% 不透明
        stage.setOpacity(0.9);

        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(200);
        stage.setY(150);

        Scene scene = new Scene(root);
        // Scene 的背景设为透明，否则即使 Stage 透明，Scene 也会是白色的
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);

        return stage;
    }
}

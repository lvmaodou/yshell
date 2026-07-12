package com.yshell.controller;

import com.yshell.theme.ThemeManager;
import com.yshell.ui.ApplicationIcons;
import com.yshell.ui.DropdownMenu;
import com.yshell.ui.HelpMenu;
import com.yshell.ui.WindowDragResize;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ToolbarController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolbarController.class);
    private static final int RESIZE_MARGIN = 10;

    @FXML
    private Parent root;

    @FXML
    private HBox btnMinimize;

    @FXML
    private HBox btnMaximize;

    @FXML
    private HBox btnExit;

    @FXML
    private HBox menuConnect;

    @FXML
    private HBox menuCmd;

    @FXML
    private HBox menuWindow;

    @FXML
    private HBox menuKey;

    @FXML
    private HBox menuSettings;

    @FXML
    private HBox menuHelp;

    @FXML
    private HBox logo;

    private FontIcon maximizeIcon;
    private WindowDragResize dragResize;
    private Stage commandManagerStage;

    @FXML
    public void initialize() {
        btnMinimize.setOnMouseClicked(e -> minimizeWindow());
        btnMaximize.setOnMouseClicked(e -> toggleFullscreenOrMaximize());
        btnExit.setOnMouseClicked(e -> closeWindow());
        configureWindowButtonCursor(btnMinimize);
        configureWindowButtonCursor(btnMaximize);
        configureWindowButtonCursor(btnExit);
        menuConnect.setOnMouseClicked(e -> openConnectionDialog());
        menuCmd.setOnMouseClicked(e -> openCommandManager());
        menuWindow.setOnMouseClicked(e -> showWindowMenu());
        menuKey.setOnMouseClicked(e -> openKeyManager());
        menuSettings.setOnMouseClicked(e -> openSettings());
        menuHelp.setOnMouseClicked(e -> showHelpMenu());

        maximizeIcon = (FontIcon) btnMaximize.getChildren().get(0);

        // 标题栏拖动 + 8方向调整大小，排除所有工具栏按钮区域不触发拖动
        dragResize = WindowDragResize.apply(root, 40, RESIZE_MARGIN, 800, 600);
        dragResize.addExcludeNodes(btnMinimize, btnMaximize, btnExit,
                menuConnect, menuCmd, menuWindow,
                menuKey, menuSettings, menuHelp, logo);

        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                updateMaximizeIcon();
                newScene.windowProperty().addListener((obs2, oldWindow, newWindow) -> {
                    if (newWindow instanceof Stage stage) {
                        stage.maximizedProperty().addListener((obs3, oldMax, newMax) -> updateMaximizeIcon());
                        stage.fullScreenProperty().addListener((obs3, oldFull, newFull) -> updateMaximizeIcon());
                    }
                });
            }
        });
    }

    private void configureWindowButtonCursor(Node button) {
        button.setCursor(Cursor.HAND);
        button.addEventFilter(MouseEvent.MOUSE_MOVED, this::updateWindowButtonCursor);
        button.setOnMouseExited(e -> button.setCursor(Cursor.HAND));
    }

    private void updateWindowButtonCursor(MouseEvent event) {
        if (!(event.getSource() instanceof Node button)) {
            return;
        }
        Cursor resizeCursor = getResizeCursor(event);
        button.setCursor(resizeCursor == null ? Cursor.HAND : resizeCursor);
    }

    private Cursor getResizeCursor(MouseEvent event) {
        Scene scene = root.getScene();
        if (scene == null) {
            return null;
        }

        double sceneWidth = scene.getWidth();
        double sceneHeight = scene.getHeight();
        double x = event.getSceneX();
        double y = event.getSceneY();
        boolean right = x >= sceneWidth - RESIZE_MARGIN;
        boolean top = y <= RESIZE_MARGIN;
        boolean bottom = y >= sceneHeight - RESIZE_MARGIN;

        if (right && bottom) {
            return Cursor.SE_RESIZE;
        }
        if (right && top) {
            return Cursor.NE_RESIZE;
        }
        if (right) {
            return Cursor.E_RESIZE;
        }
        if (top) {
            return Cursor.N_RESIZE;
        }
        if (bottom) {
            return Cursor.S_RESIZE;
        }
        return null;
    }

    private void minimizeWindow() {
        if (dragResize.isResizing()) {
            return;
        }
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        Stage stage = (Stage) scene.getWindow();
        stage.setIconified(true);
    }

    private void toggleFullscreenOrMaximize() {
        if (dragResize.isResizing()) {
            return;
        }
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        Stage stage = (Stage) scene.getWindow();
        if (stage.isFullScreen()) {
            stage.setFullScreen(false);
        } else {
            stage.setMaximized(!stage.isMaximized());
        }
        updateMaximizeIcon();
    }

    private void updateMaximizeIcon() {
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        Stage stage = (Stage) scene.getWindow();
        if (stage == null) {
            return;
        }
        if (stage.isFullScreen()) {
            maximizeIcon.setIconLiteral("far-square");
        } else if (stage.isMaximized()) {
            maximizeIcon.setIconLiteral("far-window-restore");
        } else {
            maximizeIcon.setIconLiteral("far-square");
        }
    }

    private void closeWindow() {
        if (dragResize.isResizing()) {
            return;
        }
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        Stage stage = (Stage) scene.getWindow();
        stage.close();
    }

    private void openConnectionDialog() {
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ConnectionManager.fxml"));
            Parent dialogRoot = loader.load();

            ConnectionManagerController controller = loader.getController();
            Stage dialogStage = new Stage();
            ApplicationIcons.applyTo(dialogStage);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.setTitle("连接管理");

            Scene dialogScene = new Scene(dialogRoot, 800, 600);
            ThemeManager.getInstance().registerScene(dialogScene);
            dialogStage.setScene(dialogScene);
            controller.setDialogStage(dialogStage);

            Stage mainStage = (Stage) scene.getWindow();
            dialogStage.setX(mainStage.getX() + (mainStage.getWidth() - 800) / 2);
            dialogStage.setY(mainStage.getY() + (mainStage.getHeight() - 600) / 2);

            dialogStage.showAndWait();
            ThemeManager.getInstance().unregisterScene(dialogScene);
        } catch (IOException e) {
            LOGGER.error("openConnectionDialog error", e);
        }
    }

    private void openCommandManager() {
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        if (commandManagerStage != null && commandManagerStage.isShowing()) {
            if (commandManagerStage.isIconified()) {
                commandManagerStage.setIconified(false);
            }
            commandManagerStage.toFront();
            commandManagerStage.requestFocus();
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/CommandManager.fxml"));
            Parent dialogRoot = loader.load();

            CommandManagerController controller = loader.getController();
            Stage dialogStage = new Stage();
            ApplicationIcons.applyTo(dialogStage);
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.setTitle("命令收藏");

            Scene dialogScene = new Scene(dialogRoot, 700, 550);
            ThemeManager.getInstance().registerScene(dialogScene);
            dialogStage.setScene(dialogScene);
            controller.setDialogStage(dialogStage);
            commandManagerStage = dialogStage;
            dialogStage.setOnHidden(e -> {
                ThemeManager.getInstance().unregisterScene(dialogScene);
                commandManagerStage = null;
            });

            Stage mainStage = (Stage) scene.getWindow();
            dialogStage.setX(mainStage.getX() + (mainStage.getWidth() - 700) / 2);
            dialogStage.setY(mainStage.getY() + (mainStage.getHeight() - 550) / 2);

            dialogStage.show();
        } catch (IOException e) {
            LOGGER.error("openCommandManager error", e);
        }
    }

    private void showWindowMenu() {
        DropdownMenu dropdown = new DropdownMenu();
        dropdown.show(menuWindow);
    }

    private void openKeyManager() {
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/KeyManager.fxml"));
            Parent dialogRoot = loader.load();

            KeyManagerController controller = loader.getController();
            Stage dialogStage = new Stage();
            ApplicationIcons.applyTo(dialogStage);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.setTitle("密钥管理");

            Scene dialogScene = new Scene(dialogRoot, 600, 400);
            ThemeManager.getInstance().registerScene(dialogScene);
            dialogStage.setScene(dialogScene);
            controller.setDialogStage(dialogStage);

            Stage mainStage = (Stage) scene.getWindow();
            dialogStage.setX(mainStage.getX() + (mainStage.getWidth() - 600) / 2);
            dialogStage.setY(mainStage.getY() + (mainStage.getHeight() - 400) / 2);

            dialogStage.showAndWait();
            ThemeManager.getInstance().unregisterScene(dialogScene);
        } catch (IOException e) {
            LOGGER.error("openKeyManager error", e);
        }
    }

    private void openSettings() {
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsManager.fxml"));
            Parent dialogRoot = loader.load();

            SettingsManagerController controller = loader.getController();
            Stage dialogStage = new Stage();
            ApplicationIcons.applyTo(dialogStage);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.setTitle("设置");

            double dialogWidth = 760;
            double dialogHeight = 520;
            Scene dialogScene = new Scene(dialogRoot, dialogWidth, dialogHeight);
            dialogStage.setScene(dialogScene);
            ThemeManager.getInstance().registerScene(dialogScene);
            controller.setDialogStage(dialogStage);

            Stage mainStage = (Stage) scene.getWindow();
            dialogStage.setY(mainStage.getY() + (mainStage.getHeight() - dialogHeight) / 2);
            dialogStage.setX(mainStage.getX() + (mainStage.getWidth() - dialogWidth) / 2);

            dialogStage.showAndWait();
            ThemeManager.getInstance().unregisterScene(dialogScene);
        } catch (IOException e) {
            LOGGER.error("openSettings error", e);
        }
    }

    private void showHelpMenu() {
        HelpMenu helpMenu = new HelpMenu();
        helpMenu.show(menuHelp);
    }
}

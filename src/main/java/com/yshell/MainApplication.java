package com.yshell;

import com.yshell.controller.UpdateDialogController;
import com.yshell.config.AppSettings;
import com.yshell.model.Manifest;
import com.yshell.model.UpdateDiff;
import com.yshell.service.ConnectionManager;
import com.yshell.service.UpdateManager;
import com.yshell.theme.ThemeManager;
import com.yshell.ui.ApplicationIcons;
import com.yshell.ui.LayoutConfig;
import com.yshell.ui.PanelManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class MainApplication extends Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainApplication.class);
    private static Stage primaryStage;

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        MainApplication.primaryStage = primaryStage;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainLayout.fxml"));
        BorderPane root = loader.load();

        Scene scene = new Scene(root, 1200, 800);

        ThemeManager.getInstance().registerScene(scene);

        primaryStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
        ApplicationIcons.applyTo(primaryStage);
        primaryStage.setScene(scene);

        // 恢复窗口大小和位置
        LayoutConfig layoutConfig = LayoutConfig.getInstance();
        layoutConfig.applyWindowSize(primaryStage);

        primaryStage.show();

        // 窗口显示后恢复分割面板位置和面板可见性
        PanelManager pm = PanelManager.getInstance();
        pm.restoreMainDivider();
        pm.restoreContentDivider();

        // 恢复面板可见性状态
        if (!layoutConfig.isLeftPanelVisible()) {
            pm.toggleLeftPanel(false);
        }
        if (!layoutConfig.isBottomPanelVisible()) {
            pm.toggleBottomPanel(false);
        }
        if (!layoutConfig.isInteractivePanelVisible()) {
            pm.toggleInteractivePanel(false);
        }
        if (!layoutConfig.isSystemInfoVisible()) {
            pm.toggleSystemInfo(false);
        }

        layoutConfig.installStageAutoSave(primaryStage);

        if (AppSettings.getInstance().isStartupUpdateCheckEnabled()) {
            checkForUpdates(primaryStage);
        }
    }

    private void checkForUpdates(Stage stage) {
        new Thread(() -> {
            try {
                UpdateManager updateManager = new UpdateManager();
                Manifest remoteManifest = updateManager.checkLatestVersion();

                if (remoteManifest != null) {
                    UpdateDiff diff = updateManager.calculateDiff(remoteManifest);

                    if (diff.hasUpdates() && !LayoutConfig.getInstance().isStartupUpdatePromptSuppressed()) {
                        Platform.runLater(() -> showUpdateDialog(stage, updateManager, diff, remoteManifest));
                    }
                }
            } catch (Exception e) {
                LOGGER.error(e.getMessage());
            }
        }).start();
    }

    private void showUpdateDialog(Stage owner, UpdateManager updateManager, UpdateDiff diff, Manifest remoteManifest) {
        try {
            Stage stage = new Stage();
            ApplicationIcons.applyTo(stage);
            stage.initOwner(owner);
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setTitle("检查更新");
            stage.setWidth(400);
            stage.setHeight(diff != null && diff.hasUpdates() ? 260 : 200);
            stage.setResizable(false);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/UpdateDialog.fxml"));
            Parent root = loader.load();
            UpdateDialogController controller = loader.getController();

            if (diff != null && remoteManifest != null) {
                controller.init(stage, updateManager, diff, remoteManifest, true);
            } else {
                controller.init(stage, updateManager);
            }

            Scene scene = new Scene(root);
            stage.setScene(scene);
            controller.applyTheme(scene);
            stage.showAndWait();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }
    }

    @Override
    public void stop() throws Exception {
        // 应用退出前的最后机会（保障机制2：无论何种退出方式都会执行）
        LayoutConfig.getInstance().save();
        ConnectionManager.getInstance().shutdown();
        applyPendingUpdateOnExit();
        super.stop();
    }

    private void applyPendingUpdateOnExit() {
        try {
            UpdateManager updateManager = new UpdateManager();
            java.io.File pendingPlanDir = updateManager.getPendingUpdatePlanDir();
            if (pendingPlanDir != null) {
                updateManager.applyPreparedUpdateOnExit(pendingPlanDir);
            }
        } catch (Exception e) {
            LOGGER.error("启动退出更新失败", e);
        }
    }
}

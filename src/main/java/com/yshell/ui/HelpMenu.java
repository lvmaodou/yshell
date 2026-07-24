package com.yshell.ui;

import com.yshell.MainApplication;
import com.yshell.controller.UpdateDialogController;
import com.yshell.model.Manifest;
import com.yshell.model.UpdateDiff;
import com.yshell.service.UpdateManager;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.*;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.net.URI;

public class HelpMenu extends Popup {
    private static final Logger LOGGER = LoggerFactory.getLogger(HelpMenu.class);
    private static final String APPLICATION_NAME = "YShell";
    private static final String PROJECT_URL = "https://github.com/lvmaodou/yshell";
    private static final String FAQ_URL = PROJECT_URL + "/blob/master/doc/faq.md";
    private static final String USAGE_GUIDE_URL = PROJECT_URL + "/blob/master/doc/usage-guide.md";
    private final VBox menuContainer;

    public HelpMenu() {
        menuContainer = new VBox();
        menuContainer.getStyleClass().add("dropdown-menu-container");
        PopupStyles.applyDropdownStylesheets(menuContainer);

        addActionItem("检查更新", "fas-sync-alt", this::checkForUpdate);
        addActionItem("软件信息", "fas-info-circle", this::showSoftwareInfo);
        addSeparator();
        addActionItem("常见问题", "fas-question-circle", this::showFAQ);
        addActionItem("Github", "fab-github", this::openOfficialWebsite);
        addActionItem("使用帮助", "fas-book", this::showHelp);

        getContent().add(menuContainer);
        setAutoHide(true);
    }

    @Override
    public void show(Window window, double x, double y) {
        PopupStyles.applyDropdownStylesheets(menuContainer);
        super.show(window, x, y);
    }

    private void addActionItem(String text, String iconLiteral, Runnable action) {
        HBox item = createMenuItem(text, iconLiteral);
        item.setOnMouseClicked(e -> {
            action.run();
            hide();
        });
        menuContainer.getChildren().add(item);
    }

    private HBox createMenuItem(String text, String iconLiteral) {
        HBox item = new HBox();
        item.getStyleClass().addAll("hbox-interactive", "dropdown-menu-item");

        FontIcon icon = new FontIcon(iconLiteral);
        icon.getStyleClass().add("dropdown-menu-icon");

        Label label = new Label(text);
        label.getStyleClass().add("dropdown-menu-label");

        item.getChildren().addAll(icon, label);
        return item;
    }

    private void addSeparator() {
        Region separator = new Region();
        separator.getStyleClass().add("dropdown-menu-separator");
        menuContainer.getChildren().add(separator);
    }

    private void checkForUpdate() {
        new Thread(() -> {
            try {
                UpdateManager updateManager = new UpdateManager();
                Manifest remoteManifest = updateManager.checkLatestVersion();

                if (remoteManifest != null) {
                    UpdateDiff diff = updateManager.calculateDiff(remoteManifest);

                    Platform.runLater(() -> {
                        try {
                            Stage stage = new Stage();
                            ApplicationIcons.applyTo(stage);
                            stage.initOwner(MainApplication.getPrimaryStage());
                            stage.initModality(Modality.APPLICATION_MODAL);
                            stage.initStyle(StageStyle.UNDECORATED);
                            stage.setTitle("检查更新");
                            stage.setWidth(400);
                            stage.setHeight(diff.hasUpdates() ? 260 : 200);
                            stage.setResizable(false);

                            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/UpdateDialog.fxml"));
                            Parent root = loader.load();
                            UpdateDialogController controller = loader.getController();

                            controller.init(stage, updateManager, diff, remoteManifest);

                            Scene scene = new Scene(root);
                            stage.setScene(scene);
                            controller.applyTheme(scene);
                            stage.showAndWait();
                        } catch (Exception e) {
                            LOGGER.error(e.getMessage());
                            DialogHelper.showError("加载更新窗口失败: " + e.getMessage());
                        }
                    });
                } else {
                    Platform.runLater(() -> DialogHelper.showError("无法连接到更新服务器"));
                }
            } catch (Exception e) {
                LOGGER.error(e.getMessage());
                Platform.runLater(() -> DialogHelper.showError("检查更新失败: " + e.getMessage()));
            }
        }).start();
    }

    private void showSoftwareInfo() {
        DialogHelper.showInfoWithHeader("软件信息", APPLICATION_NAME,
                "SSH/SFTP 终端与服务器管理工具\n\n"
                        + "版本：" + resolveApplicationVersion() + "\n"
                        + "Java：" + System.getProperty("java.version", "unknown") + "\n"
                        + "JavaFX：" + System.getProperty("javafx.version", "unknown") + "\n"
                        + "系统：" + resolveOperatingSystem() + "\n"
                        + "项目地址：" + PROJECT_URL);
    }

    private void showFAQ() {
        openUrl(FAQ_URL);
    }

    private void openOfficialWebsite() {
        openUrl(PROJECT_URL);
    }

    private void showHelp() {
        openUrl(USAGE_GUIDE_URL);
    }

    private String resolveApplicationVersion() {
        String version = System.getProperty("jpackage.app-version");
        if (version != null && !version.isBlank()) {
            return version;
        }

        Package appPackage = HelpMenu.class.getPackage();
        if (appPackage != null) {
            version = appPackage.getImplementationVersion();
            if (version != null && !version.isBlank()) {
                return version;
            }
        }
        return "--";
    }

    private String resolveOperatingSystem() {
        return System.getProperty("os.name", "unknown")
                + " " + System.getProperty("os.version", "")
                + " (" + System.getProperty("os.arch", "unknown") + ")";
    }

    private void openUrl(String url) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                DialogHelper.showWarning("打开失败", "当前系统不支持自动打开浏览器，请手动访问：\n" + url);
                return;
            }
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            LOGGER.error("Failed to open url: {}", url, e);
            DialogHelper.showError("打开失败", "无法打开链接：\n" + url + "\n\n" + e.getMessage());
        }
    }

    public void show(Node anchor) {
        Point2D screenCoords = anchor.localToScreen(0, anchor.getBoundsInLocal().getHeight());
        show(anchor.getScene().getWindow(), screenCoords.getX(), screenCoords.getY());
    }
}

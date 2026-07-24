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
        DialogHelper.showInfoWithHeader("常见问题", "常见问题",
                """
                        连接失败
                        检查主机、端口、用户名、密码或私钥，并确认服务器 SSH 服务可访问。
                        
                        私钥无法登录
                        确认私钥格式、文件权限和 passphrase，必要时先用系统 ssh 命令验证。
                        
                        文件传输失败
                        确认账号有目标目录读写权限，并检查远程磁盘空间是否充足。
                        
                        终端显示异常
                        确认远程环境变量 LANG/LC_ALL 使用 UTF-8 编码。""");
    }

    private void openOfficialWebsite() {
        openUrl();
    }

    private void showHelp() {
        DialogHelper.showInfoWithHeader("使用帮助", "快速使用",
                """
                        新建连接
                        在左侧连接列表中新建服务器，填写主机、端口、用户和认证信息。
                        
                        打开终端
                        双击连接项打开终端会话，连接成功后可直接执行命令。
                        
                        管理文件
                        在文件面板浏览远程目录，支持上传、下载、重命名、删除和权限修改。
                        
                        常用命令
                        在命令管理中维护常用命令，并对当前会话执行。""");
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

    private void openUrl() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                DialogHelper.showWarning("打开失败", "当前系统不支持自动打开浏览器，请手动访问：\n" + HelpMenu.PROJECT_URL);
                return;
            }
            Desktop.getDesktop().browse(URI.create(HelpMenu.PROJECT_URL));
        } catch (Exception e) {
            LOGGER.error("Failed to open url: {}", HelpMenu.PROJECT_URL, e);
            DialogHelper.showError("打开失败", "无法打开链接：\n" + HelpMenu.PROJECT_URL + "\n\n" + e.getMessage());
        }
    }

    public void show(Node anchor) {
        Point2D screenCoords = anchor.localToScreen(0, anchor.getBoundsInLocal().getHeight());
        show(anchor.getScene().getWindow(), screenCoords.getX(), screenCoords.getY());
    }
}

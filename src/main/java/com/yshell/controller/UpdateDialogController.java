package com.yshell.controller;

import com.yshell.model.Manifest;
import com.yshell.model.UpdateDiff;
import com.yshell.service.UpdateManager;
import com.yshell.theme.ThemeManager;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.LayoutConfig;
import com.yshell.ui.WindowDragResize;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.Objects;
import java.util.ResourceBundle;

public class UpdateDialogController implements Initializable {
    private static final String CHANGELOG_URL = "https://github.com/lvmaodou/yshell/commits/master";

    @FXML
    private BorderPane root;

    @FXML
    private Button btnClose;

    @FXML
    private Label titleLabel;

    @FXML
    private HBox versionBox;

    @FXML
    private Label currentVersionLabel;

    @FXML
    private Label newVersionLabel;

    @FXML
    private Label versionOnlyLabel;

    @FXML
    private Label sizeLabel;

    @FXML
    private VBox progressBox;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private Label progressLabel;

    @FXML
    private CheckBox startupPromptCheckBox;

    @FXML
    private Button changelogButton;

    @FXML
    private Button cancelButton;

    @FXML
    private Button updateButton;

    private Stage stage;
    private UpdateManager updateManager;
    private UpdateDiff diff;
    private Manifest remoteManifest;
    private String currentVersion;
    private boolean hasUpdates;
    private boolean startupPrompt;
    private volatile boolean downloading;
    private volatile boolean updateReady;
    private File preparedPlanDir;
    private static final Object READY_DIALOG_LOCK = new Object();
    private static String readyDialogVersion;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
    }

    public void init(Stage stage, UpdateManager updateManager) {
        this.stage = stage;
        this.updateManager = updateManager;
        this.diff = null;
        this.remoteManifest = null;
        this.currentVersion = getCurrentVersion();
        this.hasUpdates = false;
        this.startupPrompt = false;

        setupUI();
        WindowDragResize.apply(root, -1, btnClose);
    }

    public void init(Stage stage, UpdateManager updateManager, UpdateDiff diff, Manifest remoteManifest) {
        init(stage, updateManager, diff, remoteManifest, false);
    }

    public void init(Stage stage, UpdateManager updateManager, UpdateDiff diff, Manifest remoteManifest,
                     boolean startupPrompt) {
        this.stage = stage;
        this.updateManager = updateManager;
        this.diff = diff;
        this.remoteManifest = remoteManifest;
        this.currentVersion = getCurrentVersion();
        this.hasUpdates = diff != null && diff.hasUpdates();
        this.startupPrompt = startupPrompt;

        setupUI();
        WindowDragResize.apply(root, -1, btnClose);
    }

    public void applyTheme(Scene scene) {
        ThemeManager themeManager = ThemeManager.getInstance();
        String themeCss = themeManager.isDarkTheme()
                ? "/css/theme-dark.css"
                : "/css/theme-light.css";
        String variablesCss = "/css/theme-variables.css";

        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource(themeCss)).toExternalForm());
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource(variablesCss)).toExternalForm());
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/css/update-dialog.css")).toExternalForm());
    }

    private String getCurrentVersion() {
        Manifest localManifest = updateManager.getLocalManifest();
        if (localManifest != null && localManifest.getVersion() != null) {
            return localManifest.getVersion();
        }
        return "1.0.0";
    }

    private void setupUI() {
        if (hasUpdates) {
            titleLabel.setText("发现新版本");
            currentVersionLabel.setText("当前版本: " + currentVersion);
            newVersionLabel.setText(diff.getNewVersion());
            sizeLabel.setText("更新大小: " + updateManager.formatSize(diff.getTotalDownloadSize()));

            versionBox.setVisible(true);
            versionBox.setManaged(true);
            versionOnlyLabel.setVisible(false);
            versionOnlyLabel.setManaged(false);
            sizeLabel.setVisible(true);

            changelogButton.setVisible(true);
            cancelButton.setVisible(true);
            cancelButton.setText("稍后更新");
            updateButton.setVisible(true);
        } else {
            titleLabel.setText("当前已是最新版本");
            versionOnlyLabel.setText("当前版本: " + currentVersion);

            versionBox.setVisible(false);
            versionBox.setManaged(false);
            versionOnlyLabel.setVisible(true);
            versionOnlyLabel.setManaged(true);
            sizeLabel.setVisible(false);
            sizeLabel.setManaged(false);

            changelogButton.setVisible(true);
            cancelButton.setText("关闭");
            cancelButton.setVisible(true);
            updateButton.setVisible(false);
            updateButton.setManaged(false);
        }

        progressBox.setVisible(false);
        progressBox.setManaged(false);
        progressBar.setProgress(0);
        progressLabel.setText("");
        setupStartupPromptCheckBox();

        if (hasUpdates && updateManager.hasActiveDownload(diff.getNewVersion())) {
            attachToActiveDownload();
        }
    }

    private void setupStartupPromptCheckBox() {
        if (startupPromptCheckBox == null) {
            return;
        }

        boolean visible = hasUpdates && startupPrompt;
        startupPromptCheckBox.setVisible(visible);
        startupPromptCheckBox.setManaged(visible);
        if (!visible) {
            startupPromptCheckBox.setSelected(false);
            return;
        }

        startupPromptCheckBox.setSelected(LayoutConfig.getInstance().isStartupUpdatePromptSuppressed());
        startupPromptCheckBox.selectedProperty().addListener((observable, oldValue, selected) ->
                LayoutConfig.getInstance().setStartupUpdatePromptSuppressed(selected));

        if (stage != null) {
            stage.setHeight(Math.max(stage.getHeight(), 285));
        }
    }

    private void resetStartupPromptSuppression() {
        LayoutConfig.getInstance().setStartupUpdatePromptSuppressed(false);
        if (startupPromptCheckBox != null) {
            startupPromptCheckBox.setSelected(false);
            startupPromptCheckBox.setVisible(false);
            startupPromptCheckBox.setManaged(false);
        }
    }

    @FXML
    private void closeDialog() {
        stage.close();
    }

    @FXML
    private void onChangelog() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                DialogHelper.showWarning("打开失败", "当前系统不支持自动打开浏览器，请手动访问：\n" + CHANGELOG_URL);
                return;
            }
            Desktop.getDesktop().browse(URI.create(CHANGELOG_URL));
        } catch (Exception e) {
            DialogHelper.showError("打开失败", "无法打开链接：\n" + CHANGELOG_URL + "\n\n" + e.getMessage());
        }
    }

    @FXML
    private void onUpdate() {
        if (diff == null || remoteManifest == null || !diff.hasUpdates()) {
            return;
        }

        resetStartupPromptSuppression();

        if (updateReady && preparedPlanDir != null) {
            applyPreparedUpdateNow(preparedPlanDir);
            return;
        }

        if (updateManager.hasActiveDownload(diff.getNewVersion())) {
            attachToActiveDownload();
            return;
        }

        showDownloadingState("准备下载...");
        updateManager.startPrepareUpdateAsync(
                remoteManifest,
                diff,
                (downloaded, total) -> Platform.runLater(() -> updateProgress(downloaded, total)),
                planDir -> Platform.runLater(() -> onDownloadCompleted(planDir)),
                error -> Platform.runLater(() -> onDownloadFailed(error))
        );
    }

    private void attachToActiveDownload() {
        boolean completed = updateManager.isActiveDownloadCompleted(diff.getNewVersion());
        showDownloadingState(completed ? "下载完成，等待重启安装" : "正在下载...");
        downloading = !completed;

        updateManager.observeActiveDownload(
                diff.getNewVersion(),
                (downloaded, total) -> Platform.runLater(() -> updateProgress(downloaded, total)),
                planDir -> Platform.runLater(() -> onDownloadCompleted(planDir)),
                error -> Platform.runLater(() -> onDownloadFailed(error))
        );
    }

    private void showDownloadingState(String message) {
        downloading = true;
        updateReady = false;
        titleLabel.setText("正在下载更新");
        progressBox.setVisible(true);
        progressBox.setManaged(true);
        progressLabel.setText(message);

        if (stage != null) {
            stage.setHeight(320);
        }

        updateButton.setText("下载中...");
        updateButton.setDisable(true);
        cancelButton.setText("后台更新");
        cancelButton.setDisable(false);
        changelogButton.setDisable(true);
        changelogButton.setVisible(false);
        changelogButton.setManaged(false);
    }

    private void updateProgress(long downloaded, long total) {
        if (!downloading) {
            return;
        }

        if (total <= 0) {
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            progressLabel.setText("已下载 " + updateManager.formatSize(downloaded));
            return;
        }

        double progress = Math.max(0, Math.min(1, downloaded / (double) total));
        progressBar.setProgress(progress);
        progressLabel.setText("已下载 " + updateManager.formatSize(downloaded)
                + " / " + updateManager.formatSize(total)
                + " (" + Math.round(progress * 100) + "%)");
    }

    private void onDownloadCompleted(File planDir) {
        downloading = false;
        updateReady = true;
        preparedPlanDir = planDir;
        progressBar.setProgress(1);
        progressLabel.setText("下载完成，等待重启安装");
        titleLabel.setText("更新已下载");
        updateButton.setText("立即重启更新");
        updateButton.setDisable(false);
        cancelButton.setText("稍后重启更新");
        cancelButton.setDisable(false);

        if (claimReadyDialog(diff.getNewVersion())) {
            showUpdateReadyDialog(planDir);
        }
    }

    private void onDownloadFailed(Exception e) {
        downloading = false;
        updateReady = false;
        preparedPlanDir = null;
        DialogHelper.showError("更新错误", "更新失败: " + e.getMessage());
        resetUpdateButtons();
    }

    private boolean claimReadyDialog(String version) {
        synchronized (READY_DIALOG_LOCK) {
            if (Objects.equals(readyDialogVersion, version)) {
                return false;
            }
            readyDialogVersion = version;
            return true;
        }
    }

    private void showUpdateReadyDialog(File planDir) {
        Window owner = stage != null ? stage.getOwner() : null;
        if (stage != null && stage.isShowing()) {
            stage.close();
        }

        Platform.runLater(() -> {
            boolean restartNow = DialogHelper.showConfirm(
                    owner,
                    "更新已下载",
                    "更新文件已下载完成。选择稍后将会在关闭应用时自动安装，不会再次弹窗。",
                    "立即重启更新",
                    "稍后重启更新"
            );

            if (restartNow) {
                applyPreparedUpdateNow(planDir);
            }
        });
    }

    private void applyPreparedUpdateNow(File planDir) {
        try {
            updateManager.applyPreparedUpdate(planDir);
        } catch (Exception e) {
            DialogHelper.showError("更新错误", "启动更新程序失败: " + e.getMessage());
        }
    }

    private void resetUpdateButtons() {
        updateButton.setText("立即更新");
        updateButton.setDisable(false);
        cancelButton.setText("稍后更新");
        cancelButton.setDisable(false);
        changelogButton.setVisible(true);
        changelogButton.setManaged(true);
        changelogButton.setDisable(false);
    }
}

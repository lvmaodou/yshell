package com.yshell.controller;

import com.yshell.config.AppSettings;
import com.yshell.transfer.TransferDirection;
import com.yshell.transfer.TransferManager;
import com.yshell.transfer.TransferStatus;
import com.yshell.transfer.TransferTask;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class TransferQueueController {
    @FXML
    private VBox root;
    @FXML
    private Button btnMinimize;
    @FXML
    private Button btnMaximize;
    @FXML
    private Button btnClose;
    @FXML
    private TextField downloadDirectoryField;
    @FXML
    private Button btnChooseDirectory;
    @FXML
    private Button btnOpenDirectory;
    @FXML
    private Button btnResumeAll;
    @FXML
    private Button btnPauseAll;
    @FXML
    private Button btnClearTasks;
    @FXML
    private TabPane transferTabs;
    @FXML
    private Tab downloadTab;
    @FXML
    private Tab uploadTab;
    @FXML
    private TableView<TransferTask> downloadTable;
    @FXML
    private TableColumn<TransferTask, String> downloadNameColumn;
    @FXML
    private TableColumn<TransferTask, String> downloadPathColumn;
    @FXML
    private TableColumn<TransferTask, Number> downloadProgressColumn;
    @FXML
    private TableColumn<TransferTask, String> downloadSizeColumn;
    @FXML
    private TableColumn<TransferTask, String> downloadSpeedColumn;
    @FXML
    private TableColumn<TransferTask, String> downloadStatusColumn;
    @FXML
    private TableColumn<TransferTask, Void> downloadActionColumn;
    @FXML
    private TableView<TransferTask> uploadTable;
    @FXML
    private TableColumn<TransferTask, String> uploadNameColumn;
    @FXML
    private TableColumn<TransferTask, String> uploadPathColumn;
    @FXML
    private TableColumn<TransferTask, Number> uploadProgressColumn;
    @FXML
    private TableColumn<TransferTask, String> uploadSizeColumn;
    @FXML
    private TableColumn<TransferTask, String> uploadSpeedColumn;
    @FXML
    private TableColumn<TransferTask, String> uploadStatusColumn;
    @FXML
    private TableColumn<TransferTask, Void> uploadActionColumn;

    private final TransferManager transferManager = TransferManager.getInstance();
    private Stage stage;
    private String connectionId;
    private Path downloadRoot;
    private Consumer<Path> downloadRootChanged = path -> {
    };
    private Runnable queueChanged = () -> {
    };
    private ObservableList<TransferTask> observedDownloads;
    private ObservableList<TransferTask> observedUploads;
    private boolean hasSeenTasks;
    private final ChangeListener<TransferStatus> taskStatusListener = (obs, oldStatus, newStatus) -> handleAutoCompletion();
    private final ListChangeListener<TransferTask> taskListListener = change -> {
        while (change.next()) {
            for (TransferTask task : change.getAddedSubList()) {
                hasSeenTasks = true;
                task.statusProperty().addListener(taskStatusListener);
            }
            for (TransferTask task : change.getRemoved()) {
                task.statusProperty().removeListener(taskStatusListener);
            }
        }
        handleAutoCompletion();
    };

    @FXML
    public void initialize() {
        btnChooseDirectory.setOnAction(e -> chooseDownloadDirectory());
        btnOpenDirectory.setOnAction(e -> openLocalDirectory(downloadRoot));
        btnResumeAll.setOnAction(e -> resumeAllTasks());
        btnPauseAll.setOnAction(e -> pauseAllTasks());
        btnClearTasks.setOnAction(e -> clearTasks());
    }

    public VBox getRoot() {
        return root;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
        btnMinimize.setOnAction(e -> stage.setIconified(true));
        btnMaximize.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        btnClose.setOnAction(e -> stage.close());
        WindowDragResize.apply(root, 32, btnMinimize, btnMaximize, btnClose);
    }

    public void configure(String connectionId, Path downloadRoot, Consumer<Path> downloadRootChanged, Runnable queueChanged) {
        this.connectionId = connectionId;
        this.downloadRoot = downloadRoot;
        this.downloadRootChanged = downloadRootChanged == null ? path -> {
        } : downloadRootChanged;
        this.queueChanged = queueChanged == null ? () -> {
        } : queueChanged;
        downloadDirectoryField.setText(downloadRoot == null ? "" : downloadRoot.toString());
        detachAutoCompletionHandling();

        setupTable(downloadTable, transferManager.downloads(connectionId), downloadNameColumn, downloadPathColumn,
                downloadProgressColumn, downloadSizeColumn, downloadSpeedColumn, downloadStatusColumn, downloadActionColumn);
        setupTable(uploadTable, transferManager.uploads(connectionId), uploadNameColumn, uploadPathColumn,
                uploadProgressColumn, uploadSizeColumn, uploadSpeedColumn, uploadStatusColumn, uploadActionColumn);
        observedDownloads = transferManager.downloads(connectionId);
        observedUploads = transferManager.uploads(connectionId);
        installAutoCompletionHandling(observedDownloads);
        installAutoCompletionHandling(observedUploads);
    }

    public void selectTab(TransferDirection direction) {
        if (transferTabs == null || direction == null) {
            return;
        }
        transferTabs.getSelectionModel().select(direction == TransferDirection.UPLOAD ? uploadTab : downloadTab);
    }

    private void setupTable(TableView<TransferTask> table,
                            ObservableList<TransferTask> tasks,
                            TableColumn<TransferTask, String> nameColumn,
                            TableColumn<TransferTask, String> pathColumn,
                            TableColumn<TransferTask, Number> progressColumn,
                            TableColumn<TransferTask, String> sizeColumn,
                            TableColumn<TransferTask, String> speedColumn,
                            TableColumn<TransferTask, String> statusColumn,
                            TableColumn<TransferTask, Void> actionColumn) {
        table.setItems(tasks);
        nameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getName()));
        pathColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getDirection() == TransferDirection.DOWNLOAD
                ? data.getValue().getRemotePath()
                : data.getValue().getLocalPath().toString()));
        progressColumn.setCellValueFactory(data -> data.getValue().transferredBytesProperty());
        progressColumn.setCellFactory(column -> new ProgressCell());
        sizeColumn.setCellValueFactory(data -> Bindings.createStringBinding(
                () -> formatBytes(data.getValue().getTransferredBytes()) + " / " + formatBytes(data.getValue().getTotalBytes()),
                data.getValue().transferredBytesProperty()));
        speedColumn.setCellValueFactory(data -> data.getValue().speedTextProperty());
        statusColumn.setCellValueFactory(data -> Bindings.createStringBinding(() -> {
            TransferTask task = data.getValue();
            String text = task.getStatus().getText();
            return task.getMessage().isBlank() ? text : text + ": " + task.getMessage();
        }, data.getValue().statusProperty(), data.getValue().messageProperty()));
        actionColumn.setCellFactory(column -> new ActionCell());
    }

    private void chooseDownloadDirectory() {
        Path selected = DialogHelper.chooseDirectory(stage, "选择下载目录", downloadRoot);
        if (selected != null) {
            downloadRoot = selected;
            downloadDirectoryField.setText(downloadRoot.toString());
            downloadRootChanged.accept(downloadRoot);
        }
    }

    private void clearTasks() {
        if (connectionId != null) {
            transferManager.clearTasks(connectionId);
            queueChanged.run();
        }
    }

    private void installAutoCompletionHandling(ObservableList<TransferTask> tasks) {
        tasks.removeListener(taskListListener);
        tasks.addListener(taskListListener);
        for (TransferTask task : tasks) {
            hasSeenTasks = true;
            task.statusProperty().removeListener(taskStatusListener);
            task.statusProperty().addListener(taskStatusListener);
        }
        handleAutoCompletion();
    }

    private void detachAutoCompletionHandling() {
        detachAutoCompletionHandling(observedDownloads);
        detachAutoCompletionHandling(observedUploads);
        observedDownloads = null;
        observedUploads = null;
    }

    private void detachAutoCompletionHandling(ObservableList<TransferTask> tasks) {
        if (tasks == null) return;
        tasks.removeListener(taskListListener);
        for (TransferTask task : tasks) {
            task.statusProperty().removeListener(taskStatusListener);
        }
    }

    private void handleAutoCompletion() {
        if (!hasSeenTasks || connectionId == null) {
            return;
        }
        ObservableList<TransferTask> downloads = transferManager.downloads(connectionId);
        ObservableList<TransferTask> uploads = transferManager.uploads(connectionId);
        if (downloads.isEmpty() && uploads.isEmpty()) {
            return;
        }
        boolean allFinished = java.util.stream.Stream.concat(downloads.stream(), uploads.stream())
                .allMatch(this::isFinished);
        if (!allFinished) {
            return;
        }
        if (AppSettings.getInstance().isTransferClearFinishedWhenDone()) {
            transferManager.clearFinished(connectionId);
            queueChanged.run();
        }
        if (AppSettings.getInstance().isTransferCloseQueueWhenFinished() && stage != null) {
            stage.close();
        }
    }

    private boolean isFinished(TransferTask task) {
        TransferStatus status = task.getStatus();
        return status == TransferStatus.COMPLETED || status == TransferStatus.FAILED || status == TransferStatus.CANCELED;
    }

    private void pauseAllTasks() {
        if (connectionId != null) {
            transferManager.pauseAll(connectionId);
            queueChanged.run();
        }
    }

    private void resumeAllTasks() {
        if (connectionId != null) {
            transferManager.resumeAll(connectionId);
            queueChanged.run();
        }
    }

    private void openLocalDirectory(Path path) {
        if (path == null) return;
        try {
            Files.createDirectories(path);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (IOException ignored) {
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static class ProgressCell extends TableCell<TransferTask, Number> {
        private final ProgressBar bar = new ProgressBar(0);

        @Override
        protected void updateItem(Number item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                bar.progressProperty().unbind();
                setGraphic(null);
                return;
            }
            TransferTask task = getTableRow().getItem();
            bar.progressProperty().unbind();
            bar.progressProperty().bind(Bindings.createDoubleBinding(task::getProgress, task.transferredBytesProperty(), task.statusProperty()));
            bar.setMaxWidth(Double.MAX_VALUE);
            setGraphic(bar);
        }
    }

    private class ActionCell extends TableCell<TransferTask, Void> {
        private final Button pause = new Button();
        private final Button restart = new Button("重试");
        private final Button open = new Button("打开");
        private final Button remove = new Button("删除");
        private final HBox box = new HBox(4);
        private TransferTask observedTask;
        private final ChangeListener<TransferStatus> statusListener = (obs, oldStatus, newStatus) -> refreshButtons();

        ActionCell() {
            pause.setOnAction(e -> {
                TransferTask task = getTableRow().getItem();
                if (task == null) return;
                if (task.getStatus() == TransferStatus.RUNNING || task.getStatus() == TransferStatus.WAITING) {
                    transferManager.pause(task);
                } else {
                    transferManager.resume(task);
                }
                refreshButtons();
            });
            restart.setOnAction(e -> {
                transferManager.restart(getTableRow().getItem());
                refreshButtons();
            });
            open.setOnAction(e -> {
                TransferTask task = getTableRow().getItem();
                if (task != null) {
                    openLocalDirectory(task.getLocalPath().getParent());
                }
            });
            remove.setOnAction(e -> {
                transferManager.remove(getTableRow().getItem());
                queueChanged.run();
            });
        }

        @Override
        protected void updateItem(Void item, boolean empty) {
            super.updateItem(item, empty);
            if (observedTask != null) {
                observedTask.statusProperty().removeListener(statusListener);
                observedTask = null;
            }
            TransferTask task = getTableRow() == null ? null : getTableRow().getItem();
            if (empty || task == null) {
                setGraphic(null);
                return;
            }
            observedTask = task;
            observedTask.statusProperty().addListener(statusListener);
            refreshButtons();
            setGraphic(box);
        }

        private void refreshButtons() {
            TransferTask task = getTableRow() == null ? null : getTableRow().getItem();
            if (task == null) return;
            TransferStatus status = task.getStatus();
            pause.setText(status == TransferStatus.COMPLETED ? "完成"
                    : task.isPauseRequested() || status == TransferStatus.PAUSED || status == TransferStatus.FAILED ? "继续" : "暂停");
            pause.setDisable(status == TransferStatus.COMPLETED || status == TransferStatus.CANCELED);
            restart.setDisable(status == TransferStatus.RUNNING || status == TransferStatus.WAITING);
            open.setDisable(status != TransferStatus.COMPLETED);

            box.getChildren().setAll(pause, restart);
            if (task.getDirection() == TransferDirection.DOWNLOAD) {
                box.getChildren().add(open);
            }
            box.getChildren().add(remove);
            queueChanged.run();
        }
    }
}

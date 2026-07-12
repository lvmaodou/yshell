package com.yshell.controller;

import com.yshell.config.AppSettings;
import com.yshell.transfer.*;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class CompressedTransferQueueController {
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
    private Button btnClearFinished;
    @FXML
    private TabPane queueTabs;
    @FXML
    private Tab downloadTab;
    @FXML
    private Tab uploadTab;
    @FXML
    private TableView<CompressedTransferTask> downloadTable;
    @FXML
    private TableColumn<CompressedTransferTask, String> downloadNameColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> downloadSourceColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> downloadTargetColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> downloadStageColumn;
    @FXML
    private TableColumn<CompressedTransferTask, Number> downloadProgressColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> downloadSizeColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> downloadStatusColumn;
    @FXML
    private TableColumn<CompressedTransferTask, Void> downloadActionColumn;
    @FXML
    private TableView<CompressedTransferTask> uploadTable;
    @FXML
    private TableColumn<CompressedTransferTask, String> uploadNameColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> uploadSourceColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> uploadTargetColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> uploadStageColumn;
    @FXML
    private TableColumn<CompressedTransferTask, Number> uploadProgressColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> uploadSizeColumn;
    @FXML
    private TableColumn<CompressedTransferTask, String> uploadStatusColumn;
    @FXML
    private TableColumn<CompressedTransferTask, Void> uploadActionColumn;

    private final CompressedTransferManager manager = CompressedTransferManager.getInstance();
    private Stage stage;
    private String connectionId;
    private Path downloadRoot;
    private Consumer<Path> downloadRootChanged = path -> {
    };
    private Runnable queueChanged = () -> {
    };
    private ObservableList<CompressedTransferTask> observedDownloads;
    private ObservableList<CompressedTransferTask> observedUploads;
    private boolean hasSeenTasks;
    private final ChangeListener<TransferStatus> taskStatusListener = (obs, oldStatus, newStatus) -> handleAutoCompletion();
    private final ListChangeListener<CompressedTransferTask> taskListListener = change -> {
        while (change.next()) {
            for (CompressedTransferTask task : change.getAddedSubList()) {
                hasSeenTasks = true;
                task.statusProperty().addListener(taskStatusListener);
            }
            for (CompressedTransferTask task : change.getRemoved()) {
                task.statusProperty().removeListener(taskStatusListener);
            }
        }
        handleAutoCompletion();
    };

    @FXML
    public void initialize() {
        btnChooseDirectory.setOnAction(e -> chooseDownloadDirectory());
        btnOpenDirectory.setOnAction(e -> openLocalDirectory(downloadRoot));
        btnClearFinished.setOnAction(e -> clearFinished());
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

        setupTable(downloadTable, manager.downloads(connectionId), downloadNameColumn, downloadSourceColumn,
                downloadTargetColumn, downloadStageColumn, downloadProgressColumn, downloadSizeColumn, downloadStatusColumn, downloadActionColumn);
        setupTable(uploadTable, manager.uploads(connectionId), uploadNameColumn, uploadSourceColumn,
                uploadTargetColumn, uploadStageColumn, uploadProgressColumn, uploadSizeColumn, uploadStatusColumn, uploadActionColumn);
        observedDownloads = manager.downloads(connectionId);
        observedUploads = manager.uploads(connectionId);
        installAutoCompletionHandling(observedDownloads);
        installAutoCompletionHandling(observedUploads);
    }

    public void selectTab(CompressedTransferDirection direction) {
        if (queueTabs == null || direction == null) return;
        queueTabs.getSelectionModel().select(direction == CompressedTransferDirection.UPLOAD ? uploadTab : downloadTab);
    }

    private void setupTable(TableView<CompressedTransferTask> table,
                            ObservableList<CompressedTransferTask> tasks,
                            TableColumn<CompressedTransferTask, String> nameColumn,
                            TableColumn<CompressedTransferTask, String> sourceColumn,
                            TableColumn<CompressedTransferTask, String> targetColumn,
                            TableColumn<CompressedTransferTask, String> stageColumn,
                            TableColumn<CompressedTransferTask, Number> progressColumn,
                            TableColumn<CompressedTransferTask, String> sizeColumn,
                            TableColumn<CompressedTransferTask, String> statusColumn,
                            TableColumn<CompressedTransferTask, Void> actionColumn) {
        table.setItems(tasks);
        nameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getName()));
        sourceColumn.setCellValueFactory(data -> data.getValue().sourceProperty());
        targetColumn.setCellValueFactory(data -> data.getValue().targetProperty());
        stageColumn.setCellValueFactory(data -> Bindings.createStringBinding(
                () -> data.getValue().getStage().getText(), data.getValue().stageProperty()));
        progressColumn.setCellValueFactory(data -> Bindings.createLongBinding(
                () -> data.getValue().getTransferredBytes(),
                data.getValue().transferredBytesProperty(),
                data.getValue().totalBytesProperty(),
                data.getValue().stageProperty()));
        progressColumn.setCellFactory(column -> new ProgressCell());
        sizeColumn.setCellValueFactory(data -> Bindings.createStringBinding(() -> {
            CompressedTransferTask task = data.getValue();
            return formatBytes(task.getTransferredBytes()) + " / " + formatBytes(task.getTotalBytes());
        }, data.getValue().transferredBytesProperty(), data.getValue().totalBytesProperty()));
        statusColumn.setCellValueFactory(data -> Bindings.createStringBinding(() -> {
            CompressedTransferTask task = data.getValue();
            String text = task.getStatus().getText();
            if (task.getStage() == CompressedTransferStage.TRANSFERRING && !task.getSpeedText().isBlank() && !"--".equals(task.getSpeedText())) {
                text += " " + task.getSpeedText();
            }
            return task.getMessage().isBlank() ? text : text + ": " + task.getMessage();
        }, data.getValue().statusProperty(), data.getValue().stageProperty(), data.getValue().speedTextProperty(), data.getValue().messageProperty()));
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

    private void clearFinished() {
        if (connectionId != null) {
            manager.clearFinished(connectionId);
            queueChanged.run();
        }
    }

    private void installAutoCompletionHandling(ObservableList<CompressedTransferTask> tasks) {
        tasks.removeListener(taskListListener);
        tasks.addListener(taskListListener);
        for (CompressedTransferTask task : tasks) {
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

    private void detachAutoCompletionHandling(ObservableList<CompressedTransferTask> tasks) {
        if (tasks == null) return;
        tasks.removeListener(taskListListener);
        for (CompressedTransferTask task : tasks) {
            task.statusProperty().removeListener(taskStatusListener);
        }
    }

    private void handleAutoCompletion() {
        if (!hasSeenTasks || connectionId == null) {
            return;
        }
        ObservableList<CompressedTransferTask> downloads = manager.downloads(connectionId);
        ObservableList<CompressedTransferTask> uploads = manager.uploads(connectionId);
        if (downloads.isEmpty() && uploads.isEmpty()) {
            return;
        }
        boolean allFinished = java.util.stream.Stream.concat(downloads.stream(), uploads.stream())
                .allMatch(this::isFinished);
        if (!allFinished) {
            return;
        }
        if (AppSettings.getInstance().isTransferClearFinishedWhenDone()) {
            manager.clearFinished(connectionId);
            queueChanged.run();
        }
        if (AppSettings.getInstance().isTransferCloseQueueWhenFinished() && stage != null) {
            stage.close();
        }
    }

    private boolean isFinished(CompressedTransferTask task) {
        TransferStatus status = task.getStatus();
        return status == TransferStatus.COMPLETED || status == TransferStatus.FAILED || status == TransferStatus.CANCELED;
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
        if (bytes <= 0) return "--";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static class ProgressCell extends TableCell<CompressedTransferTask, Number> {
        private final ProgressBar bar = new ProgressBar(0);

        @Override
        protected void updateItem(Number item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                bar.progressProperty().unbind();
                setText(null);
                setGraphic(null);
                return;
            }
            CompressedTransferTask task = getTableRow().getItem();
            if (task.getStage() != CompressedTransferStage.TRANSFERRING
                    && task.getStage() != CompressedTransferStage.COMPLETED) {
                bar.progressProperty().unbind();
                setGraphic(null);
                setText("--");
                return;
            }
            bar.progressProperty().unbind();
            bar.progressProperty().bind(Bindings.createDoubleBinding(task::getTransferProgress,
                    task.transferredBytesProperty(), task.totalBytesProperty(), task.stageProperty()));
            bar.setMaxWidth(Double.MAX_VALUE);
            setText(null);
            setGraphic(bar);
        }
    }

    private class ActionCell extends TableCell<CompressedTransferTask, Void> {
        private final Button cancel = new Button("取消");
        private final Button restart = new Button("重试");
        private final Button open = new Button("打开");
        private final Button remove = new Button("删除");
        private final HBox box = new HBox(4);
        private CompressedTransferTask observedTask;
        private final ChangeListener<TransferStatus> statusListener = (obs, oldStatus, newStatus) -> refreshButtons();

        ActionCell() {
            cancel.setOnAction(e -> {
                manager.cancel(getTableRow().getItem());
                refreshButtons();
                queueChanged.run();
            });
            restart.setOnAction(e -> {
                manager.restart(getTableRow().getItem());
                refreshButtons();
                queueChanged.run();
            });
            open.setOnAction(e -> {
                CompressedTransferTask task = getTableRow().getItem();
                if (task != null && task.getLocalTargetDirectory() != null) {
                    openLocalDirectory(task.getLocalTargetDirectory());
                }
            });
            remove.setOnAction(e -> {
                manager.remove(getTableRow().getItem());
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
            CompressedTransferTask task = getTableRow() == null ? null : getTableRow().getItem();
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
            CompressedTransferTask task = getTableRow() == null ? null : getTableRow().getItem();
            if (task == null) return;
            TransferStatus status = task.getStatus();
            boolean running = status == TransferStatus.RUNNING || status == TransferStatus.WAITING;
            cancel.setDisable(!running || task.isCancelRequested());
            restart.setDisable(running);
            open.setDisable(task.getDirection() != CompressedTransferDirection.DOWNLOAD || status != TransferStatus.COMPLETED);
            box.getChildren().setAll(cancel, restart);
            if (task.getDirection() == CompressedTransferDirection.DOWNLOAD) {
                box.getChildren().add(open);
            }
            box.getChildren().add(remove);
            queueChanged.run();
        }
    }
}

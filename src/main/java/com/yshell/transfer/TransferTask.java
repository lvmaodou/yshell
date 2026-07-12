package com.yshell.transfer;

import javafx.application.Platform;
import javafx.beans.property.*;

import java.nio.file.Path;
import java.util.UUID;

public class TransferTask {
    private final String id = UUID.randomUUID().toString();
    private final TransferDirection direction;
    private final String name;
    private final String remotePath;
    private final Path localPath;
    private final long totalBytes;
    private final String connectionId;

    private final ObjectProperty<TransferStatus> status = new SimpleObjectProperty<>(TransferStatus.WAITING);
    private final LongProperty transferredBytes = new SimpleLongProperty(0);
    private final StringProperty speedText = new SimpleStringProperty("--");
    private final StringProperty message = new SimpleStringProperty("");
    private volatile TransferStatus currentStatus = TransferStatus.WAITING;
    private volatile boolean pauseRequested;
    private volatile boolean cancelRequested;

    public TransferTask(TransferDirection direction, String connectionId, String name, String remotePath, Path localPath, long totalBytes) {
        this.direction = direction;
        this.connectionId = connectionId;
        this.name = name;
        this.remotePath = remotePath;
        this.localPath = localPath;
        this.totalBytes = Math.max(totalBytes, 0L);
    }

    public String getId() {
        return id;
    }

    public TransferDirection getDirection() {
        return direction;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getName() {
        return name;
    }

    public String getRemotePath() {
        return remotePath;
    }

    public Path getLocalPath() {
        return localPath;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public ObjectProperty<TransferStatus> statusProperty() {
        return status;
    }

    public TransferStatus getStatus() {
        return currentStatus;
    }

    public LongProperty transferredBytesProperty() {
        return transferredBytes;
    }

    public long getTransferredBytes() {
        return transferredBytes.get();
    }

    public StringProperty speedTextProperty() {
        return speedText;
    }

    public String getSpeedText() {
        return speedText.get();
    }

    public StringProperty messageProperty() {
        return message;
    }

    public String getMessage() {
        return message.get();
    }

    public double getProgress() {
        if (totalBytes <= 0) {
            return getStatus() == TransferStatus.COMPLETED ? 1.0 : 0.0;
        }
        return Math.min(1.0, getTransferredBytes() / (double) totalBytes);
    }

    public ReadOnlyDoubleProperty progressProperty() {
        ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper();
        progress.bind(transferredBytes.divide(Math.max(totalBytes, 1.0)));
        return progress.getReadOnlyProperty();
    }

    public boolean isPauseRequested() {
        return pauseRequested;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void requestPause() {
        pauseRequested = true;
    }

    public void clearPauseRequest() {
        pauseRequested = false;
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    public void resetForRestart() {
        pauseRequested = false;
        cancelRequested = false;
        setTransferredBytes(0);
        setSpeedText("--");
        setMessage("");
        setStatus(TransferStatus.WAITING);
    }

    public void setStatus(TransferStatus value) {
        currentStatus = value;
        runFx(() -> status.set(value));
    }

    public void setTransferredBytes(long value) {
        runFx(() -> transferredBytes.set(Math.max(value, 0L)));
    }

    public void setSpeedText(String value) {
        runFx(() -> speedText.set(value == null || value.isBlank() ? "--" : value));
    }

    public void setMessage(String value) {
        runFx(() -> message.set(value == null ? "" : value));
    }

    private void runFx(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }
}

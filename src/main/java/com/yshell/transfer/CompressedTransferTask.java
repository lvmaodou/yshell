package com.yshell.transfer;

import javafx.application.Platform;
import javafx.beans.property.*;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public class CompressedTransferTask {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String id = UUID.randomUUID().toString();
    private final String connectionId;
    private final CompressedTransferDirection direction;
    private final String name;
    private final List<Path> localSources;
    private final String remoteSource;
    private final String remoteTargetDirectory;
    private final Path localTargetDirectory;
    private final String createdAt = LocalDateTime.now().format(TIME_FORMAT);

    private final ObjectProperty<CompressedTransferStage> stage = new SimpleObjectProperty<>(CompressedTransferStage.WAITING);
    private final ObjectProperty<TransferStatus> status = new SimpleObjectProperty<>(TransferStatus.WAITING);
    private final StringProperty source = new SimpleStringProperty("");
    private final StringProperty target = new SimpleStringProperty("");
    private final StringProperty sizeText = new SimpleStringProperty("--");
    private final LongProperty totalBytes = new SimpleLongProperty(0);
    private final LongProperty transferredBytes = new SimpleLongProperty(0);
    private final StringProperty speedText = new SimpleStringProperty("--");
    private final StringProperty message = new SimpleStringProperty("");
    private volatile CompressedTransferStage currentStage = CompressedTransferStage.WAITING;
    private volatile TransferStatus currentStatus = TransferStatus.WAITING;
    private volatile boolean cancelRequested;

    public CompressedTransferTask(String connectionId,
                                  CompressedTransferDirection direction,
                                  String name,
                                  List<Path> localSources,
                                  String remoteSource,
                                  String remoteTargetDirectory,
                                  Path localTargetDirectory) {
        this.connectionId = connectionId;
        this.direction = direction;
        this.name = name;
        this.localSources = localSources == null ? List.of() : List.copyOf(localSources);
        this.remoteSource = remoteSource;
        this.remoteTargetDirectory = remoteTargetDirectory;
        this.localTargetDirectory = localTargetDirectory;
        setSource(direction == CompressedTransferDirection.UPLOAD ? summarizeLocalSources(this.localSources) : remoteSource);
        setTarget(direction == CompressedTransferDirection.UPLOAD
                ? remoteTargetDirectory
                : localTargetDirectory == null ? "" : localTargetDirectory.toString());
    }

    public String getId() {
        return id;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public CompressedTransferDirection getDirection() {
        return direction;
    }

    public String getName() {
        return name;
    }

    public List<Path> getLocalSources() {
        return localSources;
    }

    public String getRemoteSource() {
        return remoteSource;
    }

    public String getRemoteTargetDirectory() {
        return remoteTargetDirectory;
    }

    public Path getLocalTargetDirectory() {
        return localTargetDirectory;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public ObjectProperty<CompressedTransferStage> stageProperty() {
        return stage;
    }

    public CompressedTransferStage getStage() {
        return currentStage;
    }

    public ObjectProperty<TransferStatus> statusProperty() {
        return status;
    }

    public TransferStatus getStatus() {
        return currentStatus;
    }

    public StringProperty sourceProperty() {
        return source;
    }

    public String getSource() {
        return source.get();
    }

    public StringProperty targetProperty() {
        return target;
    }

    public String getTarget() {
        return target.get();
    }

    public StringProperty sizeTextProperty() {
        return sizeText;
    }

    public String getSizeText() {
        return sizeText.get();
    }

    public LongProperty totalBytesProperty() {
        return totalBytes;
    }

    public long getTotalBytes() {
        return totalBytes.get();
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

    public double getTransferProgress() {
        long total = getTotalBytes();
        if (total <= 0) {
            return getStage() == CompressedTransferStage.COMPLETED ? 1.0 : 0.0;
        }
        return Math.min(1.0, getTransferredBytes() / (double) total);
    }

    public StringProperty messageProperty() {
        return message;
    }

    public String getMessage() {
        return message.get();
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    public void resetForRestart() {
        cancelRequested = false;
        setMessage("");
        setSizeText("--");
        setTotalBytes(0);
        setTransferredBytes(0);
        setSpeedText("--");
        setStage(CompressedTransferStage.WAITING);
        setStatus(TransferStatus.WAITING);
    }

    public void setStage(CompressedTransferStage value) {
        currentStage = value;
        runFx(() -> stage.set(value));
    }

    public void setStatus(TransferStatus value) {
        currentStatus = value;
        runFx(() -> status.set(value));
    }

    public void setSource(String value) {
        runFx(() -> source.set(value == null ? "" : value));
    }

    public void setTarget(String value) {
        runFx(() -> target.set(value == null ? "" : value));
    }

    public void setSizeText(String value) {
        runFx(() -> sizeText.set(value == null || value.isBlank() ? "--" : value));
    }

    public void setTotalBytes(long value) {
        runFx(() -> totalBytes.set(Math.max(value, 0L)));
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

    private String summarizeLocalSources(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return "";
        if (paths.size() == 1) return paths.get(0).toString();
        return paths.get(0) + " 等 " + paths.size() + " 项";
    }

    private void runFx(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }
}

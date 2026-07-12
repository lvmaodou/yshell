package com.yshell.transfer;

import com.yshell.config.AppSettings;
import com.yshell.service.ConnectionManager;
import com.yshell.service.SshService;
import com.yshell.service.SshService.RemoteFileInfo;
import com.yshell.ui.DialogHelper;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransferManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransferManager.class);
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_ACTIVE_CONNECTIONS = 5;

    private static final TransferManager INSTANCE = new TransferManager();

    private final Map<String, QueueState> queues = new ConcurrentHashMap<>();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FileTransferCoordinator");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService transferExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "FileTransferWorker");
        t.setDaemon(true);
        return t;
    });
    private final Object schedulerLock = new Object();
    private final LinkedHashSet<String> activeConnections = new LinkedHashSet<>();
    private final LinkedHashSet<String> waitingConnections = new LinkedHashSet<>();

    private TransferManager() {
        ConnectionManager.getInstance().addOnConnectionClosedListener(this::clearConnection);
    }

    public static TransferManager getInstance() {
        return INSTANCE;
    }

    public ObservableList<TransferTask> downloads(String connId) {
        return state(connId).downloads;
    }

    public ObservableList<TransferTask> uploads(String connId) {
        return state(connId).uploads;
    }

    public void enqueueDownload(String connId, RemoteFileInfo remote, Path downloadRoot) {
        if (connId == null || remote == null || downloadRoot == null) return;
        coordinator.submit(() -> {
            try {
                SshService service = service(connId);
                Files.createDirectories(downloadRoot);
                try (SftpClient sftp = service.createSftpClient()) {
                    SftpClient.Attributes attrs = sftp.stat(remote.fullPath());
                    if (attrs != null && attrs.isDirectory()) {
                        Path base = resolveDuplicatePath(downloadRoot.resolve(safeName(remote.name())));
                        if (base == null) {
                            return;
                        }
                        List<TransferTask> tasks = new ArrayList<>();
                        collectRemoteFiles(sftp, connId, remote.fullPath(), base, tasks);
                        addTasks(connId, tasks);
                    } else if (attrs != null && attrs.isRegularFile()) {
                        Path local = resolveDuplicatePath(downloadRoot.resolve(safeName(remote.name())));
                        if (local == null) {
                            return;
                        }
                        long size = attrs.getSize() >= 0 ? attrs.getSize() : remote.size();
                        addTask(connId, new TransferTask(TransferDirection.DOWNLOAD, connId, remote.name(), remote.fullPath(), local, size));
                    }
                }
                scheduleConnection(connId);
            } catch (Exception e) {
                LOGGER.error("enqueueDownload failed", e);
            }
        });
    }

    public void clearFinished(String connId) {
        QueueState state = queues.get(connId);
        if (state == null) return;
        state.tasks.removeIf(this::isFinished);
        runFx(() -> {
            state.downloads.removeIf(this::isFinished);
            state.uploads.removeIf(this::isFinished);
        });
    }

    public void enqueueUploadFiles(String connId, List<Path> localPaths, String remoteDirectory, Runnable onCompleted) {
        if (connId == null || localPaths == null || localPaths.isEmpty() || remoteDirectory == null) return;
        coordinator.submit(() -> {
            try {
                List<TransferTask> tasks = new ArrayList<>();
                SshService service = service(connId);
                try (SftpClient sftp = service.createSftpClient()) {
                    for (Path path : localPaths) {
                        if (path == null || !Files.exists(path)) continue;
                        if (Files.isDirectory(path)) {
                            String remoteBase = joinRemote(remoteDirectory, path.getFileName().toString());
                            String resolvedBase = resolveRemoteDuplicatePath(sftp, remoteBase, true);
                            if (resolvedBase != null) {
                                collectLocalFilesToBase(connId, path, resolvedBase, tasks);
                            }
                        } else if (Files.isRegularFile(path)) {
                            String remotePath = resolveRemoteDuplicatePath(sftp,
                                    joinRemote(remoteDirectory, path.getFileName().toString()), false);
                            if (remotePath != null) {
                                tasks.add(new TransferTask(TransferDirection.UPLOAD, connId, path.getFileName().toString(), remotePath, path, Files.size(path)));
                            }
                        }
                    }
                }
                addTasks(connId, tasks);
                notifyWhenUploadBatchCompletes(tasks, onCompleted);
                scheduleConnection(connId);
            } catch (Exception e) {
                LOGGER.error("enqueueUploadFiles failed", e);
            }
        });
    }

    private void notifyWhenUploadBatchCompletes(List<TransferTask> tasks, Runnable onCompleted) {
        if (onCompleted == null || tasks == null || tasks.isEmpty()) return;
        List<TransferTask> uploadTasks = tasks.stream()
                .filter(task -> task.getDirection() == TransferDirection.UPLOAD)
                .toList();
        if (uploadTasks.isEmpty()) return;

        runFxAndWait(() -> {
            Set<String> terminalTasks = new HashSet<>();
            boolean[] hasCompletedTask = {false};
            AtomicBoolean notified = new AtomicBoolean(false);

            ChangeListener<TransferStatus> listener = (obs, oldStatus, newStatus) -> {
                if (newStatus == TransferStatus.COMPLETED) {
                    hasCompletedTask[0] = true;
                }
                if (newStatus == TransferStatus.COMPLETED
                        || newStatus == TransferStatus.FAILED
                        || newStatus == TransferStatus.CANCELED) {
                    uploadTasks.stream()
                            .filter(candidate -> candidate.statusProperty() == obs)
                            .findFirst().ifPresent(task -> terminalTasks.add(task.getId()));
                }
                if (terminalTasks.size() == uploadTasks.size()
                        && hasCompletedTask[0]
                        && notified.compareAndSet(false, true)) {
                    onCompleted.run();
                }
            };

            for (TransferTask task : uploadTasks) {
                task.statusProperty().addListener(listener);
            }
        });
    }

    public void pause(TransferTask task) {
        if (task == null) return;
        task.requestPause();
        if (task.getStatus() == TransferStatus.WAITING) {
            task.setStatus(TransferStatus.PAUSED);
        }
        onConnectionStateChanged(task.getConnectionId());
    }

    public void pauseAll(String connId) {
        if (connId == null) return;
        QueueState state = queues.get(connId);
        if (state == null) return;
        for (TransferTask task : state.all()) {
            pauseTask(task);
        }
        onConnectionStateChanged(connId);
    }

    public void resume(TransferTask task) {
        if (task == null) return;
        if (task.getStatus() == TransferStatus.RUNNING && task.isPauseRequested()) {
            task.clearPauseRequest();
            return;
        }
        if (task.getStatus() == TransferStatus.PAUSED || task.getStatus() == TransferStatus.FAILED || task.getStatus() == TransferStatus.CANCELED) {
            resumeTask(task);
            scheduleConnection(task.getConnectionId());
        }
    }

    public void resumeAll(String connId) {
        if (connId == null) return;
        QueueState state = queues.get(connId);
        if (state == null) return;
        boolean changed = false;
        for (TransferTask task : state.all()) {
            TransferStatus status = task.getStatus();
            if (status == TransferStatus.PAUSED || status == TransferStatus.FAILED || status == TransferStatus.CANCELED) {
                resumeTask(task);
                changed = true;
            } else if (status == TransferStatus.RUNNING && task.isPauseRequested()) {
                task.clearPauseRequest();
            }
        }
        if (changed) {
            scheduleConnection(connId);
        }
    }

    public void restart(TransferTask task) {
        if (task == null) return;
        task.requestCancel();
        coordinator.submit(() -> {
            try {
                if (task.getDirection() == TransferDirection.DOWNLOAD) {
                    Files.deleteIfExists(task.getLocalPath());
                } else {
                    SshService service = service(task.getConnectionId());
                    try (SftpClient sftp = service.createSftpClient()) {
                        try {
                            sftp.remove(task.getRemotePath());
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("restart cleanup failed", e);
            } finally {
                task.resetForRestart();
                scheduleConnection(task.getConnectionId());
            }
        });
    }

    public void remove(TransferTask task) {
        if (task == null) return;
        task.requestCancel();
        coordinator.submit(() -> {
            QueueState state = queues.get(task.getConnectionId());
            if (state != null) {
                if (task.getStatus() != TransferStatus.RUNNING) {
                    state.tasks.remove(task);
                }
                runFx(() -> {
                    state.downloads.remove(task);
                    state.uploads.remove(task);
                });
                onConnectionStateChanged(task.getConnectionId());
            }
        });
    }

    public void clearConnection(String connId) {
        QueueState state = queues.remove(connId);
        if (state == null) return;
        synchronized (schedulerLock) {
            activeConnections.remove(connId);
            waitingConnections.remove(connId);
        }
        for (TransferTask task : state.all()) task.requestCancel();
        state.tasks.clear();
        runFx(() -> {
            state.downloads.clear();
            state.uploads.clear();
        });
        scheduleWaitingConnections();
    }

    public void clearTasks(String connId) {
        QueueState state = queues.get(connId);
        if (state == null) return;
        for (TransferTask task : state.all()) task.requestCancel();
        state.tasks.removeIf(task -> task.getStatus() != TransferStatus.RUNNING);
        runFx(() -> {
            state.downloads.clear();
            state.uploads.clear();
        });
        synchronized (schedulerLock) {
            waitingConnections.remove(connId);
            if (hasRunningTasks(connId)) {
                activeConnections.remove(connId);
            }
        }
        onConnectionStateChanged(connId);
    }

    private void pauseTask(TransferTask task) {
        task.requestPause();
        if (task.getStatus() == TransferStatus.WAITING) {
            task.setStatus(TransferStatus.PAUSED);
        }
    }

    private void resumeTask(TransferTask task) {
        task.clearPauseRequest();
        task.setStatus(TransferStatus.WAITING);
        task.setMessage("");
    }

    private void scheduleConnection(String connId) {
        if (connId == null) return;
        synchronized (schedulerLock) {
            if (!hasWaitingTasks(connId)) {
                waitingConnections.remove(connId);
                return;
            }
            if (activeConnections.contains(connId)) {
                startConnectionPumps(connId);
                return;
            }
            waitingConnections.add(connId);
            scheduleWaitingConnectionsLocked();
        }
    }

    private void scheduleWaitingConnections() {
        synchronized (schedulerLock) {
            scheduleWaitingConnectionsLocked();
        }
    }

    private void scheduleWaitingConnectionsLocked() {
        while (activeConnections.size() < MAX_ACTIVE_CONNECTIONS && !waitingConnections.isEmpty()) {
            String connId = waitingConnections.iterator().next();
            waitingConnections.remove(connId);
            if (!queues.containsKey(connId) || !hasWaitingTasks(connId)) {
                continue;
            }
            activeConnections.add(connId);
            startConnectionPumps(connId);
        }
    }

    private void startConnectionPumps(String connId) {
        QueueState state = queues.get(connId);
        if (state == null) return;
        pump(connId, TransferDirection.DOWNLOAD, state);
        pump(connId, TransferDirection.UPLOAD, state);
    }

    private void pump(String connId, TransferDirection direction, QueueState state) {
        var scheduled = direction == TransferDirection.DOWNLOAD ? state.downloadPumpScheduled : state.uploadPumpScheduled;
        if (!scheduled.compareAndSet(false, true)) return;
        transferExecutor.submit(() -> {
            try {
                TransferTask next;
                while (isActiveConnection(connId) && (next = nextWaitingTask(connId, direction)) != null) {
                    runTask(next);
                }
            } finally {
                scheduled.set(false);
                if (isActiveConnection(connId) && hasWaitingTask(connId, direction)) {
                    pump(connId, direction, state);
                }
                onConnectionStateChanged(connId);
            }
        });
    }

    private TransferTask nextWaitingTask(String connId, TransferDirection direction) {
        QueueState state = queues.get(connId);
        if (state == null) return null;
        List<TransferTask> snapshot = snapshotTasks(state);
        return snapshot.stream()
                .filter(t -> t.getDirection() == direction)
                .filter(t -> t.getStatus() == TransferStatus.WAITING)
                .findFirst()
                .orElse(null);
    }

    private boolean isActiveConnection(String connId) {
        synchronized (schedulerLock) {
            return activeConnections.contains(connId);
        }
    }

    private void onConnectionStateChanged(String connId) {
        if (connId == null) return;
        synchronized (schedulerLock) {
            boolean hasWaiting = hasWaitingTasks(connId);
            if (!hasWaiting) {
                waitingConnections.remove(connId);
            }
            if (!activeConnections.contains(connId)) {
                scheduleWaitingConnectionsLocked();
                return;
            }
            if (hasWaiting) {
                startConnectionPumps(connId);
                return;
            }
            if (hasRunningTasks(connId)) {
                activeConnections.remove(connId);
                waitingConnections.remove(connId);
                scheduleWaitingConnectionsLocked();
            }
        }
    }

    private boolean hasWaitingTasks(String connId) {
        QueueState state = queues.get(connId);
        return state != null && snapshotTasks(state).stream()
                .anyMatch(task -> task.getStatus() == TransferStatus.WAITING);
    }

    private boolean hasWaitingTask(String connId, TransferDirection direction) {
        QueueState state = queues.get(connId);
        return state != null && snapshotTasks(state).stream()
                .anyMatch(task -> task.getDirection() == direction && task.getStatus() == TransferStatus.WAITING);
    }

    private boolean hasRunningTasks(String connId) {
        QueueState state = queues.get(connId);
        return state == null || snapshotTasks(state).stream()
                .noneMatch(task -> task.getStatus() == TransferStatus.RUNNING);
    }

    private void runTask(TransferTask task) {
        task.clearPauseRequest();
        task.setStatus(TransferStatus.RUNNING);
        task.setMessage("");
        try {
            SshService service = service(task.getConnectionId());
            try (SftpClient sftp = service.createSftpClient()) {
                if (task.getDirection() == TransferDirection.DOWNLOAD) {
                    downloadFile(sftp, task);
                } else {
                    uploadFile(sftp, task);
                }
            }
            if (task.isCancelRequested()) {
                task.setStatus(TransferStatus.CANCELED);
            } else if (task.isPauseRequested()) {
                task.setStatus(TransferStatus.PAUSED);
            } else {
                task.setTransferredBytes(task.getTotalBytes());
                task.setSpeedText("--");
                task.setStatus(TransferStatus.COMPLETED);
            }
        } catch (TransferInterruptedException e) {
            task.setSpeedText("--");
            task.setStatus(task.isCancelRequested() ? TransferStatus.CANCELED : TransferStatus.PAUSED);
        } catch (Exception e) {
            LOGGER.error("transfer failed: {}", task.getRemotePath(), e);
            task.setSpeedText("--");
            task.setMessage(e.getMessage());
            task.setStatus(TransferStatus.FAILED);
        }
    }

    private void downloadFile(SftpClient sftp, TransferTask task) throws IOException {
        Files.createDirectories(task.getLocalPath().getParent());
        long offset = Files.exists(task.getLocalPath()) ? Files.size(task.getLocalPath()) : 0L;
        if (task.getTotalBytes() > 0 && offset > task.getTotalBytes()) {
            Files.delete(task.getLocalPath());
            offset = 0L;
        }
        task.setTransferredBytes(offset);

        byte[] buffer = new byte[BUFFER_SIZE];
        long position = offset;
        long speedStartBytes = position;
        long speedStartTime = System.nanoTime();

        try (SftpClient.CloseableHandle handle = sftp.open(task.getRemotePath(), SftpClient.OpenMode.Read);
             RandomAccessFile out = new RandomAccessFile(task.getLocalPath().toFile(), "rw")) {
            out.seek(position);
            while (task.getTotalBytes() <= 0 || position < task.getTotalBytes()) {
                checkInterrupt(task);
                int read = sftp.read(handle, position, buffer, 0, buffer.length);
                if (read < 0) break;
                out.write(buffer, 0, read);
                position += read;
                task.setTransferredBytes(position);
                long[] speedState = updateSpeed(task, position, speedStartBytes, speedStartTime);
                speedStartBytes = speedState[0];
                speedStartTime = speedState[1];
            }
        }
    }

    private void uploadFile(SftpClient sftp, TransferTask task) throws IOException {
        mkdirsRemote(sftp, remoteParent(task.getRemotePath()));
        long offset = 0L;
        try {
            SftpClient.Attributes attrs = sftp.stat(task.getRemotePath());
            offset = attrs == null ? 0L : attrs.getSize();
        } catch (IOException ignored) {
        }
        if (task.getTotalBytes() > 0 && offset > task.getTotalBytes()) {
            sftp.remove(task.getRemotePath());
            offset = 0L;
        }
        task.setTransferredBytes(offset);

        byte[] buffer = new byte[BUFFER_SIZE];
        long position = offset;
        long speedStartBytes = position;
        long speedStartTime = System.nanoTime();

        try (RandomAccessFile in = new RandomAccessFile(task.getLocalPath().toFile(), "r");
             SftpClient.CloseableHandle handle = sftp.open(task.getRemotePath(), SftpClient.OpenMode.Create, SftpClient.OpenMode.Write)) {
            in.seek(position);
            while (position < task.getTotalBytes()) {
                checkInterrupt(task);
                int read = in.read(buffer);
                if (read < 0) break;
                sftp.write(handle, position, buffer, 0, read);
                position += read;
                task.setTransferredBytes(position);
                long[] speedState = updateSpeed(task, position, speedStartBytes, speedStartTime);
                speedStartBytes = speedState[0];
                speedStartTime = speedState[1];
            }
        }
    }

    private long[] updateSpeed(TransferTask task, long position, long speedStartBytes, long speedStartTime) {
        long now = System.nanoTime();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - speedStartTime);
        if (elapsedMs >= 500) {
            double bytesPerSecond = (position - speedStartBytes) * 1000.0 / elapsedMs;
            task.setSpeedText(formatBytes((long) bytesPerSecond) + "/s");
            return new long[]{position, now};
        }
        return new long[]{speedStartBytes, speedStartTime};
    }

    private void checkInterrupt(TransferTask task) {
        if (task.isPauseRequested() || task.isCancelRequested()) {
            throw new TransferInterruptedException();
        }
    }

    private void collectRemoteFiles(SftpClient sftp, String connId, String remoteDirectory, Path localDirectory, List<TransferTask> tasks) throws IOException {
        Files.createDirectories(localDirectory);
        for (SftpClient.DirEntry entry : sftp.readDir(remoteDirectory)) {
            String filename = entry.getFilename();
            if (".".equals(filename) || "..".equals(filename)) continue;
            String childRemote = joinRemote(remoteDirectory, filename);
            Path childLocal = localDirectory.resolve(safeName(filename));
            SftpClient.Attributes attrs = entry.getAttributes();
            if (attrs != null && attrs.isDirectory()) {
                collectRemoteFiles(sftp, connId, childRemote, childLocal, tasks);
            } else if (attrs != null && attrs.isRegularFile()) {
                tasks.add(new TransferTask(TransferDirection.DOWNLOAD, connId, filename, childRemote, childLocal, attrs.getSize()));
            }
        }
    }

    private void collectLocalFilesToBase(String connId, Path directory, String remoteBase, List<TransferTask> tasks) throws IOException {
        try (var stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        try {
                            Path rel = directory.relativize(path);
                            String remotePath = joinRemote(remoteBase, toRemotePath(rel));
                            tasks.add(new TransferTask(TransferDirection.UPLOAD, connId, path.getFileName().toString(), remotePath, path, Files.size(path)));
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    });
        } catch (CompletionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw e;
        }
    }

    private void mkdirsRemote(SftpClient sftp, String remoteDirectory) {
        if (remoteDirectory == null || remoteDirectory.isBlank() || "/".equals(remoteDirectory)) return;
        String normalized = remoteDirectory.startsWith("/") ? remoteDirectory : "/" + remoteDirectory;
        String[] parts = normalized.split("/");
        String current = "";
        for (String part : parts) {
            if (part.isBlank()) continue;
            current = current.isEmpty() ? "/" + part : current + "/" + part;
            try {
                sftp.mkdir(current);
            } catch (IOException ignored) {
            }
        }
    }

    private void addTask(String connId, TransferTask task) {
        addTasks(connId, List.of(task));
    }

    private void addTasks(String connId, List<TransferTask> tasks) {
        if (tasks == null || tasks.isEmpty()) return;
        QueueState state = state(connId);
        state.tasks.addAll(tasks);
        runFxAndWait(() -> {
            for (TransferTask task : tasks) {
                if (task.getDirection() == TransferDirection.DOWNLOAD) {
                    state.downloads.add(task);
                } else {
                    state.uploads.add(task);
                }
            }
        });
    }

    private QueueState state(String connId) {
        return queues.computeIfAbsent(connId, ignored -> new QueueState());
    }

    private SshService service(String connId) throws IOException {
        SshService service = ConnectionManager.getInstance().getConnectionById(connId);
        if (service == null || !service.isConnected()) {
            throw new IOException("连接已断开");
        }
        return service;
    }

    private Path resolveDuplicatePath(Path path) {
        AppSettings.DuplicateStrategy strategy = AppSettings.getInstance().getTransferDuplicateStrategy();
        if (!Files.exists(path)) return path;
        return switch (strategy) {
            case SKIP -> null;
            case RENAME -> uniquePath(path);
            case ASK -> resolveDuplicatePathByPrompt(path);
            case OVERWRITE -> {
                try {
                    if (Files.isDirectory(path)) {
                        yield uniquePath(path);
                    }
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOGGER.warn("delete duplicated local file failed: {}", path, e);
                    yield uniquePath(path);
                }
                yield path;
            }
        };
    }

    private Path resolveDuplicatePathByPrompt(Path path) {
        final int[] choice = {-1};
        runFxAndWait(() -> choice[0] = DialogHelper.showConfirmThree(
                "同名文件",
                "本地已存在同名文件：\n" + path + "\n请选择处理方式。",
                "覆盖",
                "重命名",
                "跳过"));
        if (choice[0] == 0) {
            try {
                if (Files.isDirectory(path)) {
                    return uniquePath(path);
                }
                Files.deleteIfExists(path);
                return path;
            } catch (IOException e) {
                LOGGER.warn("delete duplicated local file failed: {}", path, e);
                return uniquePath(path);
            }
        }
        if (choice[0] == 1) {
            return uniquePath(path);
        }
        return null;
    }

    private String resolveRemoteDuplicatePath(SftpClient sftp, String remotePath, boolean directory) {
        if (remoteExists(sftp, remotePath)) return remotePath;
        AppSettings.DuplicateStrategy strategy = AppSettings.getInstance().getTransferDuplicateStrategy();
        return switch (strategy) {
            case SKIP -> null;
            case RENAME -> uniqueRemotePath(sftp, remotePath);
            case ASK -> resolveRemoteDuplicatePathByPrompt(sftp, remotePath, directory);
            case OVERWRITE -> {
                if (!directory) {
                    try {
                        sftp.remove(remotePath);
                    } catch (IOException e) {
                        LOGGER.warn("delete duplicated remote file failed: {}", remotePath, e);
                        yield uniqueRemotePath(sftp, remotePath);
                    }
                }
                yield remotePath;
            }
        };
    }

    private String resolveRemoteDuplicatePathByPrompt(SftpClient sftp, String remotePath, boolean directory) {
        final int[] choice = {-1};
        runFxAndWait(() -> choice[0] = DialogHelper.showConfirmThree(
                "同名文件",
                "远端已存在同名" + (directory ? "目录" : "文件") + "：\n" + remotePath + "\n请选择处理方式。",
                "覆盖",
                "重命名",
                "跳过"));
        if (choice[0] == 0) {
            if (!directory) {
                try {
                    sftp.remove(remotePath);
                } catch (IOException e) {
                    LOGGER.warn("delete duplicated remote file failed: {}", remotePath, e);
                    return uniqueRemotePath(sftp, remotePath);
                }
            }
            return remotePath;
        }
        if (choice[0] == 1) {
            return uniqueRemotePath(sftp, remotePath);
        }
        return null;
    }

    private boolean remoteExists(SftpClient sftp, String remotePath) {
        try {
            sftp.stat(remotePath);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private String uniqueRemotePath(SftpClient sftp, String remotePath) {
        if (remoteExists(sftp, remotePath)) return remotePath;
        String parent = remoteParent(remotePath);
        String fileName = remoteName(remotePath);
        String stem = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int i = 1; i < 10_000; i++) {
            String candidate = joinRemote(parent, stem + " (" + i + ")" + ext);
            if (remoteExists(sftp, candidate)) return candidate;
        }
        return remotePath;
    }

    private Path uniquePath(Path path) {
        if (!Files.exists(path)) return path;
        Path parent = path.getParent();
        String fileName = path.getFileName().toString();
        String stem = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int i = 1; i < 10_000; i++) {
            Path candidate = parent.resolve(stem + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return path;
    }

    private boolean isFinished(TransferTask task) {
        TransferStatus status = task.getStatus();
        return status == TransferStatus.COMPLETED || status == TransferStatus.FAILED || status == TransferStatus.CANCELED;
    }

    private String remoteParent(String remotePath) {
        int index = remotePath.lastIndexOf('/');
        if (index <= 0) return "/";
        return remotePath.substring(0, index);
    }

    private String remoteName(String remotePath) {
        if (remotePath == null || remotePath.isBlank() || "/".equals(remotePath)) return "unnamed";
        int index = remotePath.lastIndexOf('/');
        return index >= 0 ? remotePath.substring(index + 1) : remotePath;
    }

    private String joinRemote(String parent, String child) {
        String cleanParent = parent == null || parent.isBlank() ? "/" : parent;
        String cleanChild = child == null ? "" : child.replace('\\', '/');
        if (cleanParent.endsWith("/")) {
            return cleanParent + cleanChild;
        }
        return cleanParent + "/" + cleanChild;
    }

    private String toRemotePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String safeName(String name) {
        return name == null || name.isBlank() ? "unnamed" : name;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void runFx(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    private void runFxAndWait(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<TransferTask> snapshotTasks(QueueState state) {
        return state == null ? List.of() : state.all();
    }

    private static class QueueState {
        private final ObservableList<TransferTask> downloads = FXCollections.observableArrayList();
        private final ObservableList<TransferTask> uploads = FXCollections.observableArrayList();
        private final CopyOnWriteArrayList<TransferTask> tasks = new CopyOnWriteArrayList<>();
        private final AtomicBoolean downloadPumpScheduled = new AtomicBoolean(false);
        private final AtomicBoolean uploadPumpScheduled = new AtomicBoolean(false);

        private List<TransferTask> all() {
            return new ArrayList<>(tasks);
        }
    }

    private static class TransferInterruptedException extends RuntimeException {
    }
}

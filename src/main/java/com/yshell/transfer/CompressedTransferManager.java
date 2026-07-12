package com.yshell.transfer;

import com.yshell.service.ConnectionManager;
import com.yshell.service.SshService;
import com.yshell.service.SshService.RemoteFileInfo;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CompressedTransferManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(CompressedTransferManager.class);
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final CompressedTransferManager INSTANCE = new CompressedTransferManager();

    private final Map<String, QueueState> queues = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CompressedTransferWorker");
        t.setDaemon(true);
        return t;
    });

    private CompressedTransferManager() {
        ConnectionManager.getInstance().addOnConnectionClosedListener(this::clearConnection);
    }

    public static CompressedTransferManager getInstance() {
        return INSTANCE;
    }

    public ObservableList<CompressedTransferTask> uploads(String connId) {
        return state(connId).uploads;
    }

    public ObservableList<CompressedTransferTask> downloads(String connId) {
        return state(connId).downloads;
    }

    public int count(String connId) {
        if (connId == null) return 0;
        QueueState state = queues.get(connId);
        return state == null ? 0 : state.uploads.size() + state.downloads.size();
    }

    public CompressedTransferTask enqueueUpload(String connId, List<Path> localPaths, String remoteDirectory) {
        if (connId == null || localPaths == null || localPaths.isEmpty() || remoteDirectory == null) return null;
        List<Path> paths = localPaths.stream().filter(Objects::nonNull).filter(Files::exists).toList();
        if (paths.isEmpty()) return null;
        CompressedTransferTask task = new CompressedTransferTask(
                connId,
                CompressedTransferDirection.UPLOAD,
                taskName(paths.get(0).getFileName() == null ? "文件" : paths.get(0).getFileName().toString(), paths.size()),
                paths,
                null,
                remoteDirectory,
                null);
        addTask(connId, task);
        executor.submit(() -> runUpload(task));
        return task;
    }

    public void enqueueDownload(String connId, String remotePath, Path localDirectory) {
        if (connId == null || remotePath == null || remotePath.isBlank() || localDirectory == null) return;
        CompressedTransferTask task = new CompressedTransferTask(
                connId,
                CompressedTransferDirection.DOWNLOAD,
                "压缩下载 " + remoteName(remotePath),
                null,
                remotePath,
                null,
                localDirectory);
        addTask(connId, task);
        executor.submit(() -> runDownload(task));
    }

    public void cancel(CompressedTransferTask task) {
        if (task == null) return;
        task.requestCancel();
        if (task.getStatus() == TransferStatus.WAITING) {
            task.setStage(CompressedTransferStage.CANCELED);
            task.setStatus(TransferStatus.CANCELED);
        }
    }

    public void restart(CompressedTransferTask task) {
        if (task == null) return;
        TransferStatus status = task.getStatus();
        if (status == TransferStatus.RUNNING || status == TransferStatus.WAITING) return;
        task.resetForRestart();
        executor.submit(() -> {
            if (task.getDirection() == CompressedTransferDirection.UPLOAD) {
                runUpload(task);
            } else {
                runDownload(task);
            }
        });
    }

    public void remove(CompressedTransferTask task) {
        if (task == null) return;
        cancel(task);
        QueueState state = queues.get(task.getConnectionId());
        if (state == null) return;
        runFx(() -> {
            state.uploads.remove(task);
            state.downloads.remove(task);
        });
    }

    public void clearFinished(String connId) {
        if (connId == null) return;
        QueueState state = queues.get(connId);
        if (state == null) return;
        runFx(() -> {
            state.uploads.removeIf(this::isFinished);
            state.downloads.removeIf(this::isFinished);
        });
    }

    private void runUpload(CompressedTransferTask task) {
        Path localArchive = null;
        String remoteArchive = "/tmp/yshell-upload-" + task.getId() + ".tar.gz";
        try {
            SshService service = service(task.getConnectionId());
            start(task);
            setStage(task, CompressedTransferStage.PACKING);
            localArchive = createLocalTarGz(task.getLocalSources());
            long archiveSize = Files.size(localArchive);
            task.setTotalBytes(archiveSize);
            task.setTransferredBytes(0);
            task.setSizeText(formatBytes(archiveSize));
            checkCancel(task);

            setStage(task, CompressedTransferStage.TRANSFERRING);
            uploadLocalFile(service, localArchive, remoteArchive, task);
            checkCancel(task);

            setStage(task, CompressedTransferStage.EXTRACTING);
            service.extractRemoteTarGz(remoteArchive, task.getRemoteTargetDirectory());
            checkCancel(task);

            setStage(task, CompressedTransferStage.CLEANING);
            deleteLocalTemp(localArchive);
            localArchive = null;
            deleteRemoteTemp(service, remoteArchive);
            remoteArchive = null;

            task.setStage(CompressedTransferStage.COMPLETED);
            task.setStatus(TransferStatus.COMPLETED);
        } catch (CompressedTransferCanceledException e) {
            task.setStage(CompressedTransferStage.CANCELED);
            task.setStatus(TransferStatus.CANCELED);
        } catch (Exception e) {
            LOGGER.error("compressed upload failed", e);
            task.setMessage(e.getMessage());
            task.setStage(CompressedTransferStage.FAILED);
            task.setStatus(TransferStatus.FAILED);
        } finally {
            cleanupQuietly(task.getConnectionId(), localArchive, remoteArchive);
        }
    }

    private void runDownload(CompressedTransferTask task) {
        Path localArchive = null;
        String remoteArchive = null;
        try {
            SshService service = service(task.getConnectionId());
            Files.createDirectories(task.getLocalTargetDirectory());
            start(task);

            setStage(task, CompressedTransferStage.PACKING);
            RemoteFileInfo archive = service.createRemoteTarGz(task.getRemoteSource());
            remoteArchive = archive.fullPath();
            task.setTotalBytes(archive.size());
            task.setTransferredBytes(0);
            task.setSizeText(formatBytes(archive.size()));
            checkCancel(task);

            setStage(task, CompressedTransferStage.TRANSFERRING);
            localArchive = Files.createTempFile("yshell-download-", ".tar.gz");
            downloadRemoteFile(service, remoteArchive, localArchive, task);
            checkCancel(task);

            setStage(task, CompressedTransferStage.EXTRACTING);
            extractLocalTarGz(localArchive, task.getLocalTargetDirectory());
            checkCancel(task);

            setStage(task, CompressedTransferStage.CLEANING);
            deleteLocalTemp(localArchive);
            localArchive = null;
            deleteRemoteTemp(service, remoteArchive);
            remoteArchive = null;

            task.setStage(CompressedTransferStage.COMPLETED);
            task.setStatus(TransferStatus.COMPLETED);
        } catch (CompressedTransferCanceledException e) {
            task.setStage(CompressedTransferStage.CANCELED);
            task.setStatus(TransferStatus.CANCELED);
        } catch (Exception e) {
            LOGGER.error("compressed download failed", e);
            task.setMessage(e.getMessage());
            task.setStage(CompressedTransferStage.FAILED);
            task.setStatus(TransferStatus.FAILED);
        } finally {
            cleanupQuietly(task.getConnectionId(), localArchive, remoteArchive);
        }
    }

    private void start(CompressedTransferTask task) {
        task.setMessage("");
        task.setStatus(TransferStatus.RUNNING);
    }

    private void setStage(CompressedTransferTask task, CompressedTransferStage stage) throws CompressedTransferCanceledException {
        checkCancel(task);
        task.setStage(stage);
    }

    private void checkCancel(CompressedTransferTask task) throws CompressedTransferCanceledException {
        if (task.isCancelRequested()) {
            throw new CompressedTransferCanceledException();
        }
    }

    private SshService service(String connId) throws IOException {
        SshService service = ConnectionManager.getInstance().getConnectionById(connId);
        if (service == null || !service.isConnected()) {
            throw new IOException("当前连接不可用");
        }
        return service;
    }

    private void addTask(String connId, CompressedTransferTask task) {
        QueueState state = state(connId);
        runFx(() -> {
            if (task.getDirection() == CompressedTransferDirection.UPLOAD) {
                state.uploads.add(task);
            } else {
                state.downloads.add(task);
            }
        });
    }

    private QueueState state(String connId) {
        return queues.computeIfAbsent(connId, id -> new QueueState());
    }

    private void clearConnection(String connId) {
        QueueState state = queues.remove(connId);
        if (state == null) return;
        for (CompressedTransferTask task : state.uploads) {
            cancel(task);
        }
        for (CompressedTransferTask task : state.downloads) {
            cancel(task);
        }
    }

    private boolean isFinished(CompressedTransferTask task) {
        TransferStatus status = task.getStatus();
        return status == TransferStatus.COMPLETED || status == TransferStatus.FAILED || status == TransferStatus.CANCELED;
    }

    private Path createLocalTarGz(List<Path> paths) throws IOException {
        Path archive = Files.createTempFile("yshell-upload-", ".tar.gz");
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(archive));
             GZIPOutputStream gzipOut = new GZIPOutputStream(fileOut);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Path path : paths) {
                if (path == null || !Files.exists(path)) continue;
                addTarPath(tarOut, path);
            }
            tarOut.finish();
        }
        return archive;
    }

    private void addTarPath(TarArchiveOutputStream tarOut, Path root) throws IOException {
        Path baseParent = root.getParent();
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    addTarEntry(tarOut, iterator.next(), baseParent);
                }
            }
        } else if (Files.isRegularFile(root)) {
            addTarEntry(tarOut, root, baseParent);
        }
    }

    private void addTarEntry(TarArchiveOutputStream tarOut, Path path, Path baseParent) throws IOException {
        String entryName = baseParent == null ? path.getFileName().toString() : baseParent.relativize(path).toString();
        entryName = entryName.replace("\\", "/");
        TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), entryName);
        tarOut.putArchiveEntry(entry);
        if (Files.isRegularFile(path)) {
            Files.copy(path, tarOut);
        }
        tarOut.closeArchiveEntry();
    }

    private void extractLocalTarGz(Path archive, Path targetDirectory) throws IOException {
        Files.createDirectories(targetDirectory);
        Path targetRoot = targetDirectory.toAbsolutePath().normalize();
        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(archive));
             GZIPInputStream gzipIn = new GZIPInputStream(fileIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path target = targetRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new IOException("压缩包包含非法路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(tarIn, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void uploadLocalFile(SshService service, Path localPath, String remotePath, CompressedTransferTask task) throws IOException, CompressedTransferCanceledException {
        try (SftpClient sftp = service.createSftpClient();
             RandomAccessFile in = new RandomAccessFile(localPath.toFile(), "r");
             SftpClient.CloseableHandle handle = sftp.open(remotePath, SftpClient.OpenMode.Create, SftpClient.OpenMode.Write)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long position = 0L;
            long speedStartBytes = 0L;
            long speedStartTime = System.nanoTime();
            int read;
            while ((read = in.read(buffer)) >= 0) {
                checkCancel(task);
                sftp.write(handle, position, buffer, 0, read);
                position += read;
                task.setTransferredBytes(position);
                long[] speedState = updateSpeed(task, position, speedStartBytes, speedStartTime);
                speedStartBytes = speedState[0];
                speedStartTime = speedState[1];
            }
            task.setSpeedText("--");
        }
    }

    private void downloadRemoteFile(SshService service, String remotePath, Path localPath, CompressedTransferTask task) throws IOException, CompressedTransferCanceledException {
        try (SftpClient sftp = service.createSftpClient();
             RandomAccessFile out = new RandomAccessFile(localPath.toFile(), "rw");
             SftpClient.CloseableHandle handle = sftp.open(remotePath, SftpClient.OpenMode.Read)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long position = 0L;
            long speedStartBytes = 0L;
            long speedStartTime = System.nanoTime();
            while (true) {
                checkCancel(task);
                int read = sftp.read(handle, position, buffer, 0, buffer.length);
                if (read < 0) break;
                out.write(buffer, 0, read);
                position += read;
                task.setTransferredBytes(position);
                long[] speedState = updateSpeed(task, position, speedStartBytes, speedStartTime);
                speedStartBytes = speedState[0];
                speedStartTime = speedState[1];
            }
            task.setSpeedText("--");
        }
    }

    private long[] updateSpeed(CompressedTransferTask task, long position, long speedStartBytes, long speedStartTime) {
        long now = System.nanoTime();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - speedStartTime);
        if (elapsedMs >= 500) {
            double bytesPerSecond = (position - speedStartBytes) * 1000.0 / elapsedMs;
            task.setSpeedText(formatBytes((long) bytesPerSecond) + "/s");
            return new long[]{position, now};
        }
        return new long[]{speedStartBytes, speedStartTime};
    }

    private void cleanupQuietly(String connId, Path localArchive, String remoteArchive) {
        deleteLocalTemp(localArchive);
        if (remoteArchive != null && !remoteArchive.isBlank()) {
            try {
                SshService service = service(connId);
                deleteRemoteTemp(service, remoteArchive);
            } catch (Exception e) {
                LOGGER.warn("delete remote temp failed: {}", remoteArchive, e);
            }
        }
    }

    private void deleteLocalTemp(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("delete local temp failed: {}", path, e);
        }
    }

    private void deleteRemoteTemp(SshService service, String path) {
        if (service == null || path == null || path.isBlank()) return;
        try {
            service.deleteRemotePath(path);
        } catch (IOException e) {
            LOGGER.warn("delete remote temp failed: {}", path, e);
        }
    }

    private String taskName(String name, int count) {
        return count <= 1 ? "压缩上传" + " " + name : "压缩上传" + " " + name + " 等 " + count + " 项";
    }

    private String remoteName(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return "/";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
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

    private static class QueueState {
        private final ObservableList<CompressedTransferTask> uploads = FXCollections.observableArrayList();
        private final ObservableList<CompressedTransferTask> downloads = FXCollections.observableArrayList();
    }

    private static class CompressedTransferCanceledException extends Exception {
    }
}

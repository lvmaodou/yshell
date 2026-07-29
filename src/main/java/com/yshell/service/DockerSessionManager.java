package com.yshell.service;

import com.yshell.model.ConnInfo;
import com.yshell.model.docker.DockerSnapshot;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class DockerSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerSessionManager.class);
    private static final DockerSessionManager INSTANCE = new DockerSessionManager();

    private final DockerService dockerService = new DockerService();
    private final Map<String, SshService> sessions = new ConcurrentHashMap<>();
    private final Map<String, DockerSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ExecutorService workerExecutor;
    private final ExecutorService sshExecutor;
    private final Object sessionLock = new Object();

    private DockerSessionManager() {
        this.workerExecutor = Executors.newCachedThreadPool(new NamedDaemonThreadFactory("docker-worker"));
        this.sshExecutor = Executors.newFixedThreadPool(2, new NamedDaemonThreadFactory("docker-ssh"));
    }

    public static DockerSessionManager getInstance() {
        return INSTANCE;
    }

    public DockerSnapshot getCachedSnapshot(String connId) {
        return connId == null ? null : snapshots.get(connId);
    }

    public CompletableFuture<DockerSnapshot> refreshSnapshot(String connId, ConnInfo connInfo) {
        if (connId == null || connInfo == null) {
            return CompletableFuture.completedFuture(DockerSnapshot.empty("Missing connection"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                SshService session = ensureSession(connId, connInfo);
                DockerSnapshot snapshot = dockerService.loadSnapshot(session);
                snapshots.put(connId, snapshot);
                return snapshot;
            } catch (Exception e) {
                LOGGER.warn("refresh docker snapshot failed for {}", connId, e);
                DockerSnapshot fallback = DockerSnapshot.empty(e.getMessage() == null ? "Docker refresh failed" : e.getMessage());
                snapshots.put(connId, fallback);
                return fallback;
            }
        }, workerExecutor);
    }

    public CompletableFuture<DockerService.DockerConfigFile> loadConfigFile(String connId, ConnInfo connInfo) {
        if (connId == null || connInfo == null) {
            return CompletableFuture.completedFuture(new DockerService.DockerConfigFile("/etc/docker/daemon.json", "", "Missing connection"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return dockerService.loadConfigFile(ensureSession(connId, connInfo));
            } catch (Exception e) {
                return new DockerService.DockerConfigFile("/etc/docker/daemon.json", "", e.getMessage() == null ? "Load Docker config failed" : e.getMessage());
            }
        }, workerExecutor);
    }

    public CompletableFuture<SshService.CommandResult> saveConfigFile(String connId, ConnInfo connInfo, String path, String content) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return dockerService.saveConfigFile(ensureSession(connId, connInfo), path, content);
            } catch (Exception e) {
                return new SshService.CommandResult(-1, "", e.getMessage() == null ? "Save Docker config failed" : e.getMessage(), false);
            }
        }, workerExecutor);
    }

    public CompletableFuture<SshService.CommandResult> restartDocker(String connId, ConnInfo connInfo) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return dockerService.restartDocker(ensureSession(connId, connInfo));
            } catch (Exception e) {
                return new SshService.CommandResult(-1, "", e.getMessage() == null ? "Restart Docker failed" : e.getMessage(), false);
            }
        }, workerExecutor);
    }

    public CompletableFuture<SshService.CommandResult> containerRun(String connId, ConnInfo connInfo, String image) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.containerRun(session, image),
                "Run Docker container failed");
    }

    public CompletableFuture<SshService.CommandResult> containerRun(String connId, ConnInfo connInfo,
                                                                    String image, String options, String command) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.containerRun(session, image, options, command),
                "Run Docker container failed");
    }

    public CompletableFuture<SshService.CommandResult> imagePull(String connId, ConnInfo connInfo, String image) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imagePull(session, image),
                "Pull Docker image failed");
    }

    public CompletableFuture<SshService.CommandResult> imageLogin(String connId, ConnInfo connInfo,
                                                                  String registry, String username, String password) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imageLogin(session, registry, username, password),
                "Login Docker registry failed");
    }

    public CompletableFuture<SshService.CommandResult> imageRemove(String connId, ConnInfo connInfo, String image) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imageRemove(session, image),
                "Remove Docker image failed");
    }

    public CompletableFuture<SshService.CommandResult> imageTag(String connId, ConnInfo connInfo, String source, String target) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imageTag(session, source, target),
                "Tag Docker image failed");
    }

    public CompletableFuture<SshService.CommandResult> imageTagAndPush(String connId, ConnInfo connInfo, String source, String target) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imageTagAndPush(session, source, target),
                "Push Docker image failed");
    }

    public CompletableFuture<SshService.CommandResult> imageInspect(String connId, ConnInfo connInfo, String image) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imageInspect(session, image),
                "Inspect Docker image failed");
    }

    public CompletableFuture<SshService.CommandResult> imageHistory(String connId, ConnInfo connInfo, String image) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imageHistory(session, image),
                "Read Docker image history failed");
    }

    public CompletableFuture<SshService.CommandResult> imageSave(String connId, ConnInfo connInfo, String image, String path) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imageSave(session, image, path),
                "Save Docker image failed");
    }

    public CompletableFuture<SshService.CommandResult> imageLoad(String connId, ConnInfo connInfo, String path) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imageLoad(session, path),
                "Load Docker image failed");
    }

    public CompletableFuture<SshService.CommandResult> imageImport(String connId, ConnInfo connInfo,
                                                                   String path, String image) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imageImport(session, path, image),
                "Import Docker image failed");
    }

    public CompletableFuture<SshService.CommandResult> imagePrune(String connId, ConnInfo connInfo, boolean all) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.imagePrune(session, all),
                "Prune Docker images failed");
    }

    public CompletableFuture<SshService.CommandResult> networkInspect(String connId, ConnInfo connInfo, String network) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.networkInspect(session, network),
                "Inspect Docker network failed");
    }

    public CompletableFuture<SshService.CommandResult> networkCreate(String connId,
                                                                     ConnInfo connInfo,
                                                                     String name,
                                                                     String driver,
                                                                     String subnet,
                                                                     String gateway,
                                                                     boolean internal) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.networkCreate(session, name, driver, subnet, gateway, internal),
                "Create Docker network failed");
    }

    public CompletableFuture<SshService.CommandResult> networkRemove(String connId, ConnInfo connInfo, String network) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.networkRemove(session, network),
                "Remove Docker network failed");
    }

    public CompletableFuture<SshService.CommandResult> networkConnect(String connId,
                                                                      ConnInfo connInfo,
                                                                      String network,
                                                                      String container) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.networkConnect(session, network, container),
                "Connect Docker network failed");
    }

    public CompletableFuture<SshService.CommandResult> networkDisconnect(String connId,
                                                                         ConnInfo connInfo,
                                                                         String network,
                                                                         String container) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.networkDisconnect(session, network, container),
                "Disconnect Docker network failed");
    }

    public CompletableFuture<SshService.CommandResult> volumeInspect(String connId, ConnInfo connInfo, String volume) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.volumeInspect(session, volume),
                "Inspect Docker volume failed");
    }

    public CompletableFuture<SshService.CommandResult> volumeContainers(String connId, ConnInfo connInfo, String volume) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.volumeContainers(session, volume),
                "Read Docker volume containers failed");
    }

    public CompletableFuture<SshService.CommandResult> volumeSize(String connId, ConnInfo connInfo, String volume) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.volumeSize(session, volume),
                "Read Docker volume size failed");
    }

    public CompletableFuture<SshService.CommandResult> volumeCreate(String connId, ConnInfo connInfo, String name) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.volumeCreate(session, name),
                "Create Docker volume failed");
    }

    public CompletableFuture<SshService.CommandResult> volumeRemove(String connId, ConnInfo connInfo, String volume) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.volumeRemove(session, volume),
                "Remove Docker volume failed");
    }

    public CompletableFuture<SshService.CommandResult> volumePrune(String connId, ConnInfo connInfo) {
        return runDockerCommand(connId, connInfo,
                dockerService::volumePrune,
                "Prune Docker volumes failed");
    }

    public CompletableFuture<SshService.RemoteCommandHandle> followContainerLogs(String connId,
                                                                                 ConnInfo connInfo,
                                                                                 String id,
                                                                                 Consumer<String> stdoutConsumer,
                                                                                 Consumer<String> stderrConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return dockerService.followContainerLogs(
                        ensureSession(connId, connInfo),
                        id,
                        stdoutConsumer,
                        stderrConsumer
                );
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, workerExecutor);
    }

    public CompletableFuture<SshService.CommandResult> containerInspect(String connId, ConnInfo connInfo, String id) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.containerInspect(session, id),
                "Inspect Docker container failed");
    }

    public CompletableFuture<SshService.CommandResult> containerStats(String connId, ConnInfo connInfo, String id) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.containerStats(session, id),
                "Read Docker container stats failed");
    }

    public CompletableFuture<SshService.CommandResult> containerTop(String connId, ConnInfo connInfo, String id) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.containerTop(session, id),
                "Read Docker container processes failed");
    }

    public CompletableFuture<SshService.CommandResult> containerDiff(String connId, ConnInfo connInfo, String id) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.containerDiff(session, id),
                "Read Docker container changes failed");
    }

    public CompletableFuture<SshService.CommandResult> containerRename(String connId, ConnInfo connInfo, String id, String name) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.containerRename(session, id, name),
                "Rename Docker container failed");
    }

    public CompletableFuture<SshService.CommandResult> containerCopy(String connId, ConnInfo connInfo, String source, String target) {
        return runDockerCommand(connId, connInfo,
                session -> dockerService.containerCopy(session, source, target),
                "Copy Docker container file failed");
    }

    public CompletableFuture<DockerBatchResult> containerBatchAction(String connId, ConnInfo connInfo, String action, List<String> ids) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> targets = ids == null ? List.of() : ids.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            if (targets.isEmpty()) {
                return new DockerBatchResult(0, 0, List.of());
            }
            try {
                SshService session = ensureSession(connId, connInfo);
                List<String> failures = new ArrayList<>();
                int succeeded = 0;
                for (String id : targets) {
                    SshService.CommandResult result = dockerService.containerAction(session, action, id);
                    if (result.isSuccess()) {
                        succeeded++;
                    } else {
                        failures.add(id + ": " + firstNonBlank(result.stderr(), firstNonBlank(result.stdout(), "执行失败")));
                    }
                }
                return new DockerBatchResult(targets.size(), succeeded, failures);
            } catch (Exception e) {
                return new DockerBatchResult(targets.size(), 0,
                        List.of(e.getMessage() == null ? "Docker batch operation failed" : e.getMessage()));
            }
        }, workerExecutor);
    }

    public void closeSession(String connId) {
        if (connId == null) {
            return;
        }
        synchronized (sessionLock) {
            SshService service = sessions.remove(connId);
            if (service != null) {
                try {
                    service.disconnect();
                } catch (Exception e) {
                    LOGGER.debug("close docker ssh session failed for {}", connId, e);
                }
            }
        }
    }

    public void clear(String connId) {
        closeSession(connId);
        if (connId != null) {
            snapshots.remove(connId);
        }
    }

    private CompletableFuture<SshService.CommandResult> runDockerCommand(String connId,
                                                                         ConnInfo connInfo,
                                                                         DockerCommand command,
                                                                         String fallbackMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return command.execute(ensureSession(connId, connInfo));
            } catch (Exception e) {
                return new SshService.CommandResult(-1, "",
                        e.getMessage() == null ? fallbackMessage : e.getMessage(), false);
            }
        }, workerExecutor);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private SshService ensureSession(String connId, ConnInfo connInfo) throws IOException {
        synchronized (sessionLock) {
            SshService existing = sessions.get(connId);
            if (existing != null && existing.isConnected()) {
                return existing;
            }
            if (existing != null) {
                sessions.remove(connId);
                try {
                    existing.disconnect();
                } catch (Exception ignored) {
                }
            }

            AtomicBoolean connected = new AtomicBoolean(false);
            AtomicReference<String> error = new AtomicReference<>();
            SshService session = new SshService(connInfo, new SshService.ConnectionCallback() {
                @Override
                public void onConnected() {
                    connected.set(true);
                }

                @Override
                public void onConnectionFailed(String errorMessage) {
                    error.set(errorMessage);
                }

                @Override
                public void onDisconnected() {
                }

                @Override
                public void onOutputReceived(String output) {
                }

                @Override
                public void onSystemInfoReceived(com.yshell.model.SystemInfo info) {
                }
            }, sshExecutor);

            session.connect();
            if (!connected.get() || !session.isConnected()) {
                String message = error.get();
                throw new IOException(message == null || message.isBlank() ? "Failed to open Docker SSH session" : message);
            }
            sessions.put(connId, session);
            return session;
        }
    }

    public record DockerBatchResult(int total, int succeeded, List<String> failures) {
        public boolean isSuccess() {
            return failures == null || failures.isEmpty();
        }
    }

    @FunctionalInterface
    private interface DockerCommand {
        SshService.CommandResult execute(SshService session);
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}

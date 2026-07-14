package com.yshell.service;

import com.yshell.model.ConnInfo;
import com.yshell.model.docker.DockerSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    public CompletableFuture<SshService> openSession(String connId, ConnInfo connInfo) {
        if (connId == null || connInfo == null) {
            return CompletableFuture.failedFuture(new IOException("Missing connection"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ensureSession(connId, connInfo);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, workerExecutor);
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

    public void shutdown() {
        sessions.keySet().forEach(this::closeSession);
        workerExecutor.shutdownNow();
        sshExecutor.shutdownNow();
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

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}

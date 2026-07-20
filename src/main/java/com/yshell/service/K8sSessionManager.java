package com.yshell.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yshell.model.ConnInfo;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class K8sSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(K8sSessionManager.class);
    private static final K8sSessionManager INSTANCE = new K8sSessionManager();

    private final K8sService k8sService = new K8sService();
    private final Map<String, SshService> sessions = new ConcurrentHashMap<>();
    private final Map<String, K8sSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ExecutorService workerExecutor;
    private final ExecutorService sshExecutor;
    private final Object sessionLock = new Object();

    private K8sSessionManager() {
        workerExecutor = Executors.newCachedThreadPool(new NamedDaemonThreadFactory("k8s-worker"));
        sshExecutor = Executors.newFixedThreadPool(2, new NamedDaemonThreadFactory("k8s-ssh"));
    }

    public static K8sSessionManager getInstance() {
        return INSTANCE;
    }

    public K8sSnapshot getCachedSnapshot(String connId) {
        return connId == null ? null : snapshots.get(connId);
    }

    public CompletableFuture<K8sSnapshot> refreshSnapshot(String connId, ConnInfo connInfo) {
        if (connId == null || connInfo == null) {
            return CompletableFuture.completedFuture(K8sSnapshot.empty("Missing connection"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                SshService session = ensureSession(connId, connInfo);
                K8sSnapshot snapshot = k8sService.loadSnapshot(session);
                snapshots.put(connId, snapshot);
                return snapshot;
            } catch (Exception e) {
                LOGGER.error("refresh kubernetes snapshot failed for {}", connId, e);
                K8sSnapshot fallback = K8sSnapshot.empty(
                        e.getMessage() == null ? "Kubernetes refresh failed" : e.getMessage());
                snapshots.put(connId, fallback);
                return fallback;
            }
        }, workerExecutor);
    }

    public CompletableFuture<ResourceListResult> listResources(String connId,
                                                               ConnInfo connInfo,
                                                               String kubectlType,
                                                               boolean namespaced,
                                                               String namespace) {
        if (connId == null || connInfo == null || kubectlType == null || kubectlType.isBlank()) {
            return CompletableFuture.completedFuture(ResourceListResult.failed("Missing connection or resource type"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return k8sService.listResources(ensureSession(connId, connInfo), kubectlType, namespaced, namespace);
            } catch (Exception e) {
                return ResourceListResult.failed(e.getMessage() == null ? "Load resources failed" : e.getMessage());
            }
        }, workerExecutor);
    }

    public CompletableFuture<Map<String, PodUsage>> podMetrics(String connId,
                                                               ConnInfo connInfo,
                                                               String namespace) {
        if (connId == null || connInfo == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return k8sService.podMetrics(ensureSession(connId, connInfo), namespace);
            } catch (Exception e) {
                LOGGER.debug("load kubernetes pod metrics failed for {}", connId, e);
                return Map.of();
            }
        }, workerExecutor);
    }

    public CompletableFuture<SshService.CommandResult> getYaml(String connId,
                                                               ConnInfo connInfo,
                                                               String kubectlType,
                                                               String namespace,
                                                               String name,
                                                               boolean namespaced) {
        return runKubectl(connId, connInfo,
                session -> k8sService.getYaml(session, kubectlType, namespace, name, namespaced),
                "Get Kubernetes resource YAML failed");
    }

    public CompletableFuture<SshService.CommandResult> getJson(String connId,
                                                               ConnInfo connInfo,
                                                               String kubectlType,
                                                               String namespace,
                                                               String name,
                                                               boolean namespaced) {
        return runKubectl(connId, connInfo,
                session -> k8sService.getJson(session, kubectlType, namespace, name, namespaced),
                "Get Kubernetes resource JSON failed");
    }

    public CompletableFuture<SshService.CommandResult> describe(String connId,
                                                                ConnInfo connInfo,
                                                                String kubectlType,
                                                                String namespace,
                                                                String name,
                                                                boolean namespaced) {
        return runKubectl(connId, connInfo,
                session -> k8sService.describe(session, kubectlType, namespace, name, namespaced),
                "Describe Kubernetes resource failed");
    }

    public CompletableFuture<SshService.CommandResult> events(String connId,
                                                              ConnInfo connInfo,
                                                              String namespace,
                                                              String name,
                                                              boolean namespaced) {
        return runKubectl(connId, connInfo,
                session -> k8sService.events(session, namespace, name, namespaced),
                "Get Kubernetes events failed");
    }

    public CompletableFuture<SshService.RemoteCommandHandle> followLogs(String connId,
                                                                        ConnInfo connInfo,
                                                                        String kubectlType,
                                                                        String namespace,
                                                                        String name,
                                                                        boolean namespaced,
                                                                        Consumer<String> stdoutConsumer,
                                                                        Consumer<String> stderrConsumer) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return k8sService.followLogs(
                        ensureSession(connId, connInfo),
                        kubectlType,
                        namespace,
                        name,
                        namespaced,
                        stdoutConsumer,
                        stderrConsumer
                );
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, workerExecutor);
    }

    public CompletableFuture<SshService.CommandResult> delete(String connId,
                                                              ConnInfo connInfo,
                                                              String kubectlType,
                                                              String namespace,
                                                              String name,
                                                              boolean namespaced) {
        return runKubectl(connId, connInfo,
                session -> k8sService.delete(session, kubectlType, namespace, name, namespaced),
                "Delete Kubernetes resource failed");
    }

    public CompletableFuture<SshService.CommandResult> scale(String connId,
                                                             ConnInfo connInfo,
                                                             String kubectlType,
                                                             String namespace,
                                                             String name,
                                                             boolean namespaced,
                                                             int replicas) {
        return runKubectl(connId, connInfo,
                session -> k8sService.scale(session, kubectlType, namespace, name, namespaced, replicas),
                "Scale Kubernetes resource failed");
    }

    public CompletableFuture<SshService.CommandResult> rolloutRestart(String connId,
                                                                      ConnInfo connInfo,
                                                                      String kubectlType,
                                                                      String namespace,
                                                                      String name,
                                                                      boolean namespaced) {
        return runKubectl(connId, connInfo,
                session -> k8sService.rolloutRestart(session, kubectlType, namespace, name, namespaced),
                "Restart Kubernetes workload failed");
    }

    public CompletableFuture<SshService.CommandResult> triggerCronJob(String connId,
                                                                      ConnInfo connInfo,
                                                                      String namespace,
                                                                      String name) {
        return runKubectl(connId, connInfo,
                session -> k8sService.triggerCronJob(session, namespace, name),
                "Trigger Kubernetes CronJob failed");
    }

    public CompletableFuture<SshService.CommandResult> applyYaml(String connId,
                                                                 ConnInfo connInfo,
                                                                 String yaml) {
        return runKubectl(connId, connInfo,
                session -> k8sService.applyYaml(session, yaml),
                "Apply Kubernetes YAML failed");
    }

    private CompletableFuture<SshService.CommandResult> runKubectl(String connId,
                                                                   ConnInfo connInfo,
                                                                   K8sCommand command,
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
                    LOGGER.debug("close k8s ssh session failed for {}", connId, e);
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
                throw new IOException(message == null || message.isBlank()
                        ? "Failed to open Kubernetes SSH session"
                        : message);
            }
            sessions.put(connId, session);
            return session;
        }
    }

    public record K8sSnapshot(
            Instant capturedAt,
            boolean kubectlAvailable,
            String errorMessage,
            String clientVersion,
            String serverVersion,
            List<String> namespaces
    ) {
        public static K8sSnapshot empty(String errorMessage) {
            return new K8sSnapshot(Instant.now(), false, errorMessage, "", "", List.of());
        }
    }

    public record ResourceListResult(
            boolean success,
            String errorMessage,
            List<JsonNode> items,
            String rawJson
    ) {
        public static ResourceListResult failed(String errorMessage) {
            return new ResourceListResult(false, errorMessage, List.of(), "");
        }
    }

    public record PodUsage(String cpu, String memory) {
    }

    @FunctionalInterface
    private interface K8sCommand {
        SshService.CommandResult execute(SshService session);
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(@NotNull Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}

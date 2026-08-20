package com.yshell.service;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.yshell.model.ConnInfo;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.helpers.AbstractConnectionService;

import java.lang.management.ManagementFactory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integration probe for the current {@link SshService#executeRemoteCommand(String, Duration)} lifecycle.
 *
 * <p>Required environment variables: {@code YSHELL_PROBE_HOST}, {@code YSHELL_PROBE_USER}, and
 * {@code YSHELL_PROBE_PASSWORD}. Optional variables: {@code YSHELL_PROBE_PORT} (default {@code 22}),
 * {@code YSHELL_PROBE_COMMAND} (default {@code true}), and {@code YSHELL_PROBE_ITERATIONS} (default {@code 10}).</p>
 *
 * <p>Run with:
 * {@code mvn -DskipTests test-compile exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=com.yshell.service.ChannelExecLifecycleProbe}</p>
 */
public final class ChannelExecLifecycleProbe {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration UNREGISTER_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_ITERATIONS = 10;
    private static final boolean REFLECTIVE_CLEANUP = Boolean.getBoolean("yshell.probe.reflectiveCleanup");

    private ChannelExecLifecycleProbe() {
    }

    public static void main(String[] args) throws Exception {
        ProbeConfig config = ProbeConfig.fromEnvironment();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<String> connectionFailure = new AtomicReference<>();
        SshService sshService = new SshService(config.connection(), new ProbeCallback(connectionFailure), executor);

        try {
            sshService.connect();
            if (!sshService.isConnected()) {
                throw new IllegalStateException("SSH connection failed: " + connectionFailure.get());
            }
            if (sshService.isExecAvailable()) {
                throw new IllegalStateException("SSH exec channel is unavailable for the probe connection");
            }

            ClientSession session = extractClientSession(sshService);
            ReferenceQueue<ChannelExec> collectedChannels = new ReferenceQueue<>();
            List<WeakReference<ChannelExec>> observedChannels = new CopyOnWriteArrayList<>();
            ChannelListener listener = new ChannelListener() {
                @Override
                public void channelInitialized(Channel channel) {
                    if (channel instanceof ChannelExec exec) {
                        observedChannels.add(new WeakReference<>(exec, collectedChannels));
                    }
                }
            };
            session.addChannelListener(listener);

            List<ProbeResult> results = new ArrayList<>();
            try {
                for (int iteration = 1; iteration <= config.iterations(); iteration++) {
                    int previousObservedCount = observedChannels.size();
                    SshService.CommandResult commandResult = sshService.executeRemoteCommand(
                            config.command(),
                            COMMAND_TIMEOUT
                    );
                    if (!commandResult.isSuccess()) {
                        throw new IllegalStateException(
                                "Probe command failed at iteration " + iteration + ": " + commandResult
                        );
                    }

                    WeakReference<ChannelExec> execReference = awaitObservedChannel(
                            observedChannels,
                            previousObservedCount,
                            UNREGISTER_TIMEOUT
                    );
                    ChannelState channelState = awaitReleased(session, execReference, UNREGISTER_TIMEOUT);
                    ChannelExec exec = execReference.get();
                    if (exec == null) {
                        throw new AssertionError("ChannelExec was collected before reflective cleanup");
                    }
                    int removedListeners = REFLECTIVE_CLEANUP
                            ? removeKexListenersFor(session, exec)
                            : 0;
                    results.add(new ProbeResult(iteration, channelState, removedListeners));
                }
            } finally {
                session.removeChannelListener(listener);
            }

            long stillRegistered = results.stream().filter(result -> result.channelState().registered()).count();
            long notFullyClosed = results.stream().filter(result -> !result.channelState().closed()).count();
            if (stillRegistered > 0 || notFullyClosed > 0) {
                throw new AssertionError(
                        "ChannelExec did not complete its release lifecycle; registered=" + stillRegistered
                                + ", notFullyClosed=" + notFullyClosed + ", failures=" + results
                );
            }

            int liveAfterGc = countLiveAfterExplicitGc(observedChannels, collectedChannels);
            System.out.printf(
                    "ChannelExec lifecycle probe complete: iterations=%d, registeredAfterClose=%d, "
                            + "notFullyClosed=%d, liveAfterGc=%d%n",
                    config.iterations(),
                    stillRegistered,
                    notFullyClosed,
                    liveAfterGc
            );
            for (ProbeResult result : results) {
                System.out.printf(
                        "iteration=%d removedKexListeners=%d %s%n",
                        result.iteration(),
                        result.removedListeners(),
                        result.channelState()
                );
            }

            if (liveAfterGc > 0) {
                dumpHeapIfConfigured();
                throw new AssertionError(
                        "ChannelExec remains strongly reachable after explicit GC. "
                                + "Inspect Path to GC Roots for ChannelExec."
                );
            }
        } finally {
            sshService.disconnect();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static WeakReference<ChannelExec> awaitObservedChannel(
            List<WeakReference<ChannelExec>> observedChannels,
            int previousObservedCount,
            Duration timeout
    ) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (observedChannels.size() > previousObservedCount) {
                return observedChannels.get(previousObservedCount);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("ChannelListener did not observe the ChannelExec created by executeRemoteCommand");
    }

    private static ChannelState awaitReleased(
            ClientSession session,
            WeakReference<ChannelExec> execReference,
            Duration timeout
    ) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        ChannelState lastState = snapshotChannelState(session, execReference);
        while ((lastState.registered() || !lastState.closed()) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(20);
            lastState = snapshotChannelState(session, execReference);
        }
        return lastState;
    }

    private static ChannelState snapshotChannelState(ClientSession session, WeakReference<ChannelExec> execReference) {
        ChannelExec exec = execReference.get();
        if (exec == null) {
            return new ChannelState(false, false, false, true);
        }

        ConnectionService connectionService = session.getService(ConnectionService.class);
        if (!(connectionService instanceof AbstractConnectionService service)) {
            throw new IllegalStateException(
                    "Unexpected ConnectionService implementation: "
                            + (connectionService == null ? "null" : connectionService.getClass().getName())
            );
        }

        boolean registered = service.getChannels().stream().anyMatch(channel -> channel == exec);
        return new ChannelState(registered, exec.isClosing(), exec.isClosed(), false);
    }

    private static int removeKexListenersFor(ClientSession session, ChannelExec target) throws IllegalAccessException {
        Object kexFilter = findObjectBySimpleName(
                session,
                "KexFilter",
                16,
                Collections.newSetFromMap(new IdentityHashMap<>())
        );
        if (kexFilter == null) {
            throw new IllegalStateException("Could not find SSHD KexFilter from ClientSession");
        }

        Field listenersField = findField(kexFilter.getClass(), "listeners");
        if (listenersField == null) {
            throw new IllegalStateException("Could not find KexFilter.listeners field on " + kexFilter.getClass());
        }
        listenersField.setAccessible(true);
        Object listenersValue = listenersField.get(kexFilter);
        if (!(listenersValue instanceof Collection<?> listeners)) {
            throw new IllegalStateException(
                    "Unexpected KexFilter.listeners type: "
                            + (listenersValue == null ? "null" : listenersValue.getClass())
            );
        }

        int removed = 0;
        for (Object listener : new ArrayList<>(listeners)) {
            if (capturesTarget(listener, target) && listeners.remove(listener)) {
                removed++;
            }
        }
        if (removed != 1) {
            throw new AssertionError(
                    "Expected to remove exactly one KexListener for " + target + ", removed=" + removed
            );
        }
        return removed;
    }

    private static boolean capturesTarget(Object listener, ChannelExec target) throws IllegalAccessException {
        if (listener == null || !listener.getClass().getName().contains("AbstractChannel")) {
            return false;
        }
        for (Field field : listener.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                if (field.get(listener) == target) {
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    private static Object findObjectBySimpleName(
            Object root,
            String simpleName,
            int remainingDepth,
            java.util.Set<Object> visited
    ) throws IllegalAccessException {
        if (root == null || remainingDepth < 0 || !visited.add(root)) {
            return null;
        }
        if (root.getClass().getSimpleName().equals(simpleName)) {
            return root;
        }

        if (root instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                Object found = findObjectBySimpleName(value, simpleName, remainingDepth - 1, visited);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (root instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                Object found = findObjectBySimpleName(value, simpleName, remainingDepth - 1, visited);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (root.getClass().isArray()) {
            int length = Array.getLength(root);
            for (int index = 0; index < length; index++) {
                Object found = findObjectBySimpleName(
                        Array.get(root, index),
                        simpleName,
                        remainingDepth - 1,
                        visited
                );
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!root.getClass().getName().startsWith("org.apache.sshd.")) {
            return null;
        }

        for (Class<?> type = root.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(root);
                    Object found = findObjectBySimpleName(value, simpleName, remainingDepth - 1, visited);
                    if (found != null) {
                        return found;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static int countLiveAfterExplicitGc(
            List<WeakReference<ChannelExec>> observedChannels,
            ReferenceQueue<ChannelExec> collectedChannels
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 10 && hasLiveReferences(observedChannels); attempt++) {
            System.gc();
            Thread.sleep(200);
        }

        int collected = 0;
        while (collectedChannels.poll() != null) {
            collected++;
        }

        int live = 0;
        for (WeakReference<ChannelExec> reference : observedChannels) {
            if (reference.get() != null) {
                live++;
            }
        }
        System.out.printf("Observed=%d, weaklyCollected=%d, stillLive=%d%n",
                observedChannels.size(), collected, live);
        return live;
    }

    private static boolean hasLiveReferences(List<WeakReference<ChannelExec>> observedChannels) {
        return observedChannels.stream().anyMatch(reference -> reference.get() != null);
    }

    private static void dumpHeapIfConfigured() throws Exception {
        String configuredPath = "C:\\Users\\tdx\\Desktop\\heap.hprof";

        Path heapDump = Path.of(configuredPath).toAbsolutePath();
        if (Files.exists(heapDump)) {
            throw new IllegalStateException("Heap dump path already exists: " + heapDump);
        }
        Path parent = heapDump.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        HotSpotDiagnosticMXBean diagnostic = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        if (diagnostic == null) {
            throw new IllegalStateException("HotSpotDiagnosticMXBean is unavailable");
        }

        System.err.println("Writing live heap dump before SshService.disconnect(): " + heapDump);
        diagnostic.dumpHeap(heapDump.toString(), true);
    }

    private static ClientSession extractClientSession(SshService sshService) throws ReflectiveOperationException {
        Field field = SshService.class.getDeclaredField("clientSession");
        field.setAccessible(true);
        ClientSession session = (ClientSession) field.get(sshService);
        if (session == null) {
            throw new IllegalStateException("SshService connected without a ClientSession");
        }
        return session;
    }

    private record ChannelState(boolean registered, boolean closing, boolean closed, boolean collectedEarly) {
    }

    private record ProbeResult(int iteration, ChannelState channelState, int removedListeners) {
    }

    private record ProbeConfig(ConnInfo connection, String command, int iterations) {
        private static ProbeConfig fromEnvironment() {
            ConnInfo connection = new ConnInfo("ssh");
            connection.setHost("192.168.145.128");
            connection.setPort(22);
            connection.setUserName("test");
            connection.setPassword("123456");
            connection.setAuthenticationType(1);
            connection.setExecChannelEnable(true);
            return new ProbeConfig(
                    connection,
                    environmentOrDefault("YSHELL_PROBE_COMMAND", "true"),
                    integerEnvironment("YSHELL_PROBE_ITERATIONS", DEFAULT_ITERATIONS)
            );
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required environment variable: " + name);
            }
            return value;
        }

        private static String environmentOrDefault(String name, String defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? defaultValue : value;
        }

        private static int integerEnvironment(String name, int defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) {
                    throw new IllegalArgumentException(name + " must be positive: " + value);
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid integer environment variable " + name + ": " + value, e);
            }
        }
    }

    private static final class ProbeCallback implements SshService.ConnectionCallback {
        private final AtomicReference<String> connectionFailure;

        private ProbeCallback(AtomicReference<String> connectionFailure) {
            this.connectionFailure = connectionFailure;
        }

        @Override
        public void onConnected() {
        }

        @Override
        public void onConnectionFailed(String error) {
            connectionFailure.set(error);
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
    }
}

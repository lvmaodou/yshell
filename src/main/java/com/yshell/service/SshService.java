package com.yshell.service;

import com.yshell.model.*;
import javafx.application.Platform;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.proxy.ProxyData;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.DynamicPortForwardingTracker;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.compression.Compression;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.util.io.input.NoCloseInputStream;
import org.apache.sshd.common.util.io.output.NoCloseOutputStream;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.nio.channels.Channel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * SSH 服务（基于 Apache MINA SSHD 3.0.0-M4）。
 *
 * <p>两套执行模型：</p>
 * <ul>
 *   <li>一次性命令（executeCommandSync / executeCommand）：用 exec channel，捕获 stdout/stderr</li>
 *   <li>持久 shell（openShell / writeToShell）：用 shell channel（带 PTY），交给 JediTermFxTerminal 双向读写</li>
 * </ul>
 */
public class SshService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SshService.class);
    private static final int MAX_SFTP_CHANNELS = 2;
    private static final int MAX_BACKGROUND_SHELL_CHANNELS = 1;
    private static final int MAX_POLLING_EXEC_CHANNELS = 4;
    private static final int WEAK_NETWORK_MAX_SFTP_CHANNELS = 1;
    private static final int WEAK_NETWORK_MAX_POLLING_EXEC_CHANNELS = 2;
    private static final long HIGH_POLL_INTERVAL_MS = 500;
    private static final long MID_POLL_INTERVAL_MS = 1000;
    private static final long SLOW_POLL_INTERVAL_MS = 10000;
    private static final long WEAK_NETWORK_HIGH_POLL_INTERVAL_MS = 2000;
    private static final long WEAK_NETWORK_MID_POLL_INTERVAL_MS = 3000;
    private static final long WEAK_NETWORK_SLOW_POLL_INTERVAL_MS = 20000;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration WEAK_NETWORK_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration WEAK_NETWORK_AUTH_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration WEAK_NETWORK_COMMAND_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DEFAULT_SHELL_OPEN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WEAK_NETWORK_SHELL_OPEN_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_BACKGROUND_SHELL_OPEN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration WEAK_NETWORK_BACKGROUND_SHELL_OPEN_TIMEOUT = Duration.ofSeconds(15);

    private final ConnInfo connInfo;
    private final ConnectionCallback callback;
    private SshClient sshClient;
    private ClientSession clientSession;     // 已认证的会话
    private String osType = "linux";
    private volatile boolean execAvailable = true;
    private final Map<String, NetworkSnapshot> lastNetworkSnapshots = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Semaphore execChannelSemaphore;
    private final Semaphore sftpSemaphore;
    private final Semaphore backgroundShellSemaphore;
    private final Object pollingLock = new Object();
    private final List<ScheduledFuture<?>> pollingTasks = new ArrayList<>();
    private final List<Channel> portForwardingTrackers = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService commandPoller;
    private volatile String latestCpuCount;
    private volatile String latestCoresPerCpu;
    private volatile String latestTotalThreads;
    private volatile Integer latestProcessTotal;
    private volatile Integer latestProcessRunning;

    // 持久 shell 会话（带 PTY）
    private ChannelShell activeShell;
    private InputStream shellInput;
    private OutputStream shellOutput;
    private final Object shellLock = new Object();
    private volatile boolean shellActive;

    public interface ConnectionCallback {
        void onConnected();

        void onConnectionFailed(String error);

        void onDisconnected();

        void onOutputReceived(String output);

        void onSystemInfoReceived(SystemInfo info);
    }

    public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }

    public static final class RemoteCommandHandle implements AutoCloseable {
        private final CompletableFuture<CommandResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile ChannelExec channel;
        private volatile Future<?> task;

        public CompletableFuture<CommandResult> completion() {
            return completion;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            ChannelExec current = channel;
            if (current != null) {
                try {
                    current.close(true);
                } catch (Exception ignored) {
                }
            }
            Future<?> currentTask = task;
            if (currentTask != null) {
                currentTask.cancel(true);
            }
            completion.complete(new CommandResult(130, "", "", false));
        }

        @Override
        public void close() {
            cancel();
        }
    }

    /**
     * 持久 shell 会话（带 PTY）的回调。
     * 一次连接、持续双向通信，对应终端的"实时交互"。
     */
    public interface ShellCallback {
        /**
         * shell 就绪，把 InputStream/OutputStream 给调用方
         */
        void onShellReady(InputStream in, OutputStream out);

        /**
         * 远端关闭
         */
        void onShellClosed();

        /**
         * 出错
         */
        void onShellError(String error);
    }

    public SshService(ConnInfo connInfo, ConnectionCallback callback, ExecutorService executor) {
        this.connInfo = connInfo;
        this.callback = callback;
        this.executor = executor;
        this.execChannelSemaphore = new Semaphore(maxPollingExecChannels(), true);
        this.sftpSemaphore = new Semaphore(maxSftpChannels(), true);
        this.backgroundShellSemaphore = new Semaphore(MAX_BACKGROUND_SHELL_CHANNELS, true);
    }

    public ConnInfo getConnInfo() {
        return connInfo;
    }

    private boolean weakNetworkMode() {
        return connInfo != null && connInfo.isAccelerate();
    }

    private int maxSftpChannels() {
        return weakNetworkMode() ? WEAK_NETWORK_MAX_SFTP_CHANNELS : MAX_SFTP_CHANNELS;
    }

    private int maxPollingExecChannels() {
        return weakNetworkMode() ? WEAK_NETWORK_MAX_POLLING_EXEC_CHANNELS : MAX_POLLING_EXEC_CHANNELS;
    }

    private Duration connectTimeout() {
        return weakNetworkMode() ? WEAK_NETWORK_CONNECT_TIMEOUT : DEFAULT_CONNECT_TIMEOUT;
    }

    private Duration authTimeout() {
        return weakNetworkMode() ? WEAK_NETWORK_AUTH_TIMEOUT : DEFAULT_AUTH_TIMEOUT;
    }

    private Duration defaultCommandTimeout() {
        return weakNetworkMode() ? WEAK_NETWORK_COMMAND_TIMEOUT : DEFAULT_COMMAND_TIMEOUT;
    }

    private Duration shellOpenTimeout() {
        return weakNetworkMode() ? WEAK_NETWORK_SHELL_OPEN_TIMEOUT : DEFAULT_SHELL_OPEN_TIMEOUT;
    }

    private Duration backgroundShellOpenTimeout() {
        return weakNetworkMode() ? WEAK_NETWORK_BACKGROUND_SHELL_OPEN_TIMEOUT : DEFAULT_BACKGROUND_SHELL_OPEN_TIMEOUT;
    }

    private long highPollIntervalMillis() {
        return weakNetworkMode() ? WEAK_NETWORK_HIGH_POLL_INTERVAL_MS : HIGH_POLL_INTERVAL_MS;
    }

    private long midPollIntervalMillis() {
        return weakNetworkMode() ? WEAK_NETWORK_MID_POLL_INTERVAL_MS : MID_POLL_INTERVAL_MS;
    }

    private long slowPollIntervalMillis() {
        return weakNetworkMode() ? WEAK_NETWORK_SLOW_POLL_INTERVAL_MS : SLOW_POLL_INTERVAL_MS;
    }

    private void applyWeakNetworkOptions(SshClient client) {
        if (!weakNetworkMode()) {
            return;
        }
        List<NamedFactory<Compression>> compressionFactories = new ArrayList<>();
        compressionFactories.add(BuiltinCompressions.delayedZlib);
        compressionFactories.add(BuiltinCompressions.zlib);
        compressionFactories.add(BuiltinCompressions.none);
        client.setCompressionFactories(compressionFactories);
    }

    private void configureAuthentication(SshClient client) {
        int authType = connInfo.getAuthenticationType();
        if (authType == 2) {
            client.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
        } else if (authType == 3) {
            UserInteraction interaction = passwordBackedUserInteraction();
            client.setUserInteraction(interaction);
            client.setUserAuthFactories(List.of(UserAuthKeyboardInteractiveFactory.INSTANCE));
        } else {
            client.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE, UserAuthKeyboardInteractiveFactory.INSTANCE));
        }
    }

    private UserInteraction passwordBackedUserInteraction() {
        return new UserInteraction() {
            @Override
            public boolean isInteractionAllowed(ClientSession session) {
                return true;
            }

            @Override
            public String[] interactive(ClientSession session, String name, String instruction, String lang,
                                        String[] prompt, boolean[] echo) {
                String password = connInfo.getPassword() != null ? connInfo.getPassword() : "";
                String[] answers = new String[prompt != null ? prompt.length : 0];
                Arrays.fill(answers, password);
                return answers;
            }

            @Override
            public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
                return connInfo.getPassword();
            }

            @Override
            public String resolveAuthPasswordAttempt(ClientSession session) {
                return connInfo.getPassword();
            }

            @Override
            public PasswordAuthentication getProxyCredentials(ClientSession session, InetSocketAddress proxy) {
                ProxyInfo proxyInfo = selectedProxy();
                if (proxyInfo == null || isBlank(proxyInfo.getUsername())) {
                    return null;
                }
                char[] password = proxyInfo.getPassword() != null ? proxyInfo.getPassword().toCharArray() : new char[0];
                return new PasswordAuthentication(proxyInfo.getUsername(), password);
            }
        };
    }

    private void configureProxy(SshClient client) {
        ProxyInfo proxyInfo = selectedProxy();
        if (proxyInfo == null) {
            return;
        }
        client.setProxyDataFactory(target -> {
            java.net.Proxy.Type type = "http".equalsIgnoreCase(proxyInfo.getType())
                    ? java.net.Proxy.Type.HTTP
                    : java.net.Proxy.Type.SOCKS;
            InetSocketAddress address = new InetSocketAddress(proxyInfo.getHost(), proxyInfo.getPort());
            String user = isBlank(proxyInfo.getUsername()) ? null : proxyInfo.getUsername();
            char[] password = proxyInfo.getPassword() != null ? proxyInfo.getPassword().toCharArray() : null;
            return new ProxyData(new java.net.Proxy(type, address), user, password);
        });
    }

    private ProxyInfo selectedProxy() {
        String proxyId = connInfo.getProxyId();
        if (isBlank(proxyId) || "0".equals(proxyId)) {
            return null;
        }
        return ProxyRepository.getInstance().load().stream()
                .filter(proxy -> proxyId.equals(proxy.getId()))
                .filter(proxy -> !isBlank(proxy.getHost()) && proxy.getPort() > 0)
                .findFirst()
                .orElse(null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void startConfiguredPortForwardings() throws IOException {
        List<Object> forwardingList = connInfo.getPortForwardingList();
        if (forwardingList == null || forwardingList.isEmpty()) {
            return;
        }
        for (Object raw : forwardingList) {
            TunnelInfo tunnel = toTunnelInfo(raw);
            if (tunnel == null || tunnel.getListenPort() <= 0) {
                continue;
            }
            String bindIp = !isBlank(tunnel.getBindIp()) ? tunnel.getBindIp() : "127.0.0.1";
            SshdSocketAddress bindAddress = new SshdSocketAddress(bindIp, tunnel.getListenPort());
            switch (tunnel.getType()) {
                case "remote" -> {
                    requireTunnelTarget(tunnel);
                    SshdSocketAddress localTarget = new SshdSocketAddress(tunnel.getTargetHost(), tunnel.getTargetPort());
                    ExplicitPortForwardingTracker tracker = clientSession.createRemotePortForwardingTracker(bindAddress, localTarget);
                    portForwardingTrackers.add(tracker);
                    LOGGER.info("Started remote port forwarding: {} -> {}", bindAddress, localTarget);
                }
                case "dynamic" -> {
                    DynamicPortForwardingTracker tracker = clientSession.createDynamicPortForwardingTracker(bindAddress);
                    portForwardingTrackers.add(tracker);
                    LOGGER.info("Started dynamic SOCKS forwarding: {}", bindAddress);
                }
                default -> {
                    requireTunnelTarget(tunnel);
                    SshdSocketAddress remoteTarget = new SshdSocketAddress(tunnel.getTargetHost(), tunnel.getTargetPort());
                    ExplicitPortForwardingTracker tracker = clientSession.createLocalPortForwardingTracker(bindAddress, remoteTarget);
                    portForwardingTrackers.add(tracker);
                    LOGGER.info("Started local port forwarding: {} -> {}", bindAddress, remoteTarget);
                }
            }
        }
    }

    private void closePortForwardings() {
        for (Channel tracker : new ArrayList<>(portForwardingTrackers)) {
            try {
                tracker.close();
            } catch (IOException e) {
                LOGGER.warn("close port forwarding failed", e);
            }
        }
        portForwardingTrackers.clear();
    }

    private void requireTunnelTarget(TunnelInfo tunnel) throws IOException {
        if (isBlank(tunnel.getTargetHost()) || tunnel.getTargetPort() <= 0) {
            throw new IOException("Invalid tunnel target: " + tunnel.getName());
        }
    }

    private TunnelInfo toTunnelInfo(Object raw) {
        if (raw instanceof TunnelInfo tunnel) {
            return tunnel;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        TunnelInfo tunnel = new TunnelInfo();
        Object id = map.get("id");
        Object name = map.get("name");
        Object type = map.get("type");
        Object listenPort = map.get("listenPort");
        Object bindIp = map.get("bindIp");
        Object targetHost = map.get("targetHost");
        Object targetPort = map.get("targetPort");
        if (id != null) tunnel.setId(String.valueOf(id));
        if (name != null) tunnel.setName(String.valueOf(name));
        if (type != null) tunnel.setType(String.valueOf(type));
        if (listenPort instanceof Number number) tunnel.setListenPort(number.intValue());
        if (bindIp != null) tunnel.setBindIp(String.valueOf(bindIp));
        if (targetHost != null) tunnel.setTargetHost(String.valueOf(targetHost));
        if (targetPort instanceof Number number) tunnel.setTargetPort(number.intValue());
        return tunnel;
    }

    private SftpClient createLimitedSftpClient() throws IOException {
        acquireSftpPermit();
        boolean created = false;
        SftpClient sftp = null;
        try {
            synchronized (shellLock) {
                if (!isConnected()) {
                    throw new IOException("Not connected");
                }
                sftp = SftpClientFactory.instance().createSftpClient(clientSession);
            }
            SftpClient limited = limitedSftpClient(sftp);
            created = true;
            return limited;
        } finally {
            if (!created) {
                if (sftp != null) {
                    try {
                        sftp.close();
                    } catch (IOException e) {
                        LOGGER.warn("close limited SFTP delegate failed", e);
                    }
                }
                sftpSemaphore.release();
            }
        }
    }

    private void acquireSftpPermit() throws IOException {
        try {
            sftpSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for SFTP channel", e);
        }
    }

    private SftpClient limitedSftpClient(SftpClient delegate) {
        AtomicBoolean released = new AtomicBoolean(false);
        return (SftpClient) Proxy.newProxyInstance(
                SftpClient.class.getClassLoader(),
                new Class<?>[]{SftpClient.class},
                (proxy, method, args) -> invokeLimitedSftp(delegate, released, method, args)
        );
    }

    private Object invokeLimitedSftp(SftpClient delegate, AtomicBoolean released, Method method, Object[] args) throws Throwable {
        if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
            try {
                return invokeSftp(delegate, method, args);
            } finally {
                if (released.compareAndSet(false, true)) {
                    sftpSemaphore.release();
                }
            }
        }
        return invokeSftp(delegate, method, args);
    }

    private Object invokeSftp(SftpClient delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ============================================================
    //  连接 / 认证
    // ============================================================
    public void connect() {
        SshClient client = null;
        ClientSession session = null;
        try {
            client = SshClient.setUpDefaultClient();
            client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
            applyWeakNetworkOptions(client);
            configureProxy(client);
            configureAuthentication(client);

            client.start();

            int port = connInfo.getPort() == 0 ? 22 : connInfo.getPort();
            ConnectFuture cf = client.connect(connInfo.getUserName(), connInfo.getHost(), port);
            session = cf.verify(connectTimeout()).getClientSession();
            if (session == null) {
                throw new IOException("连接失败：未获取到 ClientSession");
            }

            if (connInfo.getAuthenticationType() == 1) {
                session.addPasswordIdentity(connInfo.getPassword());
            } else if (connInfo.getAuthenticationType() == 2) {
                SshKeyService.ResolvedKey resolvedKey = SshKeyService.getInstance().resolve(connInfo.getSecretKeyId());
                String keyPath = resolvedKey.privateKeyPath();
                if (keyPath == null || keyPath.isEmpty()) {
                    throw new IOException("未指定私钥文件路径");
                }
                FileKeyPairProvider provider = new FileKeyPairProvider(Paths.get(keyPath));
                if (resolvedKey.passphrase() != null && !resolvedKey.passphrase().isBlank()) {
                    provider.setPasswordFinder(org.apache.sshd.common.config.keys.FilePasswordProvider.of(resolvedKey.passphrase()));
                }
                Iterable<KeyPair> keys = provider.loadKeys(session);
                boolean any = false;
                for (KeyPair kp : keys) {
                    session.addPublicKeyIdentity(kp);
                    any = true;
                }
                if (!any) {
                    throw new IOException("未能在 " + keyPath + " 中找到任何密钥");
                }
            } else if (connInfo.getAuthenticationType() == 3) {
                session.setUserInteraction(passwordBackedUserInteraction());
            }
            AuthFuture af = session.auth().verify(authTimeout());
            if (!af.isSuccess()) {
                throw new IOException("认证失败");
            }

            this.sshClient = client;
            this.clientSession = session;

            this.execAvailable = connInfo.isExecChannelEnable() && probeExecAvailable();
            if (execAvailable) {
                detectOsType();
            } else {
                LOGGER.warn("exec channel unavailable for {}@{}; system monitoring disabled",
                        connInfo.getUserName(), connInfo.getHost());
            }
            startConfiguredPortForwardings();
            callback.onConnected();

        } catch (Exception e) {
            LOGGER.error("connect 失败", e);
            callback.onConnectionFailed(e.getMessage());
            if (session != null) {
                try {
                    session.close();
                } catch (IOException ex) {
                    LOGGER.warn("关闭 session 失败", ex);
                }
            }
            if (client != null) {
                client.stop();
            }
        }
    }

    public void disconnect() {
        stopSystemInfoPolling();
        closePortForwardings();
        closeShell();
        synchronized (shellLock) {
            if (clientSession != null) {
                try {
                    clientSession.close();
                } catch (IOException e) {
                    LOGGER.warn("关闭 clientSession 失败", e);
                }
                clientSession = null;
            }
            if (sshClient != null) {
                sshClient.stop();
                sshClient = null;
            }
            lastNetworkSnapshots.clear();
        }
        callback.onDisconnected();
    }

    public boolean isConnected() {
        synchronized (shellLock) {
            return clientSession != null && clientSession.isOpen();
        }
    }

    public boolean isExecAvailable() {
        return execAvailable;
    }

    public SftpClient createSftpClient() throws IOException {
        return createLimitedSftpClient();
    }

    // ============================================================
    //  持久 Shell 会话（带 PTY）
    // ============================================================

    /**
     * 打开一个分配了 PTY 的交互式 shell。
     * 成功后会调用 callback.onShellReady(in, out)，
     * 之后就可以双向收发字节了。
     * <p>
     * 本方法是可重入的：如果已经有打开的 shell，则直接把已有的流交给
     * 调用方，而不会重复打开新 shell。这确保在多个终端/面板之间切换时，
     * 同一个 SshService 只有一个 shell 会话，避免远程服务器出现多余进程。
     */
    public void openShell(ShellCallback cb) {
        executor.submit(() -> {
            synchronized (shellLock) {
                if (!isConnected()) {
                    cb.onShellError("未连接");
                    return;
                }
                if (shellActive && shellInput != null && shellOutput != null) {
                    // shell 已经打开：直接复用已有流（可重入）
                    cb.onShellReady(shellInput, shellOutput);
                    return;
                }
                try {
                    // PTY 配置：80x24，xterm
                    PtyChannelConfiguration ptyConfig = new PtyChannelConfiguration();
                    ptyConfig.setPtyType("xterm-256color");
                    ptyConfig.setPtyColumns(80);
                    ptyConfig.setPtyLines(24);
                    ptyConfig.setPtyWidth(640);
                    ptyConfig.setPtyHeight(384);
                    ptyConfig.setPtyModes(Collections.emptyMap());

                    // 环境变量
                    Map<String, String> env = new HashMap<>();
                    env.put("TERM", "xterm-256color");

                    // 打开 shell channel
                    ChannelShell shell = clientSession.createShellChannel(ptyConfig, env);
                    shell.open().verify(shellOpenTimeout());

                    // 用 NoClose 流：避免外部 close 把 channel 整个关掉
                    InputStream in = new NoCloseInputStream(shell.getInvertedOut());
                    OutputStream out = new NoCloseOutputStream(shell.getInvertedIn());

                    activeShell = shell;
                    shellInput = in;
                    shellOutput = out;
                    shellActive = true;

                    // 把 InputStream 交给调用方（终端），由终端的 reader 线程负责读取
                    cb.onShellReady(shellInput, shellOutput);
                } catch (Exception e) {
                    LOGGER.error("openShell 失败", e);
                    cb.onShellError(e.getMessage());
                }
            }
        });
    }

    /**
     * 写入字节到远端 shell（用户键盘输入）。
     */
    public void writeToShell(byte[] data) {
        synchronized (shellLock) {
            if (!shellActive || shellOutput == null) return;
            try {
                shellOutput.write(data);
                shellOutput.flush();
            } catch (IOException e) {
                LOGGER.warn("writeToShell 失败", e);
                closeShell();
            }
        }
    }

    /**
     * 主动调整 PTY 大小（终端 resize 时调用）。
     */
    public void resizeShell(int cols, int rows) {
        synchronized (shellLock) {
            if (activeShell == null) return;
            try {
                activeShell.sendWindowChange(cols, rows, cols * 8, rows * 16);
            } catch (Exception e) {
                LOGGER.warn("resizeShell 失败", e);
            }
        }
    }

    public boolean isShellOpen() {
        synchronized (shellLock) {
            return shellActive && activeShell != null;
        }
    }

    public void closeShell() {
        synchronized (shellLock) {
            if (activeShell != null) {
                try {
                    activeShell.close();
                } catch (IOException e) {
                    LOGGER.warn("关闭 activeShell 失败", e);
                }
            }
            // shell 关闭时不需要 close clientSession 整个会话
            activeShell = null;
            shellInput = null;
            shellOutput = null;
            shellActive = false;
        }
    }

    private boolean probeExecAvailable() {
        String marker = "YSHELL_EXEC_OK";
        String output = executeCommandOnExecutor("printf " + marker, defaultCommandTimeout());
        return marker.equals(output.trim());
    }

    private void detectOsType() {
        String osRelease = executeCommandOnExecutor();
        String lower = osRelease.toLowerCase();
        if (lower.contains("alpine")) {
            this.osType = "alpine";
        } else if (lower.contains("busybox")) {
            this.osType = "busybox";
        } else if (lower.contains("debian") || lower.contains("ubuntu")) {
            this.osType = "debian";
        } else if (lower.contains("centos") || lower.contains("red hat") ||
                lower.contains("fedora") || lower.contains("rocky")) {
            this.osType = "rhel";
        } else {
            this.osType = "linux";
        }
    }

    private String getCommand(String commandType) {
        return switch (commandType) {
            case "distro" ->
                    "cat /etc/os-release 2>/dev/null | grep PRETTY_NAME | cut -d'\"' -f2 || cat /etc/redhat-release 2>/dev/null || uname -s";
            case "cpu_model" -> switch (osType) {
                case "alpine", "busybox" ->
                        "cat /proc/cpuinfo | grep 'model name' | head -1 | sed 's/model name\\s*: //' || cat /proc/cpuinfo | grep 'Processor' | head -1 | sed 's/Processor\\s*: //'";
                default -> "cat /proc/cpuinfo | grep 'model name' | head -1 | sed 's/model name\\s*: //'";
            };
            case "cpu_percent" -> switch (osType) {
                case "alpine", "busybox" ->
                        "top -bn1 | head -3 | grep 'CPU' | awk '{print $2}' | cut -d'%' -f1 || mpstat 1 1 | grep 'all' | awk '{print 100-$12}'";
                default -> "top -bn1 | head -5 | grep 'Cpu(s)' | awk '{print $2}' | cut -d'%' -f1";
            };
            case "mem_info" -> "free -h";
            case "mem_info_m" -> "free -m";
            case "process_list" -> "ps aux --sort=-%mem | head -6 | tail -5";
            case "top_processes" -> "ps aux --no-header --sort=-%cpu | head -5";
            case "disk_info" -> "df -h 2>/dev/null || df -k";
            case "users" ->
                    "(getent passwd 2>/dev/null || cat /etc/passwd 2>/dev/null) | awk -F: 'BEGIN { while ((\"getent group 2>/dev/null || cat /etc/group 2>/dev/null\") | getline line) { split(line, g, \":\"); groups[g[3]]=g[1] } } { print $3, $1, $4, (groups[$4] ? groups[$4] : \"\") }'";
            case "network" ->
                    "hostname -I 2>/dev/null | awk '{print $1}' || ip addr show | grep 'inet ' | grep -v '127.0.0.1' | head -1 | awk '{print $2}' | cut -d'/' -f1";
            case "network_interfaces" ->
                    "ip link show | grep -E '^[0-9]+:' | awk '{print $2}' | sed 's/:$//' | grep -v lo || ls /sys/class/net/ | grep -v lo";
            case "network_stats" -> "cat /proc/net/dev";
            case "ping" -> "ping -c 1 -W 1 8.8.8.8 | grep 'time=' | awk -F'time=' '{print $2}' | awk '{print $1}'";
            default -> "";
        };
    }

    private static class NetworkSnapshot {
        long rxBytes;
        long txBytes;
        long timestamp;
    }

    public void resetNetworkSnapshot(String nic) {
        if (nic == null || nic.isBlank()) {
            lastNetworkSnapshots.clear();
            return;
        }
        lastNetworkSnapshots.remove(nic);
        lastNetworkSnapshots.remove(normalizeNetworkInterfaceName(nic));
    }

    private void updateNetworkSpeed(SystemInfo info, String nic, String output) {
        if (output.isEmpty()) return;

        String normalizedNic = normalizeNetworkInterfaceName(nic);
        String statsLine = findNetworkStatsLine(output, normalizedNic);
        if (statsLine == null) return;

        int colonIndex = statsLine.indexOf(':');
        if (colonIndex < 0 || colonIndex == statsLine.length() - 1) return;

        String[] parts = statsLine.substring(colonIndex + 1).trim().split("\\s+");
        if (parts.length < 16) return;

        try {
            long rxBytes = Long.parseLong(parts[0]);
            long txBytes = Long.parseLong(parts[8]);
            long now = System.currentTimeMillis();

            NetworkSnapshot last = lastNetworkSnapshots.get(normalizedNic);
            if (last != null) {
                long timeDiff = now - last.timestamp;
                long rxDiff = rxBytes - last.rxBytes;
                long txDiff = txBytes - last.txBytes;
                if (timeDiff > 0 && rxDiff >= 0 && txDiff >= 0) {
                    double rxSpeed = rxDiff * 1000.0 / timeDiff / 1024.0;
                    double txSpeed = txDiff * 1000.0 / timeDiff / 1024.0;
                    info.downloadSpeed = String.format("%.1f KB/s", rxSpeed);
                    info.uploadSpeed = String.format("%.1f KB/s", txSpeed);

                    Platform.runLater(() -> {
                        var leftPanelController = ConnectionManager.getInstance().getLeftPanelController();
                        if (leftPanelController != null) {
                            leftPanelController.updateNetworkChart(txSpeed, rxSpeed);
                        }
                    });
                }
            }

            NetworkSnapshot current = new NetworkSnapshot();
            current.rxBytes = rxBytes;
            current.txBytes = txBytes;
            current.timestamp = now;
            lastNetworkSnapshots.put(normalizedNic, current);
        } catch (Exception e) {
            LOGGER.warn("计算网速失败", e);
        }
    }

    private String findNetworkStatsLine(String output, String normalizedNic) {
        if (normalizedNic == null || normalizedNic.isBlank()) {
            return null;
        }
        for (String line : output.split("\\R")) {
            int colonIndex = line.indexOf(':');
            if (colonIndex < 0) {
                continue;
            }
            String iface = normalizeNetworkInterfaceName(line.substring(0, colonIndex).trim());
            if (normalizedNic.equals(iface)) {
                return line;
            }
        }
        return null;
    }

    private static String normalizeNetworkInterfaceName(String nic) {
        if (nic == null) {
            return "";
        }
        String normalized = nic.trim();
        int atIndex = normalized.indexOf('@');
        if (atIndex >= 0) {
            normalized = normalized.substring(0, atIndex);
        }
        return normalized;
    }

    public void startSystemInfoPolling() {
        if (!execAvailable) {
            return;
        }
        synchronized (pollingLock) {
            stopSystemInfoPollingLocked();
            latestCpuCount = null;
            latestCoresPerCpu = null;
            latestTotalThreads = null;
            latestProcessTotal = null;
            latestProcessRunning = null;
            commandPoller = Executors.newScheduledThreadPool(maxPollingExecChannels(), r -> {
                Thread t = new Thread(r, "SystemInfoCommandPoller-" + connInfo.getHost());
                t.setDaemon(true);
                return t;
            });
            scheduleInitialPollingTasks();
            scheduleHighPollingTasks();
            scheduleMidPollingTasks();
            scheduleSlowPollingTasks();
        }
    }

    public void stopSystemInfoPolling() {
        synchronized (pollingLock) {
            stopSystemInfoPollingLocked();
        }
    }

    private void stopSystemInfoPollingLocked() {
        for (ScheduledFuture<?> task : pollingTasks) {
            task.cancel(false);
        }
        pollingTasks.clear();
        if (commandPoller != null) {
            commandPoller.shutdown();
            commandPoller = null;
        }
    }

    private void scheduleInitialPollingTasks() {
        scheduleOneShotPollingTask("distro", this::pollDistro);
        scheduleOneShotPollingTask("kernel", this::pollKernel);
        scheduleOneShotPollingTask("cpu_model", this::pollCpuModel);
    }

    private void scheduleHighPollingTasks() {
        long interval = highPollIntervalMillis();
        scheduleFixedDelayPollingTask("cpu_percent", interval, this::pollCpuPercent);
        scheduleFixedDelayPollingTask("mem_info", interval, this::pollMemory);
        scheduleFixedDelayPollingTask("cpu_count", interval, this::pollCpuCount);
        scheduleFixedDelayPollingTask("cores_per_cpu", interval, this::pollCoresPerCpu);
        scheduleFixedDelayPollingTask("total_threads", interval, this::pollTotalThreads);
        scheduleFixedDelayPollingTask("process_total", interval, this::pollProcessTotal);
        scheduleFixedDelayPollingTask("process_running", interval, this::pollProcessRunning);
        scheduleFixedDelayPollingTask("top_processes", interval, this::pollTopProcesses);
        scheduleFixedDelayPollingTask("ping", interval, this::pollPing);
        scheduleFixedDelayPollingTask("network_speed", interval, this::pollNetworkSpeed);
    }

    private void scheduleMidPollingTasks() {
        scheduleFixedDelayPollingTask("system_time", midPollIntervalMillis(), this::pollSystemTime);
    }

    private void scheduleSlowPollingTasks() {
        long interval = slowPollIntervalMillis();
        scheduleFixedDelayPollingTask("network_interfaces", interval, this::pollNetworkInterfaces);
        scheduleFixedDelayPollingTask("disk_info", interval, this::pollDiskInfo);
        scheduleFixedDelayPollingTask("users", interval, this::pollUsers);
        scheduleFixedDelayPollingTask("uptime", interval, this::pollUptime);
    }

    private void scheduleOneShotPollingTask(String name, Runnable task) {
        if (commandPoller == null) {
            return;
        }
        pollingTasks.add(commandPoller.schedule(() -> runPollingTask(name, task), 0, TimeUnit.MILLISECONDS));
    }

    private void scheduleFixedDelayPollingTask(String name, long intervalMs, Runnable task) {
        if (commandPoller == null) {
            return;
        }
        pollingTasks.add(commandPoller.scheduleWithFixedDelay(
                () -> runPollingTask(name, task),
                0,
                intervalMs,
                TimeUnit.MILLISECONDS
        ));
    }

    private void runPollingTask(String name, Runnable task) {
        if (!execAvailable || !isConnected()) {
            return;
        }
        try {
            task.run();
        } catch (Exception e) {
            LOGGER.warn("system info polling task failed: {}", name, e);
        }
    }

    private void publishSystemInfo(Consumer<SystemInfo> updater) {
        SystemInfo info = new SystemInfo();
        updater.accept(info);
        callback.onSystemInfoReceived(info);
    }

    private void pollDistro() {
        String output = trimToNull(executeCommandSync(getCommand("distro")));
        if (output != null) {
            publishSystemInfo(info -> info.distro = output.replace("\"", "").trim());
        }
    }

    private void pollKernel() {
        String output = trimToNull(executeCommandSync("uname -r"));
        if (output != null) {
            publishSystemInfo(info -> info.kernel = output);
        }
    }

    private void pollCpuModel() {
        String output = trimToNull(executeCommandSync(getCommand("cpu_model")));
        if (output != null) {
            publishSystemInfo(info -> info.cpuModel = output);
        }
    }

    private void pollSystemTime() {
        String output = trimToNull(executeCommandSync("date '+%Y/%m/%d %H:%M:%S'"));
        if (output != null) {
            publishSystemInfo(info -> info.systemTime = output);
        }
    }

    private void pollCpuPercent() {
        Double value = parseDoubleValue(executeCommandSync(getCommand("cpu_percent")));
        if (value != null) {
            publishSystemInfo(info -> info.cpuPercent = value);
        }
    }

    private void pollMemory() {
        String memInfo = executeCommandSync(getCommand("mem_info"));
        SystemInfo info = new SystemInfo();
        if (memInfo.contains("Mem:")) {
            parseMemInfoNewFormat(memInfo, info);
        } else {
            String oldMemInfo = executeCommandSync(getCommand("mem_info_m"));
            if (oldMemInfo.isEmpty()) {
                return;
            }
            parseMemInfoOldFormat(oldMemInfo, info);
        }
        if (info.memValue != null || info.memPercent >= 0 || info.swapValue != null || info.swapPercent >= 0) {
            callback.onSystemInfoReceived(info);
        }
    }

    private void pollCpuCount() {
        String output = trimToNull(executeCommandSync("grep 'physical id' /proc/cpuinfo 2>/dev/null | sort -u | wc -l"));
        if (output != null) {
            latestCpuCount = output;
            publishCpuCoresIfReady();
        }
    }

    private void pollCoresPerCpu() {
        String output = trimToNull(executeCommandSync("grep 'cpu cores' /proc/cpuinfo 2>/dev/null | head -1 | awk '{print $4}'"));
        if (output != null) {
            latestCoresPerCpu = output;
            publishCpuCoresIfReady();
        }
    }

    private void pollTotalThreads() {
        String output = trimToNull(executeCommandSync("nproc 2>/dev/null || grep -c '^processor' /proc/cpuinfo"));
        if (output != null) {
            latestTotalThreads = output;
            publishCpuCoresIfReady();
        }
    }

    private void publishCpuCoresIfReady() {
        String cpuCount = latestCpuCount;
        String coresPerCpu = latestCoresPerCpu;
        String totalThreads = latestTotalThreads;
        if (cpuCount == null || coresPerCpu == null || totalThreads == null) {
            return;
        }
        try {
            int totalCores = Integer.parseInt(cpuCount) * Integer.parseInt(coresPerCpu);
            publishSystemInfo(info -> info.cpuCores = cpuCount + " CPU / " + totalCores + "核 / " + totalThreads + "线程");
        } catch (Exception e) {
            LOGGER.debug("parse cpu cores failed", e);
        }
    }

    private void pollProcessTotal() {
        Integer total = parseIntValue(executeCommandSync("ps aux --no-header 2>/dev/null | wc -l || ps -ef --no-header | wc -l"));
        if (total != null) {
            latestProcessTotal = total;
            publishProcessInfo(total, latestProcessRunning);
        }
    }

    private void pollProcessRunning() {
        Integer running = parseIntValue(executeCommandSync("ps aux --no-header 2>/dev/null | grep -E 'R[+ ]' | wc -l || ps -ef --no-header | grep -E ' R ' | wc -l"));
        if (running != null) {
            latestProcessRunning = running;
            publishProcessInfo(latestProcessTotal, running);
        }
    }

    private void publishProcessInfo(Integer total, Integer running) {
        SystemInfo info = new SystemInfo();
        if (total != null) {
            info.processTotal = total;
        }
        if (running != null) {
            info.processRunning = running;
        }
        if (total != null && running != null) {
            info.processSleeping = Math.max(0, total - running);
        }
        callback.onSystemInfoReceived(info);
    }

    private void pollTopProcesses() {
        String output = trimToNull(executeCommandSync(getCommand("top_processes")));
        if (output != null) {
            ProcessInfo[] processes = parseTopProcesses(output);
            publishSystemInfo(info -> info.topProcesses = processes);
        }
    }

    private void pollPing() {
        String output = trimToNull(executeCommandSync(getCommand("ping")));
        if (output != null) {
            publishSystemInfo(info -> info.latency = output + " ms");
        }
    }

    private void pollNetworkSpeed() {
        var leftPanelController = ConnectionManager.getInstance().getLeftPanelController();
        if (leftPanelController == null) {
            return;
        }
        String selectedNic = leftPanelController.getSelectedNic();
        if (selectedNic == null || selectedNic.isEmpty()) {
            return;
        }
        String output = executeCommandSync(getCommand("network_stats"));
        SystemInfo info = new SystemInfo();
        updateNetworkSpeed(info, selectedNic, output);
        if (info.uploadSpeed != null || info.downloadSpeed != null) {
            callback.onSystemInfoReceived(info);
        }
    }

    private void pollNetworkInterfaces() {
        String output = trimToNull(executeCommandSync(getCommand("network_interfaces")));
        if (output == null) {
            return;
        }
        String[] nics = Arrays.stream(output.split("\n"))
                .map(String::trim)
                .map(SshService::normalizeNetworkInterfaceName)
                .filter(value -> !value.isEmpty())
                .filter(value -> !"lo".equals(value))
                .distinct()
                .toArray(String[]::new);
        publishSystemInfo(info -> info.networkInterfaces = nics);
    }

    private void pollDiskInfo() {
        String output = trimToNull(executeCommandSync(getCommand("disk_info")));
        if (output != null) {
            DiskInfo[] diskInfo = parseDiskInfo(output);
            publishSystemInfo(info -> info.diskInfo = diskInfo);
        }
    }

    private void pollUsers() {
        String output = trimToNull(executeCommandSync(getCommand("users")));
        if (output != null) {
            UserInfo[] users = parseAllUsers(output);
            publishSystemInfo(info -> info.allUsers = users);
        }
    }

    private void pollUptime() {
        String output = trimToNull(executeCommandSync("uptime -p 2>/dev/null || uptime | sed 's/.*up //; s/,.*//'"));
        if (output != null) {
            publishSystemInfo(info -> info.uptime = formatUptime(output));
        }
    }

    private String trimToNull(String output) {
        if (output == null) {
            return null;
        }
        String trimmed = output.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer parseIntValue(String output) {
        String trimmed = trimToNull(output);
        if (trimmed == null) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDoubleValue(String output) {
        String trimmed = trimToNull(output);
        if (trimmed == null) {
            return null;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }


    private String executeCommandSync(String command) {
        return executeCommandSync(command, defaultCommandTimeout());
    }

    private String executeCommandOnExecutor() {
        return executeCommandOnExecutor("cat /etc/os-release 2>/dev/null || cat /etc/redhat-release 2>/dev/null || uname -s", defaultCommandTimeout());
    }

    private String executeCommandOnExecutor(String command, Duration timeout) {
        Future<String> future = null;
        try {
            future = executor.submit(() -> executeCommandSync(command, timeout));
            long waitMillis = Math.max(1000, timeout.toMillis() + 1000);
            return future.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            LOGGER.warn("Command executor timeout: {}", command);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            LOGGER.warn("Command executor interrupted: {}", command);
            return "";
        } catch (Exception e) {
            LOGGER.error("executeCommandOnExecutor failed for '{}'", command, e);
            return "";
        }
    }

    private String executeCommandSync(String command, Duration timeout) {
        CommandResult result = executeRemoteCommand(command, timeout);
        return result.isSuccess() ? result.stdout().trim() : "";
    }

    public CommandResult executeRemoteCommand(String command, Duration timeout) {
        if (!isConnected()) {
            return new CommandResult(-1, "", "Not connected", false);
        }

        boolean acquired = false;
        try {
            execChannelSemaphore.acquire();
            acquired = true;
            if (!isConnected()) {
                return new CommandResult(-1, "", "Not connected", false);
            }
            try (ChannelExec exec = clientSession.createExecChannel(command)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();

                // 高级 API：直接设置输出流
                exec.setOut(out);
                exec.setErr(err);

                // 打开 channel
                long verifySeconds = Math.max(1, timeout.toSeconds());
                exec.open().verify(verifySeconds, TimeUnit.SECONDS);

                // 优雅等待完成
                Set<ClientChannelEvent> events = exec.waitFor(
                        EnumSet.of(ClientChannelEvent.CLOSED),
                        timeout
                );

                // 检查是否超时
                if (!events.contains(ClientChannelEvent.CLOSED)) {
                    LOGGER.warn("Command timeout: {}", command);
                    exec.close(true);
                    return new CommandResult(-1,
                            out.toString(StandardCharsets.UTF_8).trim(),
                            err.toString(StandardCharsets.UTF_8).trim(),
                            true);
                }

                String stdout = out.toString(StandardCharsets.UTF_8).trim();
                String stderr = err.toString(StandardCharsets.UTF_8).trim();
                Integer exitStatus = exec.getExitStatus();

                if (!stderr.isEmpty()) {
                    LOGGER.debug("stderr empty for '{}': {}", command, stderr);
                }

                return new CommandResult(exitStatus == null ? -1 : exitStatus, stdout, stderr, false);
            }

        } catch (Exception e) {
            SshChannelOpenException openException = findChannelOpenException(e);
            String errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (openException != null) {
                int code = openException.getReasonCode();
                LOGGER.warn("executeCommandSync channel open failed for '{}': reason={}({}), message={},host={}",
                        command, code, SshConstants.getOpenErrorCodeName(code), openException.getMessage(), connInfo.getHost());
                errorMessage = openException.getMessage();
            } else {
                LOGGER.error("executeCommandSync failed for '{}'", command, e);
            }
            return new CommandResult(-1, "", errorMessage, false);
        } finally {
            if (acquired) {
                execChannelSemaphore.release();
            }
        }
    }

    public RemoteCommandHandle streamRemoteCommand(String command,
                                                   Consumer<String> stdoutConsumer,
                                                   Consumer<String> stderrConsumer) {
        RemoteCommandHandle handle = new RemoteCommandHandle();
        handle.task = executor.submit(() -> {
            boolean acquired = false;
            try {
                execChannelSemaphore.acquire();
                acquired = true;
                if (!isConnected()) {
                    handle.completion.complete(new CommandResult(-1, "", "Not connected", false));
                    return;
                }
                try (ChannelExec exec = clientSession.createExecChannel(command)) {
                    handle.channel = exec;
                    exec.setOut(new StreamingOutputStream(stdoutConsumer));
                    exec.setErr(new StreamingOutputStream(stderrConsumer));
                    long verifySeconds = Math.max(1, defaultCommandTimeout().toSeconds());
                    exec.open().verify(verifySeconds, TimeUnit.SECONDS);

                    while (!handle.isCancelled()) {
                        Set<ClientChannelEvent> events = exec.waitFor(
                                EnumSet.of(ClientChannelEvent.CLOSED),
                                Duration.ofMillis(250)
                        );
                        if (events.contains(ClientChannelEvent.CLOSED)) {
                            break;
                        }
                    }
                    if (handle.isCancelled()) {
                        exec.close(true);
                    }
                    Integer exitStatus = exec.getExitStatus();
                    int exitCode = exitStatus == null
                            ? (handle.isCancelled() ? 130 : -1)
                            : exitStatus;
                    handle.completion.complete(new CommandResult(exitCode, "", "", false));
                }
            } catch (Exception e) {
                if (handle.isCancelled()) {
                    handle.completion.complete(new CommandResult(130, "", "", false));
                } else {
                    String errorMessage = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    handle.completion.complete(new CommandResult(-1, "", errorMessage, false));
                }
            } finally {
                handle.channel = null;
                if (acquired) {
                    execChannelSemaphore.release();
                }
            }
        });
        return handle;
    }

    private static final class StreamingOutputStream extends OutputStream {
        private final Consumer<String> consumer;

        private StreamingOutputStream(Consumer<String> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void write(int b) {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(@NotNull byte[] b, int off, int len) {
            if (consumer != null && len > 0) {
                consumer.accept(new String(b, off, len, StandardCharsets.UTF_8));
            }
        }
    }

    private SshChannelOpenException findChannelOpenException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SshChannelOpenException openException) {
                return openException;
            }
            current = current.getCause();
        }
        return null;
    }

    private void executeCommandInBackgroundShell(String command, Duration timeout) throws IOException {
        acquireBackgroundShellPermit();
        try {
            executeCommandInBackgroundShellUnlocked(command, timeout);
        } finally {
            backgroundShellSemaphore.release();
        }
    }

    private void acquireBackgroundShellPermit() throws IOException {
        try {
            backgroundShellSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for background shell channel", e);
        }
    }

    private void executeCommandInBackgroundShellUnlocked(String command, Duration timeout) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to remote server");
        }
        String marker = "__YSHELL_SHELL_RC_" + UUID.randomUUID().toString().replace("-", "") + "__:";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ChannelShell shell = clientSession.createShellChannel()) {
            shell.setOut(out);
            shell.setErr(err);
            shell.open().verify(backgroundShellOpenTimeout());

            String script = command + "\n"
                    + "rc=$?\n"
                    + "printf '\\n" + marker + "%s\\n' \"$rc\"\n"
                    + "exit $rc\n";
            OutputStream input = shell.getInvertedIn();
            input.write(script.getBytes(StandardCharsets.UTF_8));
            input.flush();

            Set<ClientChannelEvent> events = shell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
            if (!events.contains(ClientChannelEvent.CLOSED)) {
                shell.close(true);
                throw new IOException("Background shell command timeout");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Background shell command failed", e);
        }

        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        int markerIndex = stdout.lastIndexOf(marker);
        if (markerIndex < 0) {
            String combined = (stdout + stderr).trim();
            throw new IOException(combined.isEmpty() ? "Missing background shell exit marker" : combined);
        }

        String beforeMarker = stdout.substring(0, markerIndex);
        String rcText = stdout.substring(markerIndex + marker.length()).trim().split("\\R", 2)[0].trim();
        int rc;
        try {
            rc = Integer.parseInt(rcText);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid background shell exit code: " + rcText, e);
        }
        String combined = (beforeMarker + stderr).trim();
        if (rc != 0) {
            throw new IOException(combined.isEmpty() ? "Background shell command exited with " + rc : combined);
        }
    }

    private void parseMemInfoNewFormat(String memInfo, SystemInfo info) {
        String[] lines = memInfo.split("\n");
        for (String line : lines) {
            if (line.startsWith("Mem:")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 7) {
                    double total = parseMemValue(parts[1]);
                    double available = parseMemValue(parts[6]);
                    double used = total - available;
                    info.memValue = String.format("%.1f/%.1f GB", used / 1024.0, total / 1024.0);
                    info.memPercent = total > 0 ? (used * 100.0) / total : 0;
                }
            } else if (line.startsWith("Swap:")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    double total = parseMemValue(parts[1]);
                    double used = parseMemValue(parts[2]);
                    info.swapValue = String.format("%.1f/%.1f GB", used / 1024.0, total / 1024.0);
                    info.swapPercent = total > 0 ? (used * 100.0) / total : 0;
                }
            }
        }
    }

    private void parseMemInfoOldFormat(String memInfo, SystemInfo info) {
        String[] lines = memInfo.split("\n");
        for (String line : lines) {
            if (line.startsWith("Mem:")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 7) {
                    int total = Integer.parseInt(parts[1]);
                    int available = Integer.parseInt(parts[6]);
                    int used = total - available;
                    info.memPercent = total > 0 ? (used * 100.0) / total : 0;
                    info.memValue = String.format("%.1f/%.1f MB", used / 1024.0, total / 1024.0);
                }
            } else if (line.startsWith("Swap:")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    int total = Integer.parseInt(parts[1]);
                    int used = Integer.parseInt(parts[2]);
                    info.swapPercent = total > 0 ? (used * 100.0) / total : 0;
                    info.swapValue = String.format("%.1f/%.1f MB", used / 1024.0, total / 1024.0);
                }
            }
        }
    }

    private double parseMemValue(String value) {
        value = value.toLowerCase().trim();
        value = value.replace("i", "");
        if (value.endsWith("g")) {
            return Double.parseDouble(value.replace("g", "")) * 1024;
        } else if (value.endsWith("m")) {
            return Double.parseDouble(value.replace("m", ""));
        } else if (value.endsWith("k")) {
            return Double.parseDouble(value.replace("k", "")) / 1024;
        } else {
            return Double.parseDouble(value.replace("b", "")) / 1024 / 1024;
        }
    }

    private String formatUptime(String rawUptime) {
        if (rawUptime == null || rawUptime.isEmpty()) {
            return null;
        }

        rawUptime = rawUptime.trim().toLowerCase();

        int weeks = 0, days = 0, hours = 0, minutes = 0;

        String[] parts = rawUptime.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].matches("\\d+")) {
                int value = Integer.parseInt(parts[i]);
                if (i + 1 < parts.length) {
                    String unit = parts[i + 1];
                    if (unit.contains("week")) {
                        weeks = value;
                    } else if (unit.contains("day")) {
                        days = value;
                    } else if (unit.contains("hour")) {
                        hours = value;
                    } else if (unit.contains("min")) {
                        minutes = value;
                    }
                }
            }
        }

        days += weeks * 7;
        hours += days / 24;
        days = days % 24;

        StringBuilder result = new StringBuilder();
        if (days > 0) {
            result.append(days).append("天");
        }
        if (hours > 0) {
            result.append(hours).append("小时");
        }
        if (minutes > 0 && days == 0) {
            result.append(minutes).append("分钟");
        }

        return !result.isEmpty() ? result.toString() : null;
    }

    private ProcessInfo[] parseTopProcesses(String output) {
        if (output == null || output.isEmpty()) {
            return new ProcessInfo[0];
        }

        String[] lines = output.split("\n");
        List<ProcessInfo> processes = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            if (parts.length >= 11) {

                double cpuPercent = Double.parseDouble(parts[2]);
                double memPercent = Double.parseDouble(parts[3]);

                String cmd = parts[10];
                if (cmd.length() > 20) {
                    cmd = cmd.substring(0, 20);
                }
                String name = cmd;
                ProcessInfo proc = new ProcessInfo(name, cpuPercent, memPercent);
                processes.add(proc);
            }
        }

        return processes.toArray(new ProcessInfo[0]);
    }

    private DiskInfo[] parseDiskInfo(String output) {
        if (output == null || output.isEmpty()) {
            return new DiskInfo[0];
        }

        String[] lines = output.split("\n");
        List<DiskInfo> disks = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("Filesystem")) continue;

            String[] parts = line.split("\\s+");
            if (parts.length >= 6) {
                String path = parts[5];
                String sizeText = parts[2] + "/" + parts[1];

                String percentStr = parts[4].replace("%", "");
                double usedPercent;
                try {
                    usedPercent = Double.parseDouble(percentStr);
                } catch (Exception e) {
                    usedPercent = 0.0;
                }
                DiskInfo disk = new DiskInfo(path, sizeText, usedPercent);
                disks.add(disk);
            }
        }

        return disks.toArray(new DiskInfo[0]);
    }

    private UserInfo[] parseAllUsers(String output) {
        if (output == null || output.isEmpty()) {
            return new UserInfo[0];
        }

        String[] lines = output.split("\n");
        List<UserInfo> users = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            if (parts.length >= 4) {
                users.add(new UserInfo(parts[0], parts[1], parts[2], parts[3]));
            } else if (parts.length == 3) {
                users.add(new UserInfo(parts[0], parts[1], parts[2], ""));
            }
        }

        return users.toArray(new UserInfo[0]);
    }

    // ============================================================
    //  远程文件操作
    // ============================================================

    /**
     * 获取远程服务器指定路径下的文件和文件夹列表（只获取下一级）
     *
     * @param path 路径，如 "/" 或 "/home/user"
     * @return 文件和文件夹信息列表
     */
    public List<RemoteFileInfo> listRemoteFiles(String path) {
        if (!isConnected()) {
            return Collections.emptyList();
        }
        try (SftpClient sftp = createSftpClient()) {
            List<RemoteFileInfo> files = new ArrayList<>();
            for (SftpClient.DirEntry entry : sftp.readDir(path)) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                String fullPath = joinRemotePath(path, name);
                files.add(toRemoteFileInfo(sftp, name, fullPath, path, entry.getAttributes()));
            }
            sortRemoteFiles(files);
            return files;
        } catch (Exception e) {
            LOGGER.warn("listRemoteFiles failed: {}", path, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取远程服务器指定路径下的文件夹列表（只获取下一级）
     *
     * @param path 路径
     * @return 文件夹信息列表
     */
    public List<RemoteFileInfo> listRemoteDirectories(String path) {
        List<RemoteFileInfo> allFiles = listRemoteFiles(path);
        return allFiles.stream()
                .filter(RemoteFileInfo::isDirectoryLike)
                .collect(Collectors.toList());
    }

    public void createRemoteDirectory(String path) throws IOException {
        requireRemotePath(path);
        try (SftpClient sftp = createSftpClient()) {
            if (sftpExists(sftp, path)) {
                throw new IOException("path exists");
            }
            sftp.mkdir(path);
        }
    }

    public void createRemoteFile(String path) throws IOException {
        requireRemotePath(path);
        try (SftpClient sftp = createSftpClient()) {
            if (sftpExists(sftp, path)) {
                throw new IOException("path exists");
            }
            ensureRemoteDirectory(sftp, remoteParent(path));
            try (OutputStream ignored = sftp.write(path, SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Exclusive)) {
                // Create an empty file via SFTP without executing a remote shell.
            }
        }
    }

    public void renameRemotePath(String oldPath, String newPath) throws IOException {
        requireRemotePath(oldPath);
        requireRemotePath(newPath);
        try (SftpClient sftp = createSftpClient()) {
            if (sftpExists(sftp, newPath)) {
                throw new IOException("target exists");
            }
            sftp.rename(oldPath, newPath);
        }
    }

    public void deleteRemotePath(String path) throws IOException {
        requireRemotePath(path);
        if ("/".equals(path.trim())) {
            throw new IOException("Cannot delete root directory");
        }
        if (!path.startsWith("/")) {
            throw new IOException("Remote path must be absolute for shell delete");
        }
        String command = String.format("rm -rf -- \"%s\" 2>&1", escapePath(path));
        executeCommandInBackgroundShell(command, Duration.ofSeconds(30));
    }

    public void chmodRemotePath(String path, String mode, boolean recursive, String recursiveScope) throws IOException {
        requireRemotePath(path);
        if (mode == null || !mode.matches("[0-7]{3,4}")) {
            throw new IOException("Invalid permission mode: " + mode);
        }
        int permissions = Integer.parseInt(mode, 8);
        try (SftpClient sftp = createSftpClient()) {
            chmodRemotePath(sftp, path, permissions, recursive, recursiveScope);
        }
    }

    public RemoteFileInfo createRemoteTarGz(String path) throws IOException {
        requireRemotePath(path);
        String name = remoteName(path);
        String archiveName = name + "-" + System.currentTimeMillis() + ".tar.gz";
        String archivePath = "/tmp/" + archiveName;
        String parent = remoteParent(path);
        String base = remoteName(path);
        String command = String.format("tar -czf \"%s\" -C \"%s\" \"%s\" 2>&1",
                escapePath(archivePath), escapePath(parent), escapePath(base));
        executeCommandInBackgroundShell(command, Duration.ofMinutes(2));

        RemoteFileStat stat = statFile(archivePath);
        long size = stat == null ? 0L : stat.sizeBytes();
        String perms = stat == null ? "" : stat.permissions();
        String owner = stat == null ? "" : stat.owner();
        String group = stat == null ? "" : stat.group();
        return new RemoteFileInfo(archiveName, archivePath, "/tmp", false, false, false,
                false, perms, owner, group, size, "");
    }

    public void extractRemoteTarGz(String archivePath, String targetDirectory) throws IOException {
        requireRemotePath(archivePath);
        requireRemotePath(targetDirectory);
        try (SftpClient sftp = createSftpClient()) {
            ensureRemoteDirectory(sftp, targetDirectory);
        }
        String command = String.format("tar -xzf \"%s\" -C \"%s\" 2>&1",
                escapePath(archivePath), escapePath(targetDirectory));
        executeCommandInBackgroundShell(command, Duration.ofMinutes(2));
    }

    private void requireRemotePath(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("远程路径为空");
        }
    }

    private String remoteParent(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return "/";
        int idx = path.lastIndexOf('/');
        if (idx <= 0) return "/";
        return path.substring(0, idx);
    }

    private String remoteName(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return "root";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * Read remote file content via SFTP only. No exec or shell command is used.
     */
    public String getFileContent(String path) {
        if (!isConnected()) {
            return "";
        }
        try (SftpClient sftp = createSftpClient();
             InputStream in = sftp.read(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("getFileContent failed for '{}'", path, e);
            return "";
        }
    }

    /**
     * Write remote file content via SFTP only. No exec or shell command is used.
     */
    public void writeFileContent(String path, String content) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to remote server");
        }
        if (path == null || path.isEmpty()) {
            throw new IOException("File path is empty");
        }
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        try (SftpClient sftp = createSftpClient()) {
            ensureRemoteDirectory(sftp, remoteParent(path));
            try (OutputStream out = sftp.write(path, SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate)) {
                out.write(bytes);
            }
        }
    }

    /**
     * 转义路径字符串，防止命令注入
     */
    private String escapePath(String path) {
        if (path == null) {
            return "";
        }
        // 转义双引号和反斜杠
        return path.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ============================================================
    //  新增：文件元数据/权限/二进制检测/大小 查询
    // ============================================================

    /**
     * 远程文件元数据：大小/mtime/权限/属主/属组
     */
    public record RemoteFileStat(long sizeBytes, long mtimeEpochSec, String permissions, String owner, String group,
                                 boolean isDirectory) {
    }

    /**
     * Query remote file metadata via SFTP stat only.
     */

    public RemoteFileStat statFile(String path) {
        if (!isConnected()) return null;
        try (SftpClient sftp = createSftpClient()) {
            return toRemoteFileStat(sftp.stat(path));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check write access with SFTP open/create probes only.
     */

    public boolean isWritable(String path) {
        if (!isConnected()) return false;
        try (SftpClient sftp = createSftpClient()) {
            if (sftpExists(sftp, path)) {
                if (sftp.stat(path).isDirectory()) {
                    String testPath = joinRemotePath(path, ".yshell_write_test_" + System.nanoTime());
                    try (OutputStream ignored = sftp.write(testPath, SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Exclusive)) {
                        // Ability to create a child proves directory write access over SFTP.
                    }
                    sftp.remove(testPath);
                    return true;
                }
                try (SftpClient.CloseableHandle ignored = sftp.open(path, SftpClient.OpenMode.Write)) {
                    return true;
                }
            }
            String parent = remoteParent(path);
            String testPath = joinRemotePath(parent, ".yshell_write_test_" + System.nanoTime());
            try (OutputStream ignored = sftp.write(testPath, SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Exclusive)) {
                // Ability to create a file proves parent write access over SFTP.
            }
            sftp.remove(testPath);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Detect binary files by reading the first bytes through SFTP and checking for NUL bytes.
     */

    public boolean isBinaryFile(String path) {
        if (!isConnected()) return false;
        byte[] buffer = new byte[512];
        try (SftpClient sftp = createSftpClient();
             SftpClient.CloseableHandle handle = sftp.open(path, SftpClient.OpenMode.Read)) {
            int read = sftp.read(handle, 0, buffer, 0, buffer.length);
            if (read <= 0) return false;
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ============================================================
    //  远程文件信息类
    // ============================================================

    private boolean sftpExists(SftpClient sftp, String path) {
        try {
            sftp.stat(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void ensureRemoteDirectory(SftpClient sftp, String directory) throws IOException {
        if (directory == null || directory.isBlank() || "/".equals(directory)) {
            return;
        }
        String normalized = directory.startsWith("/") ? directory : "/" + directory;
        StringBuilder current = new StringBuilder();
        for (String part : normalized.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            current.append('/').append(part);
            String next = current.toString();
            if (!sftpExists(sftp, next)) {
                sftp.mkdir(next);
            }
        }
    }

    private void chmodRemotePath(SftpClient sftp, String path, int permissions, boolean recursive, String recursiveScope) throws IOException {
        SftpClient.Attributes attrs = sftp.stat(path);
        boolean isDirectory = attrs.isDirectory();
        boolean applyHere = !recursive
                || recursiveScope == null
                || recursiveScope.isBlank()
                || "all".equals(recursiveScope)
                || (isDirectory && "directories".equals(recursiveScope))
                || (!isDirectory && "files".equals(recursiveScope));
        if (applyHere) {
            sftp.setStat(path, new SftpClient.Attributes().perms(permissions));
        }
        if (recursive && isDirectory) {
            for (SftpClient.DirEntry entry : sftp.readDir(path)) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                chmodRemotePath(sftp, joinRemotePath(path, name), permissions, true, recursiveScope);
            }
        }
    }

    private String joinRemotePath(String parent, String name) {
        if (parent == null || parent.isBlank() || "/".equals(parent)) {
            return "/" + name;
        }
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    private RemoteFileInfo toRemoteFileInfo(SftpClient sftp, String name, String fullPath, String parentPath, SftpClient.Attributes attrs) {
        boolean symbolicLink = attrs != null && attrs.isSymbolicLink();
        boolean linkTargetDirectory = false;
        boolean linkTargetRegularFile = false;
        SftpClient.Attributes effectiveAttrs = attrs;
        if (symbolicLink) {
            try {
                SftpClient.Attributes targetAttrs = sftp.stat(fullPath);
                linkTargetDirectory = targetAttrs != null && targetAttrs.isDirectory();
                linkTargetRegularFile = targetAttrs != null && targetAttrs.isRegularFile();
                if (targetAttrs != null) {
                    effectiveAttrs = targetAttrs;
                }
            } catch (IOException ignored) {
                // Broken or inaccessible links stay visible as link files.
            }
        }
        boolean directory = effectiveAttrs != null && effectiveAttrs.isDirectory();
        long size = effectiveAttrs == null || directory ? 0L : Math.max(0L, effectiveAttrs.getSize());
        String permissions = permissionsString(attrs);
        String owner = ownerString(attrs);
        String group = groupString(attrs);
        String lastModified = formatSftpTime(attrs == null ? null : attrs.getModifyTime());
        return new RemoteFileInfo(name, fullPath, parentPath, directory, symbolicLink,
                linkTargetDirectory, linkTargetRegularFile, permissions, owner, group, size, lastModified);
    }

    private RemoteFileStat toRemoteFileStat(SftpClient.Attributes attrs) {
        if (attrs == null) return null;
        FileTime modifyTime = attrs.getModifyTime();
        long mtime = modifyTime == null ? 0L : modifyTime.toInstant().getEpochSecond();
        return new RemoteFileStat(Math.max(0L, attrs.getSize()), mtime, permissionsString(attrs),
                ownerString(attrs),
                groupString(attrs),
                attrs.isDirectory());
    }

    private String ownerString(SftpClient.Attributes attrs) {
        if (attrs == null) return "";
        String owner = attrs.getOwner();
        if (owner != null && !owner.isBlank()) return owner;
        if (hasSftpAttribute(attrs)) {
            return String.valueOf(attrs.getUserId());
        }
        return "";
    }

    private String groupString(SftpClient.Attributes attrs) {
        if (attrs == null) return "";
        String group = attrs.getGroup();
        if (group != null && !group.isBlank()) return group;
        if (hasSftpAttribute(attrs)) {
            return String.valueOf(attrs.getGroupId());
        }
        return "";
    }

    private boolean hasSftpAttribute(SftpClient.Attributes attrs) {
        return attrs != null && attrs.getFlags() != null && attrs.getFlags().contains(SftpClient.Attribute.UidGid);
    }

    private String permissionsString(SftpClient.Attributes attrs) {
        if (attrs == null) return "";
        char type = attrs.isSymbolicLink() ? 'l' : attrs.isDirectory() ? 'd' : '-';
        int perms = attrs.getPermissions();
        if (perms == 0) {
            return String.valueOf(type);
        }
        StringBuilder value = new StringBuilder();
        value.append(type);
        int[] masks = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};
        char[] chars = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        for (int i = 0; i < masks.length; i++) {
            value.append((perms & masks[i]) != 0 ? chars[i] : '-');
        }
        return value.toString();
    }

    private String formatSftpTime(FileTime time) {
        if (time == null) return "";
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(time.toInstant());
    }

    private void sortRemoteFiles(List<RemoteFileInfo> files) {
        files.sort((f1, f2) -> {
            if (f1.isDirectoryLike() && !f2.isDirectoryLike()) {
                return -1;
            }
            if (!f1.isDirectoryLike() && f2.isDirectoryLike()) {
                return 1;
            }
            return f1.name().compareToIgnoreCase(f2.name());
        });
    }

    public record RemoteFileInfo(String name, String fullPath, String parentPath, boolean isDirectory,
                                 boolean isSymbolicLink, boolean isLinkTargetDirectory, boolean isLinkTargetRegularFile,
                                 String permissions, String owner, String group, long size, String lastModified) {

        public boolean isDirectoryLike() {
            return isDirectory || (isSymbolicLink && isLinkTargetDirectory);
        }

        public String getFormattedSize() {
            if (isDirectoryLike()) {
                return "-";
            }
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }
    }
}

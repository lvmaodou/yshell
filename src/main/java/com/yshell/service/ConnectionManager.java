package com.yshell.service;

import com.yshell.controller.ConnectionToolbarController;
import com.yshell.controller.FilesViewController;
import com.yshell.controller.LeftPanelController;
import com.yshell.controller.TerminalPanelController;
import com.yshell.model.ConnInfo;
import com.yshell.model.ConnectionTabInfo;
import com.yshell.model.SystemInfo;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.PanelManager;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class ConnectionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionManager.class);

    private static ConnectionManager instance;

    private final Map<String, SshService> connections = new ConcurrentHashMap<>();
    private final Map<String, TerminalPanelController> terminalPanelControllers = new ConcurrentHashMap<>();
    private final Object pollingStateLock = new Object();
    private String currentConnectionId;
    private String pollingConnectionId;
    private volatile boolean rdpConnectionTreeMode;

    private LeftPanelController leftPanelController;
    private TerminalPanelController terminalPanelController;
    private ConnectionToolbarController connectionToolbarController;
    private FilesViewController filesViewController;

    /**
     * Notifies listeners after a connection is removed.
     */
    @FunctionalInterface
    public interface OnConnectionClosedListener {
        void onConnectionClosed(String connId);
    }

    /**
     * Notifies listeners when connection state or the active connection changes.
     */
    @FunctionalInterface
    public interface OnConnectionStateChangedListener {
        void onConnectionStateChanged();
    }

    private final List<OnConnectionClosedListener> connectionClosedListeners = new CopyOnWriteArrayList<>();
    private final List<OnConnectionStateChangedListener> connectionStateChangedListeners = new CopyOnWriteArrayList<>();

    public void addOnConnectionClosedListener(OnConnectionClosedListener listener) {
        if (listener != null) connectionClosedListeners.add(listener);
    }

    public void addOnConnectionStateChangedListener(OnConnectionStateChangedListener listener) {
        if (listener != null) connectionStateChangedListeners.add(listener);
    }

    public void removeOnConnectionStateChangedListener(OnConnectionStateChangedListener listener) {
        connectionStateChangedListeners.remove(listener);
    }

    private void fireConnectionClosed(String connId) {
        if (connId == null) return;
        for (OnConnectionClosedListener l : connectionClosedListeners) {
            try {
                l.onConnectionClosed(connId);
            } catch (Exception e) {
                LOGGER.error("fireConnectionClosed error", e);
            }
        }
        fireConnectionStateChanged();
    }

    private void fireConnectionStateChanged() {
        for (OnConnectionStateChangedListener l : connectionStateChangedListeners) {
            try {
                l.onConnectionStateChanged();
            } catch (Exception e) {
                LOGGER.error("fireConnectionStateChanged error", e);
            }
        }
    }

    private final ExecutorService executor;

    private ConnectionManager() {
        executor = Executors.newFixedThreadPool(4);
    }

    public static synchronized ConnectionManager getInstance() {
        if (instance == null) {
            instance = new ConnectionManager();
        }
        return instance;
    }

    private String generateConnectionId(ConnInfo connInfo) {
        String baseId = connInfo.getHost() + ":" + connInfo.getPort() + ":" + connInfo.getUserName();
        return baseId + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    public void connect(ConnInfo connInfo) {
        connect(connInfo, null, true);
    }

    public void connect(ConnInfo connInfo, String connId, boolean isCurrent) {
        if (isRdpConnection(connInfo)) {
            connectRdp(connInfo);
            return;
        }
        boolean createTab = connId == null;
        connId = connId == null ? generateConnectionId(connInfo) : connId;

        String finalConnId = connId;
        AtomicReference<SshService> serviceReference = new AtomicReference<>();
        SshService sshService = new SshService(connInfo, new SshService.ConnectionCallback() {
            @Override
            public void onConnected() {
                Platform.runLater(() -> {
                    SshService connectedService = serviceReference.get();
                    if (!isManagedConnection(finalConnId, connectedService)) {
                        if (connectedService != null) {
                            connectedService.disconnect();
                        }
                        return;
                    }
                    RecentConnectionRepository.getInstance().record(connInfo);
                    TerminalPanelController terminalPanel = getTerminalPanelController(finalConnId);
                    if (terminalPanel != null) {
                        terminalPanel.appendOutput("\r\n认证成功，正在打开远程 Shell...\r\n");
                        terminalPanel.onShellReady(connectedService);
                    }
                    if (filesViewController != null) {
                        filesViewController.showForConnection(finalConnId);
                    }
                    if (isCurrent) {
                        rdpConnectionTreeMode = false;
                        stopCurrentPolling();
                        currentConnectionId = finalConnId;
                        if (leftPanelController != null) {
                            leftPanelController.showConnection(finalConnId, connInfo);
                        }
                        reconcileSystemInfoPolling();
                    }
                    fireConnectionStateChanged();
                });

            }

            @Override
            public void onConnectionFailed(String error) {
                String message = normalizeConnectionError(error);
                Platform.runLater(() -> {
                    SshService failedService = serviceReference.get();
                    if (!connections.remove(finalConnId, failedService)) {
                        return;
                    }
                    DockerSessionManager.getInstance().clear(finalConnId);
                    K8sSessionManager.getInstance().clear(finalConnId);
                    TerminalPanelController terminalPanel = getTerminalPanelController(finalConnId);
                    if (terminalPanel != null) {
                        terminalPanel.cancelKeyboardInteractive();
                        terminalPanel.appendOutput("连接失败: " + error + "\n");
                    }
                    fireConnectionClosed(finalConnId);
                    DialogHelper.showError("连接失败", connectionDisplayName(connInfo) + "\n" + message);
                });
            }

            @Override
            public void onDisconnected() {
                Platform.runLater(() -> {
                    SshService disconnectedService = serviceReference.get();
                    if (!connections.remove(finalConnId, disconnectedService)) {
                        return;
                    }
                    disconnectedService.stopSystemInfoPolling();
                    if (finalConnId.equals(currentConnectionId)) {
                        stopCurrentPolling();
                        currentConnectionId = null;
                    }
                    DockerSessionManager.getInstance().clear(finalConnId);
                    K8sSessionManager.getInstance().clear(finalConnId);
                    TerminalPanelController terminalPanel = getTerminalPanelController(finalConnId);
                    if (terminalPanel != null) {
                        terminalPanel.cancelKeyboardInteractive();
                        terminalPanel.appendOutput("连接已断开\n");
                    }
                    if (leftPanelController != null) {
                        leftPanelController.removeConnectionInfo(finalConnId);
                    }
                    fireConnectionClosed(finalConnId);
                });
            }

            @Override
            public void onOutputReceived(String output) {
                Platform.runLater(() -> {
                    if (!isManagedConnection(finalConnId, serviceReference.get())) {
                        return;
                    }
                    TerminalPanelController terminalPanel = getTerminalPanelController(finalConnId);
                    if (terminalPanel != null) {
                        terminalPanel.appendOutput(output);
                    }
                });
            }

            @Override
            public void onSystemInfoReceived(SystemInfo info) {
                Platform.runLater(() -> {
                    SshService activeService = serviceReference.get();
                    if (leftPanelController != null
                            && isManagedConnection(finalConnId, activeService)
                            && activeService.isConnected()) {
                        leftPanelController.updateConnectionInfo(finalConnId, info);
                    }
                });
            }

            @Override
            public boolean supportsKeyboardInteractive() {
                return isManagedConnection(finalConnId, serviceReference.get())
                        && getTerminalPanelController(finalConnId) != null;
            }

            @Override
            public String[] onKeyboardInteractive(String name, String instruction, String lang,
                                                  String[] prompts, boolean[] echo) {
                if (!isManagedConnection(finalConnId, serviceReference.get())) {
                    return null;
                }
                TerminalPanelController terminalPanel = getTerminalPanelController(finalConnId);
                return terminalPanel == null ? null
                        : terminalPanel.requestKeyboardInteractive(name, instruction, prompts, echo);
            }
        }, executor);

        serviceReference.set(sshService);
        SshService existingService = connections.putIfAbsent(finalConnId, sshService);
        if (existingService != null) {
            if (isCurrent && existingService.isConnected()) {
                switchConnectionInternal(finalConnId, existingService.getConnInfo());
            }
            return;
        }
        try {
            prepareTerminalForConnection(connInfo, finalConnId, createTab);
        } catch (RuntimeException e) {
            connections.remove(finalConnId, sshService);
            throw e;
        }
        executor.submit(sshService::connect);
    }

    private boolean isManagedConnection(String connId, SshService service) {
        return connId != null && service != null && connections.get(connId) == service;
    }

    private void prepareTerminalForConnection(ConnInfo connInfo, String connId, boolean createTab) {
        Runnable prepare = () -> {
            if (createTab && connectionToolbarController != null) {
                connectionToolbarController.createConnectionTab(new ConnectionTabInfo(connInfo, connId));
            }
            TerminalPanelController terminalPanel = getTerminalPanelController(connId);
            if (terminalPanel != null) {
                terminalPanel.clearOutput();
                terminalPanel.appendOutput("正在连接 " + connectionDisplayName(connInfo) + "...\r\n");
                terminalPanel.focusTerminal();
            }
        };
        if (Platform.isFxApplicationThread()) {
            prepare.run();
        } else {
            CountDownLatch terminalReady = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    prepare.run();
                } finally {
                    terminalReady.countDown();
                }
            });
            try {
                terminalReady.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isRdpConnection(ConnInfo connInfo) {
        return connInfo != null
                && ("rdp".equalsIgnoreCase(connInfo.getType()) || connInfo.getConnectionType() == 200);
    }

    private void connectRdp(ConnInfo connInfo) {
        executor.submit(() -> {
            try {
                RdpService.connect(connInfo);
                rdpConnectionTreeMode = false;
                RecentConnectionRepository.getInstance().record(connInfo);
            } catch (Exception e) {
                LOGGER.error("RDP connect failed", e);
                Platform.runLater(() -> DialogHelper.showError("RDP 连接失败", e.getMessage()));
            }
        });
    }

    private void stopCurrentPolling() {
        synchronized (pollingStateLock) {
            stopPollingLocked();
        }
    }

    private void stopPollingLocked() {
        String runningConnId = pollingConnectionId;
        pollingConnectionId = null;
        if (runningConnId != null) {
            SshService service = connections.get(runningConnId);
            if (service != null) {
                service.stopSystemInfoPolling();
            }
        }
    }

    public void reconcileSystemInfoPolling() {
        synchronized (pollingStateLock) {
            String desiredConnId = desiredPollingConnectionId();
            if (Objects.equals(pollingConnectionId, desiredConnId)) {
                return;
            }

            stopPollingLocked();
            if (desiredConnId == null) {
                return;
            }

            SshService service = connections.get(desiredConnId);
            if (service != null) {
                service.startSystemInfoPolling();
                pollingConnectionId = desiredConnId;
            }
        }
    }

    private String desiredPollingConnectionId() {
        if (!PanelManager.getInstance().isLeftPanelVisible()
                || !PanelManager.getInstance().isSystemInfoVisible()
                || rdpConnectionTreeMode
                || currentConnectionId == null) {
            return null;
        }

        SshService service = connections.get(currentConnectionId);
        if (service == null
                || !service.getConnInfo().isExecChannelEnable()
                || !service.isConnected()
                || !service.isExecAvailable()) {
            return null;
        }
        return currentConnectionId;
    }


    public void disconnect(String connId) {
        SshService service = connections.remove(connId);
        if (service != null) {
            service.stopSystemInfoPolling();
            if (connId.equals(currentConnectionId)) {
                stopCurrentPolling();
                currentConnectionId = null;
            }
            DockerSessionManager.getInstance().clear(connId);
            K8sSessionManager.getInstance().clear(connId);
            if (leftPanelController != null) {
                leftPanelController.removeConnectionInfo(connId);
            }
            service.disconnect();
            fireConnectionClosed(connId);
        }
    }

    public void disconnectCurrent() {
        if (currentConnectionId != null) {
            disconnect(currentConnectionId);
        }
    }

    public void switchConnectionById(String connId, ConnInfo connInfo) {
        SshService service = connId == null ? null : connections.get(connId);
        if (service != null && service.isConnected()) {
            switchConnectionInternal(connId, service.getConnInfo());
            return;
        }
        switchToDisconnectedConnection(connInfo);
    }

    private void switchConnectionInternal(String connId, ConnInfo connInfo) {
        rdpConnectionTreeMode = false;
        stopCurrentPolling();
        this.currentConnectionId = connId;

        if (leftPanelController != null) {
            leftPanelController.showConnection(connId, connInfo);
        }

        SshService sshService = getConnectionById(connId);

        Platform.runLater(() -> {
            TerminalPanelController terminalPanel = getTerminalPanelController(connId);
            if (terminalPanel != null && sshService != null && sshService.isConnected()) {
                terminalPanel.onShellReady(sshService);
                terminalPanel.focusTerminal();
            }
            if (leftPanelController != null) {
                leftPanelController.setConnected(true);
            }
        });
        reconcileSystemInfoPolling();
        fireConnectionStateChanged();
    }

    private void switchToDisconnectedConnection(ConnInfo connInfo) {
        rdpConnectionTreeMode = false;
        stopCurrentPolling();
        currentConnectionId = null;
        if (leftPanelController != null) {
            leftPanelController.setConnected(false);
            leftPanelController.clearData(connInfo);
        }
        fireConnectionStateChanged();
    }

    public boolean isConnected() {
        return getCurrentSshService() != null && getCurrentSshService().isConnected();
    }

    public boolean isConnected(String connId) {
        SshService service = connId == null ? null : connections.get(connId);
        return service != null && service.isConnected();
    }

    public SshService getCurrentSshService() {
        if (currentConnectionId == null) {
            return null;
        }
        return connections.get(currentConnectionId);
    }

    /**
     * Finds a service by connection id. The returned service may already be disconnected.
     */
    public SshService getConnectionById(String connId) {
        if (connId == null) return null;
        return connections.get(connId);
    }

    public ConnInfo getCurrentConnection() {
        SshService service = getCurrentSshService();
        return service != null ? service.getConnInfo() : null;
    }

    public String getCurrentConnectionId() {
        return currentConnectionId;
    }

    public Map<String, SshService> getAllConnections() {
        return new ConcurrentHashMap<>(connections);
    }

    private String normalizeConnectionError(String error) {
        return error == null || error.isBlank() ? "未知错误" : error;
    }

    private String connectionDisplayName(ConnInfo connInfo) {
        if (connInfo == null) {
            return "";
        }
        String name = connInfo.getName();
        String host = connInfo.getHost();
        int port = connInfo.getPort() > 0 ? connInfo.getPort() : 22;
        if (name != null && !name.isBlank()) {
            return name + " (" + (host != null ? host : "") + ":" + port + ")";
        }
        return (host != null && !host.isBlank() ? host : "SSH") + ":" + port;
    }


    public void setLeftPanelController(LeftPanelController controller) {
        this.leftPanelController = controller;
    }

    public void setTerminalPanelController(TerminalPanelController controller) {
        this.terminalPanelController = controller;
    }

    public void registerTerminalPanel(String connId, TerminalPanelController controller) {
        if (connId == null || controller == null) return;
        terminalPanelControllers.put(connId, controller);
    }

    public void unregisterTerminalPanel(String connId) {
        if (connId == null) return;
        terminalPanelControllers.remove(connId);
    }

    public void setConnectionToolbarController(ConnectionToolbarController controller) {
        this.connectionToolbarController = controller;
    }

    public void refreshConnectionTab(ConnInfo connInfo, String connId) {
        if (connectionToolbarController != null) {
            connectionToolbarController.refreshConnectionTab(connInfo, connId);
        }
    }

    public void setFilesViewController(FilesViewController controller) {
        this.filesViewController = controller;
    }

    public void showFilesForConnection(String connId) {
        if (filesViewController != null) {
            filesViewController.showForConnection(connId);
        }
    }

    public void refreshFilesForSavedFile(String connId, String filePath) {
        if (filesViewController != null) {
            filesViewController.refreshIfShowingSavedFileDirectory(connId, filePath);
        }
    }

    public LeftPanelController getLeftPanelController() {
        return leftPanelController;
    }

    public TerminalPanelController getTerminalPanelController() {
        return terminalPanelController;
    }

    public TerminalPanelController getTerminalPanelController(String connId) {
        if (connId == null) return null;
        return terminalPanelControllers.get(connId);
    }

    public void shutdown() {
        stopCurrentPolling();
        executor.shutdown();
    }
}

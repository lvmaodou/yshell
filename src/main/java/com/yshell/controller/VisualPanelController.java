package com.yshell.controller;

import com.yshell.service.ConnectionManager;
import com.yshell.ui.AiAssistantWindowManager;
import com.yshell.ui.PanelManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class VisualPanelController {
    private static final String DEFAULT_TAB = "files";
    private final Map<String, String> selectedTabsByConnection = new ConcurrentHashMap<>();
    private String activeConnectionId;
    private String activeTab = DEFAULT_TAB;
    private final AiAssistantWindowManager aiAssistantWindowManager =
            new AiAssistantWindowManager(this::onAiAssistantDocked);
    private final Consumer<Boolean> terminalVisibilityListener =
            visible -> Platform.runLater(this::refreshTerminalVisibilityButton);

    @FXML
    private Label tabFiles;

    @FXML
    private Label tabDocker;

    @FXML
    private Label tabK8s;

    @FXML
    private Label tabAi;

    @FXML
    private Button btnTerminalVisibility;

    @FXML
    private Button btnAiFloat;

    @FXML
    private FontIcon terminalVisibilityIcon;

    @FXML
    private StackPane contentArea;

    @FXML
    private VBox filesView;

    @FXML
    private VBox dockerView;

    @FXML
    private DockerViewController dockerViewController;

    @FXML
    private VBox k8sView;

    @FXML
    private K8sViewController k8sViewController;

    @FXML
    private StackPane aiHost;

    @FXML
    public void initialize() {
        setupViewConstraints();

        activeConnectionId = ConnectionManager.getInstance().getCurrentConnectionId();
        ConnectionManager.getInstance().addOnConnectionStateChangedListener(
                () -> Platform.runLater(this::onConnectionStateChanged));
        PanelManager.getInstance().addInteractivePanelVisibilityListener(terminalVisibilityListener);

        if (tabFiles != null) {
            tabFiles.setOnMouseClicked(e -> switchTab("files"));
        }
        if (tabDocker != null) {
            tabDocker.setOnMouseClicked(e -> switchTab("docker"));
        }
        if (tabK8s != null) {
            tabK8s.setOnMouseClicked(e -> switchTab("k8s"));
        }
        if (tabAi != null) {
            tabAi.setOnMouseClicked(e -> switchTab("ai"));
        }

        setViewVisible(dockerView, false);
        setViewVisible(k8sView, false);
        setViewVisible(aiHost, false);
        if (dockerViewController != null) {
            dockerViewController.showForConnection(activeConnectionId);
            dockerViewController.setTabVisible(false);
        }
        if (k8sViewController != null) {
            k8sViewController.showForConnection(activeConnectionId);
            k8sViewController.setTabVisible(false);
        }
        applyTab(tabForConnection(activeConnectionId));
        refreshTerminalVisibilityButton();
    }

    private void setupViewConstraints() {
        bindWidthHeight(filesView);
        bindWidthHeight(dockerView);
        bindWidthHeight(k8sView);
    }

    private void bindWidthHeight(VBox vBox) {
        if (vBox != null) {
            vBox.prefWidthProperty().bind(contentArea.widthProperty());
            vBox.prefHeightProperty().bind(contentArea.heightProperty());
        }
    }

    private void switchTab(String tabName) {
        activeTab = tabName;
        String connId = activeConnectionId;
        if (connId != null) {
            selectedTabsByConnection.put(connId, tabName);
        }
        applyTab(tabName);
    }

    public void showForConnection(String connId) {
        if (Objects.equals(activeConnectionId, connId)) {
            if (dockerViewController != null) {
                dockerViewController.showForConnection(connId);
            }
            if (k8sViewController != null) {
                k8sViewController.showForConnection(connId);
            }
            refreshTerminalVisibilityButton();
            return;
        }
        if (activeConnectionId != null && activeTab != null) {
            selectedTabsByConnection.put(activeConnectionId, activeTab);
        }
        activeConnectionId = connId;
        applyTab(tabForConnection(connId));
        refreshTerminalVisibilityButton();
    }

    public void disposeForConnection(String connId) {
        if (connId == null || connId.isBlank()) {
            return;
        }
        selectedTabsByConnection.remove(connId);
        aiAssistantWindowManager.dispose(connId);
    }

    private void onConnectionStateChanged() {
        String currentConnId = ConnectionManager.getInstance().getCurrentConnectionId();
        if (activeConnectionId == null && currentConnId != null) {
            showForConnection(currentConnId);
            return;
        }
        if (dockerViewController != null) {
            dockerViewController.showForConnection(activeConnectionId);
        }
        if (k8sViewController != null) {
            k8sViewController.showForConnection(activeConnectionId);
        }
        refreshTerminalVisibilityButton();
    }

    private String tabForConnection(String connId) {
        return connId == null ? DEFAULT_TAB : selectedTabsByConnection.getOrDefault(connId, DEFAULT_TAB);
    }

    private void applyTab(String tabName) {
        activeTab = tabName;
        if (dockerViewController != null) {
            dockerViewController.showForConnection(activeConnectionId);
        }
        if (k8sViewController != null) {
            k8sViewController.showForConnection(activeConnectionId);
        }
        if (tabFiles != null) tabFiles.getStyleClass().remove("active");
        if (tabDocker != null) tabDocker.getStyleClass().remove("active");
        if (tabK8s != null) tabK8s.getStyleClass().remove("active");
        if (tabAi != null) tabAi.getStyleClass().remove("active");

        setViewVisible(filesView, false);
        setViewVisible(dockerView, false);
        setViewVisible(k8sView, false);
        setViewVisible(aiHost, false);
        if (dockerViewController != null) {
            dockerViewController.setTabVisible("docker".equals(tabName));
        }
        if (k8sViewController != null) {
            k8sViewController.setTabVisible("k8s".equals(tabName));
        }

        switch (tabName) {
            case "files":
                if (tabFiles != null) tabFiles.getStyleClass().add("active");
                setViewVisible(filesView, true);
                break;
            case "docker":
                if (tabDocker != null) tabDocker.getStyleClass().add("active");
                setViewVisible(dockerView, true);
                break;
            case "k8s":
                if (tabK8s != null) tabK8s.getStyleClass().add("active");
                setViewVisible(k8sView, true);
                break;
            case "ai":
                if (tabAi != null) tabAi.getStyleClass().add("active");
                aiAssistantWindowManager.mountForConnection(activeConnectionId, aiHost);
                setViewVisible(aiHost, true);
                break;
        }
    }

    private void setViewVisible(Node view, boolean visible) {
        if (view == null) {
            return;
        }
        view.setVisible(visible);
        view.setManaged(visible);
    }

    @FXML
    private void toggleTerminalVisibility() {
        TerminalPanelController terminalController = terminalControllerForActiveConnection();
        if (terminalController == null) {
            return;
        }
        terminalController.setInteractivePanelVisible(!terminalController.isInteractivePanelVisible());
        refreshTerminalVisibilityButton();
    }

    @FXML
    private void showFloatingAiAssistant() {
        if (activeConnectionId == null || activeConnectionId.isBlank()) {
            return;
        }
        aiAssistantWindowManager.showFloating(activeConnectionId);
    }

    private void onAiAssistantDocked(String connId) {
        if (Objects.equals(activeConnectionId, connId)) {
            switchTab("ai");
        }
    }

    private void refreshTerminalVisibilityButton() {
        TerminalPanelController terminalController = terminalControllerForActiveConnection();
        boolean terminalVisible = terminalController != null && terminalController.isInteractivePanelVisible();
        if (btnTerminalVisibility != null) {
            btnTerminalVisibility.setDisable(terminalController == null);
            btnTerminalVisibility.setAccessibleText(terminalVisible ? "隐藏终端" : "显示终端");
        }
        if (btnAiFloat != null) {
            boolean available = activeConnectionId != null && !activeConnectionId.isBlank();
            btnAiFloat.setDisable(!available);
            btnAiFloat.setAccessibleText("浮动 AI 助手");
        }
        if (terminalVisibilityIcon != null) {
            terminalVisibilityIcon.setIconLiteral(terminalVisible ? "fas-eye-slash" : "fas-eye");
        }
    }

    private TerminalPanelController terminalControllerForActiveConnection() {
        return activeConnectionId == null
                ? null
                : ConnectionManager.getInstance().getTerminalPanelController(activeConnectionId);
    }
}

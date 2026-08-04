package com.yshell.controller;

import com.yshell.model.ConnectionTabInfo;
import com.yshell.service.ConnectionManager;
import com.yshell.ui.LayoutConfig;
import com.yshell.ui.PanelManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

public class RightPanelController implements Initializable {

    @FXML
    private StackPane contentArea;

    @FXML
    private SplitPane contentSplitPane;

    @FXML
    private StackPane terminalHost;

    @FXML
    private Node terminalPanel;

    @FXML
    private TerminalPanelController terminalPanelController;

    /**
     * 使用 fx:id + Controller 后缀自动注入子控制器
     * FXML中需要对应设置: <fx:include fx:id="connectionToolbar" source="ConnectionToolbar.fxml"/>
     */
    @FXML
    private ConnectionToolbarController connectionToolbarController;

    @FXML
    private QuickConnectPanelController quickConnectPanelController;

    /**
     * 快速连接面板节点
     */
    private Node quickConnectPanelNode;
    private Node visualPanelNode;
    private VisualPanelController visualPanelController;
    private boolean initialTerminalPanelAvailable = true;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        PanelManager pm = PanelManager.getInstance();
        pm.setContentSplitPane(contentSplitPane);
        pm.setTerminalPanelNode(terminalHost);
        pm.setVisualPanelSupplier(this::ensureVisualPanelNode);

        // 获取快速连接面板节点（StackPane中第二个子节点）
        if (contentArea.getChildren().size() > 1) {
            quickConnectPanelNode = contentArea.getChildren().get(1);
        }

        // 设置Tab切换回调
        if (connectionToolbarController != null) {
            connectionToolbarController.setOnTabChanged(this::handleTabChanged);
        }

        // 初始显示快速连接面板（默认选中"新建连接"tab）
        pm.toggleBottomPanel(LayoutConfig.getInstance().isBottomPanelVisible());
        showQuickConnect();
    }

    /**
     * Tab切换处理
     */
    private void handleTabChanged(ConnectionTabInfo tabInfo) {
        TerminalPanelController currentTerminalPanel = ConnectionManager.getInstance().getTerminalPanelController();
        if (currentTerminalPanel != null) {
            currentTerminalPanel.captureCurrentBottomPanelLayout();
        }
        if (tabInfo == null) {
            ConnectionManager.getInstance().showFilesForConnection(null);
            showQuickConnect();
        } else {
            showContent(tabInfo);
        }
    }

    /**
     * 显示终端+可视化内容区（IP连接tab时调用）
     */
    private void showContent(ConnectionTabInfo tabInfo) {
        Node activeTerminalPanel = ensureTerminalPanel(tabInfo);
        if (activeTerminalPanel == null) {
            return;
        }
        ensureTerminalHostMounted();
        showTerminalPanel(activeTerminalPanel);
        ConnectionManager.getInstance().setTerminalPanelController(tabInfo.getTerminalPanelController());
        ConnectionManager.getInstance().showFilesForConnection(tabInfo.getConnId());
        VisualPanelController visualController = ensureVisualPanelController();
        if (visualController != null) {
            visualController.showForConnection(tabInfo.getConnId());
        }
        if (quickConnectPanelNode != null) {
            quickConnectPanelNode.setVisible(false);
            quickConnectPanelNode.setManaged(false);
        }
        contentSplitPane.setVisible(true);
        contentSplitPane.setManaged(true);
        if (tabInfo.getTerminalPanelController() != null) {
            tabInfo.getTerminalPanelController().applyBottomPanelState();
            tabInfo.getTerminalPanelController().applyInteractivePanelState();
            Platform.runLater(tabInfo.getTerminalPanelController()::focusTerminal);
        }
    }

    private void ensureTerminalHostMounted() {
        if (terminalHost == null) {
            return;
        }
        if (!contentSplitPane.getItems().contains(terminalHost)) {
            contentSplitPane.getItems().add(0, terminalHost);
        } else if (contentSplitPane.getItems().indexOf(terminalHost) > 0) {
            contentSplitPane.getItems().remove(terminalHost);
            contentSplitPane.getItems().add(0, terminalHost);
        }
        PanelManager.getInstance().setTerminalPanelNode(terminalHost);
    }

    private void showTerminalPanel(Node activeTerminalPanel) {
        if (terminalHost == null) {
            return;
        }
        if (!terminalHost.getChildren().contains(activeTerminalPanel)) {
            terminalHost.getChildren().add(activeTerminalPanel);
        }
        for (Node child : terminalHost.getChildren()) {
            boolean active = child == activeTerminalPanel;
            child.setVisible(active);
            child.setManaged(active);
        }
        activeTerminalPanel.toFront();
    }

    private Node ensureTerminalPanel(ConnectionTabInfo tabInfo) {
        if (tabInfo.getTerminalPanelNode() != null) {
            return tabInfo.getTerminalPanelNode();
        }
        try {
            Node node;
            TerminalPanelController controller;
            if (initialTerminalPanelAvailable && terminalPanel != null && terminalPanelController != null) {
                node = terminalPanel;
                controller = terminalPanelController;
                initialTerminalPanelAvailable = false;
            } else {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/TerminalPanel.fxml"));
                node = loader.load();
                controller = loader.getController();
            }
            controller.configureConnection(tabInfo.getConnId(), tabInfo.getConnInfo());
            tabInfo.setTerminalPanelNode(node);
            tabInfo.setTerminalPanelController(controller);
            ConnectionManager.getInstance().registerTerminalPanel(tabInfo.getConnId(), controller);
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load terminal panel", e);
        }
    }

    /**
     * 显示快速连接面板（新建连接tab时调用）
     */
    private void showQuickConnect() {
        contentSplitPane.setVisible(false);
        contentSplitPane.setManaged(false);
        ConnectionManager.getInstance().setTerminalPanelController(null);
        if (quickConnectPanelNode != null) {
            quickConnectPanelNode.setVisible(true);
            quickConnectPanelNode.setManaged(true);
            // 刷新连接列表
            if (quickConnectPanelController != null) {
                quickConnectPanelController.refresh();
            }
        }
    }

    private Node ensureVisualPanelNode() {
        if (visualPanelNode != null) {
            return visualPanelNode;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/VisualPanel.fxml"));
            visualPanelNode = loader.load();
            visualPanelController = loader.getController();
            PanelManager.getInstance().setVisualPanelNode(visualPanelNode);
            return visualPanelNode;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load visual panel", e);
        }
    }

    private VisualPanelController ensureVisualPanelController() {
        ensureVisualPanelNode();
        return visualPanelController;
    }
}

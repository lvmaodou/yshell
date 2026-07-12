package com.yshell.controller;

import com.yshell.model.ConnInfo;
import com.yshell.model.ConnectionTabInfo;
import com.yshell.service.ConnectionManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.PopupWindow;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 顶部连接工具栏：维护多个连接 Tab 及状态。
 * <p>
 * 连接/断开逻辑与 TerminalPanelController.btnConnect 共享同一个数据源：
 * ConnectionManager.isConnected(connId)。为了让两边 UI 状态互通，
 * 本控制器通过 OnConnectionStateChangedListener 订阅 ConnectionManager 的事件，
 * 在任何地方触发的连接/断开/切换都会统一刷新 UI 颜色。
 */
public class ConnectionToolbarController {

    /**
     * 同步关闭编辑器中绑定到指定 SSH 连接的所有 Tab。
     */
    private void closeEditorTabsForConnection(String connId) {
        if (connId == null) return;
        EditorViewController.closeTabsForConnectionStatic(connId);
    }

    @FXML
    private FlowPane tabsContainer;

    @FXML
    private Button btnNewConnection;

    private final Map<HBox, ConnectionTabInfo> tabInfoMap = new LinkedHashMap<>();
    private final List<HBox> allTabs = new ArrayList<>();
    private HBox currentTab;

    @FunctionalInterface
    public interface OnTabChangedListener {
        void onTabChanged(ConnectionTabInfo tabInfo);
    }

    private OnTabChangedListener onTabChangedListener;

    public void setOnTabChanged(OnTabChangedListener listener) {
        this.onTabChangedListener = listener;
    }

    @FXML
    public void initialize() {
        btnNewConnection.setOnAction(e -> createNewTab());

        ConnectionManager.getInstance().setConnectionToolbarController(this);

        // 订阅 ConnectionManager 的状态变化事件：
        // 无论是哪里触发的连接、断开、Tab 切换，都统一在此刷新所有 Tab 的状态点颜色。
        ConnectionManager.getInstance().addOnConnectionStateChangedListener(
                () -> Platform.runLater(this::refreshAllTabsStatus));

        HBox defaultTab = buildTab("新建连接", null);
        setTabNumberText(defaultTab, "1");
        defaultTab.getStyleClass().add("active");
        allTabs.add(defaultTab);
        currentTab = defaultTab;

        tabsContainer.getChildren().add(0, defaultTab);
        // 初始化一次
        refreshAllTabsStatus();
    }

    private void selectTab(HBox tab) {
        if (currentTab.equals(tab)) {
            return;
        }
        currentTab = tab;
        for (HBox t : allTabs) {
            t.getStyleClass().remove("active");
        }
        tab.getStyleClass().add("active");

        ConnectionTabInfo tabInfo = tabInfoMap.get(tab);
        if (tabInfo != null && tabInfo.getConnId() != null) {
            ConnectionManager.getInstance().switchConnectionById(tabInfo.getConnId());
        }

        if (onTabChangedListener != null) {
            onTabChangedListener.onTabChanged(tabInfo);
        }
    }

    private void closeTab(HBox tab) {
        if (allTabs.size() <= 1) {
            ConnectionTabInfo tabInfo = tabInfoMap.remove(tab);
            if (tabInfo != null) {
                allTabs.remove(tab);
                tabsContainer.getChildren().remove(tab);
                if (tabInfo.getConnId() != null) {
                    ConnectionManager.getInstance().disconnect(tabInfo.getConnId());
                    ConnectionManager.getInstance().unregisterTerminalPanel(tabInfo.getConnId());
                    if (tabInfo.getTerminalPanelController() != null) {
                        tabInfo.getTerminalPanelController().shutdownTerminal();
                    }
                    removeTerminalPanelNode(tabInfo);
                    closeEditorTabsForConnection(tabInfo.getConnId());
                }
                createNewTab();
            }
            return;
        }

        boolean wasActive = tab.getStyleClass().contains("active");

        ConnectionTabInfo tabInfo = tabInfoMap.remove(tab);
        if (tabInfo != null && tabInfo.getConnId() != null) {
            ConnectionManager.getInstance().disconnect(tabInfo.getConnId());
            ConnectionManager.getInstance().unregisterTerminalPanel(tabInfo.getConnId());
            if (tabInfo.getTerminalPanelController() != null) {
                tabInfo.getTerminalPanelController().shutdownTerminal();
            }
            removeTerminalPanelNode(tabInfo);
            closeEditorTabsForConnection(tabInfo.getConnId());
        }

        allTabs.remove(tab);
        tabsContainer.getChildren().remove(tab);
        renumberTabs();

        if (wasActive && !allTabs.isEmpty()) {
            selectTab(allTabs.get(allTabs.size() - 1));
        }
    }

    private void removeTerminalPanelNode(ConnectionTabInfo tabInfo) {
        if (tabInfo == null || tabInfo.getTerminalPanelNode() == null) {
            return;
        }
        if (tabInfo.getTerminalPanelNode().getParent() instanceof Pane parent) {
            parent.getChildren().remove(tabInfo.getTerminalPanelNode());
        }
    }

    private void closeAllTabs() {
        for (HBox tab : new ArrayList<>(allTabs)) {
            closeTab(tab);
        }
    }

    private void closeOtherTabs(HBox excludeTab) {
        for (HBox tab : new ArrayList<>(allTabs)) {
            if (tab != excludeTab) {
                closeTab(tab);
            }
        }
    }

    private void disconnectAll() {
        for (HBox tab : allTabs) {
            ConnectionTabInfo tabInfo = tabInfoMap.get(tab);
            if (tabInfo != null && tabInfo.getConnId() != null) {
                ConnectionManager.getInstance().disconnect(tabInfo.getConnId());
                closeEditorTabsForConnection(tabInfo.getConnId());
            }
        }
    }

    private void renumberTabs() {
        int num = 1;
        for (HBox tab : allTabs) {
            setTabNumberText(tab, String.valueOf(num++));
        }
    }

    private void createNewTab() {
        HBox newTab = buildTab("新建连接", null);
        allTabs.add(newTab);
        renumberTabs();
        tabsContainer.getChildren().add(tabsContainer.getChildren().size() - 1, newTab);
        selectTab(newTab);
    }

    public void createConnectionTab(ConnectionTabInfo tabInfo) {
        String host = tabInfo.getConnInfo().getHost();
        Label label = (Label) currentTab.getChildren().get(2);
        if (label.getText().equals("新建连接")) {
            label.setText(host);
            tabInfoMap.put(currentTab, tabInfo);
            if (onTabChangedListener != null) {
                onTabChangedListener.onTabChanged(tabInfo);
            }
        } else {
            HBox newTab = buildTab(host, tabInfo);
            int insertIdx = allTabs.size();
            allTabs.add(insertIdx, newTab);
            renumberTabs();
            tabsContainer.getChildren().add(tabsContainer.getChildren().size() - 1, newTab);
            selectTab(newTab);
        }
    }

    public void refreshConnectionTab(ConnInfo connInfo, String connId) {
        if (connInfo == null || connId == null) {
            return;
        }
        for (Map.Entry<HBox, ConnectionTabInfo> entry : tabInfoMap.entrySet()) {
            ConnectionTabInfo tabInfo = entry.getValue();
            if (tabInfo == null || !connId.equals(tabInfo.getConnId())) {
                continue;
            }
            tabInfo.setConnInfo(connInfo);
            Label label = (Label) entry.getKey().getChildren().get(2);
            label.setText(connInfo.getHost() != null && !connInfo.getHost().isEmpty()
                    ? connInfo.getHost()
                    : connInfo.getName());
            break;
        }
    }

    /**
     * 刷新所有 Tab 的状态点颜色：按 ConnectionManager 中对应 connId 的实际连通性设置
     * 绿色(connected) 或 红色(disconnected)。这是唯一负责刷新 Tab 颜色的入口。
     */
    private void refreshAllTabsStatus() {
        for (HBox tab : allTabs) {
            ConnectionTabInfo tabInfo = tabInfoMap.get(tab);
            if (tabInfo == null || tabInfo.getConnId() == null) {
                continue;
            }
            boolean connected = ConnectionManager.getInstance()
                    .getAllConnections().containsKey(tabInfo.getConnId())
                    && ConnectionManager.getInstance().getConnectionById(tabInfo.getConnId()).isConnected();
            setStatusDotColor(tab, connected);
        }
    }

    private void setStatusDotColor(HBox tab, boolean connected) {
        Platform.runLater(() -> {
            for (var node : tab.getChildren()) {
                if (node instanceof Region region && region.getStyleClass().contains("tab-status-dot")) {
                    region.getStyleClass().removeAll("connected", "disconnected");
                    if (connected) {
                        region.getStyleClass().add("connected");
                    } else {
                        region.getStyleClass().add("disconnected");
                    }
                    break;
                }
            }
        });
    }

    private void connectAll(HBox hBox) {
        for (HBox tab : allTabs) {
            ConnectionTabInfo tabInfo = tabInfoMap.get(tab);
            if (tabInfo != null && !isConnected(tabInfo.getConnId())) {
                ConnectionManager.getInstance().connect(tabInfo.getConnInfo(), tabInfo.getConnId(), tab.equals(hBox));
            }
        }
    }

    private boolean isConnected(String connId) {
        if (connId == null) {
            return false;
        }
        return ConnectionManager.getInstance().getAllConnections().containsKey(connId)
                && ConnectionManager.getInstance().getConnectionById(connId).isConnected();
    }

    private boolean hasAnyConnectedTab() {
        for (HBox tab : allTabs) {
            ConnectionTabInfo tabInfo = tabInfoMap.get(tab);
            if (tabInfo != null && isConnected(tabInfo.getConnId())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyDisconnectedTab() {
        for (HBox tab : allTabs) {
            ConnectionTabInfo tabInfo = tabInfoMap.get(tab);
            if (tabInfo != null && !isConnected(tabInfo.getConnId())) {
                return true;
            }
        }
        return false;
    }

    private void showContextMenu(HBox tab, double screenX, double screenY) {
        ContextMenu contextMenu = new ContextMenu();
        contextMenu.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_TOP_LEFT);

        ConnectionTabInfo tabInfo = tabInfoMap.get(tab);
        boolean isNewConnectionTab = tabInfo == null;

        if (!isNewConnectionTab) {
            MenuItem connectItem = new MenuItem("连接");
            connectItem.setOnAction(e -> {
                ConnectionManager.getInstance().connect(tabInfo.getConnInfo(), tabInfo.getConnId(), true);
                contextMenu.hide();
            });
            boolean isConnected = isConnected(tabInfo.getConnId());
            connectItem.setDisable(isConnected);
            contextMenu.getItems().add(connectItem);

            MenuItem connectAllItem = new MenuItem("连接全部");
            connectAllItem.setOnAction(e -> {
                connectAll(tab);
                contextMenu.hide();
            });
            connectAllItem.setDisable(!hasAnyDisconnectedTab());
            contextMenu.getItems().add(connectAllItem);

            MenuItem disconnectItem = new MenuItem("断开");
            disconnectItem.setOnAction(e -> {
                if (tabInfo.getConnId() != null) {
                    ConnectionManager.getInstance().disconnect(tabInfo.getConnId());
                    closeEditorTabsForConnection(tabInfo.getConnId());
                }
                contextMenu.hide();
            });
            disconnectItem.setDisable(!isConnected);
            contextMenu.getItems().add(disconnectItem);

            MenuItem disconnectAllItem = new MenuItem("断开全部");
            disconnectAllItem.setOnAction(e -> {
                disconnectAll();
                contextMenu.hide();
            });
            disconnectAllItem.setDisable(!hasAnyConnectedTab());
            contextMenu.getItems().add(disconnectAllItem);
        }

        MenuItem closeItem = new MenuItem("关闭");
        closeItem.setOnAction(e -> {
            closeTab(tab);
            contextMenu.hide();
        });
        contextMenu.getItems().add(closeItem);

        MenuItem closeAllItem = new MenuItem("关闭所有");
        closeAllItem.setOnAction(e -> {
            closeAllTabs();
            contextMenu.hide();
        });
        contextMenu.getItems().add(closeAllItem);

        MenuItem closeOtherItem = new MenuItem("关闭其他");
        closeOtherItem.setOnAction(e -> {
            closeOtherTabs(tab);
            contextMenu.hide();
        });
        contextMenu.getItems().add(closeOtherItem);

        contextMenu.show(tab.getScene().getWindow(), screenX, screenY);
    }

    private HBox buildTab(String labelText, ConnectionTabInfo tabInfo) {
        HBox tab = new HBox();
        tab.getStyleClass().addAll("hbox-interactive", "connection-tab");

        Region statusDot = new Region();
        statusDot.getStyleClass().add("tab-status-dot");
        if (tabInfo != null) {
            statusDot.getStyleClass().add("disconnected");
        }

        Label numberLabel = new Label();
        numberLabel.getStyleClass().add("tab-number");

        Label tabLabel = new Label(labelText);
        tabLabel.getStyleClass().add("tab-label");

        Label closeBtn = new Label();
        closeBtn.getStyleClass().addAll("label-close", "tab-close");
        FontIcon closeIcon = new FontIcon("fas-times");
        closeIcon.setIconSize(12);
        closeBtn.setGraphic(closeIcon);

        tab.getChildren().addAll(statusDot, numberLabel, tabLabel, closeBtn);
        tabInfoMap.put(tab, tabInfo);

        tab.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                selectTab(tab);
            } else if (e.getButton() == MouseButton.SECONDARY) {
                selectTab(tab);
                showContextMenu(tab, e.getScreenX(), e.getScreenY());
            }
        });

        closeBtn.setOnMouseClicked(e -> {
            e.consume();
            closeTab(tab);
        });

        return tab;
    }

    private void setTabNumberText(HBox tab, String text) {
        for (var node : tab.getChildren()) {
            if (node instanceof Label label && label.getStyleClass().contains("tab-number")) {
                label.setText(text);
                break;
            }
        }
    }
}

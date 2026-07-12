package com.yshell.ui;

import com.yshell.controller.TerminalPanelController;
import com.yshell.service.ConnectionManager;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.function.Consumer;

public class DropdownMenu extends Popup {

    private final VBox menuContainer;
    private final PanelManager panelManager;

    public DropdownMenu() {
        GlobalState globalState = GlobalState.getInstance();
        panelManager = PanelManager.getInstance();
        TerminalPanelController terminalPanel = currentTerminalPanel();

        menuContainer = new VBox();
        menuContainer.getStyleClass().add("dropdown-menu-container");
        PopupStyles.applyDropdownStylesheets(menuContainer);

        addToggleItem("左侧边栏", "fas-columns", globalState.leftPanelVisibleProperty(), panelManager::toggleLeftPanel);
        addToggleItem("底栏", "fas-window-maximize",
                new SimpleBooleanProperty(isBottomPanelVisible(terminalPanel)),
                this::setBottomPanelVisible);
        addToggleItem("交互区", "fas-terminal",
                new SimpleBooleanProperty(isInteractivePanelVisible(terminalPanel)),
                this::setInteractivePanelVisible);
        addSeparator();
        addActionItem(this::toggleFullscreen);
        addSeparator();
        addToggleItem("左侧连接信息", "fas-info-circle",
                new SimpleBooleanProperty(isConnectionInfoVisible()),
                this::setConnectionInfoVisible);

        getContent().add(menuContainer);
        setAutoHide(true);
    }

    @Override
    public void show(javafx.stage.Window window, double x, double y) {
        PopupStyles.applyDropdownStylesheets(menuContainer);
        super.show(window, x, y);
    }

    private void addToggleItem(String text, String iconLiteral, BooleanProperty property, Consumer<Boolean> changeHandler) {
        HBox item = createMenuItem(text, iconLiteral);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        FontIcon checkmark = new FontIcon("fas-check");
        checkmark.getStyleClass().add("dropdown-menu-checkmark");
        checkmark.visibleProperty().bind(property);

        item.getChildren().addAll(spacer, checkmark);
        item.setOnMouseClicked(e -> {
            boolean visible = !property.get();
            property.set(visible);
            changeHandler.accept(visible);
            hide();
        });

        menuContainer.getChildren().add(item);
    }

    private void addActionItem(Runnable action) {
        HBox item = createMenuItem("全屏", "fas-expand");
        item.setOnMouseClicked(e -> {
            action.run();
            hide();
        });
        menuContainer.getChildren().add(item);
    }

    private HBox createMenuItem(String text, String iconLiteral) {
        HBox item = new HBox();
        item.getStyleClass().addAll("hbox-interactive", "dropdown-menu-item");

        FontIcon icon = new FontIcon(iconLiteral);
        icon.getStyleClass().add("dropdown-menu-icon");

        Label label = new Label(text);
        label.getStyleClass().add("dropdown-menu-label");

        item.getChildren().addAll(icon, label);
        return item;
    }

    private void addSeparator() {
        Region separator = new Region();
        separator.getStyleClass().add("dropdown-menu-separator");
        menuContainer.getChildren().add(separator);
    }

    private TerminalPanelController currentTerminalPanel() {
        return ConnectionManager.getInstance().getTerminalPanelController();
    }

    private boolean isBottomPanelVisible(TerminalPanelController terminalPanel) {
        return terminalPanel != null
                ? terminalPanel.isBottomPanelVisible()
                : panelManager.isBottomPanelVisible();
    }

    private boolean isInteractivePanelVisible(TerminalPanelController terminalPanel) {
        return terminalPanel != null
                ? terminalPanel.isInteractivePanelVisible()
                : panelManager.isInteractivePanelVisible();
    }

    private void setBottomPanelVisible(boolean visible) {
        TerminalPanelController terminalPanel = currentTerminalPanel();
        if (terminalPanel != null) {
            terminalPanel.setBottomPanelVisible(visible);
        } else {
            panelManager.toggleBottomPanel(visible);
        }
    }

    private void setInteractivePanelVisible(boolean visible) {
        TerminalPanelController terminalPanel = currentTerminalPanel();
        if (terminalPanel != null) {
            terminalPanel.setInteractivePanelVisible(visible);
        } else {
            GlobalState.getInstance().setInteractivePanelVisible(visible);
            panelManager.toggleInteractivePanel(visible);
        }
    }

    private boolean isConnectionInfoVisible() {
        return !panelManager.isSystemInfoVisible();
    }

    private void setConnectionInfoVisible(boolean visible) {
        panelManager.toggleSystemInfo(!visible);
    }

    private void toggleFullscreen() {
        TerminalPanelController terminalPanel = currentTerminalPanel();
        if (terminalPanel != null) {
            terminalPanel.toggleFullScreen();
        } else {
            panelManager.toggleFullscreen();
        }
    }

    public void show(Node anchor) {
        Point2D screenCoords = anchor.localToScreen(0, anchor.getBoundsInLocal().getHeight());
        show(anchor.getScene().getWindow(), screenCoords.getX(), screenCoords.getY());
    }

    public static class GlobalState {
        private static GlobalState instance;

        private final BooleanProperty leftPanelVisible = new SimpleBooleanProperty(true);
        private final BooleanProperty bottomPanelVisible = new SimpleBooleanProperty(true);
        private final BooleanProperty interactivePanelVisible = new SimpleBooleanProperty(true);
        private final BooleanProperty connTreeVisible = new SimpleBooleanProperty(true);

        private GlobalState() {
        }

        public static GlobalState getInstance() {
            if (instance == null) {
                instance = new GlobalState();
            }
            return instance;
        }

        public BooleanProperty leftPanelVisibleProperty() {
            return leftPanelVisible;
        }

        public boolean isLeftPanelVisible() {
            return leftPanelVisible.get();
        }

        public void setLeftPanelVisible(boolean visible) {
            leftPanelVisible.set(visible);
        }

        public BooleanProperty bottomPanelVisibleProperty() {
            return bottomPanelVisible;
        }

        public boolean isBottomPanelVisible() {
            return bottomPanelVisible.get();
        }

        public void setBottomPanelVisible(boolean visible) {
            bottomPanelVisible.set(visible);
        }

        public BooleanProperty interactivePanelVisibleProperty() {
            return interactivePanelVisible;
        }

        public boolean isInteractivePanelVisible() {
            return interactivePanelVisible.get();
        }

        public void setInteractivePanelVisible(boolean visible) {
            interactivePanelVisible.set(visible);
        }

        public BooleanProperty connTreeVisibleProperty() {
            return connTreeVisible;
        }

        public boolean isConnTreeVisible() {
            return connTreeVisible.get();
        }

        public void setConnTreeVisible(boolean visible) {
            connTreeVisible.set(visible);
        }
    }
}

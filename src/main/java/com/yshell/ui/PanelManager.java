package com.yshell.ui;

import com.yshell.MainApplication;
import com.yshell.service.ConnectionManager;
import javafx.animation.FadeTransition;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PanelManager {

    private static final double DEFAULT_LEFT_PANEL_WIDTH = 300.0;
    private static final double FULLSCREEN_HINT_DISPLAY_SECONDS = 1.0;
    private static final double FULLSCREEN_HINT_FADE_SECONDS = 0.2;
    private static final String FULLSCREEN_HINT_TEXT = "按 ESC 可退出全屏模式。";

    private static PanelManager instance;

    private SplitPane mainSplitPane;
    private SplitPane contentSplitPane;
    private Node leftPanelNode;
    private Node terminalPanelNode;
    private Node visualPanelNode;
    private Node systemInfoNode;
    private Node connectionInfoNode;
    private Supplier<Node> visualPanelSupplier;

    private final List<Consumer<Boolean>> bottomPanelListeners = new ArrayList<>();
    private final List<Consumer<Boolean>> terminalFullscreenListeners = new ArrayList<>();
    private final Set<SplitPane> autoSaveSplitPanes = Collections.newSetFromMap(new IdentityHashMap<>());
    private double rememberedLeftPanelWidth = DEFAULT_LEFT_PANEL_WIDTH;
    private boolean updatingMainDivider;
    private boolean adjustingMainDividerForResize;

    // 面板可见性状态
    private boolean leftPanelVisible = true;
    private boolean bottomPanelVisible = true;
    private boolean interactivePanelVisible = true;
    private boolean systemInfoVisible = true;
    private boolean forceConnectionInfoVisible;
    private boolean terminalFullscreenActive;
    private boolean restoringTerminalFullscreen;
    private Node fullscreenNode;
    private Parent fullscreenOriginalRoot;
    private Pane fullscreenOriginalParent;
    private int fullscreenOriginalIndex = -1;
    private Scene fullscreenScene;
    private Stage fullscreenStage;
    private boolean fullscreenListenerInstalled;
    private StackPane fullscreenWrapper;
    private StackPane fullscreenHintPane;
    private FadeTransition fullscreenHintTransition;
    private String fullscreenPreviousExitHint;

    public static PanelManager getInstance() {
        if (instance == null) {
            instance = new PanelManager();
        }
        return instance;
    }

    public void setMainSplitPane(SplitPane pane) {
        this.mainSplitPane = pane;
        installDividerAutoSave(pane);
        installMainLeftPanelWidthKeeper(pane);
        if (pane != null && !pane.getItems().isEmpty()) {
            leftPanelNode = pane.getItems().get(0);
            // 设置左侧面板固定宽度
            if (!LayoutConfig.getInstance().hasMainDividerPosition()) {
                applyLeftPanelWidth(pane);
            }
        }
    }

    public void setContentSplitPane(SplitPane pane) {
        this.contentSplitPane = pane;
        installDividerAutoSave(pane);
        if (pane != null && !pane.getItems().isEmpty()) {
            terminalPanelNode = pane.getItems().get(0);
            if (pane.getItems().size() > 1) {
                visualPanelNode = pane.getItems().get(1);
            }
        }
    }

    public void setSystemInfoNode(Node node) {
        this.systemInfoNode = node;
    }

    public void setConnectionInfoNode(Node node) {
        this.connectionInfoNode = node;
    }

    public void setTerminalPanelNode(Node node) {
        this.terminalPanelNode = node;
    }

    public void setVisualPanelNode(Node node) {
        this.visualPanelNode = node;
    }

    public void setVisualPanelSupplier(Supplier<Node> supplier) {
        this.visualPanelSupplier = supplier;
    }

    public void addBottomPanelVisibilityListener(Consumer<Boolean> listener) {
        if (listener != null && !bottomPanelListeners.contains(listener)) {
            bottomPanelListeners.add(listener);
            listener.accept(bottomPanelVisible);
        }
    }

    public void removeBottomPanelVisibilityListener(Consumer<Boolean> listener) {
        bottomPanelListeners.remove(listener);
    }

    public void addTerminalFullscreenListener(Consumer<Boolean> listener) {
        if (listener != null && !terminalFullscreenListeners.contains(listener)) {
            terminalFullscreenListeners.add(listener);
            listener.accept(terminalFullscreenActive);
        }
    }

    public void removeTerminalFullscreenListener(Consumer<Boolean> listener) {
        terminalFullscreenListeners.remove(listener);
    }

    /**
     * 应用左侧面板固定宽度（300px），根据总宽度计算分割比例
     */
    private void applyLeftPanelWidth(SplitPane pane) {
        if (pane == null) {
            return;
        }
        if (pane.getWidth() <= 0) {
            // 窗口尚未显示，等待布局完成后设置
            ChangeListener<Number> initialWidthListener = new ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends Number> obs,
                                    Number oldVal,
                                    Number newVal) {
                    if (newVal.doubleValue() > 0) {
                        pane.widthProperty().removeListener(this);
                        rememberedLeftPanelWidth = DEFAULT_LEFT_PANEL_WIDTH;
                        setMainDividerForLeftWidth(pane, rememberedLeftPanelWidth);
                    }
                }
            };
            pane.widthProperty().addListener(initialWidthListener);
        } else {
            rememberedLeftPanelWidth = DEFAULT_LEFT_PANEL_WIDTH;
            setMainDividerForLeftWidth(pane, rememberedLeftPanelWidth);
        }
    }

    private void installMainLeftPanelWidthKeeper(SplitPane pane) {
        if (pane == null) {
            return;
        }
        pane.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (!leftPanelVisible || pane.getItems().size() < 2 || newVal.doubleValue() <= 0) {
                return;
            }
            if (updatingMainDivider) {
                return;
            }
            if (oldVal.doubleValue() <= 0 && pane.getDividerPositions().length > 0) {
                rememberedLeftPanelWidth = pane.getDividerPositions()[0] * newVal.doubleValue();
            }
            adjustingMainDividerForResize = true;
            try {
                setMainDividerForLeftWidth(pane, rememberedLeftPanelWidth);
            } finally {
                adjustingMainDividerForResize = false;
            }
        });
    }

    private void rememberCurrentLeftPanelWidth() {
        if (mainSplitPane == null || mainSplitPane.getDividerPositions().length == 0 || mainSplitPane.getWidth() <= 0) {
            return;
        }
        rememberedLeftPanelWidth = mainSplitPane.getDividerPositions()[0] * mainSplitPane.getWidth();
    }

    private void setMainDividerForLeftWidth(SplitPane pane, double leftWidth) {
        if (pane == null || pane.getWidth() <= 0) {
            return;
        }
        updatingMainDivider = true;
        try {
            pane.setDividerPositions(clamp(leftWidth / pane.getWidth()));
        } finally {
            updatingMainDivider = false;
        }
    }

    public void toggleLeftPanel(boolean visible) {
        leftPanelVisible = visible;
        DropdownMenu.GlobalState.getInstance().setLeftPanelVisible(visible);
        if (mainSplitPane == null || leftPanelNode == null) {
            LayoutConfig.getInstance().requestSave();
            return;
        }

        if (visible) {
            if (!mainSplitPane.getItems().contains(leftPanelNode)) {
                mainSplitPane.getItems().add(0, leftPanelNode);
                setMainDividerForLeftWidth(mainSplitPane, rememberedLeftPanelWidth);
            }
        } else {
            mainSplitPane.getItems().remove(leftPanelNode);
        }
        LayoutConfig.getInstance().requestSave();
    }

    public void toggleBottomPanel(boolean visible) {
        if (!visible && !interactivePanelVisible) {
            visible = true;
        }
        bottomPanelVisible = visible;
        DropdownMenu.GlobalState.getInstance().setBottomPanelVisible(visible);
        Node node = ensureVisualPanelNode();
        if (contentSplitPane == null || node == null) {
            fireBottomPanelListeners();
            LayoutConfig.getInstance().requestSave();
            return;
        }

        if (visible) {
            if (!contentSplitPane.getItems().contains(node)) {
                contentSplitPane.getItems().add(node);
                restoreContentDivider();
            }
            node.setVisible(true);
            node.setManaged(true);
        } else {
            node.setVisible(false);
            node.setManaged(false);
            contentSplitPane.getItems().remove(node);
        }
        fireBottomPanelListeners();
        LayoutConfig.getInstance().requestSave();
    }

    public void toggleInteractivePanel(boolean visible) {
        if (!visible && !bottomPanelVisible) {
            visible = true;
        }
        interactivePanelVisible = visible;
        DropdownMenu.GlobalState.getInstance().setInteractivePanelVisible(visible);
        if (contentSplitPane == null || terminalPanelNode == null) {
            LayoutConfig.getInstance().requestSave();
            return;
        }

        if (visible) {
            if (contentSplitPane.getItems().isEmpty() || !contentSplitPane.getItems().contains(terminalPanelNode)) {
                contentSplitPane.getItems().add(0, terminalPanelNode);
                if (contentSplitPane.getItems().size() > 1) {
                    restoreContentDivider();
                }
            }
        } else {
            contentSplitPane.getItems().remove(terminalPanelNode);
        }
        LayoutConfig.getInstance().requestSave();
    }

    public void toggleSystemInfo(boolean visible) {
        systemInfoVisible = visible;
        DropdownMenu.GlobalState.getInstance().setConnTreeVisible(visible);
        applyLeftContentVisibility();
        LayoutConfig.getInstance().requestSave();
    }

    public void setForceConnectionInfoVisible(boolean forceVisible) {
        forceConnectionInfoVisible = forceVisible;
        applyLeftContentVisibility();
    }

    private void applyLeftContentVisibility() {
        boolean showSystemInfo = systemInfoVisible && !forceConnectionInfoVisible;
        setNodeVisible(systemInfoNode, showSystemInfo);
        setNodeVisible(connectionInfoNode, !showSystemInfo);
        ConnectionManager.getInstance().onSystemInfoPanelVisibilityChanged(showSystemInfo);
    }

    private void setNodeVisible(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    public void toggleFullscreen() {
        if (fullscreenNode instanceof Parent parent && terminalFullscreenActive) {
            toggleTerminalFullscreen(parent);
            return;
        }
        if (MainApplication.getPrimaryStage() != null) {
            Stage stage = MainApplication.getPrimaryStage();
            stage.setFullScreen(!stage.isFullScreen());
        }
    }

    public void toggleTerminalFullscreen(Parent terminalRoot) {
        if (terminalRoot == null || terminalRoot.getScene() == null) return;

        if (terminalFullscreenActive && fullscreenNode == terminalRoot) {
            exitTerminalFullscreen();
            return;
        }

        enterTerminalFullscreen(terminalRoot);
    }

    public boolean isTerminalFullscreen() {
        return terminalFullscreenActive;
    }

    public void exitTerminalFullscreen(Parent terminalRoot) {
        if (fullscreenNode == terminalRoot) {
            exitTerminalFullscreen();
        }
    }

    private Node ensureVisualPanelNode() {
        if (visualPanelNode == null && visualPanelSupplier != null) {
            visualPanelNode = visualPanelSupplier.get();
        }
        return visualPanelNode;
    }

    private void enterTerminalFullscreen(Parent terminalRoot) {
        Scene scene = terminalRoot.getScene();
        if (scene == null || scene.getWindow() == null) return;

        if (terminalFullscreenActive) {
            exitTerminalFullscreen();
        }

        fullscreenScene = scene;
        fullscreenStage = (Stage) scene.getWindow();
        fullscreenNode = terminalRoot;
        fullscreenOriginalRoot = scene.getRoot();
        fullscreenOriginalParent = terminalRoot.getParent() instanceof Pane pane ? pane : null;
        if (fullscreenOriginalParent != null) {
            fullscreenOriginalIndex = fullscreenOriginalParent.getChildren().indexOf(terminalRoot);
            fullscreenOriginalParent.getChildren().remove(terminalRoot);
        } else {
            fullscreenOriginalIndex = -1;
        }

        installFullscreenListener();
        fullscreenWrapper = new StackPane(terminalRoot);
        fullscreenScene.setRoot(fullscreenWrapper);
        terminalFullscreenActive = true;
        fullscreenPreviousExitHint = fullscreenStage.getFullScreenExitHint();
        fullscreenStage.setFullScreenExitHint("");
        fullscreenStage.setFullScreen(true);
        showFullscreenHint();
        fireTerminalFullscreenListeners();
    }

    private void exitTerminalFullscreen() {
        if (!terminalFullscreenActive || fullscreenScene == null) return;

        restoringTerminalFullscreen = true;
        try {
            if (fullscreenStage != null) {
                fullscreenStage.setFullScreen(false);
                fullscreenStage.setFullScreenExitHint(fullscreenPreviousExitHint);
            }
            if (fullscreenOriginalRoot != null && fullscreenScene.getRoot() == fullscreenWrapper) {
                fullscreenScene.setRoot(fullscreenOriginalRoot);
            }
            hideFullscreenHint();
            if (fullscreenWrapper != null && fullscreenNode != null) {
                fullscreenWrapper.getChildren().remove(fullscreenNode);
            }
            if (fullscreenOriginalParent != null && fullscreenNode != null) {
                if (fullscreenOriginalIndex >= 0 && fullscreenOriginalIndex <= fullscreenOriginalParent.getChildren().size()) {
                    fullscreenOriginalParent.getChildren().add(fullscreenOriginalIndex, fullscreenNode);
                } else {
                    fullscreenOriginalParent.getChildren().add(fullscreenNode);
                }
            }
        } finally {
            terminalFullscreenActive = false;
            fullscreenNode = null;
            fullscreenOriginalRoot = null;
            fullscreenOriginalParent = null;
            fullscreenOriginalIndex = -1;
            fullscreenScene = null;
            fullscreenStage = null;
            fullscreenWrapper = null;
            fullscreenPreviousExitHint = null;
            restoringTerminalFullscreen = false;
            fireTerminalFullscreenListeners();
        }
    }

    private void installFullscreenListener() {
        if (fullscreenListenerInstalled || fullscreenStage == null) {
            return;
        }
        fullscreenListenerInstalled = true;
        fullscreenStage.fullScreenProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal && terminalFullscreenActive && !restoringTerminalFullscreen) {
                exitTerminalFullscreen();
            }
        });
    }

    private void showFullscreenHint() {
        if (fullscreenWrapper == null) {
            return;
        }
        hideFullscreenHint();

        Label label = new Label(FULLSCREEN_HINT_TEXT);
        label.getStyleClass().add("label-fullscreen-hint");

        fullscreenHintPane = new StackPane(label);
        fullscreenHintPane.getStyleClass().add("stack-pane-fullscreen-hint");
        fullscreenHintPane.setMouseTransparent(true);
        fullscreenHintPane.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);
        StackPane.setAlignment(fullscreenHintPane, Pos.CENTER);
        fullscreenWrapper.getChildren().add(fullscreenHintPane);

        fullscreenHintTransition = new FadeTransition(Duration.seconds(FULLSCREEN_HINT_FADE_SECONDS), fullscreenHintPane);
        fullscreenHintTransition.setDelay(Duration.seconds(FULLSCREEN_HINT_DISPLAY_SECONDS));
        fullscreenHintTransition.setFromValue(1.0);
        fullscreenHintTransition.setToValue(0.0);
        fullscreenHintTransition.setOnFinished(event -> hideFullscreenHint());
        fullscreenHintTransition.play();
    }

    private void hideFullscreenHint() {
        if (fullscreenHintTransition != null) {
            fullscreenHintTransition.stop();
            fullscreenHintTransition = null;
        }
        if (fullscreenHintPane != null && fullscreenHintPane.getParent() instanceof Pane parent) {
            parent.getChildren().remove(fullscreenHintPane);
        }
        fullscreenHintPane = null;
    }

    private void fireBottomPanelListeners() {
        for (Consumer<Boolean> listener : new ArrayList<>(bottomPanelListeners)) {
            listener.accept(bottomPanelVisible);
        }
    }

    private void fireTerminalFullscreenListeners() {
        for (Consumer<Boolean> listener : new ArrayList<>(terminalFullscreenListeners)) {
            listener.accept(terminalFullscreenActive);
        }
    }

    /**
     * 恢复内容分割面板的分割位置（从配置中读取）
     */
    public void restoreContentDivider() {
        LayoutConfig config = LayoutConfig.getInstance();
        if (contentSplitPane == null || contentSplitPane.getItems().size() < 2) {
            return;
        }
        if (config.hasContentDividerPosition()) {
            contentSplitPane.setDividerPositions(config.getContentDividerPosition());
        } else {
            contentSplitPane.setDividerPositions(0.5);
        }
    }

    /**
     * 恢复主分割面板的分割位置（从配置中读取）
     */
    public void restoreMainDivider() {
        LayoutConfig config = LayoutConfig.getInstance();
        if (mainSplitPane == null) {
            return;
        }
        if (config.hasMainDividerPosition()) {
            mainSplitPane.setDividerPositions(config.getMainDividerPosition());
            rememberCurrentLeftPanelWidth();
        } else {
            applyLeftPanelWidth(mainSplitPane);
        }
    }

    // Getter 方法供 LayoutConfig 使用

    private void installDividerAutoSave(SplitPane pane) {
        if (pane == null || !autoSaveSplitPanes.add(pane)) {
            return;
        }
        for (SplitPane.Divider divider : pane.getDividers()) {
            installDividerAutoSave(divider);
        }
        pane.getDividers().addListener((ListChangeListener<SplitPane.Divider>) change -> {
            while (change.next()) {
                for (SplitPane.Divider divider : change.getAddedSubList()) {
                    installDividerAutoSave(divider);
                }
            }
        });
    }

    private void installDividerAutoSave(SplitPane.Divider divider) {
        divider.positionProperty().addListener((obs, oldVal, newVal) -> {
            if (mainSplitPane != null && mainSplitPane.getDividers().contains(divider)) {
                if (!updatingMainDivider && !adjustingMainDividerForResize) {
                    rememberCurrentLeftPanelWidth();
                }
            }
            LayoutConfig.getInstance().requestSave();
        });
    }

    public SplitPane getMainSplitPane() {
        return mainSplitPane;
    }

    public SplitPane getContentSplitPane() {
        return contentSplitPane;
    }

    public boolean isLeftPanelVisible() {
        return leftPanelVisible;
    }

    public boolean isBottomPanelVisible() {
        return bottomPanelVisible;
    }

    public boolean isInteractivePanelVisible() {
        return interactivePanelVisible;
    }

    public boolean isSystemInfoVisible() {
        return systemInfoVisible;
    }

    public boolean isSystemInfoPanelVisible() {
        return systemInfoVisible && !forceConnectionInfoVisible;
    }

    private double clamp(double value) {
        return Math.max(0.1, Math.min(0.5, value));
    }
}

package com.yshell.ui;

import com.yshell.config.AppConfig;
import com.yshell.config.AppConfigStore;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LayoutConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(LayoutConfig.class);

    private double windowX = Double.NaN;
    private double windowY = Double.NaN;
    private double windowWidth = 1200;
    private double windowHeight = 800;
    private boolean windowMaximized = false;
    private double mainDividerPosition = Double.NaN;
    private double contentDividerPosition = Double.NaN;
    private boolean leftPanelVisible = true;
    private boolean bottomPanelVisible = true;
    private boolean interactivePanelVisible = true;
    private boolean systemInfoVisible = true;
    private boolean startupUpdatePromptSuppressed = false;
    private boolean layoutResetPending = false;
    private PauseTransition delayedSave;

    private static LayoutConfig instance;

    public static LayoutConfig getInstance() {
        if (instance == null) {
            instance = new LayoutConfig();
            instance.load();
        }
        return instance;
    }

    public void load() {
        AppConfig config = AppConfigStore.getInstance().getConfig();
        AppConfig.Layout layout = config.layout;
        windowX = layout.windowX == null ? Double.NaN : layout.windowX;
        windowY = layout.windowY == null ? Double.NaN : layout.windowY;
        windowWidth = layout.windowWidth;
        windowHeight = layout.windowHeight;
        windowMaximized = layout.windowMaximized;
        mainDividerPosition = layout.mainDividerPosition == null ? Double.NaN : layout.mainDividerPosition;
        contentDividerPosition = layout.contentDividerPosition == null ? Double.NaN : layout.contentDividerPosition;
        leftPanelVisible = layout.leftPanelVisible;
        bottomPanelVisible = layout.bottomPanelVisible;
        interactivePanelVisible = layout.interactivePanelVisible;
        systemInfoVisible = layout.systemInfoVisible;
        startupUpdatePromptSuppressed = config.update.startupPromptSuppressed;
    }

    public void save() {
        if (delayedSave != null) {
            delayedSave.stop();
        }

        if (!layoutResetPending) {
            Stage stage = com.yshell.MainApplication.getPrimaryStage();
            if (stage != null) {
                windowMaximized = stage.isMaximized();
                if (!stage.isMaximized() && !stage.isFullScreen()) {
                    windowX = stage.getX();
                    windowY = stage.getY();
                    windowWidth = stage.getWidth();
                    windowHeight = stage.getHeight();
                }
            }

            PanelManager pm = PanelManager.getInstance();
            if (pm.getMainSplitPane() != null && pm.getMainSplitPane().getDividerPositions().length > 0) {
                mainDividerPosition = pm.getMainSplitPane().getDividerPositions()[0];
            }
            if (pm.getContentSplitPane() != null && pm.getContentSplitPane().getDividerPositions().length > 0) {
                contentDividerPosition = pm.getContentSplitPane().getDividerPositions()[0];
            }

            leftPanelVisible = pm.isLeftPanelVisible();
            bottomPanelVisible = pm.isBottomPanelVisible();
            interactivePanelVisible = pm.isInteractivePanelVisible();
            systemInfoVisible = pm.isSystemInfoVisible();
        }

        AppConfig config = AppConfigStore.getInstance().getConfig();
        AppConfig.Layout layout = config.layout;
        layout.windowX = Double.isNaN(windowX) ? null : windowX;
        layout.windowY = Double.isNaN(windowY) ? null : windowY;
        layout.windowWidth = windowWidth;
        layout.windowHeight = windowHeight;
        layout.windowMaximized = windowMaximized;
        layout.mainDividerPosition = Double.isNaN(mainDividerPosition) ? null : mainDividerPosition;
        layout.contentDividerPosition = Double.isNaN(contentDividerPosition) ? null : contentDividerPosition;
        layout.leftPanelVisible = leftPanelVisible;
        layout.bottomPanelVisible = bottomPanelVisible;
        layout.interactivePanelVisible = interactivePanelVisible;
        layout.systemInfoVisible = systemInfoVisible;
        config.update.startupPromptSuppressed = startupUpdatePromptSuppressed;
        AppConfigStore.getInstance().save();
        LOGGER.debug("[LayoutConfig] 布局配置已保存到: {}", AppConfigStore.getInstance().getConfigPath());
    }

    public void applyWindowSize(Stage stage) {
        // 确保窗口在可见屏幕范围内
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();

        double width = clamp(windowWidth, 800, bounds.getWidth());
        double height = clamp(windowHeight, 600, bounds.getHeight());

        stage.setWidth(width);
        stage.setHeight(height);
        stage.setMaximized(windowMaximized);

        if (!Double.isNaN(windowX) && !Double.isNaN(windowY)) {
            double x = clamp(windowX, bounds.getMinX(), bounds.getMaxX() - width);
            double y = clamp(windowY, bounds.getMinY(), bounds.getMaxY() - height);
            stage.setX(x);
            stage.setY(y);
        }
    }

    public boolean hasMainDividerPosition() {
        return !Double.isNaN(mainDividerPosition);
    }

    public double getMainDividerPosition() {
        return mainDividerPosition;
    }

    public boolean hasContentDividerPosition() {
        return !Double.isNaN(contentDividerPosition);
    }

    public double getContentDividerPosition() {
        return contentDividerPosition;
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

    public boolean isStartupUpdatePromptSuppressed() {
        return startupUpdatePromptSuppressed;
    }

    public void setStartupUpdatePromptSuppressed(boolean startupUpdatePromptSuppressed) {
        if (this.startupUpdatePromptSuppressed == startupUpdatePromptSuppressed) {
            return;
        }
        this.startupUpdatePromptSuppressed = startupUpdatePromptSuppressed;
        requestSave();
    }

    public void installStageAutoSave(Stage stage) {
        if (stage == null) {
            return;
        }
        stage.xProperty().addListener((obs, oldVal, newVal) -> requestSave());
        stage.yProperty().addListener((obs, oldVal, newVal) -> requestSave());
        stage.widthProperty().addListener((obs, oldVal, newVal) -> requestSave());
        stage.heightProperty().addListener((obs, oldVal, newVal) -> requestSave());
        stage.maximizedProperty().addListener((obs, oldVal, newVal) -> requestSave());
        stage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, event -> save());
    }

    public void requestSave() {
        if (Platform.isFxApplicationThread()) {
            scheduleSave();
        } else {
            Platform.runLater(this::scheduleSave);
        }
    }

    public void reset() {
        if (delayedSave != null) {
            delayedSave.stop();
        }
        windowX = Double.NaN;
        windowY = Double.NaN;
        windowWidth = 1200;
        windowHeight = 800;
        windowMaximized = false;
        mainDividerPosition = Double.NaN;
        contentDividerPosition = Double.NaN;
        leftPanelVisible = true;
        bottomPanelVisible = true;
        interactivePanelVisible = true;
        systemInfoVisible = true;
        startupUpdatePromptSuppressed = false;
        layoutResetPending = true;
        AppConfig config = AppConfigStore.getInstance().getConfig();
        config.layout = new AppConfig.Layout();
        config.update.startupPromptSuppressed = false;
        AppConfigStore.getInstance().save();
    }

    private void scheduleSave() {
        if (delayedSave == null) {
            delayedSave = new PauseTransition(Duration.millis(500));
            delayedSave.setOnFinished(event -> save());
        }
        delayedSave.playFromStart();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

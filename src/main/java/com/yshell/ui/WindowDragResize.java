package com.yshell.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 窗口拖动和8方向调整大小的公共工具类
 * <p>
 * 使用示例：
 * <pre>
 *   // 支持拖动 + 8方向调整大小（headerHeight 为标题栏高度）
 *   WindowDragResize.apply(root, 40, closeButton);
 *
 *   // 仅支持拖动，不支持8方向调整大小（headerHeight 传 -1）
 *   WindowDragResize.apply(root, -1, closeButton);
 * </pre>
 */
public class WindowDragResize {

    private Parent root;

    private double xOffset = 0;
    private double yOffset = 0;
    private boolean canDrag = false;

    private double startX = 0;
    private double startY = 0;
    private double startWidth = 0;
    private double startHeight = 0;
    private double startStageX = 0;
    private double startStageY = 0;
    private String resizeDirection = "";

    private final double headerHeight;
    private final boolean supportResize;
    private final int resizeMargin;
    private final double minWidth;
    private final double minHeight;
    private final List<Node> dragExcludeNodes = new ArrayList<>();

    /**
     * 应用拖动和调整大小功能到指定根节点（排除特定节点不触发拖动）
     *
     * @param root             根节点
     * @param headerHeight     标题栏高度，传 -1 表示仅支持拖动、不支持8方向调整大小
     * @param dragExcludeNodes 排除的节点列表（如关闭按钮等），这些节点上不会触发拖动
     */
    public static void apply(Parent root, double headerHeight, Node... dragExcludeNodes) {
        WindowDragResize instance = new WindowDragResize(headerHeight);
        Collections.addAll(instance.dragExcludeNodes, dragExcludeNodes);
        instance.bindTo(root);
    }

    /**
     * 完整参数构造
     *
     * @param root         根节点
     * @param headerHeight 标题栏高度
     * @param resizeMargin 调整大小的边缘检测范围（像素）
     * @param minWidth     最小宽度
     * @param minHeight    最小高度
     */
    public static WindowDragResize apply(Parent root, double headerHeight, int resizeMargin,
                                         double minWidth, double minHeight) {
        WindowDragResize instance = new WindowDragResize(headerHeight, resizeMargin, minWidth, minHeight);
        instance.bindTo(root);
        return instance;
    }

    private WindowDragResize(double headerHeight) {
        this(headerHeight, 6, 500, 400);
    }

    private WindowDragResize(double headerHeight, int resizeMargin, double minWidth, double minHeight) {
        this.headerHeight = headerHeight;
        this.supportResize = headerHeight != -1;
        this.resizeMargin = resizeMargin;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    /**
     * 是否正在调整大小（用于按钮事件中排除调整大小操作）
     */
    public boolean isResizing() {
        return !resizeDirection.isEmpty();
    }

    /**
     * 添加排除节点（用于先 apply 再补充排除节点的场景）
     */
    public void addExcludeNodes(Node... nodes) {
        Collections.addAll(dragExcludeNodes, nodes);
    }

    private void bindTo(Parent root) {
        this.root = root;
        Scene existingScene = root.getScene();
        if (existingScene != null) {
            attachFilters(existingScene);
        }
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                detachFilters(oldScene);
            }
            if (newScene != null) {
                attachFilters(newScene);
            }
        });
    }

    private void attachFilters(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
    }

    private void detachFilters(Scene scene) {
        scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        scene.removeEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        scene.removeEventFilter(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
        resizeDirection = "";
        canDrag = false;
    }

    private void onMousePressed(MouseEvent event) {
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        if (supportResize && !resizeDirection.isEmpty()) {
            Stage stage = (Stage) scene.getWindow();
            startX = event.getScreenX();
            startY = event.getScreenY();
            startWidth = stage.getWidth();
            startHeight = stage.getHeight();
            startStageX = stage.getX();
            startStageY = stage.getY();
            event.consume();
        } else {
            Object target = event.getTarget();
            if (isExcludedNode(target)) {
                canDrag = false;
            } else if (supportResize && event.getSceneY() > headerHeight) {
                canDrag = false;
            } else {
                canDrag = true;
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            }
        }
    }

    private boolean isExcludedNode(Object target) {
        if (target instanceof Node node) {
            for (Node excluded : dragExcludeNodes) {
                if (node == excluded) {
                    return true;
                }
            }
            if (node.getParent() != null) {
                return isExcludedNode(node.getParent());
            }
        }
        return false;
    }

    private void onMouseDragged(MouseEvent event) {
        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        Stage stage = (Stage) scene.getWindow();

        if (supportResize && !resizeDirection.isEmpty()) {
            double deltaX = event.getScreenX() - startX;
            double deltaY = event.getScreenY() - startY;

            switch (resizeDirection) {
                case "SE":
                    stage.setWidth(Math.max(minWidth, startWidth + deltaX));
                    stage.setHeight(Math.max(minHeight, startHeight + deltaY));
                    break;
                case "SW":
                    stage.setX(startStageX + deltaX);
                    stage.setWidth(Math.max(minWidth, startWidth - deltaX));
                    stage.setHeight(Math.max(minHeight, startHeight + deltaY));
                    break;
                case "NE":
                    stage.setY(startStageY + deltaY);
                    stage.setWidth(Math.max(minWidth, startWidth + deltaX));
                    stage.setHeight(Math.max(minHeight, startHeight - deltaY));
                    break;
                case "NW":
                    stage.setX(startStageX + deltaX);
                    stage.setY(startStageY + deltaY);
                    stage.setWidth(Math.max(minWidth, startWidth - deltaX));
                    stage.setHeight(Math.max(minHeight, startHeight - deltaY));
                    break;
                case "E":
                    stage.setWidth(Math.max(minWidth, startWidth + deltaX));
                    break;
                case "W":
                    stage.setX(startStageX + deltaX);
                    stage.setWidth(Math.max(minWidth, startWidth - deltaX));
                    break;
                case "S":
                    stage.setHeight(Math.max(minHeight, startHeight + deltaY));
                    break;
                case "N":
                    stage.setY(startStageY + deltaY);
                    stage.setHeight(Math.max(minHeight, startHeight - deltaY));
                    break;
            }
            event.consume();
        } else if (canDrag) {
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        }
    }

    private void onMouseMoved(MouseEvent event) {
        if (!supportResize) {
            return;
        }

        Scene scene = root.getScene();
        if (scene == null) {
            return;
        }
        double sceneWidth = scene.getWidth();
        double sceneHeight = scene.getHeight();
        double x = event.getSceneX();
        double y = event.getSceneY();

        if (x <= resizeMargin && y >= sceneHeight - resizeMargin) {
            scene.setCursor(javafx.scene.Cursor.SW_RESIZE);
            resizeDirection = "SW";
        } else if (x >= sceneWidth - resizeMargin && y >= sceneHeight - resizeMargin) {
            scene.setCursor(javafx.scene.Cursor.SE_RESIZE);
            resizeDirection = "SE";
        } else if (x >= sceneWidth - resizeMargin && y <= resizeMargin) {
            scene.setCursor(javafx.scene.Cursor.NE_RESIZE);
            resizeDirection = "NE";
        } else if (x <= resizeMargin && y <= resizeMargin) {
            scene.setCursor(javafx.scene.Cursor.NW_RESIZE);
            resizeDirection = "NW";
        } else if (x <= resizeMargin) {
            scene.setCursor(javafx.scene.Cursor.W_RESIZE);
            resizeDirection = "W";
        } else if (x >= sceneWidth - resizeMargin) {
            scene.setCursor(javafx.scene.Cursor.E_RESIZE);
            resizeDirection = "E";
        } else if (y <= resizeMargin) {
            scene.setCursor(javafx.scene.Cursor.N_RESIZE);
            resizeDirection = "N";
        } else if (y >= sceneHeight - resizeMargin) {
            scene.setCursor(javafx.scene.Cursor.S_RESIZE);
            resizeDirection = "S";
        } else {
            scene.setCursor(javafx.scene.Cursor.DEFAULT);
            resizeDirection = "";
        }
    }
}

package com.yshell.ui;

import com.yshell.model.ConnInfo;
import com.yshell.model.TreeNode;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome5.FontAwesomeBrands;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.function.Consumer;

public class ConnectionTreeCell extends TreeCell<TreeNode> {

    public final HBox content;
    private final FontIcon typeIcon;
    private final Label nameLabel;
    private final HBox hostBox;
    private final Label hostLabel;
    private final HBox portBox;
    private final Label portLabel;
    private final HBox userBox;
    private final Label userLabel;
    private final HBox actionsBox;
    private final Button editBtn;
    private final Button deleteBtn;
    private final Button connectBtn;

    private Consumer<TreeNode> editHandler;
    private Consumer<TreeNode> deleteHandler;
    private Consumer<TreeNode> connectHandler;
    private Consumer<TreeNode> renameHandler;
    private Consumer<TreeNode> newFolderHandler;

    /**
     * 待编辑的目标（由 Controller 设置，Cell 渲染时检查并自动进入编辑模式）
     */
    public static TreeNode pendingEditTarget;

    // 内联编辑输入框
    private final TextField nameField = new TextField();
    private boolean editing = false;

    public ConnectionTreeCell() {
        // ===== 第1列：[类型icon + 名称] =====
        typeIcon = new FontIcon();
        typeIcon.setIconSize(16);
        nameLabel = new Label();
        nameLabel.getStyleClass().add("tree-cell-name");
        HBox nameBox = new HBox(4, typeIcon, nameLabel);
        nameBox.getStyleClass().add("data-col");

        // ===== 第2列：[IP icon + IP] =====
        FontIcon hostIcon = new FontIcon(FontAwesomeSolid.GLOBE);
        hostIcon.setIconSize(16);
        hostIcon.getStyleClass().add("icon-detail-info");
        hostLabel = new Label();
        hostLabel.getStyleClass().add("detail-host");
        hostBox = new HBox(4, hostIcon, hostLabel);
        hostBox.getStyleClass().add("data-col");

        // ===== 第3列：[端口 icon + 端口] =====
        FontIcon portIcon = new FontIcon(FontAwesomeSolid.NETWORK_WIRED);
        portIcon.setIconSize(16);
        portIcon.getStyleClass().add("icon-detail-info");
        portLabel = new Label();
        portLabel.getStyleClass().add("detail-port");
        portBox = new HBox(4, portIcon, portLabel);
        portBox.getStyleClass().add("data-col");

        // ===== 第4列：[用户 icon + 用户名] =====
        FontIcon userIcon = new FontIcon(FontAwesomeSolid.USER);
        userIcon.setIconSize(16);
        userIcon.getStyleClass().add("icon-detail-info");
        userLabel = new Label();
        userLabel.getStyleClass().add("detail-user");
        userBox = new HBox(4, userIcon, userLabel);
        userBox.getStyleClass().add("data-col");

        // 操作按钮区
        actionsBox = new HBox(4);
        actionsBox.getStyleClass().add("tree-cell-actions");

        editBtn = createMiniButton(FontAwesomeSolid.PEN);
        connectBtn = createMiniButton(FontAwesomeSolid.PLUG);
        deleteBtn = createMiniButton(FontAwesomeSolid.TRASH);

        Tooltip edit = new Tooltip("编辑");
        edit.setShowDelay(Duration.millis(200));
        editBtn.setTooltip(edit);

        Tooltip del = new Tooltip("删除");
        del.setShowDelay(Duration.millis(200));
        deleteBtn.setTooltip(del);

        Tooltip conn = new Tooltip("连接");
        conn.setShowDelay(Duration.millis(200));
        connectBtn.setTooltip(conn);

        actionsBox.getChildren().addAll(editBtn, connectBtn, deleteBtn);

        // 操作按钮预留区域（固定宽度）
        StackPane actionArea = new StackPane(actionsBox);
        actionArea.getStyleClass().add("action-area");
        actionArea.setMinWidth(80);
        actionArea.setMaxWidth(80);

        // ===== 核心布局 =====
        content = new HBox(6,
                nameBox,
                hostBox,
                portBox,
                userBox,
                actionArea
        );
        content.getStyleClass().add("tree-cell-content");
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // 4列均分
        double colPrefWidth = 160;
        nameBox.setPrefWidth(colPrefWidth);
        hostBox.setPrefWidth(colPrefWidth);
        portBox.setPrefWidth(colPrefWidth);
        userBox.setPrefWidth(colPrefWidth);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        HBox.setHgrow(hostBox, Priority.ALWAYS);
        HBox.setHgrow(portBox, Priority.ALWAYS);
        HBox.setHgrow(userBox, Priority.ALWAYS);

        // hover 时显示操作按钮
        hoverProperty().addListener((obs, ov, nv) -> {
            if (!editing) actionsBox.setVisible(nv);
        });

        // 内联编辑输入框
        nameField.getStyleClass().add("tree-name-field");
        nameField.setOnAction(e -> commitEdit());
        nameField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelEdit();
            }
        });
        nameField.focusedProperty().addListener((obs, ov, nv) -> {
            if (!nv && editing) commitEdit();
        });

        // 右键菜单
        setupContextMenu();
    }

    private Button createMiniButton(FontAwesomeSolid iconLiteral) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(11);
        Button btn = new Button();
        btn.setGraphic(icon);
        btn.getStyleClass().addAll("button-icon-mini", "tree-mini-btn");
        return btn;
    }

    public void setEditHandler(Consumer<TreeNode> handler) {
        this.editHandler = handler;
    }

    public void setDeleteHandler(Consumer<TreeNode> handler) {
        this.deleteHandler = handler;
    }

    public void setConnectHandler(Consumer<TreeNode> handler) {
        this.connectHandler = handler;
    }

    public void setRenameHandler(Consumer<TreeNode> handler) {
        this.renameHandler = handler;
    }

    public void setNewFolderHandler(Consumer<TreeNode> handler) {
        this.newFolderHandler = handler;
    }

    public void startInlineEdit(String initialText) {
        editing = true;
        actionsBox.setVisible(false);
        nameLabel.setVisible(false);
        nameField.setText(initialText != null ? initialText : "");
        nameField.setVisible(true);
        HBox nameBox = (HBox) content.getChildren().get(0);
        if (!nameBox.getChildren().contains(nameField)) {
            nameBox.getChildren().set(1, nameField);
        }
        nameField.selectAll();
        nameField.requestFocus();
    }

    private void commitEdit() {
        if (!editing || getItem() == null) return;
        String newName = nameField.getText().trim();
        if (!newName.isEmpty() && renameHandler != null) {
            TreeNode item = getItem();
            item.setName(newName);
            renameHandler.accept(item);
        }
        exitEditMode();
    }

    public void cancelEdit() {
        exitEditMode();
    }

    private void exitEditMode() {
        editing = false;
        nameField.setVisible(false);
        nameLabel.setVisible(true);
        HBox nameBox = (HBox) content.getChildren().get(0);
        if (!nameBox.getChildren().contains(nameLabel)) {
            nameBox.getChildren().set(1, nameLabel);
        }
    }

    private void setupContextMenu() {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem newFolderItem = new MenuItem("新建子文件夹");
        FontIcon folderIcon = new FontIcon(FontAwesomeSolid.FOLDER_PLUS);
        folderIcon.setIconSize(14);
        folderIcon.getStyleClass().add("context-menu-icon");
        newFolderItem.setGraphic(folderIcon);

        MenuItem renameItem = new MenuItem("重命名");
        FontIcon renameIcon = new FontIcon(FontAwesomeSolid.PEN);
        renameIcon.setIconSize(14);
        renameIcon.getStyleClass().add("context-menu-icon");
        renameItem.setGraphic(renameIcon);

        MenuItem deleteItem = new MenuItem("删除");
        FontIcon deleteIcon = new FontIcon(FontAwesomeSolid.TRASH);
        deleteIcon.setIconSize(14);
        deleteIcon.getStyleClass().add("context-menu-icon");
        deleteItem.setGraphic(deleteIcon);

        newFolderItem.setOnAction(e -> {
            TreeNode item = getItem();
            if (item != null && newFolderHandler != null) {
                newFolderHandler.accept(item);
            }
        });

        renameItem.setOnAction(e -> {
            TreeNode item = getItem();
            if (item != null) {
                startInlineEdit(item.getName());
            }
        });

        deleteItem.setOnAction(e -> {
            TreeNode item = getItem();
            if (item != null && deleteHandler != null) {
                deleteHandler.accept(item);
            }
        });

        contextMenu.getItems().addAll(newFolderItem, renameItem, deleteItem);
        setContextMenu(contextMenu);
    }

    @Override
    protected void updateItem(TreeNode item, boolean empty) {
        super.updateItem(item, empty);

        if (editing) return;

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            actionsBox.setVisible(false);
            exitEditMode();
            return;
        }

        if (item.isFolder()) {
            setupFolderCell(item);
        } else {
            setupConnectionCell((ConnInfo) item);
        }

        setGraphic(content);

        // 检查待编辑目标
        if (pendingEditTarget != null && pendingEditTarget.getId().equals(item.getId())) {
            pendingEditTarget = null;
            Platform.runLater(() -> startInlineEdit(item.getName()));
        }
    }

    private void setupFolderCell(TreeNode item) {
        typeIcon.setIconCode(FontAwesomeSolid.FOLDER);
        typeIcon.setIconSize(16);
        typeIcon.getStyleClass().removeAll("tree-icon-conn", "icon-folder", "icon-connection-windows", "icon-connection-linux");
        typeIcon.getStyleClass().add("icon-folder");

        nameLabel.setText(item.getName());
        nameLabel.getStyleClass().add("tree-cell-name");

        hostBox.setVisible(false);
        portBox.setVisible(false);
        userBox.setVisible(false);

        actionsBox.getChildren().clear();

        Tooltip del = new Tooltip("删除");
        del.setShowDelay(Duration.millis(200));
        deleteBtn.setTooltip(del);

        Tooltip conn = new Tooltip("连接");
        conn.setShowDelay(Duration.millis(200));
        connectBtn.setTooltip(conn);

        actionsBox.getChildren().addAll(deleteBtn, connectBtn);
        actionsBox.setVisible(isHover());

        deleteBtn.setOnAction(e -> {
            if (deleteHandler != null) deleteHandler.accept(item);
        });
        connectBtn.setOnAction(e -> {
            if (connectHandler != null) connectHandler.accept(item);
        });
    }

    private void setupConnectionCell(ConnInfo item) {
        String type = item.getType();
        boolean isLinux = "ssh".equals(type);
        if (isLinux) {
            typeIcon.setIconCode(FontAwesomeBrands.LINUX);
        } else {
            typeIcon.setIconCode(FontAwesomeBrands.WINDOWS);
        }
        typeIcon.setIconSize(16);
        typeIcon.getStyleClass().removeAll("tree-icon-conn", "icon-folder", "icon-connection-windows", "icon-connection-linux");
        typeIcon.getStyleClass().addAll("tree-icon-conn", isLinux ? "icon-connection-linux" : "icon-connection-windows");

        nameLabel.setText(item.getName());
        nameLabel.getStyleClass().add("tree-cell-name");

        hostBox.setVisible(true);
        portBox.setVisible(true);
        userBox.setVisible(true);

        hostLabel.setText(item.getHost() != null ? item.getHost() : "");
        portLabel.setText(item.getPort() > 0 ? String.valueOf(item.getPort()) : "");
        userLabel.setText(item.getUserName() != null ? item.getUserName() : "");

        actionsBox.getChildren().clear();
        actionsBox.getChildren().addAll(editBtn, connectBtn, deleteBtn);
        actionsBox.setVisible(isHover());

        editBtn.setOnAction(e -> {
            if (editHandler != null) editHandler.accept(item);
        });
        deleteBtn.setOnAction(e -> {
            if (deleteHandler != null) deleteHandler.accept(item);
        });
        connectBtn.setOnAction(e -> {
            if (connectHandler != null) connectHandler.accept(item);
        });
    }
}

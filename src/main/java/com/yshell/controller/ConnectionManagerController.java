package com.yshell.controller;

import com.yshell.model.ConnInfo;
import com.yshell.model.Folder;
import com.yshell.model.TreeNode;
import com.yshell.service.ConnectionManager;
import com.yshell.service.ConnectionRepository;
import com.yshell.service.RecentConnectionRepository;
import com.yshell.theme.ThemeManager;
import com.yshell.ui.ApplicationIcons;
import com.yshell.ui.ConnectionTreeCell;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionManagerController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionManagerController.class);
    private static final String CONNECTION_DRAG_PREFIX = "YSHELL_CONNECTION_TREE_MOVE:";

    @FXML
    private Button btnClose;

    @FXML
    private Button btnNewConn;

    @FXML
    private Button btnNewFolder;

    @FXML
    private Button btnExpandAll;

    @FXML
    private Button btnCollapseAll;

    @FXML
    private ComboBox<String> searchType;

    @FXML
    private TextField searchInput;

    @FXML
    private Button btnClearSearch;

    @FXML
    private TreeView<TreeNode> connTreeView;

    @FXML
    private CheckBox closeAfterConnect;

    @FXML
    private Parent root;

    private Stage dialogStage;
    /**
     * 扁平化的树节点列表（文件夹 + 连接混合）
     */
    private List<TreeNode> connectionData = new ArrayList<>();

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    @FXML
    public void initialize() {
        setupComboBoxes();
        setupEventHandlers();
        setupTreeView();
        loadData();
        WindowDragResize.apply(root, 40, btnClose);
    }

    private void setupComboBoxes() {
        searchType.getItems().addAll("全部", "名称", "IP", "端口", "用户名", "描述");
        searchType.setValue("全部");
    }

    private void setupEventHandlers() {
        btnClose.setOnAction(e -> closeDialog());
        btnNewConn.setOnAction(e -> openNewConnection());
        btnNewFolder.setOnAction(e -> createNewFolder());
        btnExpandAll.setOnAction(e -> expandAll());
        btnCollapseAll.setOnAction(e -> collapseAll());
        btnClearSearch.setOnAction(e -> searchInput.clear());
        btnClearSearch.managedProperty().bind(btnClearSearch.visibleProperty());
        btnClearSearch.visibleProperty().bind(searchInput.textProperty().isNotEmpty());
        searchInput.textProperty().addListener((obs, ov, nv) -> filterTree());
        searchType.setOnAction(e -> filterTree());
    }

    private void setupTreeView() {
        connTreeView.setCellFactory(param -> {
            ConnectionTreeCell cell = new ConnectionTreeCell();
            cell.setEditHandler(this::openEditConnection);
            cell.setDeleteHandler(this::deleteConnection);
            cell.setConnectHandler(this::connectToConnection);
            cell.setRenameHandler(node -> {
                refreshTree();
                saveData();
            });
            cell.setNewFolderHandler(this::createNewFolderUnder);
            cell.setOnDragDetected(event -> {
                TreeNode node = cell.getItem();
                if (cell.isEmpty() || node == null) {
                    return;
                }
                Dragboard dragboard = cell.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(CONNECTION_DRAG_PREFIX + node.getId());
                dragboard.setContent(content);
                event.consume();
            });
            cell.setOnDragOver(event -> {
                String draggedId = parseConnectionDrag(event.getDragboard());
                TreeNode target = cell.getItem();
                if (draggedId != null) {
                    if (cell.isEmpty() ? canMoveConnectionNodeToParent(draggedId, "root") : canMoveConnectionNode(draggedId, target)) {
                        event.acceptTransferModes(TransferMode.MOVE);
                    }
                    event.consume();
                }
            });
            cell.setOnDragDropped(event -> {
                String draggedId = parseConnectionDrag(event.getDragboard());
                TreeNode target = cell.getItem();
                if (draggedId != null) {
                    boolean moved = cell.isEmpty() ? moveConnectionNodeToParent(draggedId, "root") : moveConnectionNode(draggedId, target);
                    event.setDropCompleted(moved);
                    event.consume();
                }
            });
            return cell;
        });

        TreeItem<TreeNode> root = new TreeItem<>();
        root.setExpanded(true);
        connTreeView.setRoot(root);
        connTreeView.setShowRoot(false);
        connTreeView.setOnDragOver(event -> {
            String draggedId = parseConnectionDrag(event.getDragboard());
            if (canMoveConnectionNodeToParent(draggedId, "root")) {
                event.acceptTransferModes(TransferMode.MOVE);
                event.consume();
            }
        });
        connTreeView.setOnDragDropped(event -> {
            String draggedId = parseConnectionDrag(event.getDragboard());
            boolean moved = moveConnectionNodeToParent(draggedId, "root");
            event.setDropCompleted(moved);
            event.consume();
        });
    }

    // ===== 数据加载与持久化 =====

    private void loadData() {
        connectionData = ConnectionRepository.getInstance().load();
        refreshTree();
    }

    private void saveData() {
        ConnectionRepository.getInstance().save(connectionData);
        RecentConnectionRepository.getInstance().notifyConnectionDataChanged();
        LeftPanelController leftPanelController = ConnectionManager.getInstance().getLeftPanelController();
        if (leftPanelController != null) {
            leftPanelController.reloadConnectionData();
        }
    }

    // ===== 树构建：从扁平列表 → parent_id 映射构建树 =====

    private void refreshTree() {
        Set<String> expandedIds = collectExpandedIds(connTreeView.getRoot());

        TreeItem<TreeNode> rootItem = connTreeView.getRoot();
        rootItem.getChildren().clear();

        if (connectionData.isEmpty()) return;

        List<TreeNode> topLevel = connectionData.stream()
                .filter(c -> "root".equals(c.getParentId()))
                .sorted(Comparator.comparingInt(TreeNode::getOrder))
                .toList();

        for (TreeNode node : topLevel) {
            buildTreeNode(rootItem, node);
        }

        restoreExpandedIds(connTreeView.getRoot(), expandedIds);
    }

    private void refreshFilteredTree(String keyword) {
        TreeItem<TreeNode> rootItem = connTreeView.getRoot();
        rootItem.getChildren().clear();

        if (connectionData.isEmpty()) return;

        List<TreeNode> topLevel = connectionData.stream()
                .filter(c -> "root".equals(c.getParentId()))
                .sorted(Comparator.comparingInt(TreeNode::getOrder))
                .toList();

        for (TreeNode node : topLevel) {
            addFilteredTreeNode(rootItem, node, keyword);
        }

        expandTreeItem(rootItem);
    }

    private Set<String> collectExpandedIds(TreeItem<TreeNode> item) {
        Set<String> ids = new HashSet<>();
        if (item.isExpanded() && item.getValue() != null) {
            ids.add(item.getValue().getId());
        }
        for (TreeItem<TreeNode> child : item.getChildren()) {
            ids.addAll(collectExpandedIds(child));
        }
        return ids;
    }

    private void restoreExpandedIds(TreeItem<TreeNode> item, Set<String> expandedIds) {
        if (item.getValue() != null && expandedIds.contains(item.getValue().getId())) {
            item.setExpanded(true);
        }
        for (TreeItem<TreeNode> child : item.getChildren()) {
            restoreExpandedIds(child, expandedIds);
        }
    }

    private void buildTreeNode(TreeItem<TreeNode> parentItem, TreeNode node) {
        TreeItem<TreeNode> item = new TreeItem<>(node);

        List<TreeNode> children = connectionData.stream()
                .filter(c -> node.getId().equals(c.getParentId()))
                .sorted(Comparator.comparingInt(TreeNode::getOrder))
                .toList();

        for (TreeNode child : children) {
            buildTreeNode(item, child);
        }

        parentItem.getChildren().add(item);
    }

    private boolean addFilteredTreeNode(TreeItem<TreeNode> parentItem, TreeNode node, String keyword) {
        boolean selfMatches = matches(node, keyword);
        TreeItem<TreeNode> item = new TreeItem<>(node);

        List<TreeNode> children = connectionData.stream()
                .filter(c -> node.getId().equals(c.getParentId()))
                .sorted(Comparator.comparingInt(TreeNode::getOrder))
                .toList();

        boolean childMatches = false;
        for (TreeNode child : children) {
            childMatches |= addFilteredTreeNode(item, child, keyword);
        }

        if (selfMatches || childMatches) {
            item.setExpanded(true);
            parentItem.getChildren().add(item);
            return true;
        }
        return false;
    }

    // ===== 对话框操作 =====

    @FXML
    private void closeDialog() {
        saveData();
        if (dialogStage != null) {
            dialogStage.close();
        }
    }

    private void openNewConnection() {
        openConnectionEditor(dialogStage, null, false, this::saveNewConnection, null);
    }

    private void openEditConnection(TreeNode node) {
        if (!(node instanceof ConnInfo)) return;
        openConnectionEditor(dialogStage, (ConnInfo) node, true, this::updateConnection, this::deleteConnection);
    }

    public static void openConnectionEditor(Window owner,
                                            ConnInfo conn,
                                            boolean editMode,
                                            Consumer<ConnInfo> saveHandler,
                                            Consumer<ConnInfo> deleteHandler) {
        try {
            FXMLLoader loader = new FXMLLoader(ConnectionManagerController.class.getResource("/fxml/ConnectionEditor.fxml"));
            Parent dialogRoot = loader.load();

            ConnectionEditorController controller = loader.getController();
            Stage stage = new Stage();
            ApplicationIcons.applyTo(stage);
            stage.setTitle(editMode ? "编辑连接" : "新建连接");
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.initStyle(StageStyle.UNDECORATED);
            if (owner != null) {
                stage.initOwner(owner);
            }

            Scene scene = new Scene(dialogRoot, 700, editMode ? 600 : 800);
            ThemeManager.getInstance().registerScene(scene);
            stage.setScene(scene);
            controller.setDialogStage(stage);
            controller.setEditMode(editMode);
            if (conn != null) {
                controller.loadConnection(conn);
            }
            controller.setSaveHandler(saveHandler);
            if (deleteHandler != null) {
                controller.setDeleteHandler(deleteHandler);
            }

            stage.showAndWait();
        } catch (IOException e) {
            DialogHelper.showError("错误", "无法加载连接编辑器: " + e.getMessage());
            LOGGER.error("加载连接编辑器 FXML 失败", e);
        }
    }

    // ===== 新建/编辑/删除操作 =====

    /**
     * 获取当前选中节点的 id 作为新节点的 parentId，顶层返回 "root"
     */
    private String resolveParentId() {
        TreeItem<TreeNode> selected = connTreeView.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null && selected.getValue().isFolder()) {
            return selected.getValue().getId();
        }
        return "root";
    }

    private void saveNewConnection(ConnInfo conn) {
        conn.setParentId(resolveParentId());
        connectionData.add(conn);
        refreshTree();
        saveData();
    }

    private void updateConnection(ConnInfo conn) {
        refreshTree();
        saveData();
    }

    private void deleteConnection(TreeNode node) {
        String itemType = node.isFolder() ? "文件夹" : "连接";
        if (DialogHelper.showConfirm("确认删除", "确定要删除" + itemType + " \"" + node.getName() + "\" 吗？")) {
            List<String> idsToDelete = removeFromFlatList(node.getId());
            RecentConnectionRepository.getInstance().removeByIds(idsToDelete);
            refreshTree();
            saveData();
        }
    }

    /**
     * 从扁平列表中移除指定 id 的节点及其所有后代（级联删除）
     */
    private List<String> removeFromFlatList(String id) {
        List<String> idsToDelete = collectChildIds(id);
        idsToDelete.add(id);
        connectionData.removeIf(c -> idsToDelete.contains(c.getId()));
        return idsToDelete;
    }

    private List<String> collectChildIds(String parentId) {
        List<String> ids = new ArrayList<>();
        for (TreeNode c : connectionData) {
            if (parentId.equals(c.getParentId())) {
                ids.add(c.getId());
                ids.addAll(collectChildIds(c.getId()));
            }
        }
        return ids;
    }

    private List<ConnInfo> collectChildConnections(String parentId) {
        List<ConnInfo> connections = new ArrayList<>();
        connectionData.stream()
                .filter(node -> parentId.equals(node.getParentId()))
                .sorted(Comparator.comparingInt(TreeNode::getOrder))
                .forEach(node -> {
                    if (node instanceof ConnInfo connInfo) {
                        connections.add(connInfo);
                    } else if (node.isFolder()) {
                        connections.addAll(collectChildConnections(node.getId()));
                    }
                });
        return connections;
    }

    // ===== 文件夹操作 =====

    private void createNewFolder() {
        doCreateFolder(resolveParentId());
    }

    private void createNewFolderUnder(TreeNode parent) {
        // 如果点击的是连接节点，新文件夹应建在其同级（同一父文件夹下）
        String targetParentId = parent.isFolder() ? parent.getId() : parent.getParentId();
        doCreateFolder(targetParentId);
    }

    private void doCreateFolder(String parentId) {
        Folder newFolder = new Folder(UUID.randomUUID().toString().replace("-", ""), "新文件夹");
        newFolder.setParentId(parentId);

        connectionData.add(newFolder);
        refreshTree();
        saveData();

        selectAndEdit(newFolder);
    }

    /**
     * 选中指定节点并触发内联重命名
     */
    private void selectAndEdit(TreeNode target) {
        TreeItem<TreeNode> found = findTreeItem(connTreeView.getRoot(), target);
        if (found != null) {
            connTreeView.getSelectionModel().select(found);
            TreeItem<TreeNode> parent = found.getParent();
            while (parent != null && parent.getValue() == null) {
                parent.setExpanded(true);
                parent = parent.getParent();
            }
            ConnectionTreeCell.pendingEditTarget = target;
        }
    }

    private TreeItem<TreeNode> findTreeItem(TreeItem<TreeNode> parent, TreeNode target) {
        if (parent.getValue() != null && parent.getValue().getId().equals(target.getId())) {
            return parent;
        }
        for (TreeItem<TreeNode> child : parent.getChildren()) {
            TreeItem<TreeNode> found = findTreeItem(child, target);
            if (found != null) return found;
        }
        return null;
    }

    // ===== 展开/折叠 =====
    private String parseConnectionDrag(Dragboard dragboard) {
        if (dragboard == null || !dragboard.hasString()) return null;
        String value = dragboard.getString();
        if (value == null || !value.startsWith(CONNECTION_DRAG_PREFIX)) return null;
        String id = value.substring(CONNECTION_DRAG_PREFIX.length()).trim();
        return id.isEmpty() ? null : id;
    }

    private boolean canMoveConnectionNode(String draggedId, TreeNode target) {
        if (target == null || !target.isFolder()) return false;
        return canMoveConnectionNodeToParent(draggedId, target.getId());
    }

    private boolean canMoveConnectionNodeToParent(String draggedId, String targetParentId) {
        TreeNode source = findNodeById(draggedId);
        if (source == null) return false;
        String parentId = targetParentId == null || targetParentId.isBlank() ? "root" : targetParentId;
        if (Objects.equals(source.getParentId(), parentId)) return false;
        if (Objects.equals(source.getId(), parentId)) return false;
        return !source.isFolder() || !collectChildIds(source.getId()).contains(parentId);
    }

    private boolean moveConnectionNode(String draggedId, TreeNode target) {
        if (target == null || !target.isFolder()) return false;
        return moveConnectionNodeToParent(draggedId, target.getId());
    }

    private boolean moveConnectionNodeToParent(String draggedId, String targetParentId) {
        if (!canMoveConnectionNodeToParent(draggedId, targetParentId)) return false;
        TreeNode source = findNodeById(draggedId);
        String parentId = targetParentId == null || targetParentId.isBlank() ? "root" : targetParentId;
        String targetName = "root".equals(parentId) ? "根目录" : Optional.ofNullable(findNodeById(parentId))
                .map(TreeNode::getName)
                .orElse("目标文件夹");
        if (!DialogHelper.showConfirm("确认移动", "确定将 \"" + source.getName() + "\" 移动到 \"" + targetName + "\" 下吗？")) {
            return false;
        }
        String oldParentId = source.getParentId();
        source.setParentId(parentId);
        source.setOrder(nextOrder(parentId));
        long now = System.currentTimeMillis();
        source.setParentUpdateTime(now);
        source.setModifiedTime(now);
        normalizeOrders(oldParentId);
        normalizeOrders(parentId);
        refreshTree();
        saveData();
        TreeItem<TreeNode> item = findTreeItem(connTreeView.getRoot(), source);
        if (item != null) {
            connTreeView.getSelectionModel().select(item);
            expandParents(item);
        }
        return true;
    }

    private TreeNode findNodeById(String id) {
        if (id == null || id.isBlank()) return null;
        for (TreeNode node : connectionData) {
            if (id.equals(node.getId())) {
                return node;
            }
        }
        return null;
    }

    private int nextOrder(String parentId) {
        String normalizedParentId = parentId == null || parentId.isBlank() ? "root" : parentId;
        return connectionData.stream()
                .filter(node -> Objects.equals(normalizedParentId, node.getParentId()))
                .mapToInt(TreeNode::getOrder)
                .max()
                .orElse(-1) + 1;
    }

    private void normalizeOrders(String parentId) {
        String normalizedParentId = parentId == null || parentId.isBlank() ? "root" : parentId;
        List<TreeNode> siblings = connectionData.stream()
                .filter(node -> Objects.equals(normalizedParentId, node.getParentId()))
                .sorted(Comparator.comparingInt(TreeNode::getOrder))
                .toList();
        for (int i = 0; i < siblings.size(); i++) {
            siblings.get(i).setOrder(i);
        }
    }

    private void expandParents(TreeItem<TreeNode> item) {
        TreeItem<TreeNode> parent = item != null ? item.getParent() : null;
        while (parent != null) {
            parent.setExpanded(true);
            parent = parent.getParent();
        }
    }

    private void expandAll() {
        expandTreeItem(connTreeView.getRoot());
    }

    private void expandTreeItem(TreeItem<TreeNode> item) {
        if (item != null) {
            item.setExpanded(true);
            for (TreeItem<TreeNode> child : item.getChildren()) {
                expandTreeItem(child);
            }
        }
    }

    private void collapseAll() {
        TreeItem<TreeNode> root = connTreeView.getRoot();
        root.setExpanded(true);
        for (TreeItem<TreeNode> child : root.getChildren()) {
            collapseTreeItem(child);
        }
    }

    private void collapseTreeItem(TreeItem<TreeNode> item) {
        if (item != null) {
            item.setExpanded(false);
            for (TreeItem<TreeNode> child : item.getChildren()) {
                collapseTreeItem(child);
            }
        }
    }

    // ===== 连接 & 搜索 =====
    private void connectToConnection(TreeNode node) {
        if (node instanceof ConnInfo connInfo) {
            connectConnections(List.of(connInfo));
            return;
        }
        if (node.isFolder()) {
            connectConnections(collectChildConnections(node.getId()));
        }
    }

    private void connectConnections(List<ConnInfo> connections) {
        if (connections.isEmpty()) {
            return;
        }
        ConnectionManager connectionManager = ConnectionManager.getInstance();
        for (int index = 0; index < connections.size(); index++) {
            boolean isCurrent = index == connections.size() - 1;
            connectionManager.connect(connections.get(index), null, isCurrent);
        }
        if (closeAfterConnect.isSelected()) {
            closeDialog();
        }
    }

    @FXML
    private void filterTree() {
        String keyword = searchInput != null && searchInput.getText() != null
                ? searchInput.getText().trim()
                : "";
        if (keyword.isEmpty()) {
            refreshTree();
            return;
        }
        refreshFilteredTree(keyword);
    }

    private boolean matches(TreeNode node, String keyword) {
        String type = searchType.getValue() != null ? searchType.getValue() : "全部";
        if ("名称".equals(type)) {
            return contains(node.getName(), keyword);
        }
        if (!(node instanceof ConnInfo conn)) {
            return "全部".equals(type) && contains(node.getName(), keyword);
        }
        if ("IP".equals(type)) {
            return contains(conn.getHost(), keyword);
        }
        if ("端口".equals(type)) {
            return contains(String.valueOf(conn.getPort()), keyword);
        }
        if ("用户名".equals(type)) {
            return contains(conn.getUserName(), keyword);
        }
        if ("描述".equals(type)) {
            return contains(conn.getDescription(), keyword);
        }
        return contains(conn.getName(), keyword)
                || contains(conn.getHost(), keyword)
                || contains(String.valueOf(conn.getPort()), keyword)
                || contains(conn.getUserName(), keyword)
                || contains(conn.getDescription(), keyword);
    }

    private boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }
}

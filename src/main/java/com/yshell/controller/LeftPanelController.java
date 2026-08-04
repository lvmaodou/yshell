package com.yshell.controller;

import com.yshell.model.*;
import com.yshell.service.ConnectionManager;
import com.yshell.service.ConnectionRepository;
import com.yshell.service.RecentConnectionRepository;
import com.yshell.service.SshService;
import com.yshell.theme.ThemeManager;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.PanelManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Window;
import org.kordamp.ikonli.fontawesome5.FontAwesomeBrands;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LeftPanelController implements Initializable {

    private static final String CONNECTION_DRAG_PREFIX = "YSHELL_LEFT_CONNECTION_TREE_MOVE:";

    @FXML
    private ScrollPane systemInfoScroll;

    @FXML
    private VBox connectionInfoPane;

    @FXML
    private TreeView<TreeNode> connectionTreeView;

    @FXML
    private Button btnNewConnectionFromTitle;

    @FXML
    private Button btnNewFolderFromTitle;

    @FXML
    private Button btnExpandAllConnections;

    @FXML
    private Button btnCollapseAllConnections;

    @FXML
    private HBox serverItem;

    @FXML
    private Pane serverIcon;

    @FXML
    private Label serverLabel;

    @FXML
    private Label distroValue;

    @FXML
    private Label kernelValue;

    @FXML
    private Label uptimeValue;

    @FXML
    private Label systemTimeValue;

    @FXML
    private Label cpuModelValue;

    @FXML
    private Label cpuCoresValue;

    @FXML
    private Region cpuProgressBar;

    @FXML
    private Label cpuPercentLabel;

    @FXML
    private Region memoryProgressBar;

    @FXML
    private Label memoryPercentLabel;

    @FXML
    private Label memoryValueLabel;

    @FXML
    private Region swapProgressBar;

    @FXML
    private Label swapPercentLabel;

    @FXML
    private Label swapValueLabel;

    @FXML
    private Label processRunningValue;

    @FXML
    private Label processSleepingValue;

    @FXML
    private Label processTotalValue;

    @FXML
    private TableView<ProcessInfo> processTableView;

    @FXML
    private TableColumn<ProcessInfo, String> nameCol;

    @FXML
    private TableColumn<ProcessInfo, Double> cpuCol;

    @FXML
    private TableColumn<ProcessInfo, Double> memCol;

    @FXML
    private TableView<DiskInfo> diskTableView;

    @FXML
    private TableColumn<DiskInfo, String> diskPathCol;

    @FXML
    private TableColumn<DiskInfo, Double> diskSizeCol;

    @FXML
    private ComboBox<String> nicSelect;

    @FXML
    private Label uploadSpeedValue;

    @FXML
    private Label downloadSpeedValue;

    @FXML
    private Canvas networkChartCanvas;

    @FXML
    private Pane networkChartPane;

    @FXML
    private Label latencyValue;

    @FXML
    private Label currentUserValue;

    @FXML
    private TableView<UserInfo> userTableView;

    @FXML
    private TableColumn<UserInfo, String> userUidCol;

    @FXML
    private TableColumn<UserInfo, String> userNameCol;

    @FXML
    private TableColumn<UserInfo, String> userGidCol;

    @FXML
    private TableColumn<UserInfo, String> userGroupCol;

    private final List<Double> uploadData = new ArrayList<>();
    private final List<Double> downloadData = new ArrayList<>();
    private final List<Long> dataTimestamps = new ArrayList<>();

    private int hoverIndex = -1;
    private double hoverX = -1;
    private double hoverY = -1;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private boolean updatingNetworkInterfaces;
    private boolean networkChartInitialized;
    private boolean connectionTreeExpansionInitialized;
    private List<String> pendingNetworkInterfaceItems;

    private final ObservableList<ProcessInfo> processListData = FXCollections.observableArrayList();
    private final ObservableList<DiskInfo> diskListData = FXCollections.observableArrayList();
    private final ObservableList<UserInfo> userListData = FXCollections.observableArrayList();
    private final Map<String, SystemInfo> systemInfoByConnId = new ConcurrentHashMap<>();
    private List<TreeNode> connectionData = new ArrayList<>();
    private String activeConnId;
    private ConnInfo activeConnInfo;
    private String pendingNewConnectionParentId;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        PanelManager.getInstance().setSystemInfoNode(systemInfoScroll);
        PanelManager.getInstance().setConnectionInfoNode(connectionInfoPane);
        if (serverItem != null) {
            serverItem.visibleProperty().bind(systemInfoScroll.visibleProperty());
            serverItem.managedProperty().bind(systemInfoScroll.managedProperty());
        }

        ConnectionManager.getInstance().setLeftPanelController(this);

        nicSelect.getItems().clear();

        nicSelect.setOnAction(e -> onNicChanged());
        nicSelect.showingProperty().addListener((obs, wasShowing, showing) -> {
            if (!showing && pendingNetworkInterfaceItems != null) {
                List<String> pendingItems = pendingNetworkInterfaceItems;
                pendingNetworkInterfaceItems = null;
                applyNetworkInterfaceItems(pendingItems);
            }
        });

        setupProgressBar(cpuProgressBar);
        setupProgressBar(memoryProgressBar);
        setupProgressBar(swapProgressBar);

        processTableView.setItems(processListData);
        nameCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().name));
        cpuCol.setCellValueFactory(cellData -> cellData.getValue().cpuPercentProperty().asObject());
        memCol.setCellValueFactory(cellData -> cellData.getValue().memPercentProperty().asObject());
        nameCol.setSortable(false);
        cpuCol.setSortable(false);
        memCol.setSortable(false);
        cpuCol.setCellFactory(col -> createProgressBarCell("proc-bar-cpu"));
        memCol.setCellFactory(col -> createProgressBarCell("proc-bar-mem"));

        diskTableView.setItems(diskListData);
        diskPathCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().path));
        diskSizeCol.setCellValueFactory(cellData -> cellData.getValue().usedPercentProperty().asObject());
        diskPathCol.setSortable(false);
        diskSizeCol.setSortable(false);
        diskSizeCol.setCellFactory(col -> createDiskBarCell());

        userTableView.setItems(userListData);
        userUidCol.setCellValueFactory(cellData -> cellData.getValue().uidProperty());
        userNameCol.setCellValueFactory(cellData -> cellData.getValue().usernameProperty());
        userGidCol.setCellValueFactory(cellData -> cellData.getValue().gidProperty());
        userGroupCol.setCellValueFactory(cellData -> cellData.getValue().groupProperty());

        setupConnectionInfoPane();
        initNetworkChart();
        clearData(null);

    }

    public void clearData(ConnInfo connInfo) {
        activeConnId = null;
        activeConnInfo = connInfo;
        resetSystemInfoDisplay(connInfo);
    }

    private void resetSystemInfoDisplay(ConnInfo connInfo) {
        setServerLabel("--");
        setBasicInfo("--", "--", "--", "--");
        setCpuInfo("--", "--", 0);
        setMemoryInfo(0, "--", 0, "--");
        setProcessInfo(0, 0, 0);
        setProcessList(new ProcessInfo[0]);
        setDiskInfo(new DiskInfo[0]);
        setNetworkInfo("--", "--", "--");
        setUserInfo(new UserInfo[0]);
        setCurrentUserValue("--");

        resetNetworkChart();
        if (connInfo != null) {
            setServerLabel(connInfo.getHost());
            setCurrentUserValue(connInfo.getUserName());
            selectConnectionNode(connInfo.getId());
        }
    }

    public void showConnection(String connId, ConnInfo connInfo) {
        activeConnId = connId;
        activeConnInfo = connInfo;
        resetSystemInfoDisplay(connInfo);
        SystemInfo snapshot = connId == null ? null : systemInfoByConnId.get(connId);
        if (snapshot != null) {
            renderSystemInfo(snapshot);
        }
    }

    private void setupConnectionInfoPane() {
        if (connectionTreeView == null) {
            return;
        }
        setupConnectionTreeView();
        loadConnectionData();
        if (connectionInfoPane != null) {
            connectionInfoPane.visibleProperty().addListener((obs, wasVisible, visible) -> {
                if (visible) {
                    loadConnectionData();
                }
            });
        }
    }

    private void setupConnectionTreeView() {
        connectionTreeView.setShowRoot(false);
        connectionTreeView.setEditable(false);
        connectionTreeView.setCellFactory(view -> new CompactConnectionTreeCell());
        connectionTreeView.setContextMenu(createConnectionTreeContextMenu(null));
        connectionTreeView.setOnDragOver(event -> {
            String draggedId = parseConnectionDrag(event.getDragboard());
            if (canMoveConnectionNodeToParent(draggedId, "root")) {
                event.acceptTransferModes(TransferMode.MOVE);
                event.consume();
            }
        });
        connectionTreeView.setOnDragDropped(event -> {
            String draggedId = parseConnectionDrag(event.getDragboard());
            boolean moved = moveConnectionNodeToParent(draggedId, "root");
            event.setDropCompleted(moved);
            event.consume();
        });
        if (btnNewConnectionFromTitle != null) {
            btnNewConnectionFromTitle.setOnAction(e -> openNewConnection(null));
        }
        if (btnNewFolderFromTitle != null) {
            btnNewFolderFromTitle.setOnAction(e -> createNewFolderUnder(null));
        }
        if (btnExpandAllConnections != null) {
            btnExpandAllConnections.setOnAction(e -> expandAll());
        }
        if (btnCollapseAllConnections != null) {
            btnCollapseAllConnections.setOnAction(e -> collapseAll());
        }
    }

    private void loadConnectionData() {
        connectionData = ConnectionRepository.getInstance().load();
        refreshConnectionTree();
    }

    public void reloadConnectionData() {
        String activeConnectionInfoId = activeConnInfo != null ? activeConnInfo.getId() : null;
        loadConnectionData();
        if (activeConnectionInfoId != null && findNodeById(activeConnectionInfoId) instanceof ConnInfo connInfo) {
            activeConnInfo = connInfo;
            setServerLabel(connInfo.getHost());
            setCurrentUserValue(connInfo.getUserName());
            selectConnectionNode(connInfo.getId());
        }
    }

    private void saveConnectionData() {
        ConnectionRepository.getInstance().save(connectionData);
        RecentConnectionRepository.getInstance().notifyConnectionDataChanged();
    }

    private void refreshConnectionTree() {
        if (connectionTreeView == null) {
            return;
        }
        Set<String> expandedIds = collectExpandedIds(connectionTreeView.getRoot());
        String selectedId = selectedConnectionTreeNodeId();

        TreeItem<TreeNode> root = new TreeItem<>();
        root.setExpanded(true);

        connectionData.stream()
                .filter(node -> "root".equals(node.getParentId()))
                .sorted(Comparator.comparingInt(TreeNode::getOrder))
                .forEach(node -> buildConnectionTreeNode(root, node, connectionData));

        connectionTreeView.setRoot(root);
        if (connectionTreeExpansionInitialized) {
            restoreExpandedIds(root, expandedIds);
        } else {
            expandTreeItem(root);
            connectionTreeExpansionInitialized = true;
        }
        selectConnectionNode(selectedId != null ? selectedId : activeConnInfo != null ? activeConnInfo.getId() : null);
    }

    private void buildConnectionTreeNode(TreeItem<TreeNode> parentItem, TreeNode node, List<TreeNode> data) {
        TreeItem<TreeNode> item = new TreeItem<>(node);
        data.stream()
                .filter(child -> node.getId().equals(child.getParentId()))
                .sorted(Comparator.comparingInt(TreeNode::getOrder))
                .forEach(child -> buildConnectionTreeNode(item, child, data));
        parentItem.getChildren().add(item);
    }

    private Set<String> collectExpandedIds(TreeItem<TreeNode> item) {
        Set<String> ids = new HashSet<>();
        if (item == null) {
            return ids;
        }
        if (item.isExpanded() && item.getValue() != null) {
            ids.add(item.getValue().getId());
        }
        for (TreeItem<TreeNode> child : item.getChildren()) {
            ids.addAll(collectExpandedIds(child));
        }
        return ids;
    }

    private void restoreExpandedIds(TreeItem<TreeNode> item, Set<String> expandedIds) {
        if (item == null) {
            return;
        }
        if (item.getValue() == null || expandedIds.contains(item.getValue().getId())) {
            item.setExpanded(true);
        }
        for (TreeItem<TreeNode> child : item.getChildren()) {
            restoreExpandedIds(child, expandedIds);
        }
    }

    private String selectedConnectionTreeNodeId() {
        if (connectionTreeView == null) {
            return null;
        }
        TreeItem<TreeNode> selected = connectionTreeView.getSelectionModel().getSelectedItem();
        return selected != null && selected.getValue() != null ? selected.getValue().getId() : null;
    }

    private void selectConnectionNode(String targetId) {
        if (targetId == null || connectionTreeView == null || connectionTreeView.getRoot() == null) {
            return;
        }
        TreeItem<TreeNode> found = findConnectionTreeItem(connectionTreeView.getRoot(), targetId);
        if (found != null) {
            connectionTreeView.getSelectionModel().select(found);
        }
    }

    private TreeItem<TreeNode> findConnectionTreeItem(TreeItem<TreeNode> item, String targetId) {
        if (item.getValue() != null && targetId.equals(item.getValue().getId())) {
            return item;
        }
        for (TreeItem<TreeNode> child : item.getChildren()) {
            TreeItem<TreeNode> found = findConnectionTreeItem(child, targetId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void openNewConnection(TreeNode contextNode) {
        pendingNewConnectionParentId = resolveConnectionParentId(contextNode);
        ConnectionManagerController.openConnectionEditor(ownerWindow(), null, false, this::saveNewConnection, null);
    }

    private void openEditConnection(TreeNode node) {
        if (node instanceof ConnInfo conn) {
            ConnectionManagerController.openConnectionEditor(ownerWindow(), conn, true, this::updateConnection, this::deleteConnection);
        }
    }

    private void saveNewConnection(ConnInfo conn) {
        if (conn == null) {
            return;
        }
        conn.setParentId(pendingNewConnectionParentId != null ? pendingNewConnectionParentId : resolveConnectionParentId(null));
        pendingNewConnectionParentId = null;
        connectionData.add(conn);
        refreshConnectionTree();
        saveConnectionData();
        selectConnectionNode(conn.getId());
    }

    private void updateConnection(ConnInfo conn) {
        refreshConnectionTree();
        saveConnectionData();
        if (conn != null) {
            selectConnectionNode(conn.getId());
        }
    }

    private void deleteConnection(ConnInfo conn) {
        if (conn != null) {
            deleteConnection((TreeNode) conn);
        }
    }

    private void deleteConnection(TreeNode node) {
        if (node == null) {
            return;
        }
        String itemType = node.isFolder() ? "文件夹" : "连接";
        if (!DialogHelper.showConfirm("确认删除", "确定要删除 " + itemType + " \"" + node.getName() + "\" 吗？")) {
            return;
        }
        List<String> idsToDelete = removeFromFlatList(node.getId());
        RecentConnectionRepository.getInstance().removeByIds(idsToDelete);
        refreshConnectionTree();
        saveConnectionData();
    }

    private List<String> removeFromFlatList(String id) {
        List<String> idsToDelete = collectChildIds(id);
        idsToDelete.add(id);
        connectionData.removeIf(node -> idsToDelete.contains(node.getId()));
        return idsToDelete;
    }

    private List<String> collectChildIds(String parentId) {
        List<String> ids = new ArrayList<>();
        for (TreeNode node : connectionData) {
            if (parentId.equals(node.getParentId())) {
                ids.add(node.getId());
                ids.addAll(collectChildIds(node.getId()));
            }
        }
        return ids;
    }

    private void createNewFolderUnder(TreeNode parent) {
        if (parent == null) {
            doCreateFolder("root");
            return;
        }
        String targetParentId = parent.isFolder() ? parent.getId() : parent.getParentId();
        doCreateFolder(targetParentId != null ? targetParentId : "root");
    }

    private void doCreateFolder(String parentId) {
        String folderName = DialogHelper.showTextInput("新建文件夹", null, "名称", "新建文件夹");
        if (folderName == null) {
            return;
        }
        Folder folder = new Folder(UUID.randomUUID().toString().replace("-", ""), folderName);
        folder.setParentId(parentId != null ? parentId : "root");
        connectionData.add(folder);
        refreshConnectionTree();
        saveConnectionData();
        selectConnectionNode(folder.getId());
        TreeItem<TreeNode> item = findConnectionTreeItem(connectionTreeView.getRoot(), folder.getId());
        expandParents(item);
    }

    private String resolveConnectionParentId(TreeNode contextNode) {
        if (contextNode == null) {
            return "root";
        }
        if (contextNode.isFolder()) {
            return contextNode.getId();
        }
        return contextNode.getParentId() != null ? contextNode.getParentId() : "root";
    }

    private void expandParents(TreeItem<TreeNode> item) {
        TreeItem<TreeNode> parent = item != null ? item.getParent() : null;
        while (parent != null) {
            parent.setExpanded(true);
            parent = parent.getParent();
        }
    }

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
        refreshConnectionTree();
        saveConnectionData();
        selectConnectionNode(source.getId());
        TreeItem<TreeNode> item = findConnectionTreeItem(connectionTreeView.getRoot(), source.getId());
        expandParents(item);
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

    private void expandAll() {
        expandTreeItem(connectionTreeView.getRoot());
        connectionTreeView.refresh();
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
        TreeItem<TreeNode> root = connectionTreeView.getRoot();
        if (root != null) {
            root.setExpanded(true);
            for (TreeItem<TreeNode> child : root.getChildren()) {
                collapseTreeItem(child);
            }
        }
        connectionTreeView.refresh();
    }

    private void collapseTreeItem(TreeItem<TreeNode> item) {
        if (item != null) {
            item.setExpanded(false);
            for (TreeItem<TreeNode> child : item.getChildren()) {
                collapseTreeItem(child);
            }
        }
    }

    private void connectToConnection(TreeNode node) {
        if (node instanceof ConnInfo conn) {
            ConnectionManager.getInstance().connect(conn);
        }
    }

    private Window ownerWindow() {
        return connectionTreeView != null && connectionTreeView.getScene() != null
                ? connectionTreeView.getScene().getWindow()
                : null;
    }

    private ContextMenu createConnectionTreeContextMenu(TreeNode contextNode) {
        ContextMenu menu = new ContextMenu();
        MenuItem newConnection = new MenuItem("新建连接");
        newConnection.setOnAction(e -> openNewConnection(contextNode));

        MenuItem newFolder = new MenuItem(contextNode != null && contextNode.isFolder() ? "新建子文件夹" : "新建文件夹");
        newFolder.setOnAction(e -> createNewFolderUnder(contextNode));

        menu.getItems().addAll(newConnection, newFolder);

        if (contextNode == null || contextNode.isFolder()) {
            MenuItem expand = new MenuItem("展开");
            expand.setOnAction(e -> {
                TreeItem<TreeNode> item = contextNode != null
                        ? findConnectionTreeItem(connectionTreeView.getRoot(), contextNode.getId())
                        : connectionTreeView.getRoot();
                expandTreeItem(item);
                connectionTreeView.refresh();
            });

            MenuItem collapse = new MenuItem("折叠");
            collapse.setOnAction(e -> {
                TreeItem<TreeNode> item = contextNode != null
                        ? findConnectionTreeItem(connectionTreeView.getRoot(), contextNode.getId())
                        : connectionTreeView.getRoot();
                if (item != null && item.getValue() == null) {
                    for (TreeItem<TreeNode> child : item.getChildren()) {
                        collapseTreeItem(child);
                    }
                } else {
                    collapseTreeItem(item);
                }
                connectionTreeView.refresh();
            });

            menu.getItems().addAll(new SeparatorMenuItem(), expand, collapse);
        }

        if (contextNode instanceof ConnInfo conn) {
            MenuItem connect = new MenuItem("连接");
            connect.setOnAction(e -> connectToConnection(conn));

            MenuItem properties = new MenuItem("属性");
            properties.setOnAction(e -> openEditConnection(conn));

            menu.getItems().add(0, new SeparatorMenuItem());
            menu.getItems().add(0, properties);
            menu.getItems().add(0, connect);
        }

        if (contextNode != null) {
            MenuItem rename = new MenuItem("重命名");
            rename.setOnAction(e -> renameConnectionNode(contextNode));

            MenuItem delete = new MenuItem("删除");
            delete.setOnAction(e -> deleteConnection(contextNode));

            menu.getItems().addAll(new SeparatorMenuItem(), rename, delete);
        }

        return menu;
    }

    private void renameConnectionNode(TreeNode node) {
        if (node == null) {
            return;
        }
        String newName = DialogHelper.showTextInput("重命名", null, "名称", node.getName());
        if (newName == null || newName.equals(node.getName())) {
            return;
        }
        node.setName(newName);
        saveConnectionData();
        refreshConnectionTree();
        selectConnectionNode(node.getId());
    }

    private class CompactConnectionTreeCell extends TreeCell<TreeNode> {
        private final FontIcon icon = new FontIcon();
        private final Label nameLabel = new Label();
        private final HBox content = new HBox(4, icon, nameLabel);
        private long lastPrimaryPressAt;
        private String lastPrimaryPressNodeId;

        CompactConnectionTreeCell() {
            addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() != MouseButton.PRIMARY || isEmpty()) {
                    return;
                }
                TreeNode item = getItem();
                if (item == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                String itemId = item.getId();
                boolean doublePress = itemId != null
                        && itemId.equals(lastPrimaryPressNodeId)
                        && now - lastPrimaryPressAt <= 450L;
                lastPrimaryPressAt = now;
                lastPrimaryPressNodeId = itemId;
                if (!doublePress) {
                    return;
                }
                TreeItem<TreeNode> treeItem = getTreeItem();
                if (item instanceof ConnInfo conn) {
                    ConnectionManager.getInstance().connect(conn);
                    event.consume();
                } else if (item.isFolder() && treeItem != null) {
                    treeItem.setExpanded(!treeItem.isExpanded());
                    event.consume();
                }
                lastPrimaryPressAt = 0L;
                lastPrimaryPressNodeId = null;
            });
            setOnDragDetected(event -> {
                TreeNode node = getItem();
                if (isEmpty() || node == null) {
                    return;
                }
                Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent clipboardContent = new ClipboardContent();
                clipboardContent.putString(CONNECTION_DRAG_PREFIX + node.getId());
                dragboard.setContent(clipboardContent);
                event.consume();
            });
            setOnDragOver(event -> {
                String draggedId = parseConnectionDrag(event.getDragboard());
                TreeNode target = getItem();
                if (draggedId != null) {
                    if (isEmpty() ? canMoveConnectionNodeToParent(draggedId, "root") : canMoveConnectionNode(draggedId, target)) {
                        event.acceptTransferModes(TransferMode.MOVE);
                    }
                    event.consume();
                }
            });
            setOnDragDropped(event -> {
                String draggedId = parseConnectionDrag(event.getDragboard());
                TreeNode target = getItem();
                if (draggedId != null) {
                    boolean moved = isEmpty() ? moveConnectionNodeToParent(draggedId, "root") : moveConnectionNode(draggedId, target);
                    event.setDropCompleted(moved);
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(TreeNode item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("left-connection-folder", "left-connection-item");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            setText(null);
            nameLabel.setText(item.getName() != null && !item.getName().isBlank() ? item.getName() : "--");
            nameLabel.getStyleClass().setAll("left-connection-name");
            icon.setIconSize(14);
            if (item.isFolder()) {
                icon.getStyleClass().setAll("icon-folder");
                icon.setIconCode(FontAwesomeSolid.FOLDER);
            } else {
                boolean isLinux = item instanceof ConnInfo conn && "ssh".equals(conn.getType());
                icon.getStyleClass().setAll(
                        "tree-icon-conn",
                        isLinux ? "icon-connection-linux" : "icon-connection-windows"
                );
                icon.setIconCode(isLinux ? FontAwesomeBrands.LINUX : FontAwesomeBrands.WINDOWS);
            }
            content.getStyleClass().setAll("left-connection-cell-content");
            setGraphic(content);
            getStyleClass().add(item.isFolder() ? "left-connection-folder" : "left-connection-item");
            setContextMenu(createConnectionTreeContextMenu(item));
        }
    }

    public void updateConnectionInfo(String connId, SystemInfo info) {
        if (connId == null || info == null) {
            return;
        }
        SystemInfo merged = mergeSystemInfo(systemInfoByConnId.get(connId), info);
        systemInfoByConnId.put(connId, merged);
        if (connId.equals(activeConnId)) {
            setConnected(true);
            renderSystemInfo(merged);
        }
    }

    public void removeConnectionInfo(String connId) {
        if (connId != null) {
            systemInfoByConnId.remove(connId);
        }
        if (connId != null && connId.equals(activeConnId)) {
            setConnected(false);
        }
    }

    private SystemInfo mergeSystemInfo(SystemInfo previous, SystemInfo incoming) {
        SystemInfo merged = previous != null ? copySystemInfo(previous) : new SystemInfo();
        if (incoming.distro != null) merged.distro = incoming.distro;
        if (incoming.kernel != null) merged.kernel = incoming.kernel;
        if (incoming.uptime != null) merged.uptime = incoming.uptime;
        if (incoming.systemTime != null) merged.systemTime = incoming.systemTime;
        if (incoming.cpuModel != null) merged.cpuModel = incoming.cpuModel;
        if (incoming.cpuCores != null) merged.cpuCores = incoming.cpuCores;
        if (incoming.cpuPercent >= 0) merged.cpuPercent = incoming.cpuPercent;
        if (incoming.memPercent >= 0) merged.memPercent = incoming.memPercent;
        if (incoming.memValue != null) merged.memValue = incoming.memValue;
        if (incoming.swapPercent >= 0) merged.swapPercent = incoming.swapPercent;
        if (incoming.swapValue != null) merged.swapValue = incoming.swapValue;
        if (incoming.processRunning >= 0) merged.processRunning = incoming.processRunning;
        if (incoming.processSleeping >= 0) merged.processSleeping = incoming.processSleeping;
        if (incoming.processTotal >= 0) merged.processTotal = incoming.processTotal;
        if (incoming.networkInterfaces != null) merged.networkInterfaces = incoming.networkInterfaces;
        if (incoming.uploadSpeed != null) merged.uploadSpeed = incoming.uploadSpeed;
        if (incoming.downloadSpeed != null) merged.downloadSpeed = incoming.downloadSpeed;
        if (incoming.latency != null) merged.latency = incoming.latency;
        if (incoming.topProcesses != null) merged.topProcesses = incoming.topProcesses;
        if (incoming.diskInfo != null) merged.diskInfo = incoming.diskInfo;
        if (incoming.allUsers != null) merged.allUsers = incoming.allUsers;
        return merged;
    }

    private SystemInfo copySystemInfo(SystemInfo source) {
        SystemInfo copy = new SystemInfo();
        copy.distro = source.distro;
        copy.kernel = source.kernel;
        copy.uptime = source.uptime;
        copy.systemTime = source.systemTime;
        copy.cpuModel = source.cpuModel;
        copy.cpuCores = source.cpuCores;
        copy.cpuPercent = source.cpuPercent;
        copy.memPercent = source.memPercent;
        copy.memValue = source.memValue;
        copy.swapPercent = source.swapPercent;
        copy.swapValue = source.swapValue;
        copy.processRunning = source.processRunning;
        copy.processSleeping = source.processSleeping;
        copy.processTotal = source.processTotal;
        copy.networkInterfaces = source.networkInterfaces;
        copy.uploadSpeed = source.uploadSpeed;
        copy.downloadSpeed = source.downloadSpeed;
        copy.latency = source.latency;
        copy.topProcesses = source.topProcesses;
        copy.diskInfo = source.diskInfo;
        copy.allUsers = source.allUsers;
        return copy;
    }

    private void renderSystemInfo(SystemInfo info) {
        if (info.distro != null || info.kernel != null || info.uptime != null || info.systemTime != null) {
            setBasicInfo(info.distro, info.kernel, info.uptime, info.systemTime);
        }
        if (info.cpuModel != null || info.cpuCores != null || info.cpuPercent >= 0) {
            setCpuInfo(info.cpuModel, info.cpuCores, info.cpuPercent);
        }
        if (info.memValue != null || info.memPercent >= 0 || info.swapValue != null || info.swapPercent >= 0) {
            setMemoryInfo(info.memPercent, info.memValue, info.swapPercent, info.swapValue);
        }
        if (info.processRunning >= 0 || info.processSleeping >= 0 || info.processTotal >= 0) {
            setProcessInfo(info.processRunning, info.processSleeping, info.processTotal);
        }
        if (info.uploadSpeed != null || info.downloadSpeed != null || info.latency != null) {
            setNetworkInfo(info.uploadSpeed, info.downloadSpeed, info.latency);
        }
        if (info.networkInterfaces != null) {
            setNetworkInterfaces(info.networkInterfaces);
        }
        if (info.topProcesses != null) {
            setProcessList(info.topProcesses);
        }
        if (info.diskInfo != null) {
            setDiskInfo(info.diskInfo);
        }
        if (info.allUsers != null) {
            setUserInfo(info.allUsers);
        }
    }

    private void onNicChanged() {
        if (updatingNetworkInterfaces) {
            return;
        }
        String selectedNic = nicSelect.getSelectionModel().getSelectedItem();
        resetNetworkChart();
        setNetworkInfo("--", "--", null);
        SshService currentService = ConnectionManager.getInstance().getCurrentSshService();
        if (currentService != null) {
            currentService.resetNetworkSnapshot(selectedNic);
        }
    }

    private void setupProgressBar(Region fill) {
        if (fill == null) return;
        StackPane container = (StackPane) fill.getParent();
        if (container == null) return;
        Rectangle clip = new Rectangle();
        clip.heightProperty().bind(container.heightProperty());
        fill.setClip(clip);
    }

    private void updateProgress(Region fill, double percent) {
        if (fill == null) return;
        Rectangle clip = (Rectangle) fill.getClip();
        if (clip == null) return;
        StackPane container = (StackPane) fill.getParent();
        if (container == null) return;
        double safePercent = Math.max(0, Math.min(100, percent));
        if (clip.widthProperty().isBound()) {
            clip.widthProperty().unbind();
        }
        clip.widthProperty().bind(container.widthProperty().multiply(safePercent / 100.0));
    }

    private void initNetworkChart() {
        resetNetworkChartData();
        if (networkChartInitialized) {
            drawNetworkChart();
            return;
        }
        networkChartInitialized = true;

        bindNetworkChartCanvas();
        networkChartCanvas.widthProperty().addListener((obs, oldValue, newValue) -> drawNetworkChart());
        networkChartCanvas.heightProperty().addListener((obs, oldValue, newValue) -> drawNetworkChart());

        networkChartCanvas.setOnMouseMoved(e -> {
            double mouseX = e.getX();
            double mouseY = e.getY();
            double width = networkChartCanvas.getWidth();
            int dataSize = uploadData.size();
            if (dataSize < 2 || width <= 0) return;

            double stepX = width / (dataSize - 1);
            int newIndex = (int) Math.round(mouseX / stepX);
            newIndex = Math.max(0, Math.min(dataSize - 1, newIndex));

            hoverX = mouseX;
            hoverY = mouseY;
            hoverIndex = newIndex;
            drawNetworkChart();
        });

        networkChartCanvas.setOnMouseExited(e -> {
            hoverIndex = -1;
            hoverX = -1;
            hoverY = -1;
            drawNetworkChart();
        });

        drawNetworkChart();
    }

    private void resetNetworkChart() {
        resetNetworkChartData();
        drawNetworkChart();
    }

    private void resetNetworkChartData() {
        long now = System.currentTimeMillis();
        uploadData.clear();
        downloadData.clear();
        dataTimestamps.clear();
        hoverIndex = -1;
        hoverX = -1;
        hoverY = -1;
        for (int i = 0; i < 80; i++) {
            uploadData.add(0.0);
            downloadData.add(0.0);
            dataTimestamps.add(now - (80 - i) * 1000L);
        }
    }

    private void drawNetworkChart() {
        GraphicsContext gc = networkChartCanvas.getGraphicsContext2D();
        double width = networkChartCanvas.getWidth();
        double height = networkChartCanvas.getHeight();

        gc.clearRect(0, 0, width, height);
        int dataSize = uploadData.size();
        if (dataSize < 2 || width <= 0 || height <= 0) {
            return;
        }

        boolean darkTheme = ThemeManager.getInstance().isDarkTheme();
        Color gridColor = darkTheme ? Color.web("#2a2a2a") : Color.web("#cbd5e1");
        Color uploadColor = darkTheme ? Color.web("#4a4") : Color.web("#22c55e");
        Color downloadColor = darkTheme ? Color.web("#4af") : Color.web("#3b82f6");
        Color hoverLineColor = darkTheme ? Color.web("#ffffff", 0.3) : Color.web("#475569", 0.35);

        gc.setStroke(gridColor);
        gc.setLineWidth(0.5);
        for (int i = 0; i <= 4; i++) {
            double y = (height / 4) * i;
            gc.strokeLine(0, y, width, y);
        }

        double stepX = width / (dataSize - 1);
        double scaleMax = calculateNetworkScaleMax();

        gc.setStroke(uploadColor);
        gc.setLineWidth(1.5);
        drawNetLine(gc, height, dataSize, stepX, scaleMax, uploadData);

        gc.setStroke(downloadColor);
        drawNetLine(gc, height, dataSize, stepX, scaleMax, downloadData);

        if (hoverIndex >= 0 && hoverIndex < dataSize) {
            double pointX = hoverIndex * stepX;

            gc.setStroke(hoverLineColor);
            gc.setLineWidth(1);
            gc.setLineDashes(4, 4);
            gc.strokeLine(pointX, 0, pointX, height);
            gc.setLineDashes();

            double uploadY = toNetworkChartY(uploadData.get(hoverIndex), height, scaleMax);
            gc.setFill(uploadColor);
            gc.fillOval(pointX - 3, uploadY - 3, 6, 6);

            double downloadY = toNetworkChartY(downloadData.get(hoverIndex), height, scaleMax);
            gc.setFill(downloadColor);
            gc.fillOval(pointX - 3, downloadY - 3, 6, 6);

            drawTooltipOnCanvas(gc, hoverIndex, width, height);
        }
    }

    private double calculateNetworkScaleMax() {
        double maxValue = 0;
        for (int i = 0; i < uploadData.size(); i++) {
            maxValue = Math.max(maxValue, uploadData.get(i));
            maxValue = Math.max(maxValue, downloadData.get(i));
        }
        if (maxValue <= 100) {
            return 100;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(maxValue)));
        double normalized = maxValue / magnitude;
        double niceNormalized = normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        return niceNormalized * magnitude;
    }

    private double toNetworkChartY(double value, double height, double scaleMax) {
        double safeValue = Math.max(0, value);
        return height - Math.min(1.0, safeValue / scaleMax) * height;
    }

    private void drawNetLine(GraphicsContext gc, double height, int dataSize, double stepX, double scaleMax, List<Double> values) {
        gc.beginPath();
        for (int i = 0; i < dataSize; i++) {
            double x = i * stepX;
            double y = toNetworkChartY(values.get(i), height, scaleMax);
            if (i == 0) {
                gc.moveTo(x, y);
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void bindNetworkChartCanvas() {
        if (networkChartPane == null || networkChartCanvas == null) {
            return;
        }
        networkChartCanvas.widthProperty().bind(networkChartPane.widthProperty());
    }

    private void drawTooltipOnCanvas(GraphicsContext gc, int index, double canvasWidth, double canvasHeight) {
        if (index < 0 || index >= uploadData.size()) return;

        double uploadVal = uploadData.get(index);
        double downloadVal = downloadData.get(index);
        long timestamp = dataTimestamps.get(index);
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());

        String line1 = String.format("上传: %.1f KB/s", uploadVal);
        String line2 = String.format("下载: %.1f KB/s", downloadVal);
        String line3 = String.format("时间: %s", time.format(TIME_FORMATTER));

        Font tooltipFont = Font.font("Monospaced", 11);
        gc.setFont(tooltipFont);

        double padding = 8;
        double lineHeight = 14;
        double boxWidth = Math.max(measureTextWidth(line1, tooltipFont),
                Math.max(measureTextWidth(line2, tooltipFont), measureTextWidth(line3, tooltipFont))) + padding * 2;
        double boxHeight = lineHeight * 3 + padding * 2;
        double margin = 6;
        double visibleWidth = canvasWidth;
        if (networkChartPane != null && networkChartPane.getWidth() > 0) {
            visibleWidth = Math.min(visibleWidth, networkChartPane.getWidth());
        }

        double tipX = this.hoverX + 12;
        double tipY = this.hoverY - boxHeight - 8;

        if (tipX + boxWidth + margin > visibleWidth) {
            tipX = this.hoverX - boxWidth - 12;
        }
        tipX = Math.max(margin, Math.min(tipX, visibleWidth - boxWidth - margin));
        if (tipY < 0) {
            tipY = this.hoverY + 18;
        }
        if (tipY + boxHeight + margin > canvasHeight) {
            tipY = Math.max(margin, canvasHeight - boxHeight - margin);
        }

        boolean darkTheme = ThemeManager.getInstance().isDarkTheme();
        Color tooltipBg = darkTheme ? Color.web("#1e1e1e", 0.92) : Color.web("#ffffff", 0.96);
        Color tooltipBorder = darkTheme ? Color.web("#555") : Color.web("#cbd5e1");
        Color tooltipText = darkTheme ? Color.web("#ddd") : Color.web("#1f2937");
        Color tooltipMuted = darkTheme ? Color.web("#888") : Color.web("#64748b");

        gc.setFill(tooltipBg);
        gc.fillRoundRect(tipX, tipY, boxWidth, boxHeight, 4, 4);

        gc.setStroke(tooltipBorder);
        gc.setLineWidth(1);
        gc.strokeRoundRect(tipX, tipY, boxWidth, boxHeight, 4, 4);

        gc.setFill(tooltipText);
        gc.fillText(line1, tipX + padding, tipY + padding + 12);
        gc.fillText(line2, tipX + padding, tipY + padding + 12 + lineHeight);
        gc.setFill(tooltipMuted);
        gc.fillText(line3, tipX + padding, tipY + padding + 12 + lineHeight * 2);
    }

    private double measureTextWidth(String text, Font font) {
        Text helper = new Text(text);
        helper.setFont(font);
        return helper.getLayoutBounds().getWidth();
    }

    public void updateNetworkChart(double uploadValue, double downloadValue) {
        uploadData.add(Math.max(0, uploadValue));
        downloadData.add(Math.max(0, downloadValue));
        dataTimestamps.add(System.currentTimeMillis());

        if (uploadData.size() > 80) {
            uploadData.remove(0);
            downloadData.remove(0);
            dataTimestamps.remove(0);
        }

        drawNetworkChart();
    }

    public void setServerLabel(String serverName) {
        if (serverLabel == null) {
            return;
        }
        this.serverLabel.setText(serverName != null ? serverName : "--");
    }

    public void setConnected(boolean connected) {
        if (serverIcon == null) {
            return;
        }
        if (connected) {
            serverIcon.getStyleClass().remove("disconnected");
        } else {
            if (!serverIcon.getStyleClass().contains("disconnected")) {
                serverIcon.getStyleClass().add("disconnected");
            }
        }
    }

    public void setBasicInfo(String distro, String kernel, String uptime, String systemTime) {
        if (distro != null && !distro.isEmpty()) {
            this.distroValue.setText(distro);
        }
        if (kernel != null && !kernel.isEmpty()) {
            this.kernelValue.setText(kernel);
        }
        if (uptime != null && !uptime.isEmpty()) {
            this.uptimeValue.setText(uptime);
        }
        if (systemTime != null && !systemTime.isEmpty()) {
            this.systemTimeValue.setText(systemTime);
        }
    }

    public void setCpuInfo(String model, String cores, double percent) {
        if (model != null && !model.isEmpty()) {
            this.cpuModelValue.setText(model);
        }
        if (cores != null && !cores.isEmpty()) {
            this.cpuCoresValue.setText(cores);
        }
        if (percent >= 0) {
            double safePercent = Math.max(0, Math.min(100, percent));
            this.cpuPercentLabel.setText(String.format("%.0f%%", safePercent));
            updateProgress(cpuProgressBar, safePercent);
        }
    }

    public void setMemoryInfo(double memPercent, String memValue, double swapPercent, String swapValue) {
        if (memPercent >= 0) {
            double safeMemPercent = Math.max(0, Math.min(100, memPercent));
            this.memoryPercentLabel.setText(String.format("%.0f%%", safeMemPercent));
            updateProgress(memoryProgressBar, safeMemPercent);
        }

        if (memValue != null && !memValue.isEmpty()) {
            this.memoryValueLabel.setText(memValue);
        }

        if (swapPercent >= 0) {
            double safeSwapPercent = Math.max(0, Math.min(100, swapPercent));
            this.swapPercentLabel.setText(String.format("%.0f%%", safeSwapPercent));
            updateProgress(swapProgressBar, safeSwapPercent);
        }

        if (swapValue != null && !swapValue.isEmpty()) {
            this.swapValueLabel.setText(swapValue);
        }
    }

    public void setProcessInfo(int running, int sleeping, int total) {
        if (running >= 0) {
            this.processRunningValue.setText(String.valueOf(running));
        }

        if (sleeping >= 0) {
            this.processSleepingValue.setText(String.valueOf(sleeping));
        }

        if (total >= 0) {
            this.processTotalValue.setText(String.valueOf(total));
        }

    }

    public void setProcessList(ProcessInfo[] processes) {
        processListData.clear();
        if (processes != null && processes.length > 0) {
            processListData.addAll(processes);
        }
        double headerHeight = 26;
        double cellSize = processTableView.getFixedCellSize();
        int rowCount = processListData.size();
        processTableView.setPrefHeight(headerHeight + rowCount * cellSize);
        processTableView.setMaxHeight(Region.USE_PREF_SIZE);
    }

    private TableCell<ProcessInfo, Double> createProgressBarCell(String barStyleClass) {
        return new TableCell<>() {
            private final Region fill = new Region();
            private final Label valueLabel = new Label();
            private final StackPane barContainer = new StackPane(fill, valueLabel);
            private boolean initialized = false;

            {
                barContainer.getStyleClass().add(barStyleClass);
                if ("proc-bar-cpu".equals(barStyleClass)) {
                    fill.getStyleClass().add("fill-progress-cpu");
                } else {
                    fill.getStyleClass().add("fill-progress-memory");
                }
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }

            @Override
            protected void updateItem(Double percent, boolean empty) {
                super.updateItem(percent, empty);
                if (empty || percent == null) {
                    setGraphic(null);
                    return;
                }
                if (!initialized) {
                    setupProgressBar(fill);
                    initialized = true;
                }
                valueLabel.setText(String.format("%.1f%%", percent));
                updateProgress(fill, Math.max(0, Math.min(100, percent)));
                setGraphic(barContainer);
            }
        };
    }

    private TableCell<DiskInfo, Double> createDiskBarCell() {
        return new TableCell<>() {
            private final Region fill = new Region();
            private final Label sizeLabel = new Label();
            private final HBox textRow = new HBox(new Pane(), sizeLabel);
            private final StackPane barContainer = new StackPane(fill, textRow);
            private boolean initialized = false;

            {
                barContainer.getStyleClass().add("disk-bar-cell");
                fill.getStyleClass().add("fill-progress-disk");
                sizeLabel.getStyleClass().add("disk-bar-size-text");
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                HBox.setHgrow(textRow.getChildren().get(1), Priority.ALWAYS);
            }

            @Override
            protected void updateItem(Double percent, boolean empty) {
                super.updateItem(percent, empty);
                if (empty || percent == null) {
                    setGraphic(null);
                    return;
                }
                if (!initialized) {
                    setupProgressBar(fill);
                    initialized = true;
                }
                DiskInfo disk = getTableView().getItems().get(getIndex());
                sizeLabel.setText(disk.sizeText != null ? disk.sizeText : "");
                updateProgress(fill, Math.max(0, Math.min(100, percent)));
                setGraphic(barContainer);
            }
        };
    }

    public void setDiskInfo(DiskInfo[] disks) {
        diskListData.clear();
        if (disks != null && disks.length > 0) {
            diskListData.addAll(disks);
        }
        double headerHeight = 26;
        double cellSize = diskTableView.getFixedCellSize();
        int rowCount = Math.min(diskListData.size(), 8);
        diskTableView.setPrefHeight(headerHeight + rowCount * cellSize);
    }

    public void setNetworkInfo(String uploadSpeed, String downloadSpeed, String latency) {
        if (uploadSpeed != null && !uploadSpeed.isEmpty()) {
            this.uploadSpeedValue.setText(uploadSpeed);
        }
        if (downloadSpeed != null && !downloadSpeed.isEmpty()) {
            this.downloadSpeedValue.setText(downloadSpeed);
        }
        if (latency != null && !latency.isEmpty()) {
            this.latencyValue.setText(latency);
        }
    }

    public void setCurrentUserValue(String currentUser) {
        this.currentUserValue.setText(currentUser != null ? currentUser : "--");
    }

    public void setUserInfo(UserInfo[] users) {
        userListData.clear();
        if (users != null && users.length > 0) {
            userListData.addAll(users);
        }
        double headerHeight = 26;
        double cellSize = userTableView.getFixedCellSize();
        int rowCount = Math.min(userListData.size(), 5);
        userTableView.setPrefHeight(headerHeight + rowCount * cellSize);
    }

    public String getSelectedNic() {
        return nicSelect.getSelectionModel().getSelectedItem();
    }

    public void setNetworkInterfaces(String[] interfaces) {
        List<String> nextItems = Arrays.stream(interfaces != null ? interfaces : new String[0])
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .filter(value -> !"lo".equals(value))
                .distinct()
                .toList();

        if (nicSelect.getItems().equals(nextItems)) {
            if (nicSelect.getValue() == null && !nextItems.isEmpty()) {
                updatingNetworkInterfaces = true;
                try {
                    nicSelect.getSelectionModel().selectFirst();
                } finally {
                    updatingNetworkInterfaces = false;
                }
            }
            return;
        }

        if (nicSelect.isShowing()) {
            pendingNetworkInterfaceItems = nextItems;
            return;
        }

        applyNetworkInterfaceItems(nextItems);
    }

    private void applyNetworkInterfaceItems(List<String> nextItems) {
        String selectedValue = nicSelect.getValue();

        updatingNetworkInterfaces = true;
        try {
            nicSelect.getItems().clear();
            nicSelect.getItems().addAll(nextItems);

            // 1. 尝试恢复之前选中的值
            if (selectedValue != null && nicSelect.getItems().contains(selectedValue)) {
                nicSelect.getSelectionModel().select(selectedValue);
            } else if (!nicSelect.getItems().isEmpty()) {
                // 2. 如果之前选中的值不存在（比如网卡被拔掉），则默认选中第一个
                nicSelect.getSelectionModel().selectFirst();
            }
        } finally {
            updatingNetworkInterfaces = false;
        }
    }
}

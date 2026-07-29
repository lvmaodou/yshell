package com.yshell.controller;

import com.yshell.config.AppSettings;
import com.yshell.service.ConnectionManager;
import com.yshell.service.SshService;
import com.yshell.service.SshService.RemoteFileInfo;
import com.yshell.theme.ThemeManager;
import com.yshell.transfer.*;
import com.yshell.ui.ApplicationIcons;
import com.yshell.ui.DialogHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FilesViewController {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilesViewController.class);
    private static final String REMOTE_MOVE_DRAG_PREFIX = "YSHELL_REMOTE_MOVE";
    private static final String DRAG_SOURCE_TABLE = "table";
    private static final String DRAG_SOURCE_TREE = "tree";
    private static final double FILE_NAME_COLUMN_MIN_WIDTH = 180;
    private static final double FILE_NAME_COLUMN_DEFAULT_WIDTH = 280;
    private static final double FILE_TABLE_EXTRA_WIDTH = 28;
    @FXML
    private TextField pathInput;

    @FXML
    private Button btnRefresh;

    @FXML
    private Button btnNavUp;

    @FXML
    private Button btnDownload;

    @FXML
    private Button btnUpload;

    @FXML
    private Button btnJumpPath;

    @FXML
    private Button btnTransferQueue;

    @FXML
    private Button btnCompressedTransferQueue;

    @FXML
    private TreeView<String> folderTree;

    @FXML
    private TableView<RemoteFileInfo> fileTable;

    @FXML
    private TableColumn<RemoteFileInfo, String> colName;

    @FXML
    private TableColumn<RemoteFileInfo, String> colSize;

    @FXML
    private TableColumn<RemoteFileInfo, String> colType;

    @FXML
    private TableColumn<RemoteFileInfo, String> colDate;

    @FXML
    private TableColumn<RemoteFileInfo, String> colPerm;

    @FXML
    private TableColumn<RemoteFileInfo, String> colOwner;

    private String currentDirectory = "/";
    private String activeConnId;
    private final Map<String, String> directoryByConnId = new ConcurrentHashMap<>();
    private List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private final Set<TreeItem<String>> loadedTreeItems = new HashSet<>();
    private final Set<TreeItem<String>> loadingTreeItems = new HashSet<>();
    private final Set<TreeItem<String>> expandAfterLoad = new HashSet<>();
    private final Map<String, RemoteFileInfo> treeDirectoryInfoByPath = new ConcurrentHashMap<>();
    private boolean isNavigatingFromTree = false;
    private boolean isRefreshingTree = false;
    private boolean intiFlag = false;
    private final TransferManager transferManager = TransferManager.getInstance();
    private final CompressedTransferManager compressedTransferManager = CompressedTransferManager.getInstance();
    private final AppSettings appSettings = AppSettings.getInstance();
    private Path downloadRoot;
    private Stage transferStage;
    private String transferStageConnId;
    private Stage compressedTransferStage;
    private String compressedTransferStageConnId;
    private String observedQueueConnId;
    private String observedCompressedQueueConnId;
    private final ListChangeListener<TransferTask> transferQueueListener = c -> updateTransferQueueButton();
    private final ListChangeListener<CompressedTransferTask> compressedQueueListener = c -> updateCompressedTransferQueueButton();

    private record PermissionEdit(String mode, boolean recursive, String recursiveScope) {
    }

    private record RemoteMoveDrag(String connId, String source, List<String> paths) {
    }

    @FXML
    public void initialize() {
        btnRefresh.setOnAction(e -> refresh());
        btnNavUp.setOnAction(e -> navigateUp());
        btnDownload.setOnAction(e -> downloadSelectedFiles());
        btnUpload.setOnAction(e -> uploadToSelectedDirectoryOrCurrent());
        btnJumpPath.setOnAction(e -> jumpToTerminal());
        btnTransferQueue.setOnAction(e -> showTransferQueueWindow(null));
        btnTransferQueue.setVisible(false);
        btnTransferQueue.setManaged(false);
        btnCompressedTransferQueue.setOnAction(e -> showCompressedTransferQueueWindow(null));
        btnCompressedTransferQueue.setVisible(false);
        btnCompressedTransferQueue.setManaged(false);
        initDownloadRoot();

        initTableView();
        initTreeView();

        ConnectionManager.getInstance().setFilesViewController(this);

        loadFolderTree();
        loadFileList();
    }

    private void initTableView() {
        fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        fileTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        configureFileTableColumnWidths();

        colName.setCellFactory(column -> new TableCell<>() {
            private final FontIcon icon = new FontIcon();

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                RemoteFileInfo file = getTableRow().getItem();
                setText(file.name());

                icon.setIconSize(14);
                if (file.isSymbolicLink()) {
                    icon.setIconLiteral("fas-link");
                    setFileIconClass(icon, file.isLinkTargetDirectory() ? "file-icon-folder" : "file-icon-txt");
                } else if (file.isDirectory()) {
                    icon.setIconLiteral("fas-folder");
                    setFileIconClass(icon, "file-icon-folder");
                } else {
                    FontIcon fileIcon = getFileIcon(file.name());
                    setGraphic(fileIcon);
                    return;
                }
                setGraphic(icon);
            }
        });

        colSize.setCellValueFactory(cellData -> {
            RemoteFileInfo file = cellData.getValue();
            return new SimpleStringProperty(file.getFormattedSize());
        });

        colType.setCellValueFactory(cellData -> {
            RemoteFileInfo file = cellData.getValue();
            if (file.isSymbolicLink() && file.isLinkTargetDirectory()) return new SimpleStringProperty("链接目录");
            if (file.isSymbolicLink() && file.isLinkTargetRegularFile()) return new SimpleStringProperty("链接文件");
            if (file.isSymbolicLink()) return new SimpleStringProperty("符号链接");
            if (file.isDirectory()) return new SimpleStringProperty("文件夹");
            return new SimpleStringProperty(getFileType(file.name()));
        });
        colDate.setCellValueFactory(cellData -> {
            RemoteFileInfo file = cellData.getValue();
            return new SimpleStringProperty(file.lastModified());
        });
        colPerm.setCellValueFactory(cellData -> {
            RemoteFileInfo file = cellData.getValue();
            return new SimpleStringProperty(file.permissions());
        });
        colOwner.setText("所有者/组");
        colOwner.setCellValueFactory(cellData -> {
            RemoteFileInfo file = cellData.getValue();
            return new SimpleStringProperty(ownerGroupDisplay(file));
        });

        fileTable.setRowFactory(tv -> {
            TableRow<RemoteFileInfo> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    RemoteFileInfo file = row.getItem();
                    if (file.isDirectoryLike()) {
                        navigateTo(file.fullPath());
                    } else {
                        // 文件：打开到编辑器
                        openFileInEditor(file.fullPath());
                    }
                }
            });
            row.setOnContextMenuRequested(event -> {
                if (row.isEmpty()) {
                    fileTable.getSelectionModel().clearSelection();
                    ContextMenu menu = createTableBlankContextMenu();
                    menu.show(row, event.getScreenX(), event.getScreenY());
                    event.consume();
                    return;
                }
                RemoteFileInfo file = row.getItem();
                if (!fileTable.getSelectionModel().getSelectedItems().contains(file)) {
                    fileTable.getSelectionModel().clearSelection();
                    fileTable.getSelectionModel().select(file);
                }
                ContextMenu menu = file.isDirectoryLike()
                        ? createTableDirectoryContextMenu(file)
                        : createTableFileContextMenu(file);
                menu.show(row, event.getScreenX(), event.getScreenY());
                event.consume();
            });
            row.setOnDragDetected(event -> {
                if (row.isEmpty() || activeConnId == null) {
                    return;
                }
                RemoteFileInfo file = row.getItem();
                if (!fileTable.getSelectionModel().getSelectedItems().contains(file)) {
                    fileTable.getSelectionModel().clearSelection();
                    fileTable.getSelectionModel().select(file);
                }
                List<String> paths = fileTable.getSelectionModel().getSelectedItems().stream()
                        .map(RemoteFileInfo::fullPath)
                        .toList();
                Dragboard dragboard = row.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(encodeRemoteMoveDrag(DRAG_SOURCE_TABLE, paths));
                dragboard.setContent(content);
                event.consume();
            });
            row.setOnDragOver(event -> {
                if (!row.isEmpty()
                        && row.getItem().isDirectoryLike()
                        && canDropRemoteMove(event.getDragboard(), DRAG_SOURCE_TABLE, row.getItem().fullPath())) {
                    event.acceptTransferModes(TransferMode.MOVE);
                    event.consume();
                }
            });
            row.setOnDragDropped(event -> {
                boolean completed = !row.isEmpty()
                        && row.getItem().isDirectoryLike()
                        && dropRemoteMove(event.getDragboard(), DRAG_SOURCE_TABLE, row.getItem().fullPath());
                event.setDropCompleted(completed);
                event.consume();
            });
            return row;
        });

        fileTable.setOnContextMenuRequested(event -> {
            fileTable.getSelectionModel().clearSelection();
            ContextMenu menu = createTableBlankContextMenu();
            menu.show(fileTable, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private String ownerGroupDisplay(RemoteFileInfo file) {
        if (file == null) return "";
        String owner = identityPart(file.owner());
        String group = identityPart(file.group());
        if (owner.isEmpty() || group.isEmpty()) return "";
        return owner + "/" + group;
    }

    private String identityPart(String value) {
        return value == null ? "" : value.trim();
    }

    private void configureFileTableColumnWidths() {
        colName.setMinWidth(FILE_NAME_COLUMN_MIN_WIDTH);
        colName.setPrefWidth(FILE_NAME_COLUMN_DEFAULT_WIDTH);
        colSize.setMinWidth(120);
        colSize.setPrefWidth(120);
        colType.setMinWidth(110);
        colType.setPrefWidth(110);
        colDate.setMinWidth(150);
        colDate.setPrefWidth(150);
        colPerm.setMinWidth(120);
        colPerm.setPrefWidth(120);
        colOwner.setMinWidth(110);
        colOwner.setPrefWidth(110);

        ChangeListener<Number> widthListener = (obs, oldWidth, newWidth) -> updateFileNameColumnWidth();
        fileTable.widthProperty().addListener(widthListener);
        colSize.widthProperty().addListener(widthListener);
        colType.widthProperty().addListener(widthListener);
        colDate.widthProperty().addListener(widthListener);
        colPerm.widthProperty().addListener(widthListener);
        colOwner.widthProperty().addListener(widthListener);
        Platform.runLater(this::updateFileNameColumnWidth);
    }

    private void updateFileNameColumnWidth() {
        if (fileTable == null || colName == null) return;
        double fixedColumnsWidth = colSize.getWidth()
                + colType.getWidth()
                + colDate.getWidth()
                + colPerm.getWidth()
                + colOwner.getWidth()
                + FILE_TABLE_EXTRA_WIDTH;
        double availableNameWidth = fileTable.getWidth() - fixedColumnsWidth;
        double nextWidth = Math.max(FILE_NAME_COLUMN_MIN_WIDTH, availableNameWidth);
        if (Math.abs(colName.getPrefWidth() - nextWidth) > 1) {
            colName.setPrefWidth(nextWidth);
        }
    }

    /**
     * 双击文件：打开一个独立的编辑器窗口
     */
    private void openFileInEditor(String path) {
        EditorViewController.open(path, activeConnId);
    }

    private ContextMenu createTreeDirectoryContextMenu(TreeItem<String> item) {
        String directory = getTreePath(item);
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                menuItem("刷新", () -> refreshDirectory(directory)),
                menuItem("新建文件夹", () -> createDirectoryIn(directory)),
                menuItem("重命名", () -> renameTreeDirectory(directory)),
                menuItem("删除", () -> deletePath(directory, true)),
                new SeparatorMenuItem(),
                menuItem("复制路径", () -> copyPath(directory)),
                new SeparatorMenuItem(),
                menuItem("上传", () -> uploadTo(directory)),
                menuItem("下载", () -> downloadPath(directory)),
                menuItem("压缩上传", () -> compressedUploadTo(directory)),
                menuItem("压缩下载", () -> compressedDownload(directory)),
                new SeparatorMenuItem(),
                menuItem("文件权限", () -> showPermissionDialog(directory, true, null))
        );
        return menu;
    }

    private ContextMenu createTableDirectoryContextMenu(RemoteFileInfo file) {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                menuItem("刷新", this::refresh),
                menuItem("新建文件夹", () -> createDirectoryIn(file.parentPath())),
                menuItem("新建文件", () -> createFileIn(file.parentPath())),
                menuItem("重命名", () -> renamePath(file.fullPath())),
                menuItem("删除", () -> deletePath(file.fullPath(), true)),
                new SeparatorMenuItem(),
                menuItem("复制路径", () -> copyPath(file.fullPath())),
                new SeparatorMenuItem(),
                menuItem("上传", () -> uploadTo(file.fullPath())),
                menuItem("下载", () -> downloadSelectedOrSingle(file)),
                menuItem("压缩上传", () -> compressedUploadTo(file.fullPath())),
                menuItem("压缩下载", () -> compressedDownload(file.fullPath())),
                new SeparatorMenuItem(),
                menuItem("文件权限", () -> showPermissionDialog(file.fullPath(), true, file.permissions()))
        );
        return menu;
    }

    private ContextMenu createTableFileContextMenu(RemoteFileInfo file) {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                menuItem("刷新", this::refresh),
                menuItem("新建文件夹", () -> createDirectoryIn(file.parentPath())),
                menuItem("新建文件", () -> createFileIn(file.parentPath())),
                menuItem("重命名", () -> renamePath(file.fullPath())),
                menuItem("删除", () -> deletePath(file.fullPath(), false)),
                new SeparatorMenuItem(),
                menuItem("打开", () -> openFileInEditor(file.fullPath())),
                menuItem("复制路径", () -> copyPath(file.fullPath())),
                new SeparatorMenuItem(),
                menuItem("上传", () -> uploadTo(file.parentPath())),
                menuItem("下载", () -> downloadSelectedOrSingle(file)),
                menuItem("压缩上传", () -> compressedUploadTo(file.parentPath())),
                new SeparatorMenuItem(),
                menuItem("文件权限", () -> showPermissionDialog(file.fullPath(), false, file.permissions()))
        );
        return menu;
    }

    private ContextMenu createTableBlankContextMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                menuItem("刷新", this::refresh),
                menuItem("新建文件夹", () -> createDirectoryIn(currentDirectory)),
                menuItem("新建文件", () -> createFileIn(currentDirectory)),
                new SeparatorMenuItem(),
                menuItem("上传", () -> uploadTo(currentDirectory)),
                menuItem("下载", () -> downloadPath(currentDirectory)),
                menuItem("压缩上传", () -> compressedUploadTo(currentDirectory)),
                menuItem("压缩下载", () -> compressedDownload(currentDirectory))
        );
        return menu;
    }

    private MenuItem menuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    private SshService activeSshService() {
        return activeConnId == null ? null : ConnectionManager.getInstance().getConnectionById(activeConnId);
    }

    private void refreshDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            refresh();
            return;
        }
        currentDirectory = directory;
        pathInput.setText(directory);
        refresh();
    }

    private void createDirectoryIn(String parentDirectory) {
        String name = DialogHelper.showTextInput("新建文件夹", null, "名称", "新建文件夹");
        if (isValidRemoteName(name)) return;
        runFileOperation("新建文件夹", () -> Objects.requireNonNull(activeSshService()).createRemoteDirectory(joinRemotePath(parentDirectory, name)));
    }

    private void createFileIn(String parentDirectory) {
        String name = DialogHelper.showTextInput("新建文件", null, "名称", "新建文件");
        if (isValidRemoteName(name)) return;
        runFileOperation("新建文件", () -> Objects.requireNonNull(activeSshService()).createRemoteFile(joinRemotePath(parentDirectory, name)));
    }

    private void renamePath(String path) {
        if (path == null || "/".equals(path)) {
            DialogHelper.showWarning("提示", "根目录不能重命名");
            return;
        }
        String oldName = remoteName(path);
        String newName = DialogHelper.showTextInput("重命名", null, "名称", oldName);
        if (isValidRemoteName(newName) || oldName.equals(newName)) return;
        runFileOperation("重命名", () -> Objects.requireNonNull(activeSshService()).renameRemotePath(path, joinRemotePath(remoteParent(path), newName)));
    }

    private void renameTreeDirectory(String path) {
        if (path == null || "/".equals(path)) {
            DialogHelper.showWarning("提示", "根目录不能重命名");
            return;
        }
        SshService sshService = activeSshService();
        if (sshService == null || !sshService.isConnected()) {
            DialogHelper.showWarning("提示", "当前连接不可用");
            return;
        }

        String oldName = remoteName(path);
        String newName = DialogHelper.showTextInput("重命名", null, "名称", oldName);
        if (isValidRemoteName(newName) || oldName.equals(newName)) return;

        String parent = remoteParent(path);
        String newPath = joinRemotePath(parent, newName);
        try {
            sshService.renameRemotePath(path, newPath);
            if (currentDirectory.equals(path) || currentDirectory.startsWith(path + "/")) {
                currentDirectory = newPath + currentDirectory.substring(path.length());
                pathInput.setText(currentDirectory);
                saveCurrentDirectoryForActiveConnection();
            }
            loadFileList();
            refreshTreeDirectory(parent, currentDirectory);
        } catch (Exception e) {
            LOGGER.error("rename failed", e);
            DialogHelper.showError("重命名失败", e.getMessage());
        }
    }

    private void deletePath(String path, boolean directory) {
        if (path == null || "/".equals(path)) {
            DialogHelper.showWarning("提示", "根目录不能删除");
            return;
        }
        String type = directory ? "文件夹" : "文件";
        if (!DialogHelper.showConfirm("删除" + type, "确定删除 " + path + " 吗？")) {
            return;
        }
        runFileOperation("删除", () -> {
            Objects.requireNonNull(activeSshService()).deleteRemotePath(path);
            if (currentDirectory.equals(path) || currentDirectory.startsWith(path + "/")) {
                currentDirectory = remoteParent(path);
                pathInput.setText(currentDirectory);
                saveCurrentDirectoryForActiveConnection();
            }
        });
    }

    private void copyPath(String path) {
        ClipboardContent content = new ClipboardContent();
        content.putString(path);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private String encodeRemoteMoveDrag(String source, List<String> paths) {
        StringBuilder value = new StringBuilder(REMOTE_MOVE_DRAG_PREFIX)
                .append('\n')
                .append(activeConnId == null ? "" : activeConnId)
                .append('\n')
                .append(source == null ? "" : source);
        for (String path : paths) {
            value.append('\n').append(Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)));
        }
        return value.toString();
    }

    private RemoteMoveDrag parseRemoteMoveDrag(Dragboard dragboard) {
        if (dragboard == null || !dragboard.hasString()) return null;
        String data = dragboard.getString();
        if (data == null || !data.startsWith(REMOTE_MOVE_DRAG_PREFIX + "\n")) return null;

        String[] lines = data.split("\n", -1);
        if (lines.length < 4) return null;
        String connId = lines[1].trim();
        String source = lines[2].trim();
        List<String> paths = new ArrayList<>();
        try {
            for (int i = 3; i < lines.length; i++) {
                String encoded = lines[i].trim();
                if (encoded.isEmpty()) continue;
                paths.add(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return paths.isEmpty() ? null : new RemoteMoveDrag(connId, source, paths);
    }

    private boolean canDropRemoteMove(Dragboard dragboard, String targetArea, String targetDirectory) {
        RemoteMoveDrag drag = parseRemoteMoveDrag(dragboard);
        if (drag == null || activeConnId == null || !Objects.equals(activeConnId, drag.connId())) return false;
        if (targetDirectory == null || targetDirectory.isBlank()) return false;
        if (DRAG_SOURCE_TABLE.equals(targetArea) && DRAG_SOURCE_TREE.equals(drag.source())) return false;
        if (!DRAG_SOURCE_TABLE.equals(drag.source()) && !DRAG_SOURCE_TREE.equals(drag.source())) return false;

        for (String sourcePath : drag.paths()) {
            if (!isValidRemoteMove(sourcePath, targetDirectory)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidRemoteMove(String sourcePath, String targetDirectory) {
        if (sourcePath == null || sourcePath.isBlank() || "/".equals(sourcePath)) return false;
        if (targetDirectory == null || targetDirectory.isBlank()) return false;
        if (Objects.equals(sourcePath, targetDirectory)) return false;
        if (Objects.equals(remoteParent(sourcePath), targetDirectory)) return false;
        return !isSameOrDescendant(targetDirectory, sourcePath);
    }

    private boolean dropRemoteMove(Dragboard dragboard, String targetArea, String targetDirectory) {
        if (!canDropRemoteMove(dragboard, targetArea, targetDirectory)) return false;
        RemoteMoveDrag drag = parseRemoteMoveDrag(dragboard);
        if (drag == null) return false;
        return moveRemotePaths(drag.paths(), targetDirectory);
    }

    private boolean moveRemotePaths(List<String> sourcePaths, String targetDirectory) {
        if (sourcePaths == null || sourcePaths.isEmpty()) return false;
        String message = sourcePaths.size() == 1
                ? "确定将 " + sourcePaths.get(0) + " 移动到 " + joinRemotePath(targetDirectory, remoteName(sourcePaths.get(0))) + " 吗？"
                : "确定将 " + sourcePaths.size() + " 项移动到 " + targetDirectory + " 吗？";
        if (!DialogHelper.showConfirm("确认移动", message)) {
            return false;
        }

        SshService sshService = activeSshService();
        if (sshService == null || !sshService.isConnected()) {
            DialogHelper.showWarning("提示", "当前连接不可用");
            return false;
        }

        Set<String> refreshDirectories = new LinkedHashSet<>();
        try {
            for (String sourcePath : sourcePaths) {
                String targetPath = joinRemotePath(targetDirectory, remoteName(sourcePath));
                if (sshService.statFile(targetPath) != null) {
                    DialogHelper.showError("移动失败", "目标已存在: " + targetPath);
                    return false;
                }
            }
            for (String sourcePath : sourcePaths) {
                String targetPath = joinRemotePath(targetDirectory, remoteName(sourcePath));
                sshService.renameRemotePath(sourcePath, targetPath);
                refreshDirectories.add(remoteParent(sourcePath));
                refreshDirectories.add(targetDirectory);
                if (currentDirectory.equals(sourcePath) || currentDirectory.startsWith(sourcePath + "/")) {
                    currentDirectory = targetPath + currentDirectory.substring(sourcePath.length());
                    pathInput.setText(currentDirectory);
                    saveCurrentDirectoryForActiveConnection();
                }
            }
            loadFileList();
            refreshTreeDirectories(refreshDirectories, currentDirectory);
            return true;
        } catch (Exception e) {
            LOGGER.error("move failed", e);
            DialogHelper.showError("移动失败", e.getMessage());
            loadFileList();
            refreshTreeDirectories(refreshDirectories, currentDirectory);
            return false;
        }
    }

    private boolean isSameOrDescendant(String path, String ancestor) {
        if (path == null || ancestor == null) return false;
        if ("/".equals(ancestor)) return path.startsWith("/");
        return path.equals(ancestor) || path.startsWith(ancestor + "/");
    }

    private void uploadTo(String remoteDirectory) {
        if (activeConnId == null) return;
        String uploadConnId = activeConnId;
        List<Path> paths = chooseUploadPaths();
        if (paths.isEmpty()) return;
        rememberUploadChooserDirectory(paths.get(0));
        transferManager.enqueueUploadFiles(uploadConnId, paths, remoteDirectory,
                () -> refreshAfterUpload(uploadConnId, remoteDirectory));
        updateTransferQueueButton();
        showTransferQueueWindow(TransferDirection.UPLOAD);
    }

    private void refreshAfterUpload(String connId, String remoteDirectory) {
        if (!Objects.equals(connId, activeConnId)) return;
        if (Objects.equals(currentDirectory, remoteDirectory)) {
            refresh();
        } else {
            refreshTreeDirectory(remoteDirectory, currentDirectory);
        }
    }

    private void uploadToSelectedDirectoryOrCurrent() {
        RemoteFileInfo selected = fileTable.getSelectionModel().getSelectedItem();
        String targetDirectory = selected != null && selected.isDirectoryLike()
                ? selected.fullPath()
                : currentDirectory;
        uploadTo(targetDirectory);
    }

    private void downloadPath(String path) {
        refreshDownloadRootFromSettings();
        RemoteFileInfo file = remoteInfoFor(path);
        if (file != null) {
            transferManager.enqueueDownload(activeConnId, file, downloadRoot);
            updateTransferQueueButton();
            showTransferQueueWindow(TransferDirection.DOWNLOAD);
        }
    }

    private void downloadSelectedOrSingle(RemoteFileInfo fallback) {
        if (activeConnId == null) return;
        refreshDownloadRootFromSettings();
        ObservableList<RemoteFileInfo> selected = fileTable.getSelectionModel().getSelectedItems();
        List<RemoteFileInfo> files = selected == null || selected.isEmpty() ? List.of(fallback) : List.copyOf(selected);
        for (RemoteFileInfo file : files) {
            transferManager.enqueueDownload(activeConnId, file, downloadRoot);
        }
        updateTransferQueueButton();
        showTransferQueueWindow(TransferDirection.DOWNLOAD);
    }

    private void compressedUploadTo(String remoteDirectory) {
        SshService sshService = activeSshService();
        if (activeConnId == null || sshService == null || !sshService.isConnected()) return;
        Path directory = DialogHelper.chooseDirectory(getOwnerWindow(), "选择压缩上传文件夹", getUploadChooserInitialDirectory());
        if (directory == null) return;
        rememberUploadChooserDirectory(directory);
        CompressedTransferTask task = compressedTransferManager.enqueueUpload(activeConnId, List.of(directory), remoteDirectory);
        if (task != null) {
            task.statusProperty().addListener((obs, oldStatus, newStatus) -> {
                if (newStatus == TransferStatus.COMPLETED) {
                    refresh();
                }
            });
        }
        updateCompressedTransferQueueButton();
        showCompressedTransferQueueWindow(CompressedTransferDirection.UPLOAD);
    }

    private void compressedDownload(String remotePath) {
        SshService sshService = activeSshService();
        if (activeConnId == null || sshService == null || !sshService.isConnected()) return;
        refreshDownloadRootFromSettings();
        compressedTransferManager.enqueueDownload(activeConnId, remotePath, downloadRoot);
        updateCompressedTransferQueueButton();
        showCompressedTransferQueueWindow(CompressedTransferDirection.DOWNLOAD);
    }

    private void showPermissionDialog(String path, boolean directory, String permissions) {
        SshService sshService = activeSshService();
        if (sshService == null || !sshService.isConnected()) return;
        String initial = permissions;
        if (initial == null || initial.isBlank()) {
            SshService.RemoteFileStat stat = sshService.statFile(path);
            initial = stat == null ? "" : stat.permissions();
            if (!directory && stat != null) {
                directory = stat.isDirectory();
            }
        }

        Optional<PermissionEdit> result = buildPermissionDialog(path, directory, initial);
        result.ifPresent(edit -> runFileOperation("文件权限", () ->
                Objects.requireNonNull(activeSshService()).chmodRemotePath(path, edit.mode(), edit.recursive(), edit.recursiveScope())));
    }

    private Optional<PermissionEdit> buildPermissionDialog(String path, boolean directory, String permissions) {
        CheckBox ownerRead = new CheckBox("读取");
        CheckBox ownerWrite = new CheckBox("写入");
        CheckBox ownerExec = new CheckBox("执行");
        CheckBox groupRead = new CheckBox("读取");
        CheckBox groupWrite = new CheckBox("写入");
        CheckBox groupExec = new CheckBox("执行");
        CheckBox otherRead = new CheckBox("读取");
        CheckBox otherWrite = new CheckBox("写入");
        CheckBox otherExec = new CheckBox("执行");
        applyPermissions(permissions, ownerRead, ownerWrite, ownerExec, groupRead, groupWrite, groupExec, otherRead, otherWrite, otherExec);

        VBox content = new VBox(8,
                new Label(remoteName(path)),
                permissionGroup("所有者", ownerRead, ownerWrite, ownerExec),
                permissionGroup("组", groupRead, groupWrite, groupExec),
                permissionGroup("其他", otherRead, otherWrite, otherExec));
        content.setPadding(new Insets(4, 8, 4, 8));
        CheckBox recursive = new CheckBox("递归设置子目录");
        RadioButton scopeAll = new RadioButton("应用到文件和目录");
        RadioButton scopeFiles = new RadioButton("只应用到文件");
        RadioButton scopeDirs = new RadioButton("只应用到目录");
        ToggleGroup scopeGroup = new ToggleGroup();
        scopeAll.setToggleGroup(scopeGroup);
        scopeFiles.setToggleGroup(scopeGroup);
        scopeDirs.setToggleGroup(scopeGroup);
        scopeAll.setSelected(true);
        VBox recursiveBox = new VBox(7, recursive, scopeAll, scopeFiles, scopeDirs);
        recursiveBox.setPadding(new Insets(8, 16, 8, 16));
        recursiveBox.getStyleClass().add("pane-outlined");
        recursiveBox.setAlignment(Pos.CENTER_LEFT);
        scopeAll.disableProperty().bind(recursive.selectedProperty().not());
        scopeFiles.disableProperty().bind(recursive.selectedProperty().not());
        scopeDirs.disableProperty().bind(recursive.selectedProperty().not());
        if (directory) {
            content.getChildren().add(recursiveBox);
        }

        return DialogHelper.showCustomDialog("修改文件权限", content, button -> {
            if (button == null || button.getButtonData() != ButtonBar.ButtonData.OK_DONE) return null;
            String mode = permissionMode(ownerRead, ownerWrite, ownerExec, groupRead, groupWrite, groupExec, otherRead, otherWrite, otherExec);
            String scope = "all";
            if (scopeFiles.isSelected()) scope = "files";
            if (scopeDirs.isSelected()) scope = "directories";
            return new PermissionEdit(mode, directory && recursive.isSelected(), scope);
        }, "confirm");
    }

    private VBox permissionGroup(String title, CheckBox read, CheckBox write, CheckBox execute) {
        Label label = new Label(title);
        HBox checks = new HBox(8, read, write, execute);
        checks.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(2, label, checks);
        box.setPadding(new Insets(0, 8, 6, 8));
        box.getStyleClass().add("pane-outlined");
        return box;
    }

    private void applyPermissions(String permissions, CheckBox... boxes) {
        String perm = permissions == null ? "" : permissions.trim();
        if (perm.length() >= 10) {
            perm = perm.substring(1, 10);
        }
        if (perm.length() < 9) {
            perm = "rw-r--r--";
        }
        for (int i = 0; i < 9 && i < boxes.length; i++) {
            boxes[i].setSelected(perm.charAt(i) != '-');
        }
    }

    private String permissionMode(CheckBox... boxes) {
        int owner = bit(boxes[0], 4) + bit(boxes[1], 2) + bit(boxes[2], 1);
        int group = bit(boxes[3], 4) + bit(boxes[4], 2) + bit(boxes[5], 1);
        int other = bit(boxes[6], 4) + bit(boxes[7], 2) + bit(boxes[8], 1);
        return "" + owner + group + other;
    }

    private int bit(CheckBox box, int value) {
        return box.isSelected() ? value : 0;
    }

    private void runFileOperation(String title, FileOperation operation) {
        SshService sshService = activeSshService();
        if (sshService == null || !sshService.isConnected()) {
            DialogHelper.showWarning("提示", "当前连接不可用");
            return;
        }
        try {
            operation.run();
            refresh();
        } catch (Exception e) {
            LOGGER.error("{} failed", title, e);
            DialogHelper.showError(title + "失败", e.getMessage());
        }
    }

    private boolean isValidRemoteName(String name) {
        if (name == null || name.isBlank()) return true;
        if (name.contains("/") || name.contains("\\")) {
            DialogHelper.showWarning("提示", "名称不能包含路径分隔符");
            return true;
        }
        return false;
    }

    private RemoteFileInfo remoteInfoFor(String path) {
        SshService sshService = activeSshService();
        if (sshService == null) return null;
        SshService.RemoteFileStat stat = sshService.statFile(path);
        if (stat == null) return null;
        return new RemoteFileInfo(remoteName(path), path, remoteParent(path), stat.isDirectory(), false,
                false, false, stat.permissions(), stat.owner(), stat.group(), stat.sizeBytes(), "");
    }

    private String joinRemotePath(String parent, String name) {
        if (parent == null || parent.isBlank() || "/".equals(parent)) return "/" + name;
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    private String remoteParent(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return "/";
        int idx = path.lastIndexOf('/');
        if (idx <= 0) return "/";
        return path.substring(0, idx);
    }

    private String remoteName(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return "/";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    @FunctionalInterface
    private interface FileOperation {
        void run() throws Exception;
    }

    private void initTreeView() {
        folderTree.setCellFactory(tv -> new TreeCell<>() {
            private final FontIcon icon = new FontIcon();
            private TreeItem<String> observedTreeItem = null;
            private final ChangeListener<Boolean> expandListener = (obs, oldVal, newVal) -> updateFolderIcon();

            {
                icon.setIconSize(14);
                setFileIconClass(icon, "file-icon-folder");
                setOnContextMenuRequested(event -> {
                    TreeItem<String> item = getTreeItem();
                    if (isEmpty() || item == null) {
                        return;
                    }
                    folderTree.getSelectionModel().select(item);
                    ContextMenu menu = createTreeDirectoryContextMenu(item);
                    menu.show(this, event.getScreenX(), event.getScreenY());
                    event.consume();
                });
                setOnDragDetected(event -> {
                    TreeItem<String> item = getTreeItem();
                    if (isEmpty() || item == null || item.getParent() == null || activeConnId == null) {
                        return;
                    }
                    folderTree.getSelectionModel().select(item);
                    Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.putString(encodeRemoteMoveDrag(DRAG_SOURCE_TREE, List.of(getTreePath(item))));
                    dragboard.setContent(content);
                    event.consume();
                });
                setOnDragOver(event -> {
                    TreeItem<String> item = getTreeItem();
                    if (!isEmpty()
                            && item != null
                            && canDropRemoteMove(event.getDragboard(), DRAG_SOURCE_TREE, getTreePath(item))) {
                        event.acceptTransferModes(TransferMode.MOVE);
                        event.consume();
                    }
                });
                setOnDragDropped(event -> {
                    TreeItem<String> item = getTreeItem();
                    boolean completed = !isEmpty()
                            && item != null
                            && dropRemoteMove(event.getDragboard(), DRAG_SOURCE_TREE, getTreePath(item));
                    event.setDropCompleted(completed);
                    event.consume();
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (observedTreeItem != null) {
                    observedTreeItem.expandedProperty().removeListener(expandListener);
                    observedTreeItem = null;
                }

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    updateFolderIcon();
                    setGraphic(icon);

                    TreeItem<String> ti = getTreeItem();
                    if (ti != null) {
                        ti.expandedProperty().addListener(expandListener);
                        observedTreeItem = ti;
                    }
                }
            }

            private void updateFolderIcon() {
                TreeItem<String> ti = getTreeItem();
                if (isLinkDirectoryTreeItem(ti)) {
                    icon.setIconLiteral("fas-link");
                    setFileIconClass(icon, "file-icon-folder");
                } else if (ti != null && ti.isExpanded() && !ti.getChildren().isEmpty()) {
                    icon.setIconLiteral("fas-folder-open");
                    setFileIconClass(icon, "file-icon-folder");
                } else {
                    icon.setIconLiteral("fas-folder");
                    setFileIconClass(icon, "file-icon-folder");
                }
            }
        });

        folderTree.setOnContextMenuRequested(event -> {
            TreeItem<String> selected = folderTree.getSelectionModel().getSelectedItem();
            if (selected == null) {
                selected = folderTree.getRoot();
            }
            if (selected == null) {
                return;
            }
            ContextMenu menu = createTreeDirectoryContextMenu(selected);
            menu.show(folderTree, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        folderTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String path = getTreePath(newVal);
                if (!isRefreshingTree) {
                    isNavigatingFromTree = true;
                    navigateTo(path);
                    isNavigatingFromTree = false;
                    loadSubDirectories(newVal, path, null);
                }
            }
        });

        folderTree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<String> selected = folderTree.getSelectionModel().getSelectedItem();
                if (selected == null) return;
                // 已加载且有 children：让 TreeView 默认的双击行为处理展开/折叠
                if (loadedTreeItems.contains(selected) && !selected.getChildren().isEmpty()) {
                    return;
                }
                // 正在加载中：标记加载完成后自动展开
                if (loadingTreeItems.contains(selected)) {
                    expandAfterLoad.add(selected);
                    return;
                }
                // 未加载：加载 children 后展开
                loadSubDirectories(selected, getTreePath(selected), () -> {
                    if (!selected.getChildren().isEmpty()) {
                        selected.setExpanded(true);
                    }
                });
            }
        });
    }

    private void loadSubDirectories(TreeItem<String> item, String path, Runnable onComplete) {
        if (loadedTreeItems.contains(item)) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (loadingTreeItems.contains(item)) {
            return;
        }
        loadingTreeItems.add(item);

        SshService sshService = ConnectionManager.getInstance().getCurrentSshService();
        if (sshService == null || !sshService.isConnected()) {
            Platform.runLater(() -> loadingTreeItems.remove(item));
            return;
        }

        List<RemoteFileInfo> dirs = sshService.listRemoteDirectories(path);

        Platform.runLater(() -> {
            removeTreeDirectoryInfoUnder(path);
            for (RemoteFileInfo dir : dirs) {
                TreeItem<String> child = new TreeItem<>(dir.name());
                treeDirectoryInfoByPath.put(dir.fullPath(), dir);
                item.getChildren().add(child);
            }
            loadedTreeItems.add(item);
            loadingTreeItems.remove(item);
            if (onComplete != null) onComplete.run();
            if (expandAfterLoad.remove(item)) {
                if (!item.getChildren().isEmpty()) {
                    item.setExpanded(true);
                }
            }
        });
    }

    private String getTreePath(TreeItem<String> item) {
        StringBuilder path = new StringBuilder();
        TreeItem<String> current = item;
        while (current != null) {
            if (!path.isEmpty()) {
                path.insert(0, "/");
            }
            path.insert(0, current.getValue());
            current = current.getParent();
        }
        String result = path.toString();
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        result = result.replaceFirst("//", "/");
        return result;
    }

    private boolean isLinkDirectoryTreeItem(TreeItem<String> item) {
        if (item == null) return false;
        RemoteFileInfo info = treeDirectoryInfoByPath.get(getTreePath(item));
        return info != null && info.isSymbolicLink() && info.isLinkTargetDirectory();
    }

    private void removeTreeDirectoryInfoUnder(String path) {
        if (path == null || path.isBlank()) return;
        if ("/".equals(path)) {
            treeDirectoryInfoByPath.clear();
            return;
        }
        String prefix = path.endsWith("/") ? path : path + "/";
        treeDirectoryInfoByPath.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @FXML
    public void onPathEnter() {
        String path = pathInput.getText().trim();
        if (!path.isEmpty()) {
            navigateTo(path);
        }
    }

    private void navigateTo(String path) {
        currentDirectory = path;
        saveCurrentDirectoryForActiveConnection();
        updateHistory(path);
        pathInput.setText(path);
        loadFileList();
        if (!isNavigatingFromTree) {
            updateTreeSelection(path);
        }
    }

    private void updateTreeSelection(String path) {
        TreeItem<String> root = folderTree.getRoot();
        if (root == null) return;

        String[] parts = path.split("/");
        expandPathTo(root, parts, 1);
    }

    private void expandPathTo(TreeItem<String> current, String[] parts, int index) {
        if (index >= parts.length) {
            folderTree.getSelectionModel().select(current);
            return;
        }

        String part = parts[index];
        if (part.isEmpty()) {
            expandPathTo(current, parts, index + 1);
            return;
        }

        TreeItem<String> child = null;
        for (TreeItem<String> c : current.getChildren()) {
            if (c.getValue().equals(part)) {
                child = c;
                break;
            }
        }

        if (child != null) {
            current.setExpanded(true);
            expandPathTo(child, parts, index + 1);
        } else if (loadedTreeItems.contains(current)) {
            current.setExpanded(true);
            folderTree.getSelectionModel().select(current);
        } else {
            loadSubDirectories(current, getTreePath(current), () -> {
                current.setExpanded(true);
                TreeItem<String> found = null;
                for (TreeItem<String> c : current.getChildren()) {
                    if (c.getValue().equals(part)) {
                        found = c;
                        break;
                    }
                }
                if (found != null) {
                    expandPathTo(found, parts, index + 1);
                } else {
                    folderTree.getSelectionModel().select(current);
                }
            });
        }
    }

    private void loadFileList() {
        String requestConnId = activeConnId;
        String requestDirectory = currentDirectory;
        SshService sshService = requestConnId == null
                ? null
                : ConnectionManager.getInstance().getConnectionById(requestConnId);
        if (sshService == null || !sshService.isConnected()) {
            Platform.runLater(() -> {
                if (requestConnId == null || requestConnId.equals(activeConnId)) {
                    fileTable.setItems(FXCollections.emptyObservableList());
                }
            });
            return;
        }

        List<RemoteFileInfo> files = sshService.listRemoteFiles(requestDirectory);

        Platform.runLater(() -> {
            if (!requestConnId.equals(activeConnId) || !requestDirectory.equals(currentDirectory)) {
                return;
            }
            ObservableList<RemoteFileInfo> observableFiles = FXCollections.observableArrayList(files);
            fileTable.setItems(observableFiles);
            fileTable.refresh();

            // 初次渲染时 VirtualFlow 的滚动条和表头 filler 可能不同步，
            // 在下一帧强制触发一次完整布局，确保表头区域与滚动条对齐
            if (!intiFlag) {
                Platform.runLater(() -> {
                    fileTable.applyCss();
                    fileTable.layout();
                    if (fileTable.getParent() != null) {
                        fileTable.getParent().applyCss();
                        fileTable.getParent().layout();
                    }
                    intiFlag = true;
                });
            }
        });
    }

    private void loadFolderTree() {
        String requestConnId = activeConnId;
        SshService sshService = requestConnId == null
                ? null
                : ConnectionManager.getInstance().getConnectionById(requestConnId);
        if (sshService == null || !sshService.isConnected()) {
            Platform.runLater(() -> {
                if (requestConnId == null || requestConnId.equals(activeConnId)) {
                    folderTree.setRoot(null);
                }
            });
            return;
        }

        List<RemoteFileInfo> dirs = sshService.listRemoteDirectories("/");

        Platform.runLater(() -> {
            if (!requestConnId.equals(activeConnId)) {
                return;
            }
            loadedTreeItems.clear();
            loadingTreeItems.clear();
            expandAfterLoad.clear();
            treeDirectoryInfoByPath.clear();
            TreeItem<String> root = new TreeItem<>("/");
            root.setExpanded(true);

            for (RemoteFileInfo dir : dirs) {
                TreeItem<String> child = new TreeItem<>(dir.name());
                treeDirectoryInfoByPath.put(dir.fullPath(), dir);
                root.getChildren().add(child);
            }
            loadedTreeItems.add(root);
            folderTree.setRoot(root);
        });
    }

    private FontIcon getFileIcon(String fileName) {
        FontIcon icon = new FontIcon();
        icon.setIconSize(14);
        String name = fileName.toLowerCase();

        if (name.endsWith(".txt")) {
            icon.setIconLiteral("fas-file-alt");
            setFileIconClass(icon, "file-icon-txt");
        } else if (name.endsWith(".md")) {
            icon.setIconLiteral("fab-markdown");
            setFileIconClass(icon, "file-icon-md");
        } else if (name.endsWith(".java")) {
            icon.setIconLiteral("fab-java");
            setFileIconClass(icon, "file-icon-java");
        } else if (name.endsWith(".xml")) {
            icon.setIconLiteral("fas-file-code");
            setFileIconClass(icon, "file-icon-xml");
        } else if (name.endsWith(".json")) {
            icon.setIconLiteral("fas-file-code");
            setFileIconClass(icon, "file-icon-json");
        } else if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            icon.setIconLiteral("fas-file-code");
            setFileIconClass(icon, "file-icon-yaml");
        } else if (name.endsWith(".sh")) {
            icon.setIconLiteral("fas-file-code");
            setFileIconClass(icon, "file-icon-sh");
        } else if (name.endsWith(".py")) {
            icon.setIconLiteral("fab-python");
            setFileIconClass(icon, "file-icon-py");
        } else if (name.endsWith(".js")) {
            icon.setIconLiteral("fab-js");
            setFileIconClass(icon, "file-icon-js");
        } else if (name.endsWith(".css")) {
            icon.setIconLiteral("fab-css3");
            setFileIconClass(icon, "file-icon-css");
        } else if (name.endsWith(".html")) {
            icon.setIconLiteral("fab-html5");
            setFileIconClass(icon, "file-icon-html");
        } else if (name.endsWith(".log")) {
            icon.setIconLiteral("fas-file-alt");
            setFileIconClass(icon, "file-icon-log");
        } else if (name.endsWith(".bashrc") || name.endsWith(".bash_history")) {
            icon.setIconLiteral("fas-file-code");
            setFileIconClass(icon, "file-icon-bashrc");
        } else {
            icon.setIconLiteral("fas-file");
            setFileIconClass(icon, "file-icon-txt");
        }
        return icon;
    }

    private void setFileIconClass(FontIcon icon, String styleClass) {
        icon.getStyleClass().removeIf(style -> style.startsWith("file-icon-"));
        icon.getStyleClass().add(styleClass);
    }

    private String getFileType(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".txt")) return "文本文件";
        if (name.endsWith(".md")) return "Markdown";
        if (name.endsWith(".java")) return "Java";
        if (name.endsWith(".xml")) return "XML";
        if (name.endsWith(".json")) return "JSON";
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return "YAML";
        if (name.endsWith(".sh")) return "Shell";
        if (name.endsWith(".py")) return "Python";
        if (name.endsWith(".js")) return "JavaScript";
        if (name.endsWith(".css")) return "CSS";
        if (name.endsWith(".html")) return "HTML";
        if (name.endsWith(".log")) return "日志文件";
        if (name.endsWith(".bashrc") || name.endsWith(".bash_history")) return "Bash配置";
        return "文件";
    }

    private void navigateUp() {
        if (currentDirectory.equals("/")) {
            return;
        }
        int lastSlash = currentDirectory.lastIndexOf("/");
        String parent = lastSlash == 0 ? "/" : currentDirectory.substring(0, lastSlash);
        navigateTo(parent);
    }

    private void updateHistory(String path) {
        history = history.subList(0, historyIndex + 1);
        history.add(path);
        historyIndex = history.size() - 1;
    }

    public void showForConnection(String connId) {
        if (connId == null) {
            clearFileView();
            return;
        }
        activeConnId = connId;
        observeTransferQueues(connId);
        observeCompressedTransferQueues(connId);
        syncOpenTransferQueueWindow(null);
        syncOpenCompressedTransferQueueWindow(null);
        isRefreshingTree = true;
        loadedTreeItems.clear();
        loadingTreeItems.clear();
        expandAfterLoad.clear();
        treeDirectoryInfoByPath.clear();
        currentDirectory = directoryByConnId.getOrDefault(connId, "/");
        pathInput.setText(currentDirectory);

        loadFolderTree();
        loadFileList();

        Platform.runLater(() -> {
            updateTreeSelection(currentDirectory);
            Platform.runLater(() -> isRefreshingTree = false);
        });
    }

    private void clearFileView() {
        observeTransferQueues(null);
        observeCompressedTransferQueues(null);
        activeConnId = null;
        closeTransferQueueWindow();
        closeCompressedTransferQueueWindow();
        currentDirectory = "/";
        pathInput.setText("/");
        loadedTreeItems.clear();
        loadingTreeItems.clear();
        expandAfterLoad.clear();
        treeDirectoryInfoByPath.clear();
        fileTable.setItems(FXCollections.emptyObservableList());
        folderTree.setRoot(null);
    }

    public void refresh() {
        isRefreshingTree = true;
        String savedPath = currentDirectory;

        loadFileList();
        refreshCurrentTreeItem(savedPath);
    }

    public void refreshIfShowingSavedFileDirectory(String connId, String filePath) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> refreshIfShowingSavedFileDirectory(connId, filePath));
            return;
        }
        if (connId == null || filePath == null || filePath.isBlank()
                || !connId.equals(activeConnId)
                || !isSavedFileInCurrentDirectoryTree(filePath)) {
            return;
        }
        refresh();
    }

    private boolean isSavedFileInCurrentDirectoryTree(String filePath) {
        String normalizedCurrentDirectory = normalizeDirectory(currentDirectory);
        String normalizedFilePath = normalizeDirectory(filePath);
        return "/".equals(normalizedCurrentDirectory)
                ? normalizedFilePath.startsWith("/")
                : normalizedFilePath.startsWith(normalizedCurrentDirectory + "/");
    }

    private String normalizeDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return "/";
        }
        int end = directory.length();
        while (end > 1 && directory.charAt(end - 1) == '/') {
            end--;
        }
        return directory.substring(0, end);
    }

    private void refreshCurrentTreeItem(String savedPath) {
        TreeItem<String> currentItem = findTreeItemByPath(savedPath);
        if (currentItem == null) {
            Platform.runLater(() -> isRefreshingTree = false);
            return;
        }

        loadedTreeItems.remove(currentItem);
        loadingTreeItems.remove(currentItem);
        expandAfterLoad.remove(currentItem);
        Platform.runLater(() -> currentItem.getChildren().clear());

        loadSubDirectories(currentItem, savedPath, () -> reselectPathAfterRefresh(savedPath));
    }

    private void refreshTreeDirectory(String directory, String selectionPath) {
        String refreshPath = directory == null || directory.isBlank() ? "/" : directory;
        String pathToSelect = selectionPath == null || selectionPath.isBlank() ? refreshPath : selectionPath;
        isRefreshingTree = true;

        TreeItem<String> item = findTreeItemByPath(refreshPath);
        if (item == null) {
            updateTreeSelection(pathToSelect);
            Platform.runLater(() -> isRefreshingTree = false);
            return;
        }

        loadedTreeItems.remove(item);
        loadingTreeItems.remove(item);
        expandAfterLoad.remove(item);
        Platform.runLater(() -> item.getChildren().clear());

        loadSubDirectories(item, refreshPath, () -> reselectPathAfterRefresh(pathToSelect));
    }

    private void refreshTreeDirectories(Collection<String> directories, String selectionPath) {
        if (directories == null || directories.isEmpty()) {
            refreshTreeDirectory(selectionPath, selectionPath);
            return;
        }

        String pathToSelect = selectionPath == null || selectionPath.isBlank() ? currentDirectory : selectionPath;
        isRefreshingTree = true;
        Set<String> uniqueDirectories = new LinkedHashSet<>(directories);
        for (String directory : uniqueDirectories) {
            String refreshPath = directory == null || directory.isBlank() ? "/" : directory;
            TreeItem<String> item = findTreeItemByPath(refreshPath);
            if (item == null) {
                continue;
            }
            loadedTreeItems.remove(item);
            loadingTreeItems.remove(item);
            expandAfterLoad.remove(item);
            Platform.runLater(() -> item.getChildren().clear());
            loadSubDirectories(item, refreshPath, null);
        }
        Platform.runLater(() -> {
            updateTreeSelection(pathToSelect);
            isRefreshingTree = false;
        });
    }

    private void reselectPathAfterRefresh(String savedPath) {
        pathInput.setText(savedPath);
        TreeItem<String> target = findTreeItemByPath(savedPath);
        if (target != null) {
            TreeItem<String> p = target.getParent();
            while (p != null) {
                p.setExpanded(true);
                p = p.getParent();
            }
            folderTree.getSelectionModel().select(target);
        }
        isRefreshingTree = false;
    }

    private TreeItem<String> findTreeItemByPath(String path) {
        TreeItem<String> root = folderTree.getRoot();
        if (root == null) return null;
        if (path.equals("/")) return root;

        String[] parts = path.split("/");
        TreeItem<String> current = root;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            TreeItem<String> next = null;
            for (TreeItem<String> c : current.getChildren()) {
                if (c.getValue().equals(part)) {
                    next = c;
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    private void jumpToTerminal() {
        SshService sshService = ConnectionManager.getInstance().getCurrentSshService();
        if (sshService != null && sshService.isConnected()) {
            String command = "cd " + currentDirectory + " && pwd\n";
            sshService.writeToShell(command.getBytes());
        }
    }

    private void saveCurrentDirectoryForActiveConnection() {
        String key = activeConnId != null ? activeConnId : ConnectionManager.getInstance().getCurrentConnectionId();
        if (key != null && currentDirectory != null && !currentDirectory.isEmpty()) {
            directoryByConnId.put(key, currentDirectory);
        }
    }

    private void downloadSelectedFiles() {
        if (activeConnId == null) return;
        refreshDownloadRootFromSettings();
        ObservableList<RemoteFileInfo> selected = fileTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            downloadPath(currentDirectory);
            return;
        }
        for (RemoteFileInfo file : selected) {
            transferManager.enqueueDownload(activeConnId, file, downloadRoot);
        }
        updateTransferQueueButton();
        showTransferQueueWindow(TransferDirection.DOWNLOAD);
    }

    private List<Path> chooseUploadPaths() {
        int choice = DialogHelper.showConfirmThree("选择上传类型",
                "请选择要上传的内容类型",
                "文件", "文件夹", "取消");
        if (choice == 0) {
            return DialogHelper.chooseFiles(getOwnerWindow(), "选择上传文件", getUploadChooserInitialDirectory());
        }
        if (choice == 1) {
            Path directory = DialogHelper.chooseDirectory(getOwnerWindow(), "选择上传文件夹", getUploadChooserInitialDirectory());
            return directory == null ? Collections.emptyList() : List.of(directory);
        }
        return Collections.emptyList();
    }

    private Window getOwnerWindow() {
        return fileTable != null && fileTable.getScene() != null ? fileTable.getScene().getWindow() : null;
    }

    private Path getUploadChooserInitialDirectory() {
        Path savedPath = appSettings.getTransferUploadChooserDirectory();
        if (savedPath != null && Files.isDirectory(savedPath)) {
            return savedPath;
        }
        return Paths.get(System.getProperty("user.home"));
    }

    private void rememberUploadChooserDirectory(Path path) {
        if (path == null) return;
        Path directory = Files.isDirectory(path) ? path : path.getParent();
        if (directory != null) {
            appSettings.setTransferUploadChooserDirectory(directory);
        }
    }

    private void initDownloadRoot() {
        downloadRoot = appSettings.getTransferDefaultDownloadDirectory();
        try {
            Files.createDirectories(downloadRoot);
        } catch (IOException ignored) {
        }
    }

    private void refreshDownloadRootFromSettings() {
        downloadRoot = appSettings.getTransferDefaultDownloadDirectory();
        try {
            Files.createDirectories(downloadRoot);
        } catch (IOException ignored) {
        }
    }

    private void observeTransferQueues(String connId) {
        if (Objects.equals(observedQueueConnId, connId)) {
            updateTransferQueueButton();
            return;
        }
        if (observedQueueConnId != null) {
            transferManager.downloads(observedQueueConnId).removeListener(transferQueueListener);
            transferManager.uploads(observedQueueConnId).removeListener(transferQueueListener);
        }
        observedQueueConnId = connId;
        if (connId != null) {
            transferManager.downloads(connId).addListener(transferQueueListener);
            transferManager.uploads(connId).addListener(transferQueueListener);
        }
        updateTransferQueueButton();
    }

    private void updateTransferQueueButton() {
        Platform.runLater(() -> {
            if (btnTransferQueue == null) {
                return;
            }
            if (activeConnId == null) {
                btnTransferQueue.setText("0");
                btnTransferQueue.setVisible(false);
                btnTransferQueue.setManaged(false);
                return;
            }
            int count = transferManager.downloads(activeConnId).size() + transferManager.uploads(activeConnId).size();
            btnTransferQueue.setText(String.valueOf(count));
            btnTransferQueue.setVisible(count > 0);
            btnTransferQueue.setManaged(count > 0);
        });
    }

    private void observeCompressedTransferQueues(String connId) {
        if (Objects.equals(observedCompressedQueueConnId, connId)) {
            updateCompressedTransferQueueButton();
            return;
        }
        if (observedCompressedQueueConnId != null) {
            compressedTransferManager.downloads(observedCompressedQueueConnId).removeListener(compressedQueueListener);
            compressedTransferManager.uploads(observedCompressedQueueConnId).removeListener(compressedQueueListener);
        }
        observedCompressedQueueConnId = connId;
        if (connId != null) {
            compressedTransferManager.downloads(connId).addListener(compressedQueueListener);
            compressedTransferManager.uploads(connId).addListener(compressedQueueListener);
        }
        updateCompressedTransferQueueButton();
    }

    private void updateCompressedTransferQueueButton() {
        Platform.runLater(() -> {
            if (btnCompressedTransferQueue == null) {
                return;
            }
            if (activeConnId == null) {
                btnCompressedTransferQueue.setText("0");
                btnCompressedTransferQueue.setVisible(false);
                btnCompressedTransferQueue.setManaged(false);
                return;
            }
            int count = compressedTransferManager.count(activeConnId);
            btnCompressedTransferQueue.setText(String.valueOf(count));
            btnCompressedTransferQueue.setVisible(count > 0);
            btnCompressedTransferQueue.setManaged(count > 0);
        });
    }

    private void showTransferQueueWindow(TransferDirection initialDirection) {
        if (activeConnId == null) return;
        if (transferStage != null && transferStage.isShowing()) {
            syncOpenTransferQueueWindow(initialDirection);
            transferStage.toFront();
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/TransferQueueView.fxml"));
            VBox root = loader.load();
            TransferQueueController controller = loader.getController();

            transferStage = new Stage();
            ApplicationIcons.applyTo(transferStage);
            transferStage.initStyle(StageStyle.UNDECORATED);
            controller.setStage(transferStage);
            configureTransferQueueController(controller);
            if (initialDirection != null) {
                controller.selectTab(initialDirection);
            }

            Scene scene = new Scene(root, 1180, 520);
            ThemeManager.getInstance().registerScene(scene);
            transferStage.setMinWidth(1020);
            transferStage.setMinHeight(420);
            transferStage.setOnHidden(e -> {
                ThemeManager.getInstance().unregisterScene(scene);
                transferStageConnId = null;
                transferStage = null;
            });
            transferStage.setScene(scene);
            transferStage.setUserData(controller);
            transferStage.show();
        } catch (IOException e) {
            LOGGER.error("showTransferQueueWindow error:", e);
        }
    }

    private void syncOpenTransferQueueWindow(TransferDirection initialDirection) {
        if (activeConnId == null || transferStage == null || !transferStage.isShowing()) {
            return;
        }
        TransferQueueController controller = (TransferQueueController) transferStage.getUserData();
        if (controller == null) {
            return;
        }
        if (!Objects.equals(transferStageConnId, activeConnId)) {
            configureTransferQueueController(controller);
        }
        if (initialDirection != null) {
            controller.selectTab(initialDirection);
        }
    }

    private void configureTransferQueueController(TransferQueueController controller) {
        controller.configure(activeConnId, downloadRoot, path -> {
            downloadRoot = path;
            appSettings.setTransferDefaultDownloadDirectory(downloadRoot);
        }, this::updateTransferQueueButton);
        transferStageConnId = activeConnId;
    }

    private void showCompressedTransferQueueWindow(CompressedTransferDirection initialDirection) {
        if (activeConnId == null) return;
        if (compressedTransferStage != null && compressedTransferStage.isShowing()) {
            syncOpenCompressedTransferQueueWindow(initialDirection);
            compressedTransferStage.toFront();
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/CompressedTransferQueueView.fxml"));
            VBox root = loader.load();
            CompressedTransferQueueController controller = loader.getController();

            compressedTransferStage = new Stage();
            ApplicationIcons.applyTo(compressedTransferStage);
            compressedTransferStage.initStyle(StageStyle.UNDECORATED);
            controller.setStage(compressedTransferStage);
            configureCompressedTransferQueueController(controller);
            if (initialDirection != null) {
                controller.selectTab(initialDirection);
            }

            Scene scene = new Scene(root, 1480, 520);
            ThemeManager.getInstance().registerScene(scene);
            compressedTransferStage.setMinWidth(1020);
            compressedTransferStage.setMinHeight(420);
            compressedTransferStage.setOnHidden(e -> {
                ThemeManager.getInstance().unregisterScene(scene);
                compressedTransferStageConnId = null;
                compressedTransferStage = null;
            });
            compressedTransferStage.setScene(scene);
            compressedTransferStage.setUserData(controller);
            compressedTransferStage.show();
        } catch (IOException e) {
            LOGGER.error("showCompressedTransferQueueWindow error:", e);
        }
    }

    private void syncOpenCompressedTransferQueueWindow(CompressedTransferDirection initialDirection) {
        if (activeConnId == null || compressedTransferStage == null || !compressedTransferStage.isShowing()) {
            return;
        }
        CompressedTransferQueueController controller = (CompressedTransferQueueController) compressedTransferStage.getUserData();
        if (controller == null) {
            return;
        }
        if (!Objects.equals(compressedTransferStageConnId, activeConnId)) {
            configureCompressedTransferQueueController(controller);
        }
        if (initialDirection != null) {
            controller.selectTab(initialDirection);
        }
    }

    private void configureCompressedTransferQueueController(CompressedTransferQueueController controller) {
        controller.configure(activeConnId, downloadRoot, path -> {
            downloadRoot = path;
            appSettings.setTransferDefaultDownloadDirectory(downloadRoot);
        }, this::updateCompressedTransferQueueButton);
        compressedTransferStageConnId = activeConnId;
    }

    private void closeTransferQueueWindow() {
        if (transferStage != null && transferStage.isShowing()) {
            transferStage.close();
        }
        transferStage = null;
        transferStageConnId = null;
    }

    private void closeCompressedTransferQueueWindow() {
        if (compressedTransferStage != null && compressedTransferStage.isShowing()) {
            compressedTransferStage.close();
        }
        compressedTransferStage = null;
        compressedTransferStageConnId = null;
    }
}

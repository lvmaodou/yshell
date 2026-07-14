package com.yshell.controller;

import com.yshell.model.ConnInfo;
import com.yshell.model.docker.DockerSnapshot;
import com.yshell.service.ConnectionManager;
import com.yshell.service.DockerService;
import com.yshell.service.DockerSessionManager;
import com.yshell.service.SshService;
import com.yshell.terminal.Imm32;
import com.yshell.ui.DialogHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class DockerViewController {
    private final DockerSessionManager sessionManager = DockerSessionManager.getInstance();
    private final ObservableList<DockerRow> rows = FXCollections.observableArrayList();
    private FilteredList<DockerRow> filteredRows;

    private Section activeSection = Section.CONTAINERS;
    private DockerSnapshot currentSnapshot;
    private String activeConnId;
    private String activeConfigPath = "/etc/docker/daemon.json";
    private boolean tabVisible;
    private long refreshSerial;
    private long configSerial;

    @FXML
    private Button navContainers;
    @FXML
    private Button navImages;
    @FXML
    private Button navNetworks;
    @FXML
    private Button navVolumes;
    @FXML
    private Button navConfig;
    @FXML
    private Label lblContainersCount;
    @FXML
    private Label lblImagesCount;
    @FXML
    private Label lblNetworksCount;
    @FXML
    private Label lblVolumesCount;
    @FXML
    private Label lblDockerVersion;
    @FXML
    private Label lblApiVersion;
    @FXML
    private Label lblDockerStatus;
    @FXML
    private HBox toolbarActions;
    @FXML
    private TextField searchBox;
    @FXML
    private TableView<DockerRow> dockerTable;
    @FXML
    private TableColumn<DockerRow, Boolean> colSelect;
    @FXML
    private TableColumn<DockerRow, String> colName;
    @FXML
    private TableColumn<DockerRow, String> colId;
    @FXML
    private TableColumn<DockerRow, String> colStatus;
    @FXML
    private TableColumn<DockerRow, String> colDetail;
    @FXML
    private TableColumn<DockerRow, String> colExtra;
    private TableColumn<DockerRow, String> colMore;
    @FXML
    private VBox configPane;
    @FXML
    private Label configPathLabel;
    @FXML
    private TextArea configEditor;

    @FXML
    public void initialize() {
        configureTable();
        configureActions();
        configureConfigEditorIme();
        ConnectionManager.getInstance().addOnConnectionStateChangedListener(
                () -> Platform.runLater(() -> Platform.runLater(this::onConnectionStateChanged)));
        switchSection(Section.CONTAINERS);
        showEmptyState();
    }

    public void setTabVisible(boolean visible) {
        if (this.tabVisible == visible) {
            return;
        }
        this.tabVisible = visible;
        if (visible) {
            refreshVisibleContent();
        } else if (activeConnId != null) {
            sessionManager.closeSession(activeConnId);
            setStatus("Docker 会话已关闭");
        }
    }

    private void configureTable() {
        filteredRows = new FilteredList<>(rows, row -> true);
        dockerTable.setItems(filteredRows);
        dockerTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        dockerTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        colSelect.setCellValueFactory(data -> data.getValue().selectedProperty());
        colSelect.setCellFactory(column -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();

            {
                setAlignment(Pos.CENTER);
                checkBox.setOnAction(event -> {
                    DockerRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row == null) {
                        return;
                    }
                    row.setSelected(checkBox.isSelected());
                    if (checkBox.isSelected()) {
                        dockerTable.getSelectionModel().select(row);
                    } else {
                        dockerTable.getSelectionModel().clearSelection(getIndex());
                    }
                    updateToolbarButtonState();
                });
            }

            @Override
            protected void updateItem(Boolean selected, boolean empty) {
                super.updateItem(selected, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                checkBox.setSelected(Boolean.TRUE.equals(selected));
                setGraphic(checkBox);
            }
        });

        colName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        colId.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().id()));
        colStatus.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().status()));
        colDetail.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().detail()));
        colExtra.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().extra()));
        colMore = new TableColumn<>();
        colMore.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().more()));
        colName.setCellFactory(column -> createSelectableCenteredCell());
        colId.setCellFactory(column -> createSelectableCenteredCell());
        colStatus.setCellFactory(column -> createSelectableCenteredCell());
        colDetail.setCellFactory(column -> createSelectableCenteredCell());
        colExtra.setCellFactory(column -> createSelectableCenteredCell());
        colMore.setCellFactory(column -> createSelectableCenteredCell());
        dockerTable.getColumns().add(colMore);

        dockerTable.getSelectionModel().getSelectedItems().addListener((ListChangeListener<DockerRow>) c -> syncSelectionState());
        dockerTable.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                copySelectedRowsToClipboard();
                event.consume();
            }
        });
        dockerTable.setRowFactory(table -> {
            TableRow<DockerRow> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    dockerTable.getSelectionModel().clearAndSelect(row.getIndex());
                    createSingleContextMenu(row.getItem()).show(row, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
            row.setOnMouseClicked(event -> {
                if (row.isEmpty()) {
                    return;
                }
                if (event.getClickCount() == 2) {
                    executeSingleOperation("详情", row.getItem());
                } else if (event.getClickCount() == 1) {
                    row.getItem().setSelected(row.isSelected());
                    updateToolbarButtonState();
                }
            });
            return row;
        });
        searchBox.textProperty().addListener((obs, old, text) -> applySearchFilter());
    }

    private TableCell<DockerRow, String> createSelectableCenteredCell() {
        return new TableCell<>() {
            private final TextField textField = new TextField();

            {
                setAlignment(Pos.CENTER_LEFT);
                textField.setEditable(false);
                textField.setAlignment(Pos.CENTER_LEFT);
                textField.setMinWidth(0);
                textField.setMaxWidth(Double.MAX_VALUE);
                textField.getStyleClass().add("docker-table-cell-text");
                textField.prefWidthProperty().bind(widthProperty().subtract(12));
                textField.setContextMenu(null);
                textField.setOnContextMenuRequested(event -> {
                    DockerRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        dockerTable.getSelectionModel().clearAndSelect(getIndex());
                        createSingleContextMenu(row).show(textField, event.getScreenX(), event.getScreenY());
                    }
                    event.consume();
                });
                textField.setOnMouseClicked(event -> {
                    DockerRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        if (!event.isShortcutDown() && !event.isShiftDown()) {
                            dockerTable.getSelectionModel().clearAndSelect(getIndex());
                        } else {
                            dockerTable.getSelectionModel().select(row);
                        }
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                textField.setText(item == null ? "" : item);
                setText(null);
                setGraphic(textField);
            }
        };
    }

    private void syncSelectionState() {
        List<DockerRow> selected = dockerTable.getSelectionModel().getSelectedItems();
        for (DockerRow row : rows) {
            row.setSelected(selected.contains(row));
        }
        updateToolbarButtonState();
    }

    private void copySelectedRowsToClipboard() {
        List<DockerRow> selected = selectedRows();
        if (selected.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (DockerRow row : selected) {
            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator());
            }
            builder.append(row.name()).append('\t')
                    .append(row.id()).append('\t')
                    .append(row.status()).append('\t')
                    .append(row.detail());
            if (colExtra.isVisible()) {
                builder.append('\t').append(row.extra());
            }
            if (row.kind() == Section.CONTAINERS || row.kind() == Section.IMAGES) {
                builder.append('\t').append(row.more());
            }
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(builder.toString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void configureActions() {
        navContainers.setOnAction(e -> switchSection(Section.CONTAINERS));
        navImages.setOnAction(e -> switchSection(Section.IMAGES));
        navNetworks.setOnAction(e -> switchSection(Section.NETWORKS));
        navVolumes.setOnAction(e -> switchSection(Section.VOLUMES));
        navConfig.setOnAction(e -> switchSection(Section.CONFIG));
    }

    private void configureConfigEditorIme() {
        configEditor.focusedProperty().addListener((obs, oldValue, focused) -> {
            if (focused) {
                Platform.runLater(this::updateConfigEditorImePosition);
            }
        });
        configEditor.setOnMouseClicked(event -> Platform.runLater(this::updateConfigEditorImePosition));
        configEditor.addEventHandler(KeyEvent.KEY_PRESSED, event -> Platform.runLater(this::updateConfigEditorImePosition));
        configEditor.addEventHandler(KeyEvent.KEY_RELEASED, event -> Platform.runLater(this::updateConfigEditorImePosition));
        configEditor.addEventHandler(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                event -> Platform.runLater(this::updateConfigEditorImePosition));
    }

    private void onConnectionStateChanged() {
        if (tabVisible) {
            refreshVisibleContent();
        }
    }

    private void refreshVisibleContent() {
        if (activeSection == Section.CONFIG) {
            loadDockerConfig();
        } else {
            refreshForCurrentConnection(true);
        }
    }

    private void refreshForCurrentConnection(boolean showCacheFirst) {
        ConnectionManager connectionManager = ConnectionManager.getInstance();
        String connId = connectionManager.getCurrentConnectionId();
        ConnInfo connInfo = connectionManager.getCurrentConnection();

        if (connId == null || connInfo == null || !connectionManager.isConnected(connId)) {
            if (activeConnId != null) {
                sessionManager.closeSession(activeConnId);
            }
            activeConnId = null;
            currentSnapshot = null;
            showEmptyState();
            return;
        }

        if (!Objects.equals(activeConnId, connId)) {
            if (activeConnId != null) {
                sessionManager.closeSession(activeConnId);
            }
            activeConnId = connId;
            currentSnapshot = null;
            searchBox.clear();
            showEmptyState();
            setStatus("正在连接 Docker...");
        }

        DockerSnapshot cached = sessionManager.getCachedSnapshot(connId);
        if (showCacheFirst && cached != null) {
            applySnapshot(cached);
            setStatus("显示缓存，正在刷新...");
        } else {
            rows.clear();
            setStatus(cached == null ? "正在获取 Docker 信息..." : "正在刷新 Docker 信息...");
        }

        long serial = ++refreshSerial;
        CompletableFuture<DockerSnapshot> future = sessionManager.refreshSnapshot(connId, connInfo);
        future.thenAccept(snapshot -> Platform.runLater(() -> {
            if (!tabVisible || serial != refreshSerial || !Objects.equals(activeConnId, connId)) {
                return;
            }
            applySnapshot(snapshot);
        }));
    }

    private void applySnapshot(DockerSnapshot snapshot) {
        currentSnapshot = snapshot;
        lblDockerVersion.setText(blankAsDash(snapshot.serverVersion()));
        lblApiVersion.setText(blankAsDash(snapshot.apiVersion()));
        lblContainersCount.setText(String.valueOf(snapshot.containers().size()));
        lblImagesCount.setText(String.valueOf(snapshot.images().size()));
        lblNetworksCount.setText(String.valueOf(snapshot.networks().size()));
        lblVolumesCount.setText(String.valueOf(snapshot.volumes().size()));
        setStatus(snapshot.dockerAvailable()
                ? "Docker 信息已刷新"
                : firstNonBlank(snapshot.errorMessage(), "Docker 不可用"));
        renderRows();
    }

    private void renderRows() {
        rows.clear();
        updateColumns();
        if (activeSection == Section.CONFIG) {
            loadDockerConfig();
            updateToolbarButtonState();
            return;
        }
        if (currentSnapshot == null) {
            updateToolbarButtonState();
            return;
        }
        switch (activeSection) {
            case CONTAINERS -> currentSnapshot.containers().forEach(container ->
                    rows.add(new DockerRow(
                            Section.CONTAINERS,
                            firstNonBlank(container.name(), container.id()),
                            shortId(container.id()),
                            firstNonBlank(container.image(), "-"),
                            firstNonBlank(container.state(), container.status()),
                            firstNonBlank(container.ports(), "-"),
                            firstNonBlank(container.createdAt(), "-"),
                            container.id()
                    )));
            case IMAGES -> currentSnapshot.images().forEach(image ->
                    rows.add(new DockerRow(
                            Section.IMAGES,
                            firstNonBlank(image.repository(), "-"),
                            shortId(image.id()),
                            firstNonBlank(image.size(), "-"),
                            firstNonBlank(image.tag(), "-"),
                            firstNonBlank(image.used(), "-"),
                            firstNonBlank(image.createdSince(), image.createdAt()),
                            image.id()
                    )));
            case NETWORKS -> currentSnapshot.networks().forEach(network ->
                    rows.add(new DockerRow(
                            Section.NETWORKS,
                            network.name(),
                            shortId(network.id()),
                            network.driver(),
                            network.scope(),
                            "IPv6=" + firstNonBlank(network.ipv6(), "false"),
                            "",
                            network.id()
                    )));
            case VOLUMES -> currentSnapshot.volumes().forEach(volume ->
                    rows.add(new DockerRow(
                            Section.VOLUMES,
                            volume.name(),
                            firstNonBlank(volume.used(), "-"),
                            firstNonBlank(volume.createdAt(), "-"),
                            firstNonBlank(volume.mountpoint(), "-"),
                            "",
                            "",
                            volume.name()
                    )));
        }
        applySearchFilter();
        updateToolbarButtonState();
    }

    private void switchSection(Section section) {
        activeSection = section;
        updateNavStyle();
        updateContentMode();
        configureToolbar();
        renderRows();
    }

    private void updateContentMode() {
        boolean config = activeSection == Section.CONFIG;
        dockerTable.setVisible(!config);
        dockerTable.setManaged(!config);
        configPane.setVisible(config);
        configPane.setManaged(config);
        searchBox.setVisible(!config);
        searchBox.setManaged(!config);
        if (config) {
            Platform.runLater(() -> {
                configEditor.requestFocus();
                configEditor.positionCaret(configEditor.getText() == null ? 0 : configEditor.getText().length());
                updateConfigEditorImePosition();
            });
        }
    }

    private void configureToolbar() {
        if (toolbarActions == null) {
            return;
        }
        List<Button> buttons = new ArrayList<>();
        buttons.add(makeToolbarButton("刷新", "fas-sync", false,
                () -> {
                    if (activeSection == Section.CONFIG) {
                        loadDockerConfig();
                    } else {
                        refreshForCurrentConnection(false);
                    }
                }));
        for (Operation operation : operationsFor(activeSection)) {
            buttons.add(makeToolbarButton(operation.name(), operation.iconLiteral(), operation.requiresSelection(),
                    () -> executeOperation(operation)));
        }
        toolbarActions.getChildren().setAll(buttons);
        updateToolbarButtonState();
    }

    private List<Operation> operationsFor(Section section) {
        return switch (section) {
            case CONTAINERS -> List.of(
                    new Operation("启动", "fas-play", true),
                    new Operation("停止", "fas-stop", true),
                    new Operation("重启", "fas-redo", true),
                    new Operation("删除", "fas-trash", true),
                    new Operation("暂停", "fas-pause", true),
                    new Operation("恢复", "fas-play-circle", true)
            );
            case IMAGES -> List.of(
                    new Operation("拉取", "fas-download", false),
                    new Operation("运行容器", "fas-play", true),
                    new Operation("删除", "fas-trash", true),
                    new Operation("推送", "fas-upload", true),
                    new Operation("打标签", "fas-tag", true),
                    new Operation("清理无用镜像", "fas-broom", false),
                    new Operation("清理悬空镜像", "fas-filter", false)
            );
            case NETWORKS -> List.of(
                    new Operation("创建", "fas-plus", false),
                    new Operation("删除", "fas-trash", true),
                    new Operation("连接容器", "fas-plug", true),
                    new Operation("断开容器", "fas-unlink", true)
            );
            case VOLUMES -> List.of(
                    new Operation("创建", "fas-plus", false),
                    new Operation("删除", "fas-trash", true),
                    new Operation("清理无用卷", "fas-broom", false)
            );
            case CONFIG -> List.of(
                    new Operation("保存配置", "fas-save", false),
                    new Operation("重启 Docker", "fas-power-off", false)
            );
        };
    }

    private ContextMenu createSingleContextMenu(DockerRow row) {
        ContextMenu menu = new ContextMenu();
        for (String action : singleActionsFor(row.kind())) {
            MenuItem item = new MenuItem(action);
            item.setOnAction(e -> executeSingleOperation(action, row));
            menu.getItems().add(item);
        }
        return menu;
    }

    private List<String> singleActionsFor(Section section) {
        return switch (section) {
            case CONTAINERS ->
                    List.of("详情", "日志", "进入容器", "资源占用", "进程", "文件变更", "重命名", "拷贝文件");
            case IMAGES -> List.of("详情", "运行容器", "历史", "构建自此镜像", "导出");
            case NETWORKS -> List.of("详情", "网络类型", "连接容器", "断开容器");
            case VOLUMES -> List.of("详情", "备份", "恢复");
            case CONFIG -> List.of();
        };
    }

    private void executeOperation(Operation operation) {
        if (activeSection == Section.CONFIG) {
            if ("fas-save".equals(operation.iconLiteral())) {
                saveDockerConfig();
                return;
            }
            if ("fas-power-off".equals(operation.iconLiteral())) {
                restartDocker();
                return;
            }
        }
        executeBatchOperation(operation.name());
    }

    private void loadDockerConfig() {
        ConnectionManager connectionManager = ConnectionManager.getInstance();
        String connId = connectionManager.getCurrentConnectionId();
        ConnInfo connInfo = connectionManager.getCurrentConnection();
        if (connId == null || connInfo == null || !connectionManager.isConnected(connId)) {
            activeConfigPath = "/etc/docker/daemon.json";
            configPathLabel.setText(activeConfigPath);
            configEditor.clear();
            setStatus("未连接");
            return;
        }
        long serial = ++configSerial;
        configPathLabel.setText(activeConfigPath);
        configEditor.setText("正在读取 Docker 配置文件...");
        sessionManager.loadConfigFile(connId, connInfo).thenAccept(config ->
                Platform.runLater(() -> applyDockerConfig(serial, config)));
    }

    private void applyDockerConfig(long serial, DockerService.DockerConfigFile config) {
        if (serial != configSerial || activeSection != Section.CONFIG) {
            return;
        }
        activeConfigPath = firstNonBlank(config.path(), "/etc/docker/daemon.json");
        configPathLabel.setText(activeConfigPath);
        configEditor.setText(config.content() == null ? "" : config.content());
        if (config.error() != null && !config.error().isBlank()) {
            setStatus(config.error());
        } else {
            setStatus("Docker 配置文件已读取");
        }
        Platform.runLater(() -> {
            configEditor.requestFocus();
            configEditor.positionCaret(configEditor.getText() == null ? 0 : configEditor.getText().length());
            updateConfigEditorImePosition();
        });
    }

    private void updateConfigEditorImePosition() {
        if (!com.sun.jna.Platform.isWindows()
                || configEditor == null
                || configEditor.getScene() == null
                || !configEditor.isFocused()) {
            return;
        }
        Point2D screenPoint = caretScreenPoint();
        if (screenPoint == null) {
            screenPoint = configEditor.localToScreen(12, 28);
        }
        if (screenPoint == null) {
            return;
        }
        double scaleX = configEditor.getScene().getWindow() == null
                ? 1.0
                : configEditor.getScene().getWindow().getOutputScaleX();
        double scaleY = configEditor.getScene().getWindow() == null
                ? 1.0
                : configEditor.getScene().getWindow().getOutputScaleY();
        Imm32.setCompositionWindowPosition(
                (int) (screenPoint.getX() * scaleX),
                (int) (screenPoint.getY() * scaleY)
        );
    }

    private Point2D caretScreenPoint() {
        Node caret = configEditor.lookup(".caret");
        if (caret == null) {
            return null;
        }
        Bounds bounds = caret.localToScreen(caret.getBoundsInLocal());
        if (bounds == null) {
            return null;
        }
        return new Point2D(bounds.getMinX(), bounds.getMaxY());
    }

    private void saveDockerConfig() {
        ConnectionManager connectionManager = ConnectionManager.getInstance();
        String connId = connectionManager.getCurrentConnectionId();
        ConnInfo connInfo = connectionManager.getCurrentConnection();
        if (connId == null || connInfo == null || !connectionManager.isConnected(connId)) {
            showInfo("保存配置", "未连接");
            return;
        }
        String path = firstNonBlank(activeConfigPath, "/etc/docker/daemon.json");
        setStatus("正在保存 Docker 配置...");
        sessionManager.saveConfigFile(connId, connInfo, path, configEditor.getText()).thenAccept(result ->
                Platform.runLater(() -> {
                    if (result.isSuccess()) {
                        setStatus("Docker 配置已保存");
                    } else {
                        setStatus("Docker 配置保存失败");
                        showInfo("保存配置", firstNonBlank(result.stderr(), "保存失败"));
                    }
                }));
    }

    private void restartDocker() {
        ConnectionManager connectionManager = ConnectionManager.getInstance();
        String connId = connectionManager.getCurrentConnectionId();
        ConnInfo connInfo = connectionManager.getCurrentConnection();
        if (connId == null || connInfo == null || !connectionManager.isConnected(connId)) {
            showInfo("重启 Docker", "未连接");
            return;
        }
        setStatus("正在重启 Docker...");
        sessionManager.restartDocker(connId, connInfo).thenAccept(result ->
                Platform.runLater(() -> {
                    if (result.isSuccess()) {
                        setStatus("Docker 已重启");
                        refreshForCurrentConnection(false);
                    } else {
                        setStatus("Docker 重启失败");
                        showInfo("重启 Docker", firstNonBlank(result.stderr(), "重启失败"));
                    }
                }));
    }

    private void executeBatchOperation(String operation) {
        if (activeSection == Section.IMAGES && "运行容器".equals(operation)) {
            runContainersFromImages();
            return;
        }
        if (activeSection != Section.CONTAINERS) {
            DialogHelper.showInfo(operation, "功能待实现" + (!selectedRows().isEmpty() ? "\n已选择 " + selectedRows().size() + " 项。" : ""));
            return;
        }
        switch (operation) {
            case "启动" -> runContainerBatch("start", operation, false);
            case "停止" -> runContainerBatch("stop", operation, true);
            case "重启" -> runContainerBatch("restart", operation, true);
            case "删除" -> runContainerBatch("rm", operation, true);
            case "暂停" -> runContainerBatch("pause", operation, false);
            case "恢复" -> runContainerBatch("unpause", operation, false);
            default -> DialogHelper.showInfo(operation, "功能待实现");
        }
    }

    private void executeSingleOperation(String operation, DockerRow row) {
        if (row == null) {
            return;
        }
        if (row.kind() == Section.IMAGES && "运行容器".equals(operation)) {
            runContainerFromImage(row);
            return;
        }
        if (row.kind() != Section.CONTAINERS) {
            DialogHelper.showInfo(operation, "功能待实现\n\n" + row.name());
            return;
        }
        DockerConnectionContext context = requireDockerConnection(operation);
        if (context == null) {
            return;
        }
        if (!supportsContainerSingleAction(operation, row)) {
            DialogHelper.showWarning(operation, "当前容器状态不支持该操作");
            return;
        }
        switch (operation) {
            case "详情" -> showContainerCommandResult(operation, row,
                    sessionManager.containerInspect(context.connId(), context.connInfo(), row.commandTarget()));
            case "日志" -> showContainerCommandResult(operation, row,
                    sessionManager.containerLogs(context.connId(), context.connInfo(), row.commandTarget()));
            case "进入容器" -> enterContainer(row);
            case "资源占用" -> showContainerCommandResult(operation, row,
                    sessionManager.containerStats(context.connId(), context.connInfo(), row.commandTarget()));
            case "进程" -> showContainerCommandResult(operation, row,
                    sessionManager.containerTop(context.connId(), context.connInfo(), row.commandTarget()));
            case "文件变更" -> showContainerCommandResult(operation, row,
                    sessionManager.containerDiff(context.connId(), context.connInfo(), row.commandTarget()));
            case "重命名" -> renameContainer(row);
            case "拷贝文件" -> copyContainerFile(row);
            default -> DialogHelper.showInfo(operation, "功能待实现\n\n" + row.name());
        }
    }

    private void runContainerFromImage(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("运行容器");
        if (context == null) {
            return;
        }
        String image = DialogHelper.showTextInput("运行容器", null, "镜像", imageReference(row));
        if (image == null) {
            return;
        }
        setStatus("正在创建容器...");
        sessionManager.containerRun(context.connId(), context.connInfo(), image).thenAccept(result ->
                Platform.runLater(() -> {
                    if (result.isSuccess()) {
                        setStatus("容器已创建");
                        refreshForCurrentConnection(false);
                    } else {
                        setStatus("创建容器失败");
                        DialogHelper.showError("运行容器", commandMessage(result));
                    }
                }));
    }

    private void runContainersFromImages() {
        DockerConnectionContext context = requireDockerConnection("运行容器");
        if (context == null) {
            return;
        }
        List<DockerRow> selected = selectedRows().stream()
                .filter(row -> row.kind() == Section.IMAGES)
                .toList();
        if (selected.isEmpty()) {
            DialogHelper.showWarning("运行容器", "请先选择镜像");
            return;
        }
        if (selected.size() == 1) {
            runContainerFromImage(selected.get(0));
            return;
        }
        if (!DialogHelper.showConfirm("运行容器", "确定要基于选中的 " + selected.size() + " 个镜像分别创建容器吗？")) {
            return;
        }
        List<String> images = selected.stream()
                .map(this::imageReference)
                .filter(image -> image != null && !image.isBlank())
                .toList();
        setStatus("正在创建容器...");
        runImageBatch(context, images, 0, 0, new ArrayList<>());
    }

    private void runImageBatch(DockerConnectionContext context, List<String> images, int index,
                               int succeeded, List<String> failures) {
        if (index >= images.size()) {
            Platform.runLater(() -> {
                setStatus("运行容器完成：" + succeeded + "/" + images.size());
                if (!failures.isEmpty()) {
                    DialogHelper.showWarning("运行容器", String.join("\n", failures));
                }
                refreshForCurrentConnection(false);
            });
            return;
        }
        String image = images.get(index);
        sessionManager.containerRun(context.connId(), context.connInfo(), image).thenAccept(result -> {
            int nextSucceeded = result.isSuccess() ? succeeded + 1 : succeeded;
            List<String> nextFailures = failures;
            if (!result.isSuccess()) {
                nextFailures = new ArrayList<>(failures);
                nextFailures.add(image + ": " + commandMessage(result));
            }
            runImageBatch(context, images, index + 1, nextSucceeded, nextFailures);
        });
    }

    private void runContainerBatch(String dockerAction, String title, boolean confirm) {
        DockerConnectionContext context = requireDockerConnection(title);
        if (context == null) {
            return;
        }
        List<DockerRow> selected = selectedRows().stream()
                .filter(row -> row.kind() == Section.CONTAINERS)
                .toList();
        if (selected.isEmpty()) {
            DialogHelper.showWarning(title, "请先选择容器");
            return;
        }
        List<DockerRow> runnable = selected.stream()
                .filter(row -> supportsContainerBatchAction(dockerAction, row))
                .toList();
        List<DockerRow> skipped = selected.stream()
                .filter(row -> !supportsContainerBatchAction(dockerAction, row))
                .toList();
        if (runnable.isEmpty()) {
            DialogHelper.showWarning(title, "所选容器当前状态不支持该操作");
            return;
        }
        if (confirm && !DialogHelper.showConfirm(title, "确定要" + title + "选中的 " + selected.size() + " 个容器吗？")) {
            return;
        }
        List<String> ids = runnable.stream().map(DockerRow::commandTarget).toList();
        setStatus("正在" + title + "容器...");
        sessionManager.containerBatchAction(context.connId(), context.connInfo(), dockerAction, ids).thenAccept(result ->
                Platform.runLater(() -> {
                    if (result.isSuccess()) {
                        setStatus(title + "完成：" + result.succeeded() + "/" + result.total());
                        if (skipped.isEmpty()) {
                            refreshForCurrentConnection(false);
                            return;
                        }
                        DialogHelper.showWarning(title, batchMessage(result, skipped));
                        refreshForCurrentConnection(false);
                    } else {
                        setStatus(title + "部分失败：" + result.succeeded() + "/" + result.total());
                        DialogHelper.showWarning(title, batchMessage(result, skipped));
                        refreshForCurrentConnection(false);
                    }
                }));
    }

    private void showContainerCommandResult(String title, DockerRow row, CompletableFuture<SshService.CommandResult> future) {
        setStatus("正在读取" + title + "...");
        future.thenAccept(result -> Platform.runLater(() -> {
            if (result.isSuccess()) {
                setStatus(title + "已读取");
                showTextDialog(title, row.name(), commandMessage(result));
            } else {
                setStatus(title + "读取失败");
                DialogHelper.showError(title, commandMessage(result));
            }
        }));
    }

    private void enterContainer(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("进入容器");
        if (context == null) {
            return;
        }
        TerminalPanelController terminalController = ConnectionManager.getInstance().getTerminalPanelController(context.connId());
        if (terminalController == null) {
            DialogHelper.showWarning("进入容器", "当前连接没有可用终端");
            return;
        }
        if (!terminalController.executeShellCommand("docker exec -it " + shellArg(row.commandTarget()) + " sh")) {
            DialogHelper.showWarning("进入容器", "终端未就绪");
        }
    }

    private void renameContainer(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("重命名");
        if (context == null) {
            return;
        }
        String name = DialogHelper.showTextInput("重命名容器", null, "名称", row.name());
        if (name == null || name.equals(row.name())) {
            return;
        }
        setStatus("正在重命名容器...");
        sessionManager.containerRename(context.connId(), context.connInfo(), row.commandTarget(), name).thenAccept(result ->
                Platform.runLater(() -> handleMutationResult("重命名", result)));
    }

    private void copyContainerFile(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("拷贝文件");
        if (context == null) {
            return;
        }
        String source = DialogHelper.showTextInput("拷贝文件", null,
                "源路径", row.name() + ":/path/in/container");
        if (source == null) {
            return;
        }
        String target = DialogHelper.showTextInput("拷贝文件", null,
                "目标路径", "/tmp/");
        if (target == null) {
            return;
        }
        setStatus("正在拷贝文件...");
        sessionManager.containerCopy(context.connId(), context.connInfo(), source, target).thenAccept(result ->
                Platform.runLater(() -> handleMutationResult("拷贝文件", result)));
    }

    private void handleMutationResult(String title, SshService.CommandResult result) {
        if (result.isSuccess()) {
            setStatus(title + "完成");
            refreshForCurrentConnection(false);
        } else {
            setStatus(title + "失败");
            DialogHelper.showError(title, commandMessage(result));
        }
    }

    private DockerConnectionContext requireDockerConnection(String title) {
        String connId = activeConnId();
        ConnInfo connInfo = activeConnInfo();
        if (connId == null || connInfo == null || !ConnectionManager.getInstance().isConnected(connId)) {
            DialogHelper.showWarning(title, "未连接");
            return null;
        }
        return new DockerConnectionContext(connId, connInfo);
    }

    private String activeConnId() {
        return activeConnId == null ? ConnectionManager.getInstance().getCurrentConnectionId() : activeConnId;
    }

    private ConnInfo activeConnInfo() {
        ConnectionManager connectionManager = ConnectionManager.getInstance();
        SshService service = connectionManager.getConnectionById(activeConnId());
        if (service != null) {
            return service.getConnInfo();
        }
        return connectionManager.getCurrentConnection();
    }

    private String commandMessage(SshService.CommandResult result) {
        if (result == null) {
            return "执行失败";
        }
        String stdout = result.stdout() == null ? "" : result.stdout().trim();
        String stderr = result.stderr() == null ? "" : result.stderr().trim();
        String message = firstNonBlank(stdout, stderr);
        if (!message.isBlank()) {
            return message;
        }
        return result.isSuccess() ? "执行成功" : "执行失败";
    }

    private void showTextDialog(String title, String header, String content) {
        TextArea area = new TextArea(content == null ? "" : content);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefColumnCount(100);
        area.setPrefRowCount(24);
        String dialogTitle = header == null || header.isBlank() ? title : title + " - " + header;
        DialogHelper.<Void>showCustomDialog(dialogTitle, area, button -> null);
    }

    private String batchMessage(DockerSessionManager.DockerBatchResult result, List<DockerRow> skipped) {
        StringBuilder builder = new StringBuilder();
        builder.append("成功 ").append(result.succeeded()).append(" / ").append(result.total());
        if (skipped != null && !skipped.isEmpty()) {
            builder.append("\n跳过 ").append(skipped.size()).append(" 项状态不支持");
        }
        if (result.failures() != null && !result.failures().isEmpty()) {
            builder.append("\n\n失败项：\n");
            for (String failure : result.failures()) {
                builder.append(failure).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String shellArg(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String imageReference(DockerRow row) {
        if (row == null) {
            return "";
        }
        String repository = row.name();
        String tag = row.detail();
        if (repository == null || repository.isBlank() || "-".equals(repository) || "<none>".equals(repository)) {
            return row.commandTarget();
        }
        if (tag == null || tag.isBlank() || "-".equals(tag) || "<none>".equals(tag)) {
            return repository;
        }
        return repository + ":" + tag;
    }

    private boolean supportsContainerBatchAction(String dockerAction, DockerRow row) {
        String state = containerState(row);
        return switch (dockerAction) {
            case "start" -> !isRunningState(state) && !isPausedState(state);
            case "stop" -> isRunningState(state) || isPausedState(state);
            case "restart" -> isRunningState(state);
            case "pause" -> isRunningState(state);
            case "unpause" -> isPausedState(state);
            case "rm" -> true;
            default -> true;
        };
    }

    private boolean supportsContainerSingleAction(String operation, DockerRow row) {
        String state = containerState(row);
        return switch (operation) {
            case "进入容器", "资源占用", "进程" -> isRunningState(state);
            default -> true;
        };
    }

    private String containerState(DockerRow row) {
        return row == null || row.detail() == null ? "" : row.detail().trim().toLowerCase(Locale.ROOT);
    }

    private boolean isRunningState(String state) {
        return "running".equals(state) || state.startsWith("up");
    }

    private boolean isPausedState(String state) {
        return "paused".equals(state);
    }

    private record DockerConnectionContext(String connId, ConnInfo connInfo) {
    }

    private void updateColumns() {
        colMore.setVisible(activeSection == Section.CONTAINERS || activeSection == Section.IMAGES);
        colExtra.setVisible(activeSection != Section.VOLUMES);
        switch (activeSection) {
            case CONTAINERS -> {
                colName.setText("名称");
                colId.setText("ID");
                colStatus.setText("镜像");
                colDetail.setText("状态");
                colExtra.setText("端口");
                colMore.setText("创建时间");
                colName.setPrefWidth(180);
                colId.setPrefWidth(130);
                colStatus.setPrefWidth(220);
                colDetail.setPrefWidth(120);
                colExtra.setPrefWidth(240);
                colMore.setPrefWidth(180);
            }
            case IMAGES -> {
                colName.setText("名称");
                colId.setText("ID");
                colStatus.setText("大小");
                colDetail.setText("标签");
                colExtra.setText("状态");
                colMore.setText("创建时间");
                colName.setPrefWidth(200);
                colId.setPrefWidth(130);
                colStatus.setPrefWidth(140);
                colDetail.setPrefWidth(180);
                colExtra.setPrefWidth(120);
                colMore.setPrefWidth(180);
            }
            case NETWORKS -> {
                colName.setText("网络");
                colId.setText("ID");
                colStatus.setText("驱动");
                colDetail.setText("作用域");
                colExtra.setText("属性");
                colName.setPrefWidth(220);
                colId.setPrefWidth(130);
                colStatus.setPrefWidth(160);
                colDetail.setPrefWidth(260);
                colExtra.setPrefWidth(180);
            }
            case VOLUMES -> {
                colName.setText("名称");
                colId.setText("状态");
                colStatus.setText("创建时间");
                colDetail.setText("挂载点");
                colName.setPrefWidth(220);
                colId.setPrefWidth(120);
                colStatus.setPrefWidth(180);
                colDetail.setPrefWidth(420);
            }
            case CONFIG -> {
                // Config mode uses the editor pane.
            }
        }
    }

    private void updateNavStyle() {
        updateNavButton(navContainers, activeSection == Section.CONTAINERS);
        updateNavButton(navImages, activeSection == Section.IMAGES);
        updateNavButton(navNetworks, activeSection == Section.NETWORKS);
        updateNavButton(navVolumes, activeSection == Section.VOLUMES);
        updateNavButton(navConfig, activeSection == Section.CONFIG);
    }

    private void updateNavButton(Button button, boolean active) {
        button.getStyleClass().remove("active");
        if (active) {
            button.getStyleClass().add("active");
        }
    }

    private void applySearchFilter() {
        if (filteredRows == null) {
            return;
        }
        String query = searchBox == null || searchBox.getText() == null
                ? ""
                : searchBox.getText().trim().toLowerCase(Locale.ROOT);
        filteredRows.setPredicate(row -> query.isEmpty()
                || contains(row.name(), query)
                || contains(row.id(), query)
                || contains(row.status(), query)
                || contains(row.detail(), query)
                || contains(row.extra(), query)
                || contains(row.more(), query));
    }

    private void updateToolbarButtonState() {
        if (toolbarActions == null) {
            return;
        }
        boolean hasSelection = !selectedRows().isEmpty();
        for (javafx.scene.Node node : toolbarActions.getChildren()) {
            if (node instanceof Button button) {
                Object data = button.getUserData();
                boolean requiresSelection = data instanceof Boolean value && value;
                button.setDisable(requiresSelection && !hasSelection);
            }
        }
    }

    private void showEmptyState() {
        rows.clear();
        lblContainersCount.setText("0");
        lblImagesCount.setText("0");
        lblNetworksCount.setText("0");
        lblVolumesCount.setText("0");
        lblDockerVersion.setText("-");
        lblApiVersion.setText("-");
        setStatus("未连接");
    }

    private List<DockerRow> selectedRows() {
        List<DockerRow> selected = new ArrayList<>();
        for (DockerRow row : rows) {
            if (row.selectedProperty().get()) {
                selected.add(row);
            }
        }
        if (selected.isEmpty() && dockerTable != null) {
            selected.addAll(dockerTable.getSelectionModel().getSelectedItems());
        }
        return selected;
    }

    private void showInfo(String title, String content) {
        DialogHelper.showInfo(title, content);
    }

    private Button makeToolbarButton(String tooltipText, String iconLiteral, boolean requiresSelection, Runnable action) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(13);
        icon.getStyleClass().add("docker-tool-icon");

        Button button = new Button();
        button.getStyleClass().add("tool-icon-btn");
        button.setGraphic(icon);
        Tooltip tooltip = new Tooltip(tooltipText);
        tooltip.setShowDelay(new Duration(200));
        button.setTooltip(tooltip);
        button.setMinSize(30, 28);
        button.setPrefSize(30, 28);
        button.setMaxSize(30, 28);
        button.setUserData(requiresSelection);
        button.setOnAction(e -> action.run());
        return button;
    }

    private void setStatus(String value) {
        lblDockerStatus.setText(value == null || value.isBlank() ? "-" : value);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String normalized = id.startsWith("sha256:") ? id.substring("sha256:".length()) : id;
        return normalized.length() <= 12 ? normalized : normalized.substring(0, 12);
    }

    private String blankAsDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private enum Section {
        CONTAINERS,
        IMAGES,
        NETWORKS,
        VOLUMES,
        CONFIG
    }

    private static final class DockerRow {
        private final Section kind;
        private final String name;
        private final String id;
        private final String status;
        private final String detail;
        private final String extra;
        private final String more;
        private final String commandTarget;
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);

        private DockerRow(Section kind, String name, String id, String status, String detail, String extra) {
            this(kind, name, id, status, detail, extra, "", id);
        }

        private DockerRow(Section kind, String name, String id, String status, String detail, String extra, String more) {
            this(kind, name, id, status, detail, extra, more, id);
        }

        private DockerRow(Section kind, String name, String id, String status, String detail, String extra,
                          String more, String commandTarget) {
            this.kind = kind;
            this.name = name;
            this.id = id;
            this.status = status;
            this.detail = detail;
            this.extra = extra;
            this.more = more;
            this.commandTarget = commandTarget;
        }

        private Section kind() {
            return kind;
        }

        private String name() {
            return name;
        }

        private String id() {
            return id;
        }

        private String status() {
            return status;
        }

        private String detail() {
            return detail;
        }

        private String extra() {
            return extra;
        }

        private String more() {
            return more;
        }

        private String commandTarget() {
            return commandTarget == null || commandTarget.isBlank() ? id : commandTarget;
        }

        private SimpleBooleanProperty selectedProperty() {
            return selected;
        }

        private void setSelected(boolean selected) {
            this.selected.set(selected);
        }
    }

    private record Operation(String name, String iconLiteral, boolean requiresSelection) {
    }
}

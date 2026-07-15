package com.yshell.controller;

import com.yshell.config.AppConfig;
import com.yshell.config.AppSettings;
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
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class DockerViewController {
    private static final int MAX_LOG_LINES = 2000;

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
            private final Tooltip tooltip = new Tooltip();

            {
                setAlignment(Pos.CENTER_LEFT);
                tooltip.setShowDelay(new Duration(200));
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
                widthProperty().addListener((obs, oldValue, newValue) -> updateTooltip());
                textField.fontProperty().addListener((obs, oldValue, newValue) -> updateTooltip());
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                    textField.setTooltip(null);
                    return;
                }
                String value = item == null ? "" : item;
                textField.setText(value);
                updateTooltip();
                setText(null);
                setGraphic(textField);
            }

            private void updateTooltip() {
                String value = textField.getText();
                if (value == null || value.isBlank() || !isTextOverflowing(value)) {
                    textField.setTooltip(null);
                    return;
                }
                tooltip.setText(value);
                textField.setTooltip(tooltip);
            }

            private boolean isTextOverflowing(String value) {
                Insets insets = textField.getInsets();
                double fieldWidth = textField.getWidth() > 0 ? textField.getWidth() : getWidth();
                double availableWidth = Math.max(0, fieldWidth - insets.getLeft() - insets.getRight());
                if (availableWidth <= 0) {
                    return false;
                }
                Text text = new Text(value);
                text.setFont(textField.getFont());
                return text.getLayoutBounds().getWidth() > availableWidth;
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
                    new Operation("加载本地镜像", "fas-folder-open", false),
                    new Operation("删除", "fas-trash", true),
                    new Operation("清理无用镜像", "fas-broom", false),
                    new Operation("清理悬空镜像", "fas-filter", false)
            );
            case NETWORKS -> List.of(
                    new Operation("创建", "fas-plus", false),
                    new Operation("删除", "fas-trash", true)
            );
            case VOLUMES -> List.of(
                    new Operation("创建", "fas-plus", false),
                    new Operation("删除", "fas-trash", true),
                    new Operation("清理无用卷", "fas-broom", false)
            );
            case CONFIG -> List.of(
                    new Operation("编辑配置", "fas-file-code", false),
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
            case IMAGES -> List.of("详情", "快速运行", "自定义运行", "历史", "导出", "打标签", "推送");
            case NETWORKS -> List.of("详情", "连接容器", "断开容器");
            case VOLUMES -> List.of("详情", "使用容器", "占用大小", "打开挂载点");
            case CONFIG -> List.of();
        };
    }

    private void executeOperation(Operation operation) {
        if (activeSection == Section.CONFIG) {
            if ("fas-file-code".equals(operation.iconLiteral())) {
                openDockerConfigInEditor();
                return;
            }
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

    private void openDockerConfigInEditor() {
        ConnectionManager connectionManager = ConnectionManager.getInstance();
        String connId = connectionManager.getCurrentConnectionId();
        ConnInfo connInfo = connectionManager.getCurrentConnection();
        if (connId == null || connInfo == null || !connectionManager.isConnected(connId)) {
            showInfo("编辑配置", "未连接");
            return;
        }
        String path = firstNonBlank(activeConfigPath, "/etc/docker/daemon.json");
        EditorViewController.open(path, connId);
        setStatus("已在编辑器打开 Docker 配置");
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
        if (activeSection == Section.IMAGES) {
            executeImageBatchOperation(operation);
            return;
        }
        if (activeSection == Section.NETWORKS) {
            executeNetworkBatchOperation(operation);
            return;
        }
        if (activeSection == Section.VOLUMES) {
            executeVolumeBatchOperation(operation);
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
        if (row.kind() == Section.IMAGES) {
            executeImageSingleOperation(operation, row);
            return;
        }
        if (row.kind() == Section.NETWORKS) {
            executeNetworkSingleOperation(operation, row);
            return;
        }
        if (row.kind() == Section.VOLUMES) {
            executeVolumeSingleOperation(operation, row);
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
            case "日志" -> showContainerLogs(operation, row, context);
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

    private void quickRunContainerFromImage(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("快速运行");
        if (context == null) {
            return;
        }
        String image = imageReference(row);
        if (image == null || image.isBlank()) {
            DialogHelper.showWarning("快速运行", "镜像不能为空");
            return;
        }
        setStatus("正在创建容器...");
        sessionManager.containerRun(context.connId(), context.connInfo(), image).thenAccept(result ->
                Platform.runLater(() -> handleContainerRunResult("快速运行", result)));
    }

    private void customRunContainerFromImage(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("自定义运行");
        if (context == null) {
            return;
        }
        ContainerRunRequest request = showContainerRunDialog(row);
        if (request == null) {
            return;
        }
        setStatus("正在创建容器...");
        sessionManager.containerRun(context.connId(), context.connInfo(), request.image(), request.options()).thenAccept(result ->
                Platform.runLater(() -> handleContainerRunResult("自定义运行", result)));
    }

    private void handleContainerRunResult(String title, SshService.CommandResult result) {
        if (result.isSuccess()) {
            setStatus("容器已创建");
            refreshForCurrentConnection(false);
        } else {
            setStatus("创建容器失败");
            DialogHelper.showError(title, commandMessage(result));
        }
    }

    private ContainerRunRequest showContainerRunDialog(DockerRow row) {
        TextField imageField = new TextField(imageReference(row));
        TextField nameField = new TextField();
        nameField.setPromptText("不填则由 Docker 自动生成");
        TextArea portsArea = runOptionTextArea("8080:80(多个换行填写)");
        TextArea volumesArea = runOptionTextArea("my-volume:/data 或 /host/path:/data(多个换行填写)");
        TextArea envArea = runOptionTextArea("KEY=VALUE(多个换行填写)");
        ComboBox<String> restartCombo = new ComboBox<>(FXCollections.observableArrayList(
                "不设置", "no", "always", "unless-stopped", "on-failure"
        ));
        restartCombo.getSelectionModel().selectFirst();
        TextArea advancedArea = runOptionTextArea("原样追加，例如：\n--network host --privileged");
        TextArea previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.setWrapText(false);
        previewArea.setPrefRowCount(4);
        previewArea.setMaxWidth(Double.MAX_VALUE);

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16, 18, 8, 18));
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("镜像"), imageField);
        grid.addRow(1, new Label("容器名"), nameField);
        grid.addRow(2, new Label("端口映射"), portsArea);
        grid.addRow(3, new Label("数据卷"), volumesArea);
        grid.addRow(4, new Label("环境变量"), envArea);
        grid.addRow(5, new Label("重启策略"), restartCombo);
        grid.addRow(6, new Label("高级参数"), advancedArea);
        grid.addRow(7, new Label("命令预览"), previewArea);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(86);
        labelColumn.setPrefWidth(86);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        for (Node node : List.of(imageField, nameField, portsArea, volumesArea, envArea, restartCombo, advancedArea, previewArea)) {
            if (node instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
        }

        Runnable updatePreview = () -> previewArea.setText(containerRunCommandPreview(
                imageField.getText(),
                buildContainerRunOptions(
                        nameField.getText(),
                        portsArea.getText(),
                        volumesArea.getText(),
                        envArea.getText(),
                        restartCombo.getValue(),
                        advancedArea.getText()
                )
        ));
        imageField.textProperty().addListener((obs, old, value) -> updatePreview.run());
        nameField.textProperty().addListener((obs, old, value) -> updatePreview.run());
        portsArea.textProperty().addListener((obs, old, value) -> updatePreview.run());
        volumesArea.textProperty().addListener((obs, old, value) -> updatePreview.run());
        envArea.textProperty().addListener((obs, old, value) -> updatePreview.run());
        restartCombo.valueProperty().addListener((obs, old, value) -> updatePreview.run());
        advancedArea.textProperty().addListener((obs, old, value) -> updatePreview.run());
        updatePreview.run();

        boolean ok = DialogHelper.showCustomDialog("自定义运行", grid,
                        button -> button != null && button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? Boolean.TRUE : null)
                .isPresent();
        if (!ok) {
            return null;
        }
        String image = trimToEmpty(imageField.getText());
        if (image.isBlank()) {
            DialogHelper.showWarning("自定义运行", "镜像不能为空");
            return null;
        }
        String options = buildContainerRunOptions(
                nameField.getText(),
                portsArea.getText(),
                volumesArea.getText(),
                envArea.getText(),
                restartCombo.getValue(),
                advancedArea.getText()
        );
        return new ContainerRunRequest(image, options);
    }

    private TextArea runOptionTextArea(String prompt) {
        TextArea area = new TextArea();
        area.setPromptText(prompt);
        area.setPrefRowCount(2);
        area.setWrapText(false);
        return area;
    }

    private String containerRunCommandPreview(String image, String options) {
        String cleanImage = trimToEmpty(image);
        String cleanOptions = trimToEmpty(options);
        return "docker run -d "
                + (cleanOptions.isBlank() ? "" : cleanOptions + " ")
                + shellArg(cleanImage.isBlank() ? "<image>" : cleanImage);
    }

    private String buildContainerRunOptions(String name,
                                            String ports,
                                            String volumes,
                                            String envs,
                                            String restart,
                                            String advanced) {
        StringBuilder builder = new StringBuilder();
        String cleanName = trimToEmpty(name);
        if (!cleanName.isBlank()) {
            appendRunOption(builder, "--name", cleanName);
        }
        appendRunOptionLines(builder, "-p", ports);
        appendRunOptionLines(builder, "-v", volumes);
        appendRunOptionLines(builder, "-e", envs);
        String cleanRestart = trimToEmpty(restart);
        if (!cleanRestart.isBlank() && !"不设置".equals(cleanRestart)) {
            appendRunOption(builder, "--restart", cleanRestart);
        }
        String cleanAdvanced = trimToEmpty(advanced);
        if (!cleanAdvanced.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(cleanAdvanced);
        }
        return builder.toString();
    }

    private void appendRunOptionLines(StringBuilder builder, String option, String lines) {
        if (lines == null || lines.isBlank()) {
            return;
        }
        for (String line : lines.split("\\R")) {
            String value = trimToEmpty(line);
            if (!value.isBlank()) {
                appendRunOption(builder, option, value);
            }
        }
    }

    private void appendRunOption(StringBuilder builder, String option, String value) {
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(option).append(' ').append(shellArg(value));
    }

    private void executeImageBatchOperation(String operation) {
        switch (operation) {
            case "拉取" -> pullImage();
            case "加载本地镜像" -> loadImage();
            case "删除" -> removeImages();
            case "清理无用镜像" -> pruneImages(true);
            case "清理悬空镜像" -> pruneImages(false);
            default -> DialogHelper.showInfo(operation, "功能待实现");
        }
    }

    private void executeImageSingleOperation(String operation, DockerRow row) {
        DockerConnectionContext context = requireDockerConnection(operation);
        if (context == null) {
            return;
        }
        switch (operation) {
            case "详情" -> showImageCommandResult(operation, row,
                    sessionManager.imageInspect(context.connId(), context.connInfo(), imageIdentifier(row)));
            case "快速运行" -> quickRunContainerFromImage(row);
            case "自定义运行" -> customRunContainerFromImage(row);
            case "历史" -> showImageCommandResult(operation, row,
                    sessionManager.imageHistory(context.connId(), context.connInfo(), imageIdentifier(row)));
            case "导出" -> saveImage(row);
            case "打标签" -> tagImage(row);
            case "推送" -> pushImage(row);
            default -> DialogHelper.showInfo(operation, "功能待实现\n\n" + row.name());
        }
    }

    private void executeNetworkBatchOperation(String operation) {
        switch (operation) {
            case "创建" -> createNetwork();
            case "删除" -> removeNetworks();
            default -> DialogHelper.showInfo(operation, "功能待实现");
        }
    }

    private void executeNetworkSingleOperation(String operation, DockerRow row) {
        DockerConnectionContext context = requireDockerConnection(operation);
        if (context == null) {
            return;
        }
        switch (operation) {
            case "详情" -> showNetworkCommandResult(operation, row,
                    sessionManager.networkInspect(context.connId(), context.connInfo(), networkTarget(row)));
            case "连接容器" -> connectNetwork(row);
            case "断开容器" -> disconnectNetwork(row);
            default -> DialogHelper.showInfo(operation, "功能待实现\n\n" + row.name());
        }
    }

    private void createNetwork() {
        DockerConnectionContext context = requireDockerConnection("创建网络");
        if (context == null) {
            return;
        }
        NetworkCreateRequest request = showNetworkCreateDialog();
        if (request == null) {
            return;
        }
        setStatus("正在创建网络...");
        sessionManager.networkCreate(
                context.connId(),
                context.connInfo(),
                request.name(),
                request.driver(),
                request.subnet(),
                request.gateway(),
                request.internal()
        ).thenAccept(result -> Platform.runLater(() -> handleMutationResult("创建网络", result)));
    }

    private void removeNetworks() {
        DockerConnectionContext context = requireDockerConnection("删除网络");
        if (context == null) {
            return;
        }
        List<NetworkBatchItem> items = selectedNetworkRows().stream()
                .map(row -> new NetworkBatchItem(row.name(), networkTarget(row)))
                .toList();
        if (items.isEmpty()) {
            DialogHelper.showWarning("删除网络", "请先选择网络");
            return;
        }
        if (!DialogHelper.showConfirm("删除网络", "确定要删除选中的 " + items.size() + " 个网络吗？")) {
            return;
        }
        runNetworkCommandBatch(items,
                item -> sessionManager.networkRemove(context.connId(), context.connInfo(), item.target()));
    }

    private void connectNetwork(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("连接容器");
        if (context == null) {
            return;
        }
        String container = showRequiredTextDialog("连接容器", "容器名或 ID", "", "容器名或 ID 不能为空");
        if (container == null) {
            return;
        }
        setStatus("正在连接容器...");
        sessionManager.networkConnect(context.connId(), context.connInfo(), networkTarget(row), container)
                .thenAccept(result -> Platform.runLater(() -> handleMutationResult("连接容器", result)));
    }

    private void disconnectNetwork(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("断开容器");
        if (context == null) {
            return;
        }
        String container = showRequiredTextDialog("断开容器", "容器名或 ID", "", "容器名或 ID 不能为空");
        if (container == null) {
            return;
        }
        setStatus("正在断开容器...");
        sessionManager.networkDisconnect(context.connId(), context.connInfo(), networkTarget(row), container)
                .thenAccept(result -> Platform.runLater(() -> handleMutationResult("断开容器", result)));
    }

    private NetworkCreateRequest showNetworkCreateDialog() {
        TextField nameField = new TextField();
        nameField.setPromptText("my-network");
        ComboBox<String> driverCombo = new ComboBox<>(FXCollections.observableArrayList(
                "bridge", "overlay", "macvlan"
        ));
        driverCombo.setEditable(true);
        driverCombo.getSelectionModel().select("bridge");
        TextField subnetField = new TextField();
        subnetField.setPromptText("可选，例如 172.18.0.0/16");
        TextField gatewayField = new TextField();
        gatewayField.setPromptText("可选，例如 172.18.0.1");
        CheckBox internalCheck = new CheckBox("仅内部网络");

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16, 18, 8, 18));
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("名称"), nameField);
        grid.addRow(1, new Label("驱动"), driverCombo);
        grid.addRow(2, new Label("子网"), subnetField);
        grid.addRow(3, new Label("网关"), gatewayField);
        grid.add(internalCheck, 1, 4);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(86);
        labelColumn.setPrefWidth(86);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        for (Node node : List.of(nameField, driverCombo, subnetField, gatewayField)) {
            if (node instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
        }

        Optional<NetworkCreateRequest> result = DialogHelper.showCustomDialog("创建网络", grid,
                button -> button != null && button.getButtonData() == ButtonBar.ButtonData.OK_DONE
                        ? new NetworkCreateRequest(
                        trimToEmpty(nameField.getText()),
                        trimToEmpty(driverCombo.getValue()),
                        trimToEmpty(subnetField.getText()),
                        trimToEmpty(gatewayField.getText()),
                        internalCheck.isSelected())
                        : null);
        NetworkCreateRequest request = result.orElse(null);
        if (request == null) {
            return null;
        }
        if (request.name().isBlank()) {
            DialogHelper.showWarning("创建网络", "网络名称不能为空");
            return null;
        }
        if (!request.gateway().isBlank() && request.subnet().isBlank()) {
            DialogHelper.showWarning("创建网络", "填写网关时也需要填写子网");
            return null;
        }
        return request;
    }

    private void showNetworkCommandResult(String title, DockerRow row, CompletableFuture<SshService.CommandResult> future) {
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

    private void runNetworkCommandBatch(List<NetworkBatchItem> items,
                                        NetworkCommandRunner runner) {
        setStatus("正在" + "删除网络" + "...");
        runNetworkCommandBatch("删除网络", items, runner, 0, 0, new ArrayList<>());
    }

    private void runNetworkCommandBatch(String title,
                                        List<NetworkBatchItem> items,
                                        NetworkCommandRunner runner,
                                        int index,
                                        int succeeded,
                                        List<String> failures) {
        if (index >= items.size()) {
            Platform.runLater(() -> {
                setStatus(title + "完成：" + succeeded + "/" + items.size());
                if (!failures.isEmpty()) {
                    DialogHelper.showWarning(title, String.join("\n", failures));
                }
                refreshForCurrentConnection(false);
            });
            return;
        }
        NetworkBatchItem item = items.get(index);
        runner.run(item).thenAccept(result -> {
            int nextSucceeded = result.isSuccess() ? succeeded + 1 : succeeded;
            List<String> nextFailures = failures;
            if (!result.isSuccess()) {
                nextFailures = new ArrayList<>(failures);
                nextFailures.add(item.label() + ": " + commandMessage(result));
            }
            runNetworkCommandBatch(title, items, runner, index + 1, nextSucceeded, nextFailures);
        });
    }

    private void executeVolumeBatchOperation(String operation) {
        switch (operation) {
            case "创建" -> createVolume();
            case "删除" -> removeVolumes();
            case "清理无用卷" -> pruneVolumes();
            default -> DialogHelper.showInfo(operation, "功能待实现");
        }
    }

    private void executeVolumeSingleOperation(String operation, DockerRow row) {
        DockerConnectionContext context = requireDockerConnection(operation);
        if (context == null) {
            return;
        }
        switch (operation) {
            case "详情" -> showVolumeCommandResult(operation, row,
                    sessionManager.volumeInspect(context.connId(), context.connInfo(), volumeTarget(row)));
            case "使用容器" -> showVolumeCommandResult(operation, row,
                    sessionManager.volumeContainers(context.connId(), context.connInfo(), volumeTarget(row)));
            case "占用大小" -> showVolumeCommandResult(operation, row,
                    sessionManager.volumeSize(context.connId(), context.connInfo(), volumeTarget(row)));
            case "打开挂载点" -> openVolumeMountPoint(row, context);
            default -> DialogHelper.showInfo(operation, "功能待实现\n\n" + row.name());
        }
    }

    private void createVolume() {
        DockerConnectionContext context = requireDockerConnection("创建卷");
        if (context == null) {
            return;
        }
        VolumeCreateRequest request = showVolumeCreateDialog();
        if (request == null) {
            return;
        }
        setStatus("正在创建卷...");
        sessionManager.volumeCreate(context.connId(), context.connInfo(), request.name())
                .thenAccept(result -> Platform.runLater(() -> handleMutationResult("创建卷", result)));
    }

    private VolumeCreateRequest showVolumeCreateDialog() {
        TextField nameField = new TextField();
        nameField.setPromptText("my-volume");

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16, 18, 8, 18));
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("名称"), nameField);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(86);
        labelColumn.setPrefWidth(86);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        nameField.setMaxWidth(Double.MAX_VALUE);

        Optional<VolumeCreateRequest> result = DialogHelper.showCustomDialog("创建卷", grid,
                button -> button != null && button.getButtonData() == ButtonBar.ButtonData.OK_DONE
                        ? new VolumeCreateRequest(trimToEmpty(nameField.getText()))
                        : null);
        VolumeCreateRequest request = result.orElse(null);
        if (request == null) {
            return null;
        }
        if (request.name().isBlank()) {
            DialogHelper.showWarning("创建卷", "卷名称不能为空");
            return null;
        }
        return request;
    }

    private void removeVolumes() {
        DockerConnectionContext context = requireDockerConnection("删除卷");
        if (context == null) {
            return;
        }
        List<VolumeBatchItem> items = selectedVolumeRows().stream()
                .map(row -> new VolumeBatchItem(row.name(), volumeTarget(row)))
                .toList();
        if (items.isEmpty()) {
            DialogHelper.showWarning("删除卷", "请先选择卷");
            return;
        }
        if (!DialogHelper.showConfirm("删除卷", "确定要删除选中的 " + items.size() + " 个卷吗？")) {
            return;
        }
        runVolumeCommandBatch(items,
                item -> sessionManager.volumeRemove(context.connId(), context.connInfo(), item.target()));
    }

    private void pruneVolumes() {
        DockerConnectionContext context = requireDockerConnection("清理无用卷");
        if (context == null) {
            return;
        }
        if (!DialogHelper.showConfirm("清理无用卷", "确定要清理所有未被容器使用的卷吗？该操作可能删除持久化数据。")) {
            return;
        }
        setStatus("正在清理无用卷...");
        sessionManager.volumePrune(context.connId(), context.connInfo())
                .thenAccept(result -> Platform.runLater(() -> handleMutationResult("清理无用卷", result)));
    }

    private void showVolumeCommandResult(String title, DockerRow row, CompletableFuture<SshService.CommandResult> future) {
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

    private void openVolumeMountPoint(DockerRow row, DockerConnectionContext context) {
        String mountPoint = trimToEmpty(row.detail());
        if (mountPoint.isBlank() || "-".equals(mountPoint)) {
            DialogHelper.showWarning("打开挂载点", "当前卷没有可用挂载点");
            return;
        }
        TerminalPanelController terminalController = ConnectionManager.getInstance().getTerminalPanelController(context.connId());
        if (terminalController == null) {
            DialogHelper.showWarning("打开挂载点", "当前连接没有可用终端");
            return;
        }
        if (terminalController.executeShellCommand("cd " + shellArg(mountPoint) + " && pwd")) {
            setStatus("已跳转到卷挂载点");
        } else {
            DialogHelper.showWarning("打开挂载点", "终端未就绪");
        }
    }

    private void runVolumeCommandBatch(List<VolumeBatchItem> items,
                                       VolumeCommandRunner runner) {
        setStatus("正在" + "删除卷" + "...");
        runVolumeCommandBatch("删除卷", items, runner, 0, 0, new ArrayList<>());
    }

    private void runVolumeCommandBatch(String title,
                                       List<VolumeBatchItem> items,
                                       VolumeCommandRunner runner,
                                       int index,
                                       int succeeded,
                                       List<String> failures) {
        if (index >= items.size()) {
            Platform.runLater(() -> {
                setStatus(title + "完成：" + succeeded + "/" + items.size());
                if (!failures.isEmpty()) {
                    DialogHelper.showWarning(title, String.join("\n", failures));
                }
                refreshForCurrentConnection(false);
            });
            return;
        }
        VolumeBatchItem item = items.get(index);
        runner.run(item).thenAccept(result -> {
            int nextSucceeded = result.isSuccess() ? succeeded + 1 : succeeded;
            List<String> nextFailures = failures;
            if (!result.isSuccess()) {
                nextFailures = new ArrayList<>(failures);
                nextFailures.add(item.label() + ": " + commandMessage(result));
            }
            runVolumeCommandBatch(title, items, runner, index + 1, nextSucceeded, nextFailures);
        });
    }

    private void pullImage() {
        DockerConnectionContext context = requireDockerConnection("拉取");
        if (context == null) {
            return;
        }
        String image = showRequiredTextDialog("拉取镜像", "镜像", "nginx:latest", "镜像不能为空");
        if (image == null) {
            return;
        }
        setStatus("正在拉取镜像...");
        sessionManager.imagePull(context.connId(), context.connInfo(), image).thenAccept(result ->
                Platform.runLater(() -> handleMutationResult("拉取镜像", result)));
    }

    private void loadImage() {
        DockerConnectionContext context = requireDockerConnection("加载本地镜像");
        if (context == null) {
            return;
        }
        String path = showRequiredTextDialog("加载本地镜像", "宿主机镜像文件路径", "/tmp/image.tar",
                "宿主机镜像文件路径不能为空");
        if (path == null) {
            return;
        }
        setStatus("正在加载本地镜像...");
        sessionManager.imageLoad(context.connId(), context.connInfo(), path).thenAccept(result ->
                Platform.runLater(() -> handleMutationResult("加载本地镜像", result)));
    }

    private void removeImages() {
        DockerConnectionContext context = requireDockerConnection("删除镜像");
        if (context == null) {
            return;
        }
        List<DockerRow> selected = selectedImageRows();
        if (selected.isEmpty()) {
            DialogHelper.showWarning("删除镜像", "请先选择镜像");
            return;
        }
        if (!DialogHelper.showConfirm("删除镜像", "确定要删除选中的 " + selected.size() + " 个镜像吗？")) {
            return;
        }
        List<ImageBatchItem> items = selected.stream()
                .map(row -> new ImageBatchItem(imageReference(row), imageReference(row), ""))
                .toList();
        runImageCommandBatch("删除镜像", items,
                item -> sessionManager.imageRemove(context.connId(), context.connInfo(), item.source())
        );
    }

    private void pushImage(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("推送镜像");
        if (context == null) {
            return;
        }
        PushImageRequest request = showPushImageDialog(row);
        if (request == null) {
            return;
        }
        ImageBatchItem item = pushBatchItem(row, request);
        String loginRegistry = registryLoginAddress(request.registry().address);
        if (hasText(request.registry().username) && hasText(request.registry().password)) {
            setStatus("正在登录镜像仓库...");
            sessionManager.imageLogin(context.connId(), context.connInfo(), loginRegistry,
                    request.registry().username, request.registry().password).thenAccept(result ->
                    Platform.runLater(() -> {
                        if (!result.isSuccess()) {
                            setStatus("登录镜像仓库失败");
                            DialogHelper.showError("推送镜像", commandMessage(result));
                            return;
                        }
                        runSingleImageCommand(item,
                                sessionManager.imageTagAndPush(context.connId(), context.connInfo(), item.source(), item.target())
                        );
                    }));
        } else {
            runSingleImageCommand(item,
                    sessionManager.imageTagAndPush(context.connId(), context.connInfo(), item.source(), item.target())
            );
        }
    }

    private void tagImage(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("打标签");
        if (context == null) {
            return;
        }
        ImageTagRequest request = showImageTagDialog(row);
        if (request == null) {
            return;
        }
        String target = targetImageName("", request.repository(), request.tag());
        if (target.equals(imageReference(row))) {
            return;
        }
        List<ImageBatchItem> items = List.of(new ImageBatchItem(imageReference(row), imageIdentifier(row), target));
        runImageCommandBatch("打标签", items,
                item -> sessionManager.imageTag(context.connId(), context.connInfo(), item.source(), item.target())
        );
    }

    private ImageTagRequest showImageTagDialog(DockerRow row) {
        TextField repositoryField = new TextField(hasRepositoryName(row) ? row.name() : "");
        TextField tagField = new TextField(hasRepositoryName(row) ? imageTag(row) : "latest");
        repositoryField.setPromptText("repository");
        tagField.setPromptText("tag");

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16, 18, 8, 18));
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("镜像名"), repositoryField);
        grid.addRow(1, new Label("标签"), tagField);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(72);
        labelColumn.setPrefWidth(72);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        repositoryField.setMaxWidth(Double.MAX_VALUE);
        tagField.setMaxWidth(Double.MAX_VALUE);

        boolean ok = DialogHelper.showCustomDialog("打标签", grid,
                        button -> button != null && button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? Boolean.TRUE : null)
                .isPresent();
        if (!ok) {
            return null;
        }
        String repository = trimToEmpty(repositoryField.getText());
        String tag = trimToEmpty(tagField.getText());
        if (repository.isBlank() || tag.isBlank()) {
            DialogHelper.showWarning("打标签", "镜像名和标签不能为空");
            return null;
        }
        if (repository.contains(":")) {
            DialogHelper.showWarning("打标签", "镜像名不要包含标签，请在标签输入框填写标签值");
            return null;
        }
        return new ImageTagRequest(repository, tag);
    }

    private void pruneImages(boolean all) {
        String title = all ? "清理无用镜像" : "清理悬空镜像";
        DockerConnectionContext context = requireDockerConnection(title);
        if (context == null) {
            return;
        }
        String message = all
                ? "确定要清理所有未被容器使用的镜像吗？"
                : "确定要清理悬空镜像吗？";
        if (!DialogHelper.showConfirm(title, message)) {
            return;
        }
        setStatus("正在" + title + "...");
        sessionManager.imagePrune(context.connId(), context.connInfo(), all).thenAccept(result ->
                Platform.runLater(() -> {
                    if (result.isSuccess()) {
                        setStatus(title + "完成");
                        showTextDialog(title, "", commandMessage(result));
                        refreshForCurrentConnection(false);
                    } else {
                        setStatus(title + "失败");
                        DialogHelper.showError(title, commandMessage(result));
                    }
                }));
    }

    private void saveImage(DockerRow row) {
        DockerConnectionContext context = requireDockerConnection("导出镜像");
        if (context == null) {
            return;
        }
        String defaultPath = "/tmp/" + safeFileName(imageReference(row)) + ".tar";
        String path = showRequiredTextDialog("导出镜像", "宿主机保存路径", defaultPath, "宿主机保存路径不能为空");
        if (path == null) {
            return;
        }
        setStatus("正在导出镜像...");
        sessionManager.imageSave(context.connId(), context.connInfo(), imageIdentifier(row), path).thenAccept(result ->
                Platform.runLater(() -> handleMutationResult("导出镜像", result)));
    }

    private String showRequiredTextDialog(String title, String label, String defaultValue, String emptyMessage) {
        TextField field = new TextField(defaultValue == null ? "" : defaultValue);
        field.setMaxWidth(Double.MAX_VALUE);

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16, 18, 8, 18));
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label(label), field);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(120);
        labelColumn.setPrefWidth(120);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);

        boolean ok = DialogHelper.showCustomDialog(title, grid,
                        button -> button != null && button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? Boolean.TRUE : null)
                .isPresent();
        if (!ok) {
            return null;
        }
        String value = trimToEmpty(field.getText());
        if (value.isBlank()) {
            DialogHelper.showWarning(title, emptyMessage);
            return null;
        }
        return value;
    }

    private void showImageCommandResult(String title, DockerRow row, CompletableFuture<SshService.CommandResult> future) {
        setStatus("正在读取" + title + "...");
        future.thenAccept(result -> Platform.runLater(() -> {
            if (result.isSuccess()) {
                setStatus(title + "已读取");
                showTextDialog(title, imageReference(row), commandMessage(result));
            } else {
                setStatus(title + "读取失败");
                DialogHelper.showError(title, commandMessage(result));
            }
        }));
    }

    private void runSingleImageCommand(ImageBatchItem item,
                                       CompletableFuture<SshService.CommandResult> future) {
        setStatus("正在" + "推送镜像" + "...");
        future.thenAccept(result -> Platform.runLater(() -> {
            if (result.isSuccess()) {
                setStatus("推送镜像" + "完成");
                String message = commandMessage(result);
                if (!"执行成功".equals(message)) {
                    showTextDialog("推送镜像", item.label(), message);
                }
            } else {
                setStatus("推送镜像" + "失败");
                DialogHelper.showError("推送镜像", item.label() + "\n\n" + commandMessage(result));
            }
        }));
    }

    private void runImageCommandBatch(String title, List<ImageBatchItem> items,
                                      ImageCommandRunner runner) {
        if (items.isEmpty()) {
            DialogHelper.showWarning(title, "没有可执行的镜像");
            return;
        }
        setStatus("正在" + title + "...");
        runImageCommandBatch(title, items, runner, true, 0, 0, new ArrayList<>());
    }

    private void runImageCommandBatch(String title, List<ImageBatchItem> items,
                                      ImageCommandRunner runner, boolean refreshAfter,
                                      int index, int succeeded, List<String> failures) {
        if (index >= items.size()) {
            Platform.runLater(() -> {
                setStatus(title + "完成：" + succeeded + "/" + items.size());
                if (!failures.isEmpty()) {
                    DialogHelper.showWarning(title, String.join("\n", failures));
                }
                if (refreshAfter) {
                    refreshForCurrentConnection(false);
                }
            });
            return;
        }
        ImageBatchItem item = items.get(index);
        runner.run(item).thenAccept(result -> {
            int nextSucceeded = result.isSuccess() ? succeeded + 1 : succeeded;
            List<String> nextFailures = failures;
            if (!result.isSuccess()) {
                nextFailures = new ArrayList<>(failures);
                nextFailures.add(item.label() + ": " + commandMessage(result));
            }
            runImageCommandBatch(title, items, runner, refreshAfter, index + 1, nextSucceeded, nextFailures);
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
                    } else {
                        setStatus(title + "部分失败：" + result.succeeded() + "/" + result.total());
                    }
                    DialogHelper.showWarning(title, batchMessage(result, skipped));
                    refreshForCurrentConnection(false);
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

    private void showContainerLogs(String title, DockerRow row, DockerConnectionContext context) {
        ObservableList<String> logLines = FXCollections.observableArrayList();
        ListView<String> logView = new ListView<>(logLines);
        logView.setPrefSize(920, 560);
        logView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        logView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        logView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setStyle("-fx-font-family: Consolas, 'Courier New', monospace;");
            }
        });
        configureLogCopy(logView);

        Dialog<Void> dialog = DialogHelper.createCustomDialog(title + " - " + row.name(), logView, List.of(
                new DialogHelper.CustomDialogButton<>("关闭", ButtonBar.ButtonData.CANCEL_CLOSE, dialogRef -> null)
        ), "dialog-content-unscrolled");
        dialog.setResizable(true);

        LogListBuffer buffer = new LogListBuffer(logLines, MAX_LOG_LINES);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean followTail = new AtomicBoolean(true);
        AtomicBoolean scrollTrackingInstalled = new AtomicBoolean(false);
        AtomicReference<SshService.RemoteCommandHandle> handleRef = new AtomicReference<>();
        logView.addEventFilter(ScrollEvent.SCROLL,
                event -> Platform.runLater(() -> followTail.set(isLogListAtBottom(logView))));
        logView.addEventFilter(MouseEvent.MOUSE_RELEASED,
                event -> Platform.runLater(() -> followTail.set(isLogListAtBottom(logView))));
        dialog.setOnHidden(event -> {
            closed.set(true);
            SshService.RemoteCommandHandle handle = handleRef.get();
            if (handle != null) {
                handle.cancel();
            }
            setStatus(title + "已关闭");
        });

        setStatus("正在读取" + title + "...");
        dialog.show();
        installLogScrollTracking(logView, followTail, scrollTrackingInstalled, 0);

        CompletableFuture<SshService.RemoteCommandHandle> future = sessionManager.followContainerLogs(
                context.connId(),
                context.connInfo(),
                row.commandTarget(),
                chunk -> Platform.runLater(() -> {
                    if (!closed.get()) {
                        appendLogChunk(logView, buffer, chunk, followTail, scrollTrackingInstalled);
                    }
                }),
                chunk -> Platform.runLater(() -> {
                    if (!closed.get()) {
                        appendLogChunk(logView, buffer, chunk, followTail, scrollTrackingInstalled);
                    }
                })
        );

        future.thenAccept(handle -> {
            if (closed.get()) {
                handle.cancel();
                return;
            }
            handleRef.set(handle);
            handle.completion().thenAccept(result -> Platform.runLater(() -> {
                if (closed.get() || handle.isCancelled()) {
                    return;
                }
                if (result.isSuccess()) {
                    setStatus(title + "已结束");
                } else {
                    setStatus(title + "读取失败");
                    appendLogChunk(logView, buffer, "\n" + commandMessage(result), followTail, scrollTrackingInstalled);
                }
            }));
        }).exceptionally(error -> {
            Platform.runLater(() -> {
                if (!closed.get()) {
                    setStatus(title + "读取失败");
                    appendLogChunk(logView, buffer, "\n" + errorMessage(error), followTail, scrollTrackingInstalled);
                }
            });
            return null;
        });
    }

    private void configureLogCopy(ListView<String> logView) {
        AtomicInteger dragAnchor = new AtomicInteger(-1);
        logView.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!event.isPrimaryButtonDown() || isScrollBarEvent(event)) {
                return;
            }
            int index = logCellIndex(logView, event);
            if (index < 0) {
                return;
            }
            dragAnchor.set(index);
            logView.getSelectionModel().clearSelection();
            logView.getSelectionModel().select(index);
            event.consume();
        });
        logView.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!event.isPrimaryButtonDown() || isScrollBarEvent(event)) {
                return;
            }
            int anchor = dragAnchor.get();
            int index = logCellIndex(logView, event);
            if (anchor < 0 || index < 0) {
                return;
            }
            selectLogRange(logView, anchor, index);
            event.consume();
        });
        logView.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> dragAnchor.set(-1));

        logView.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                copyLogLines(logView, false);
                event.consume();
            }
        });

        MenuItem copySelected = new MenuItem("复制选中");
        copySelected.setOnAction(event -> copyLogLines(logView, false));
        MenuItem copyAll = new MenuItem("复制全部");
        copyAll.setOnAction(event -> copyLogLines(logView, true));
        logView.setContextMenu(new ContextMenu(copySelected, copyAll));
    }

    private void selectLogRange(ListView<String> logView, int first, int second) {
        int start = Math.max(0, Math.min(first, second));
        int end = Math.min(logView.getItems().size() - 1, Math.max(first, second));
        logView.getSelectionModel().clearSelection();
        for (int index = start; index <= end; index++) {
            logView.getSelectionModel().select(index);
        }
    }

    private int logCellIndex(ListView<String> logView, MouseEvent event) {
        Node node = event.getPickResult() == null ? null : event.getPickResult().getIntersectedNode();
        while (node != null && node != logView) {
            if (node instanceof ListCell<?> cell) {
                int index = cell.getIndex();
                return index >= 0 && index < logView.getItems().size() ? index : -1;
            }
            node = node.getParent();
        }
        return -1;
    }

    private boolean isScrollBarEvent(MouseEvent event) {
        Node node = event.getPickResult() == null ? null : event.getPickResult().getIntersectedNode();
        while (node != null) {
            if (node instanceof ScrollBar) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void copyLogLines(ListView<String> logView, boolean all) {
        List<String> lines;
        if (all) {
            lines = new ArrayList<>(logView.getItems());
        } else {
            List<Integer> indices = new ArrayList<>(logView.getSelectionModel().getSelectedIndices());
            indices.sort(Integer::compareTo);
            lines = indices.stream()
                    .filter(index -> index >= 0 && index < logView.getItems().size())
                    .map(index -> logView.getItems().get(index))
                    .toList();
        }
        if (lines.isEmpty() && !all) {
            lines = new ArrayList<>(logView.getItems());
        }
        if (lines.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(String.join(System.lineSeparator(), lines));
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void appendLogChunk(ListView<String> logView,
                                LogListBuffer buffer,
                                String chunk,
                                AtomicBoolean followTail,
                                AtomicBoolean scrollTrackingInstalled) {
        if (!scrollTrackingInstalled.get()) {
            installLogScrollTracking(logView, followTail, scrollTrackingInstalled, 0);
        }
        boolean shouldFollowTail = followTail.get();
        int firstVisibleIndex = shouldFollowTail ? -1 : firstVisibleLogIndex(logView);
        LogAppendResult appendResult = buffer.append(chunk);
        if (shouldFollowTail) {
            logView.scrollTo(Math.max(0, logView.getItems().size() - 1));
        } else if (appendResult.removedLines() > 0 && firstVisibleIndex >= 0) {
            logView.scrollTo(Math.max(0, firstVisibleIndex - appendResult.removedLines()));
        }
    }

    private void installLogScrollTracking(ListView<String> logView,
                                          AtomicBoolean followTail,
                                          AtomicBoolean scrollTrackingInstalled,
                                          int attempt) {
        ScrollBar verticalScrollBar = verticalScrollBar(logView);
        if (verticalScrollBar == null) {
            if (attempt < 8) {
                Platform.runLater(() -> installLogScrollTracking(
                        logView, followTail, scrollTrackingInstalled, attempt + 1));
            }
            return;
        }
        if (!scrollTrackingInstalled.compareAndSet(false, true)) {
            return;
        }
        verticalScrollBar.valueProperty().addListener((obs, oldValue, newValue) ->
                followTail.set(isLogListAtBottom(logView)));
    }

    private boolean isLogListAtBottom(ListView<String> logView) {
        ScrollBar verticalScrollBar = verticalScrollBar(logView);
        if (verticalScrollBar == null || !verticalScrollBar.isVisible()) {
            return true;
        }
        double min = verticalScrollBar.getMin();
        double max = verticalScrollBar.getMax();
        double range = max - min;
        if (range <= 0) {
            return true;
        }
        double epsilon = Math.max(0.000001, range * 0.001);
        return verticalScrollBar.getValue() >= max - epsilon;
    }

    private int firstVisibleLogIndex(ListView<String> logView) {
        Bounds viewport = logView.localToScene(logView.getBoundsInLocal());
        int first = Integer.MAX_VALUE;
        for (Node node : logView.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell
                    && cell.getIndex() >= 0
                    && cell.getIndex() < logView.getItems().size()
                    && !cell.isEmpty()) {
                Bounds bounds = cell.localToScene(cell.getBoundsInLocal());
                if (viewport != null
                        && bounds != null
                        && bounds.getMaxY() > viewport.getMinY()
                        && bounds.getMinY() < viewport.getMaxY()) {
                    first = Math.min(first, cell.getIndex());
                }
            }
        }
        return first == Integer.MAX_VALUE ? -1 : first;
    }

    private ScrollBar verticalScrollBar(Node node) {
        for (Node child : node.lookupAll(".scroll-bar")) {
            if (child instanceof ScrollBar scrollBar
                    && scrollBar.getOrientation() == Orientation.VERTICAL) {
                return scrollBar;
            }
        }
        return null;
    }

    private String errorMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null || current.getMessage().isBlank()
                ? "执行失败"
                : current.getMessage();
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
        CopyContainerFileRequest request = showCopyContainerFileDialog();
        if (request == null) {
            return;
        }
        setStatus("正在拷贝文件...");
        String source = row.commandTarget() + ":" + request.containerPath();
        sessionManager.containerCopy(context.connId(), context.connInfo(), source, request.hostPath()).thenAccept(result ->
                Platform.runLater(() -> handleMutationResult("拷贝文件", result)));
    }

    private CopyContainerFileRequest showCopyContainerFileDialog() {
        TextField containerPathField = new TextField();
        containerPathField.setPromptText("/path/in/container");

        TextField hostPathField = new TextField("/tmp/");
        hostPathField.setPromptText("/path/on/host");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 18, 8, 18));
        grid.addRow(0, new Label("容器内路径"), containerPathField);
        grid.addRow(1, new Label("宿主机路径"), hostPathField);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(96);
        labelColumn.setPrefWidth(96);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        containerPathField.setMaxWidth(Double.MAX_VALUE);
        hostPathField.setMaxWidth(Double.MAX_VALUE);

        Dialog<CopyContainerFileRequest> dialog = DialogHelper.createCustomDialog(
                "拷贝文件",
                grid,
                List.of(
                        new DialogHelper.CustomDialogButton<>("确定", ButtonBar.ButtonData.OK_DONE, dialogRef -> new CopyContainerFileRequest(
                                trimToEmpty(containerPathField.getText()),
                                trimToEmpty(hostPathField.getText())
                        )),
                        new DialogHelper.CustomDialogButton<>("取消", ButtonBar.ButtonData.CANCEL_CLOSE, dialogRef -> null)
                )
        );
        Button okButton = (Button) dialog.getDialogPane().lookupButton(
                new ButtonType("确定", ButtonBar.ButtonData.OK_DONE));
        if (okButton != null) {
            okButton.disableProperty().bind(
                    containerPathField.textProperty().isEmpty().or(hostPathField.textProperty().isEmpty())
            );
        }
        Optional<CopyContainerFileRequest> result = dialog.showAndWait();
        return result.orElse(null);
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
        DialogHelper.<Void>showCustomDialog(dialogTitle, area, List.of(
                new DialogHelper.CustomDialogButton<>("确定", ButtonBar.ButtonData.OK_DONE, dialog -> null)
        ));
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

    private String imageIdentifier(DockerRow row) {
        if (row == null) {
            return "";
        }
        String target = row.commandTarget();
        return target == null || target.isBlank() ? imageReference(row) : target;
    }

    private String imageSourceForPush(DockerRow row) {
        return hasRepositoryName(row) ? imageReference(row) : imageIdentifier(row);
    }

    private String imageTag(DockerRow row) {
        String tag = row == null ? "" : row.detail();
        return tag == null || tag.isBlank() || "-".equals(tag) || "<none>".equals(tag) ? "latest" : tag;
    }

    private String simpleImageName(DockerRow row) {
        String repository = row == null ? "" : row.name();
        if (repository == null || repository.isBlank() || "-".equals(repository) || "<none>".equals(repository)) {
            return "image-" + shortId(row == null ? "" : row.commandTarget());
        }
        int slash = repository.lastIndexOf('/');
        return slash >= 0 && slash < repository.length() - 1 ? repository.substring(slash + 1) : repository;
    }

    private PushImageRequest showPushImageDialog(DockerRow row) {
        List<AppConfig.DockerRegistry> registries = AppSettings.getInstance().getDockerRegistries().stream()
                .filter(registry -> registry != null && hasText(registry.address))
                .toList();
        if (registries.isEmpty()) {
            DialogHelper.showWarning("推送镜像", "请先在设置中配置 Docker 仓库");
            return null;
        }

        ComboBox<AppConfig.DockerRegistry> registryCombo = new ComboBox<>(FXCollections.observableArrayList(registries));
        registryCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(AppConfig.DockerRegistry registry) {
                return registryLabel(registry);
            }

            @Override
            public AppConfig.DockerRegistry fromString(String value) {
                return null;
            }
        });
        registryCombo.getSelectionModel().selectFirst();

        TextField namespaceField = new TextField(normalizeRegistryTargetBase(registries.get(0).address));
        TextField imageField = new TextField(simpleImageName(row));
        TextField tagField = new TextField(imageTag(row));
        registryCombo.valueProperty().addListener((obs, old, registry) -> {
            if (registry != null) {
                namespaceField.setText(normalizeRegistryTargetBase(registry.address));
            }
        });

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16, 18, 8, 18));
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("仓库"), registryCombo);
        grid.addRow(1, new Label("命名空间"), namespaceField);
        grid.addRow(2, new Label("镜像"), imageField);
        grid.addRow(3, new Label("标签"), tagField);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(72);
        labelColumn.setPrefWidth(72);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        registryCombo.setMaxWidth(Double.MAX_VALUE);
        namespaceField.setMaxWidth(Double.MAX_VALUE);
        imageField.setMaxWidth(Double.MAX_VALUE);
        tagField.setMaxWidth(Double.MAX_VALUE);

        boolean ok = DialogHelper.showCustomDialog("推送镜像", grid,
                        button -> button != null && button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? Boolean.TRUE : null)
                .isPresent();
        if (!ok) {
            return null;
        }
        AppConfig.DockerRegistry registry = registryCombo.getValue();
        String namespace = normalizeRegistryTargetBase(namespaceField.getText());
        String image = trimToEmpty(imageField.getText());
        String tag = trimToEmpty(tagField.getText());
        if (registry == null || namespace.isBlank() || image.isBlank() || tag.isBlank()) {
            DialogHelper.showWarning("推送镜像", "仓库、命名空间、镜像和标签不能为空");
            return null;
        }
        return new PushImageRequest(registry, namespace, image, tag);
    }

    private ImageBatchItem pushBatchItem(DockerRow row, PushImageRequest request) {
        String source = imageSourceForPush(row);
        String image = request.image();
        String tag = request.tag();
        String target = targetImageName(request.namespace(), image, tag);
        return new ImageBatchItem(source + " -> " + target, source, target);
    }

    private String targetImageName(String namespace, String image, String tag) {
        String base = normalizeRegistryTargetBase(namespace);
        String cleanImage = trimToEmpty(image);
        String cleanTag = trimToEmpty(tag);
        if (cleanTag.isBlank()) {
            cleanTag = "latest";
        }
        return (base.isBlank() ? cleanImage : base + "/" + cleanImage) + ":" + cleanTag;
    }

    private String registryLoginAddress(String address) {
        String base = normalizeRegistryTargetBase(address);
        int slash = base.indexOf('/');
        return slash >= 0 ? base.substring(0, slash) : base;
    }

    private String normalizeRegistryTargetBase(String value) {
        String text = trimToEmpty(value);
        if (text.startsWith("http://")) {
            text = text.substring("http://".length());
        } else if (text.startsWith("https://")) {
            text = text.substring("https://".length());
        }
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private String registryLabel(AppConfig.DockerRegistry registry) {
        if (registry == null) {
            return "";
        }
        String name = trimToEmpty(registry.name);
        String address = normalizeRegistryTargetBase(registry.address);
        return name.isBlank() ? address : name + " (" + address + ")";
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<DockerRow> selectedImageRows() {
        return selectedRows().stream()
                .filter(row -> row.kind() == Section.IMAGES)
                .toList();
    }

    private List<DockerRow> selectedNetworkRows() {
        return selectedRows().stream()
                .filter(row -> row.kind() == Section.NETWORKS)
                .toList();
    }

    private List<DockerRow> selectedVolumeRows() {
        return selectedRows().stream()
                .filter(row -> row.kind() == Section.VOLUMES)
                .toList();
    }

    private String networkTarget(DockerRow row) {
        if (row == null) {
            return "";
        }
        String target = row.commandTarget();
        return target == null || target.isBlank() ? row.name() : target;
    }

    private String volumeTarget(DockerRow row) {
        if (row == null) {
            return "";
        }
        String target = row.commandTarget();
        return target == null || target.isBlank() ? row.name() : target;
    }

    private boolean hasRepositoryName(DockerRow row) {
        String repository = row == null ? "" : row.name();
        return repository != null
                && !repository.isBlank()
                && !"-".equals(repository)
                && !"<none>".equals(repository);
    }

    private String safeFileName(String value) {
        String text = value == null || value.isBlank() ? "image" : value;
        return text.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private boolean supportsContainerBatchAction(String dockerAction, DockerRow row) {
        String state = containerState(row);
        return switch (dockerAction) {
            case "start" -> !isRunningState(state) && !isPausedState(state);
            case "stop" -> isRunningState(state) || isPausedState(state);
            case "restart", "pause" -> isRunningState(state);
            case "unpause" -> isPausedState(state);
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

    private static final class LogListBuffer {
        private final int maxLines;
        private final ObservableList<String> lines;
        private String partial = "";
        private boolean partialVisible;

        private LogListBuffer(ObservableList<String> lines, int maxLines) {
            this.lines = lines;
            this.maxLines = Math.max(1, maxLines);
        }

        private LogAppendResult append(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return new LogAppendResult("", 0);
            }
            if (partialVisible && !lines.isEmpty()) {
                lines.remove(lines.size() - 1);
                partialVisible = false;
            }
            String normalizedChunk = chunk.replace("\r\n", "\n").replace('\r', '\n');
            String text = partial + normalizedChunk;
            int start = 0;
            int newline;
            while ((newline = text.indexOf('\n', start)) >= 0) {
                lines.add(text.substring(start, newline));
                start = newline + 1;
            }
            partial = text.substring(start);
            if (!partial.isEmpty()) {
                lines.add(partial);
                partialVisible = true;
            }
            return new LogAppendResult(normalizedChunk, trim());
        }

        private int trim() {
            int overflow = lines.size() - maxLines;
            if (overflow <= 0) {
                return 0;
            }
            lines.remove(0, overflow);
            return overflow;
        }
    }

    private record LogAppendResult(String text, int removedLines) {
    }

    private record DockerConnectionContext(String connId, ConnInfo connInfo) {
    }

    private record ContainerRunRequest(String image, String options) {
    }

    private record CopyContainerFileRequest(String containerPath, String hostPath) {
    }

    private record NetworkCreateRequest(String name, String driver, String subnet, String gateway, boolean internal) {
    }

    private record NetworkBatchItem(String label, String target) {
    }

    private record VolumeCreateRequest(String name) {
    }

    private record VolumeBatchItem(String label, String target) {
    }

    private record ImageBatchItem(String label, String source, String target) {
    }

    private record PushImageRequest(AppConfig.DockerRegistry registry, String namespace, String image, String tag) {
    }

    private record ImageTagRequest(String repository, String tag) {
    }

    @FunctionalInterface
    private interface ImageCommandRunner {
        CompletableFuture<SshService.CommandResult> run(ImageBatchItem item);
    }

    @FunctionalInterface
    private interface NetworkCommandRunner {
        CompletableFuture<SshService.CommandResult> run(NetworkBatchItem item);
    }

    @FunctionalInterface
    private interface VolumeCommandRunner {
        CompletableFuture<SshService.CommandResult> run(VolumeBatchItem item);
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

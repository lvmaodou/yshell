package com.yshell.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.ConnInfo;
import com.yshell.service.ConnectionManager;
import com.yshell.service.K8sSessionManager;
import com.yshell.service.SshService;
import com.yshell.ui.DialogHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class K8sViewController {
    private static final Logger LOGGER = LoggerFactory.getLogger(K8sViewController.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_LOG_LINES = 2000;
    private static final String ALL_NAMESPACES = "全部 namespace";

    private final K8sSessionManager sessionManager = K8sSessionManager.getInstance();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Category, Button> categoryButtons = new LinkedHashMap<>();
    private final Map<Category, VBox> childContainers = new LinkedHashMap<>();
    private final ObservableList<K8sRow> allRows = FXCollections.observableArrayList();
    private final ObservableList<K8sRow> visibleRows = FXCollections.observableArrayList();
    private ResourceKind activeKind = ResourceKind.CRON_JOBS;
    private Category expandedCategory;
    private K8sSessionManager.K8sSnapshot currentSnapshot;
    private String boundConnId;
    private String activeConnId;
    private boolean tabVisible;
    private boolean namespaceInitialized;
    private boolean updatingNamespaceChoices;
    private long snapshotSerial;
    private long rowSerial;
    private long detailSerial;
    private int currentPageIndex;
    private String statusDetailText = "";

    @FXML
    private VBox navGroups;
    @FXML
    private VBox k8sSideStatus;
    @FXML
    private Label lblK8sVersion;
    @FXML
    private Label lblK8sStatus;
    @FXML
    private HBox toolbarActions;
    @FXML
    private TextField searchBox;
    @FXML
    private ComboBox<String> namespaceCombo;
    @FXML
    private TableView<K8sRow> resourceTable;
    @FXML
    private Label totalInfoLabel;
    @FXML
    private Label currentPageLabel;
    @FXML
    private Button firstPageButton;
    @FXML
    private Button prevPageButton;
    @FXML
    private Button nextPageButton;
    @FXML
    private Button lastPageButton;
    @FXML
    private TextField jumpPageField;
    @FXML
    private Button jumpPageButton;

    @FXML
    public void initialize() {
        configureSidebar();
        configureToolbar();
        configureFilters();
        configureTable();
        configureStatusLabel();
        ConnectionManager.getInstance().addOnConnectionStateChangedListener(
                () -> Platform.runLater(this::onConnectionStateChanged));
        expandedCategory = Category.WORKLOADS;
        updateSidebarStyle();
        updateNamespaceChoices(List.of());
        setVersionText("-");
        setStatusText("未连接");
        showEmptyState();
    }

    public void setTabVisible(boolean visible) {
        if (tabVisible == visible) {
            return;
        }
        tabVisible = visible;
        if (visible) {
            refreshVisibleContent();
        } else if (activeConnId != null) {
            sessionManager.closeSession(activeConnId);
            setVersionText("-");
            setStatusText("K8s 会话已关闭");
        }
    }

    public void showForConnection(String connId) {
        if (Objects.equals(boundConnId, connId)) {
            return;
        }
        if (activeConnId != null) {
            sessionManager.closeSession(activeConnId);
        }
        boundConnId = connId;
        activeConnId = null;
        currentSnapshot = null;
        namespaceInitialized = false;
        snapshotSerial++;
        rowSerial++;
        detailSerial++;
        updateNamespaceChoices(List.of());
        showEmptyState();
        if (tabVisible) {
            refreshVisibleContent();
        }
    }

    private void onConnectionStateChanged() {
        if (boundConnId != null && !ConnectionManager.getInstance().isConnected(boundConnId)) {
            if (activeConnId != null) {
                sessionManager.closeSession(activeConnId);
            }
            activeConnId = null;
            currentSnapshot = null;
            namespaceInitialized = false;
            snapshotSerial++;
            rowSerial++;
            detailSerial++;
            updateNamespaceChoices(List.of());
            setVersionText("-");
            setStatusText("未连接");
            showEmptyState();
            return;
        }
        if (tabVisible) {
            refreshVisibleContent();
        }
    }

    private void configureSidebar() {
        navGroups.getChildren().clear();
        for (Category category : Category.values()) {
            Button categoryButton = new Button();
            categoryButton.getStyleClass().add("k8s-category-button");
            categoryButton.setGraphic(createCategoryGraphic(category));
            categoryButton.setMaxWidth(Double.MAX_VALUE);
            categoryButton.setOnAction(event -> selectCategory(category));

            VBox children = new VBox();
            children.getStyleClass().add("k8s-child-list");
            children.setVisible(false);
            children.setManaged(false);
            for (ResourceKind kind : category.children()) {
                Button childButton = new Button(kind.label());
                childButton.getStyleClass().add("k8s-child-button");
                childButton.setMaxWidth(Double.MAX_VALUE);
                childButton.setOnAction(event -> selectResource(kind));
                children.getChildren().add(childButton);
            }

            categoryButtons.put(category, categoryButton);
            childContainers.put(category, children);
            navGroups.getChildren().addAll(categoryButton, children);
        }
    }

    private HBox createCategoryGraphic(Category category) {
        FontIcon icon = new FontIcon(category.iconLiteral());
        icon.setIconSize(12);
        icon.getStyleClass().add("k8s-nav-icon");

        Label label = new Label(category.label());
        label.getStyleClass().add("k8s-category-label");
        HBox.setHgrow(label, Priority.ALWAYS);

        FontIcon disclosure = new FontIcon("fas-chevron-right");
        disclosure.setIconSize(10);
        disclosure.getStyleClass().add("k8s-disclosure-icon");

        HBox graphic = new HBox(icon, label, disclosure);
        graphic.getStyleClass().add("k8s-category-content");
        return graphic;
    }

    private void configureToolbar() {
        Button createButton = makeToolbarButton("fas-plus", "新建资源", this::createResource);
        Button refreshButton = makeToolbarButton("fas-sync-alt", "刷新资源", this::refreshVisibleContent);
        toolbarActions.getChildren().setAll(createButton, refreshButton);
        configurePageButton(firstPageButton, "头页", "fas-angle-double-left", () -> goToPage(0));
        configurePageButton(prevPageButton, "上一页", "fas-angle-left", () -> goToPage(currentPageIndex - 1));
        configurePageButton(nextPageButton, "下一页", "fas-angle-right", () -> goToPage(currentPageIndex + 1));
        configurePageButton(lastPageButton, "尾页", "fas-angle-double-right",
                () -> goToPage(pageCount(filteredRows()) - 1));
        jumpPageButton.setOnAction(event -> jumpToPage());
        jumpPageField.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                jumpToPage();
                event.consume();
            }
        });
    }

    private void configureFilters() {
        namespaceCombo.setItems(FXCollections.observableArrayList(
                ALL_NAMESPACES
        ));
        namespaceCombo.getSelectionModel().select(ALL_NAMESPACES);
        namespaceCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            currentPageIndex = 0;
            if (updatingNamespaceChoices) {
                applyFilters();
            } else {
                refreshRowsForCurrentKind(true);
            }
        });
        searchBox.textProperty().addListener((obs, oldValue, newValue) -> {
            currentPageIndex = 0;
            applyFilters();
        });
    }

    private void configureTable() {
        resourceTable.setItems(visibleRows);
        resourceTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        resourceTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        resourceTable.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                copySelectedRowsToClipboard();
                event.consume();
            }
        });
        resourceTable.setRowFactory(table -> {
            TableRow<K8sRow> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    resourceTable.getSelectionModel().clearAndSelect(row.getIndex());
                    createContextMenu(row.getItem()).show(row, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getClickCount() == 2) {
                    showResourceAction(Action.DETAIL, row.getItem());
                }
            });
            return row;
        });
    }

    private void rebuildColumns() {
        resourceTable.getColumns().clear();
        for (String columnName : activeKind.columns()) {
            TableColumn<K8sRow, String> column = new TableColumn<>(columnName);
            column.setPrefWidth(widthFor(columnName));
            column.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().value(columnName)));
            column.setCellFactory(tableColumn -> createCopyableCell());
            resourceTable.getColumns().add(column);
        }
    }

    private double widthFor(String columnName) {
        return switch (columnName) {
            case "状态", "暂停", "活跃任务", "重启次数", "Phase", "Ready", "类型", "Age" -> 90;
            case "名称" -> 220;
            case "命名空间", "Storage Class", "访问模式", "Cluster IP", "Controller" -> 140;
            case "镜像", "标签", "Message", "Subjects", "Endpoints", "内部端点", "外部端点" -> 260;
            case "CPU 可分配", "CPU 容量", "内存 可分配", "内存 容量", "Pods 容量" -> 160;
            default -> 130;
        };
    }

    private TableCell<K8sRow, String> createCopyableCell() {
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
                    K8sRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        resourceTable.getSelectionModel().clearAndSelect(getIndex());
                        createContextMenu(row).show(textField, event.getScreenX(), event.getScreenY());
                    }
                    event.consume();
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
                textField.setText(item == null ? "" : item);
                updateTooltip();
                setText(null);
                setGraphic(textField);
            }

            private void updateTooltip() {
                String value = textField.getText();
                if (value == null || value.isBlank()) {
                    textField.setTooltip(null);
                    return;
                }
                Text helper = new Text(value);
                helper.setFont(textField.getFont());
                double available = Math.max(0, getWidth() - 18);
                if (helper.getLayoutBounds().getWidth() > available) {
                    tooltip.setText(value);
                    textField.setTooltip(tooltip);
                } else {
                    textField.setTooltip(null);
                }
            }
        };
    }

    private void selectCategory(Category category) {
        if (expandedCategory == category) {
            expandedCategory = null;
            updateSidebarStyle();
            return;
        }
        expandedCategory = category;
        selectResource(category.children().get(0));
    }

    private void selectResource(ResourceKind kind) {
        activeKind = kind;
        if (expandedCategory != kind.category()) {
            expandedCategory = kind.category();
        }
        updateSidebarStyle();
        renderRows();
    }

    private void updateSidebarStyle() {
        for (Category category : Category.values()) {
            boolean expanded = category == expandedCategory;
            Button categoryButton = categoryButtons.get(category);
            VBox children = childContainers.get(category);
            categoryButton.getStyleClass().remove("active");
            if (expanded) {
                categoryButton.getStyleClass().add("active");
            }
            setVisibleManaged(children, expanded);
            updateDisclosure(categoryButton, expanded);

            for (javafx.scene.Node node : children.getChildren()) {
                if (node instanceof Button button) {
                    button.getStyleClass().remove("active");
                    ResourceKind kind = ResourceKind.fromLabel(button.getText());
                    if (kind == activeKind) {
                        button.getStyleClass().add("active");
                    }
                }
            }
        }
    }

    private void updateDisclosure(Button categoryButton, boolean expanded) {
        if (!(categoryButton.getGraphic() instanceof HBox graphic)) {
            return;
        }
        if (graphic.getChildren().size() < 3 || !(graphic.getChildren().get(2) instanceof FontIcon icon)) {
            return;
        }
        icon.setIconLiteral(expanded ? "fas-chevron-down" : "fas-chevron-right");
    }

    private void renderRows() {
        rebuildColumns();
        refreshRowsForCurrentKind(true);
    }

    private void refreshVisibleContent() {
        refreshForCurrentConnection();
    }

    private void refreshForCurrentConnection() {
        ConnectionContext context = boundConnection();
        ConnInfo connInfo = context == null ? null : context.connInfo();
        String connId = context == null ? boundConnId : context.connId();
        if (connId == null || connInfo == null || !ConnectionManager.getInstance().isConnected(connId)) {
            if (activeConnId != null) {
                sessionManager.closeSession(activeConnId);
            }
            activeConnId = null;
            currentSnapshot = null;
            namespaceInitialized = false;
            setVersionText("-");
            setStatusText("未连接");
            showEmptyState();
            return;
        }

        if (!Objects.equals(activeConnId, connId)) {
            if (activeConnId != null) {
                sessionManager.closeSession(activeConnId);
            }
            activeConnId = connId;
            currentSnapshot = null;
            namespaceInitialized = false;
            allRows.clear();
            visibleRows.clear();
            setStatusText("正在连接 Kubernetes...");
        }

        K8sSessionManager.K8sSnapshot cached = sessionManager.getCachedSnapshot(connId);
        boolean hasCached = cached != null;
        if (cached != null) {
            applySnapshot(cached);
            setStatusText("显示缓存，正在刷新...");
            refreshRowsForCurrentKind(false);
        } else {
            setStatusText("正在读取 Kubernetes 命名空间...");
        }

        long snapshotToken = ++snapshotSerial;
        sessionManager.refreshSnapshot(connId, connInfo).thenAccept(snapshot ->
                Platform.runLater(() -> {
                    if (snapshotToken != snapshotSerial || !Objects.equals(activeConnId, connId)) {
                        return;
                    }
                    applySnapshot(snapshot);
                    if (!hasCached) {
                        refreshRowsForCurrentKind(false);
                    }
                }));
    }

    private void refreshRowsForCurrentKind(boolean resetPage) {
        ConnectionContext context = boundConnection();
        ConnInfo connInfo = context == null ? null : context.connInfo();
        String connId = context == null ? boundConnId : context.connId();
        if (connId == null || connInfo == null || !ConnectionManager.getInstance().isConnected(connId)) {
            showEmptyState();
            return;
        }
        if (!Objects.equals(activeConnId, connId)) {
            if (activeConnId != null) {
                sessionManager.closeSession(activeConnId);
            }
            activeConnId = connId;
            namespaceInitialized = false;
        }

        long rowToken = ++rowSerial;
        setStatusText("正在加载 " + activeKind.label() + "...");
        String namespace = namespaceCombo.getValue();
        String listNamespace = namespace == null || namespace.isBlank() || Objects.equals(namespace, ALL_NAMESPACES)
                ? ""
                : namespace;
        sessionManager.listResources(connId, connInfo, activeKind.kubectlType(), activeKind.namespaced(), listNamespace)
                .thenAccept(result -> Platform.runLater(() -> {
                    if (rowToken != rowSerial || !Objects.equals(activeConnId, connId)) {
                        return;
                    }
                    if (result == null || !result.success()) {
                        allRows.clear();
                        visibleRows.clear();
                        totalInfoLabel.setText("总数 0");
                        currentPageLabel.setText("第 0/1 页");
                        if (result != null && result.errorMessage() != null && !result.errorMessage().isBlank()) {
                            String message = result.errorMessage();
                            setStatusText(message);
                            LOGGER.error("load kubernetes resources failed: kind={}, namespace={}, error={}",
                                    activeKind.kubectlType(), listNamespace.isBlank() ? "<all/current>" : listNamespace, message);
                        }
                        return;
                    }
                    List<K8sRow> rows = new ArrayList<>(result.items().size());
                    for (JsonNode item : result.items()) {
                        rows.add(new K8sRow(activeKind, valuesFor(activeKind, item), item));
                    }
                    rows.sort(this::compareByCreationTimeDesc);
                    allRows.setAll(rows);
                    if (resetPage) {
                        currentPageIndex = 0;
                    }
                    applyFilters();
                    setStatusText(activeKind.label() + " 已加载：" + rows.size());
                }));
    }

    private void applySnapshot(K8sSessionManager.K8sSnapshot snapshot) {
        currentSnapshot = snapshot;
        if (snapshot == null) {
            setVersionText("-");
            setStatusText("未连接");
            return;
        }
        updateNamespaceChoices(snapshot.namespaces());
        setVersionText(snapshotVersionText(snapshot));
        if (!snapshot.kubectlAvailable()) {
            String message = firstNonBlank(snapshot.errorMessage(), "kubectl 不可用");
            setStatusText(message);
            LOGGER.error("refresh kubernetes snapshot failed: {}", message);
        }
    }

    private void updateNamespaceChoices(List<String> namespaces) {
        List<String> namespaceItems = new ArrayList<>();
        if (namespaces != null) {
            namespaces.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .sorted(String::compareToIgnoreCase)
                    .forEach(namespaceItems::add);
        }
        List<String> items = new ArrayList<>(namespaceItems);
        items.add(ALL_NAMESPACES);
        String previous = namespaceCombo.getValue();
        String defaultSelection = namespaceItems.isEmpty() ? ALL_NAMESPACES : namespaceItems.get(0);
        boolean defaultToFirstNamespace = !namespaceInitialized && !namespaceItems.isEmpty();
        updatingNamespaceChoices = true;
        try {
            namespaceCombo.setItems(FXCollections.observableArrayList(items));
            if (previous != null
                    && items.contains(previous)
                    && !(defaultToFirstNamespace && Objects.equals(previous, ALL_NAMESPACES))) {
                namespaceCombo.getSelectionModel().select(previous);
            } else {
                namespaceCombo.getSelectionModel().select(defaultSelection);
            }
        } finally {
            updatingNamespaceChoices = false;
        }
        if (!namespaceItems.isEmpty()) {
            namespaceInitialized = true;
        }
    }

    private String snapshotVersionText(K8sSessionManager.K8sSnapshot snapshot) {
        if (snapshot == null) {
            return "-";
        }
        if (!snapshot.kubectlAvailable()) {
            return "-";
        }
        String client = snapshot.clientVersion();
        String server = snapshot.serverVersion();
        if (!client.isBlank() && !server.isBlank()) {
            return server + " / " + client;
        }
        return firstNonBlank(server, firstNonBlank(client, "-"));
    }

    private void setVersionText(String value) {
        lblK8sVersion.setText(firstNonBlank(value, "-"));
    }

    private void setStatusText(String value) {
        if (lblK8sStatus != null) {
            String fullText = firstNonBlank(value, "-");
            statusDetailText = fullText;
            lblK8sStatus.setText(statusSummary(fullText));
            lblK8sStatus.setTooltip(new Tooltip(hasStatusDetail(fullText) ? "点击查看完整信息" : fullText));
            lblK8sStatus.setCursor(hasStatusDetail(fullText) ? Cursor.HAND : Cursor.DEFAULT);
            if (k8sSideStatus != null) {
                k8sSideStatus.setCursor(hasStatusDetail(fullText) ? Cursor.HAND : Cursor.DEFAULT);
            }
        }
    }

    private void configureStatusLabel() {
        if (lblK8sStatus == null) {
            return;
        }
        if (k8sSideStatus != null) {
            k8sSideStatus.setOnMouseClicked(this::showStatusDetail);
        }
        lblK8sStatus.setWrapText(false);
        lblK8sStatus.setTextOverrun(OverrunStyle.ELLIPSIS);
        lblK8sStatus.setMaxWidth(Double.MAX_VALUE);
        lblK8sStatus.setPickOnBounds(true);
    }

    private void showStatusDetail(MouseEvent event) {
        showStatusDetail();
        if (event != null) {
            event.consume();
        }
    }

    private void showStatusDetail() {
        if (!hasStatusDetail(statusDetailText)) {
            return;
        }
        TextArea area = new TextArea(statusDetailText);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefColumnCount(100);
        area.setPrefRowCount(18);
        DialogHelper.<Void>showCustomDialog("Kubernetes 状态详情", area, List.of(
                new DialogHelper.CustomDialogButton<>("确定", ButtonBar.ButtonData.OK_DONE, dialog -> null)
        ));
    }

    private boolean hasStatusDetail(String value) {
        String text = firstNonBlank(value, "-");
        String lower = text.toLowerCase(Locale.ROOT);
        return text.contains("\n")
                || text.contains("\r")
                || text.length() > 80
                || lower.contains("error")
                || lower.contains("failed")
                || lower.contains("refused")
                || lower.contains("forbidden")
                || lower.contains("unauthorized")
                || lower.contains("timeout")
                || text.contains("失败")
                || text.contains("错误")
                || text.contains("不可用")
                || text.contains("未授权")
                || text.contains("权限不足");
    }

    private String statusSummary(String value) {
        String text = firstNonBlank(value, "-").replace("\r", "\n");
        if (text.equals("-")) {
            return text;
        }
        String compact = text.replaceAll("(?m)^[EWI]\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+[^]]+]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        String lower = compact.toLowerCase(Locale.ROOT);
        if (lower.contains("connection refused")) {
            return "加载失败：connection refused";
        }
        if (lower.contains("forbidden")) {
            return "加载失败：权限不足";
        }
        if (lower.contains("unauthorized")) {
            return "加载失败：未授权";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "加载失败：连接超时";
        }
        if (lower.contains("no such host")) {
            return "加载失败：无法解析主机";
        }
        if (compact.length() <= 80) {
            return compact;
        }
        return compact.substring(0, 77) + "...";
    }

    private void showEmptyState() {
        rebuildColumns();
        allRows.clear();
        visibleRows.clear();
        currentPageIndex = 0;
        applyFilters();
        setVersionText(currentSnapshot == null ? "-" : snapshotVersionText(currentSnapshot));
    }

    private void applyFilters() {
        List<K8sRow> filtered = filteredRows();
        int pageCount = pageCount(filtered);
        if (currentPageIndex >= pageCount) {
            currentPageIndex = pageCount - 1;
        }
        if (currentPageIndex < 0) {
            currentPageIndex = 0;
        }
        updatePage(filtered);
    }

    private List<K8sRow> filteredRows() {
        String query = searchBox.getText() == null
                ? ""
                : searchBox.getText().trim().toLowerCase(Locale.ROOT);
        String namespace = namespaceCombo.getValue();
        boolean namespaceFiltered = namespace != null
                && !namespace.isBlank()
                && !Objects.equals(namespace, ALL_NAMESPACES);

        List<K8sRow> filtered = new ArrayList<>();
        for (K8sRow row : allRows) {
            if (namespaceFiltered && row.kind().namespaced() && !Objects.equals(row.value("命名空间"), namespace)) {
                continue;
            }
            if (query.isEmpty() || row.contains(query)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private void updatePage(List<K8sRow> filtered) {
        int pageCount = pageCount(filtered);
        int from = Math.min(currentPageIndex * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        visibleRows.setAll(filtered.subList(from, to));
        resourceTable.getSelectionModel().clearSelection();
        totalInfoLabel.setText("总数 " + filtered.size());
        currentPageLabel.setText("第 " + (filtered.isEmpty() ? 0 : currentPageIndex + 1) + "/" + pageCount + " 页");
        firstPageButton.setDisable(currentPageIndex <= 0 || filtered.isEmpty());
        prevPageButton.setDisable(currentPageIndex <= 0 || filtered.isEmpty());
        nextPageButton.setDisable(currentPageIndex >= pageCount - 1 || filtered.isEmpty());
        lastPageButton.setDisable(currentPageIndex >= pageCount - 1 || filtered.isEmpty());
    }

    private int pageCount(List<K8sRow> rows) {
        return Math.max(1, (int) Math.ceil(rows.size() / (double) PAGE_SIZE));
    }

    private void goToPage(int pageIndex) {
        List<K8sRow> filtered = filteredRows();
        int pageCount = pageCount(filtered);
        currentPageIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
        updatePage(filtered);
    }

    private void jumpToPage() {
        String text = jumpPageField.getText() == null ? "" : jumpPageField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            goToPage(Integer.parseInt(text) - 1);
        } catch (NumberFormatException ignored) {
            jumpPageField.clear();
        }
    }

    private ContextMenu createContextMenu(K8sRow row) {
        ContextMenu menu = new ContextMenu();
        for (Action action : row.kind().actions()) {
            MenuItem item = new MenuItem(action.label());
            item.setOnAction(event -> showResourceAction(action, row));
            menu.getItems().add(item);
        }
        return menu;
    }

    private void copySelectedRowsToClipboard() {
        List<K8sRow> selected = new ArrayList<>(resourceTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        List<String> columns = activeKind.columns();
        StringBuilder builder = new StringBuilder(String.join("\t", columns));
        for (K8sRow row : selected) {
            builder.append(System.lineSeparator());
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    builder.append('\t');
                }
                builder.append(row.value(columns.get(i)));
            }
        }
        copyText(builder.toString());
    }

    private void copyText(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value == null ? "" : value);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void createResource() {
        ConnectionContext context = requireConnection("新建资源");
        if (context == null) {
            return;
        }
        String template = resourceTemplate(activeKind, selectedNamespace());
        Optional<String> yaml = showYamlEditor("新建 " + activeKind.label(), template);
        if (yaml.isEmpty()) {
            return;
        }
        setStatusText("正在创建...");
        sessionManager.applyYaml(context.connId(), context.connInfo(), yaml.get()).thenAccept(result ->
                Platform.runLater(() -> handleMutationResult("创建资源", result)));
    }

    private void showResourceAction(Action action, K8sRow row) {
        if (action == Action.DETAIL) {
            showDetailWindow(row);
            return;
        }
        switch (action) {
            case LOGS -> showLogs(row);
            case EXEC -> execIntoPod(row);
            case EDIT -> editResource(row);
            case DELETE -> deleteResource(row);
            case SCALE -> scaleResource(row);
            case RESTART -> restartResource(row);
            case TRIGGER -> triggerCronJob(row);
            default -> DialogHelper.showInfo("Kubernetes",
                    action.label() + ": " + row.kind().label() + " / " + row.value("名称")
                            + "\n\n等效操作: " + kubectlHint(action, row));
        }
    }

    private void showDetailWindow(K8sRow row) {
        ConnectionContext context = requireConnection("查看详情");
        if (context == null) {
            return;
        }

        long serial = ++detailSerial;
        CompletableFuture<SshService.CommandResult> describeFuture = sessionManager.describe(
                context.connId(), context.connInfo(), row.kind().kubectlType(), rowNamespace(row), rowName(row), row.kind().namespaced());
        CompletableFuture<SshService.CommandResult> yamlFuture = sessionManager.getYaml(
                context.connId(), context.connInfo(), row.kind().kubectlType(), rowNamespace(row), rowName(row), row.kind().namespaced());
        CompletableFuture<SshService.CommandResult> eventsFuture = sessionManager.events(
                context.connId(), context.connInfo(), rowNamespace(row), rowName(row), row.kind().namespaced());

        CompletableFuture.allOf(describeFuture, yamlFuture, eventsFuture).thenRun(() ->
                Platform.runLater(() -> {
                    if (serial != detailSerial) {
                        return;
                    }
                    SshService.CommandResult describe = describeFuture.join();
                    SshService.CommandResult yaml = yamlFuture.join();
                    SshService.CommandResult events = eventsFuture.join();

                    List<K8sDetailController.DetailActionSpec> actionSpecs = new ArrayList<>();
                    for (Action action : row.kind().actions()) {
                        if (action == Action.DETAIL) {
                            continue;
                        }
                        actionSpecs.add(new K8sDetailController.DetailActionSpec(
                                action.label(),
                                kubectlHint(action, row),
                                action == Action.EDIT || action == Action.SCALE || action == Action.TRIGGER
                        ));
                    }

                    List<String> eventLines = parseEventLines(events);
                    List<K8sDetailController.DetailSectionSpec> sections = new ArrayList<>();
                    sections.add(K8sDetailController.DetailSectionSpec.kv("资源摘要", resourceSummaryFor(row)));
                    sections.add(K8sDetailController.DetailSectionSpec.text("资源描述", commandMessage(describe)));

                    K8sDetailController.DetailPageData data = new K8sDetailController.DetailPageData(
                            row.kind().label(),
                            row.kind().label() + "详情",
                            row.value("名称") + namespaceSubtitle(row),
                            metadataFor(row),
                            resourceInfoFor(row),
                            sections,
                            actionSpecs,
                            eventLines,
                            commandMessage(yaml),
                            spec -> handleDetailAction(row, spec)
                    );

                    K8sDetailController.show(resourceTable.getScene() == null ? null : resourceTable.getScene().getWindow(), data);
                }));
    }

    private void handleDetailAction(K8sRow row, K8sDetailController.DetailActionSpec spec) {
        Action action = Action.fromLabel(spec.label());
        if (action == null) {
            DialogHelper.showInfo("Kubernetes", spec.label() + "\n\n等效操作: " + spec.hint());
            return;
        }
        showResourceAction(action, row);
    }

    private void showLogs(K8sRow row) {
        ConnectionContext context = requireConnection("查看日志");
        if (context == null) {
            return;
        }
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

        Dialog<Void> dialog = DialogHelper.createCustomDialog("查看日志 - " + row.value("名称"), logView, List.of(
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
            setStatusText("日志已关闭");
        });

        setStatusText("正在读取日志...");
        dialog.show();
        installLogScrollTracking(logView, followTail, scrollTrackingInstalled, 0);

        CompletableFuture<SshService.RemoteCommandHandle> future = sessionManager.followLogs(
                context.connId(),
                context.connInfo(),
                row.kind().kubectlType(),
                rowNamespace(row),
                rowName(row),
                row.kind().namespaced(),
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
                    setStatusText("日志已结束");
                } else {
                    setStatusText("日志读取失败");
                    appendLogChunk(logView, buffer, "\n" + commandMessage(result), followTail, scrollTrackingInstalled);
                }
            }));
        }).exceptionally(error -> {
            Platform.runLater(() -> {
                if (!closed.get()) {
                    setStatusText("日志读取失败");
                    appendLogChunk(logView, buffer, "\n" + errorMessage(error), followTail, scrollTrackingInstalled);
                }
            });
            return null;
        });
    }

    private void execIntoPod(K8sRow row) {
        ConnectionContext context = requireConnection("执行");
        if (context == null) {
            return;
        }
        if (row.kind() != ResourceKind.PODS) {
            DialogHelper.showWarning("Kubernetes", "执行仅支持 Pod。");
            return;
        }
        TerminalPanelController terminalController = ConnectionManager.getInstance().getTerminalPanelController(context.connId());
        if (terminalController == null) {
            DialogHelper.showWarning("执行", "当前连接没有可用终端");
            return;
        }
        String command = "kubectl exec -it -n " + shellArg(rowNamespace(row)) + " "
                + shellArg(rowName(row)) + " -- sh";
        if (terminalController.executeShellCommand(command)) {
            setStatusText("已进入 Pod 终端");
        } else {
            DialogHelper.showWarning("执行", "终端未就绪");
        }
    }

    private void editResource(K8sRow row) {
        ConnectionContext context = requireConnection("编辑资源");
        if (context == null) {
            return;
        }
        setStatusText("正在读取资源配置...");
        CompletableFuture<SshService.CommandResult> yamlFuture = sessionManager.getYaml(
                context.connId(), context.connInfo(), row.kind().kubectlType(),
                rowNamespace(row), rowName(row), row.kind().namespaced());
        CompletableFuture<SshService.CommandResult> jsonFuture = sessionManager.getJson(
                context.connId(), context.connInfo(), row.kind().kubectlType(),
                rowNamespace(row), rowName(row), row.kind().namespaced());
        CompletableFuture.allOf(yamlFuture, jsonFuture).thenRun(() ->
                Platform.runLater(() -> {
                    SshService.CommandResult yamlResult = yamlFuture.join();
                    SshService.CommandResult jsonResult = jsonFuture.join();
                    if (!yamlResult.isSuccess()) {
                        showCommandResult(row.value("名称"), yamlResult);
                        return;
                    }
                    if (!jsonResult.isSuccess()) {
                        showCommandResult(row.value("名称"), jsonResult);
                        return;
                    }
                    Optional<String> resourceText = showResourceEditor(
                            "编辑 " + row.kind().label() + " / " + row.value("名称"),
                            yamlResult.stdout(), jsonResult.stdout());
                    if (resourceText.isEmpty()) {
                        setStatusText("已取消编辑");
                        return;
                    }
                    setStatusText("正在应用资源配置...");
                    sessionManager.applyYaml(context.connId(), context.connInfo(), resourceText.get()).thenAccept(applyResult ->
                            Platform.runLater(() -> handleMutationResult("编辑资源", applyResult)));
                }));
    }

    private void deleteResource(K8sRow row) {
        ConnectionContext context = requireConnection("删除资源");
        if (context == null) {
            return;
        }
        if (!DialogHelper.showConfirm("删除资源",
                "确定要删除 " + row.kind().label() + " / " + row.value("名称") + " 吗？")) {
            return;
        }
        setStatusText("正在删除...");
        sessionManager.delete(context.connId(), context.connInfo(), row.kind().kubectlType(),
                        rowNamespace(row), rowName(row), row.kind().namespaced())
                .thenAccept(result -> Platform.runLater(() -> handleMutationResult("删除资源", result)));
    }

    private void scaleResource(K8sRow row) {
        ConnectionContext context = requireConnection("扩缩容");
        if (context == null) {
            return;
        }
        Optional<Integer> value = showScaleDialog(row);
        if (value.isEmpty()) {
            return;
        }
        setStatusText("正在扩缩容...");
        sessionManager.scale(context.connId(), context.connInfo(), row.kind().kubectlType(),
                        rowNamespace(row), rowName(row), row.kind().namespaced(), value.get())
                .thenAccept(result -> Platform.runLater(() -> handleMutationResult("扩缩容", result)));
    }

    private void restartResource(K8sRow row) {
        ConnectionContext context = requireConnection("重启");
        if (context == null) {
            return;
        }
        if (!DialogHelper.showConfirm("重启", "确定要重启 " + row.kind().label() + " / " + row.value("名称") + " 吗？")) {
            return;
        }
        setStatusText("正在重启...");
        sessionManager.rolloutRestart(context.connId(), context.connInfo(), row.kind().kubectlType(),
                        rowNamespace(row), rowName(row), row.kind().namespaced())
                .thenAccept(result -> Platform.runLater(() -> handleMutationResult("重启", result)));
    }

    private void triggerCronJob(K8sRow row) {
        ConnectionContext context = requireConnection("触发执行");
        if (context == null) {
            return;
        }
        if (!DialogHelper.showConfirm("触发执行", "确定要基于该 CronJob 创建一次性 Job 吗？")) {
            return;
        }
        setStatusText("正在触发...");
        sessionManager.triggerCronJob(context.connId(), context.connInfo(), rowNamespace(row), rowName(row))
                .thenAccept(result -> Platform.runLater(() -> handleMutationResult("触发执行", result)));
    }

    private void handleMutationResult(String title, SshService.CommandResult result) {
        if (result != null && result.isSuccess()) {
            setStatusText(title + "完成");
            DialogHelper.showInfoWithHeader(title, title, commandMessage(result));
            refreshRowsForCurrentKind(false);
        } else {
            setStatusText(commandMessage(result));
            DialogHelper.showError(title, commandMessage(result));
        }
    }

    private void showCommandResult(String header, SshService.CommandResult result) {
        if (result != null && result.isSuccess()) {
            setStatusText("编辑资源" + "已读取");
            DialogHelper.showInfoWithHeader("编辑资源", header, commandMessage(result));
        } else {
            setStatusText(commandMessage(result));
            DialogHelper.showError("编辑资源", commandMessage(result));
        }
    }

    private Optional<String> showYamlEditor(String title, String yaml) {
        TextArea editor = resourceEditorTextArea(yaml);
        return DialogHelper.showCustomDialog(title, editor, List.of(
                new DialogHelper.CustomDialogButton<>(
                        "创建",
                        ButtonBar.ButtonData.OK_DONE,
                        dialog -> editor.getText()
                ),
                new DialogHelper.CustomDialogButton<>(
                        "取消",
                        ButtonBar.ButtonData.CANCEL_CLOSE,
                        dialog -> null
                )
        ));
    }

    private Optional<String> showResourceEditor(String title, String yaml, String json) {
        TextArea editor = resourceEditorTextArea(yaml);
        ToggleGroup formatGroup = new ToggleGroup();
        ToggleButton yamlButton = new ToggleButton("YAML");
        ToggleButton jsonButton = new ToggleButton("JSON");
        yamlButton.setToggleGroup(formatGroup);
        jsonButton.setToggleGroup(formatGroup);
        yamlButton.getStyleClass().add("format-toggle-button");
        jsonButton.getStyleClass().add("format-toggle-button");
        yamlButton.setSelected(true);

        HBox formatBar = new HBox(yamlButton, jsonButton);
        formatBar.getStyleClass().add("format-toggle-bar");
        formatBar.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(formatBar, editor);
        content.setFillWidth(true);
        VBox.setVgrow(editor, Priority.ALWAYS);

        final ResourceEditFormat[] currentFormat = {ResourceEditFormat.YAML};
        final boolean[] updatingFormat = {false};
        formatGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (updatingFormat[0]) {
                return;
            }
            if (newToggle == null) {
                updatingFormat[0] = true;
                try {
                    oldToggle.setSelected(true);
                } finally {
                    updatingFormat[0] = false;
                }
                return;
            }
            ResourceEditFormat targetFormat = newToggle == jsonButton ? ResourceEditFormat.JSON : ResourceEditFormat.YAML;
            if (targetFormat == currentFormat[0]) {
                return;
            }
            editor.setText(targetFormat == ResourceEditFormat.JSON ? firstNonBlank(json, "") : firstNonBlank(yaml, ""));
            currentFormat[0] = targetFormat;
        });

        return DialogHelper.showCustomDialog(title, content, List.of(
                new DialogHelper.CustomDialogButton<>(
                        "应用",
                        ButtonBar.ButtonData.OK_DONE,
                        dialog -> editor.getText()
                ),
                new DialogHelper.CustomDialogButton<>(
                        "取消",
                        ButtonBar.ButtonData.CANCEL_CLOSE,
                        dialog -> null
                )
        ));
    }

    private TextArea resourceEditorTextArea(String content) {
        TextArea editor = new TextArea(content == null ? "" : content);
        editor.setWrapText(false);
        editor.setPrefColumnCount(110);
        editor.setPrefRowCount(28);
        editor.getStyleClass().add("detail-yaml");
        return editor;
    }

    private Optional<Integer> showScaleDialog(K8sRow row) {
        Spinner<Integer> replicas = new Spinner<>();
        replicas.setEditable(true);
        replicas.setPrefWidth(140);
        replicas.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                0, 10000, currentReplicas(row), 1));

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.add(new Label("资源"), 0, 0);
        grid.add(new Label(row.kind().label() + " / " + row.value("名称")), 1, 0);
        grid.add(new Label("当前副本数"), 0, 1);
        grid.add(new Label(row.value("Pods")), 1, 1);
        grid.add(new Label("副本数"), 0, 2);
        grid.add(replicas, 1, 2);

        return DialogHelper.showCustomDialog("扩缩容", grid, List.of(
                new DialogHelper.CustomDialogButton<>("确定", ButtonBar.ButtonData.OK_DONE,
                        dialog -> replicas.getValue()),
                new DialogHelper.CustomDialogButton<>("取消", ButtonBar.ButtonData.CANCEL_CLOSE,
                        dialog -> null)
        ), "custom-dialog-content-body");
    }

    private int currentReplicas(K8sRow row) {
        JsonNode raw = row.raw();
        if (raw != null) {
            JsonNode specReplicas = raw.path("spec").path("replicas");
            if (specReplicas.isInt()) {
                return Math.max(0, specReplicas.asInt());
            }
        }
        String pods = row.value("Pods");
        if (pods != null && pods.contains("/")) {
            String desired = pods.substring(pods.indexOf('/') + 1).trim();
            try {
                return Math.max(0, Integer.parseInt(desired));
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
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

    private String shellArg(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private ConnectionContext requireConnection(String title) {
        ConnectionContext context = boundConnection();
        if (context == null) {
            DialogHelper.showWarning(title, "请先连接一台可执行 kubectl 的 SSH 主机。");
            return null;
        }
        activeConnId = context.connId();
        return context;
    }

    private ConnectionContext boundConnection() {
        ConnectionManager connectionManager = ConnectionManager.getInstance();
        String connId = boundConnId;
        if (connId == null || !connectionManager.isConnected(connId)) {
            return null;
        }
        SshService service = connectionManager.getConnectionById(connId);
        ConnInfo connInfo = service == null ? null : service.getConnInfo();
        return connInfo == null ? null : new ConnectionContext(connId, connInfo);
    }

    private String rowName(K8sRow row) {
        return row.value("名称");
    }

    private String rowNamespace(K8sRow row) {
        String namespace = row.value("命名空间");
        if (namespace == null || namespace.isBlank() || "-".equals(namespace)) {
            return "default";
        }
        return namespace;
    }

    private String selectedNamespace() {
        String selected = namespaceCombo.getValue();
        if (selected == null || selected.isBlank() || ALL_NAMESPACES.equals(selected)) {
            return "default";
        }
        return selected;
    }

    private String commandMessage(SshService.CommandResult result) {
        if (result == null) {
            return "";
        }
        String stdout = result.stdout() == null ? "" : result.stdout().trim();
        String stderr = result.stderr() == null ? "" : result.stderr().trim();
        if (!stdout.isBlank() && !stderr.isBlank()) {
            return stdout + "\n" + stderr;
        }
        if (!stdout.isBlank()) {
            return stdout;
        }
        if (!stderr.isBlank()) {
            return stderr;
        }
        return result.isSuccess() ? "执行成功" : "执行失败";
    }

    private List<String> parseEventLines(SshService.CommandResult result) {
        if (result == null || result.stdout() == null || result.stdout().isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(result.stdout());
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                return List.of();
            }
            List<String> events = new ArrayList<>();
            for (JsonNode item : items) {
                String type = item.path("type").asText("-");
                String reason = item.path("reason").asText("-");
                String time = firstNonBlank(
                        ageSince(firstNonBlank(item.path("lastTimestamp").asText(""),
                                item.path("eventTime").asText(""))),
                        "-"
                );
                String message = item.path("message").asText("");
                events.add(type + "  " + reason + "  " + time + "  " + message);
            }
            return events;
        } catch (Exception e) {
            return result.stdout().lines()
                    .filter(line -> !line.isBlank())
                    .limit(50)
                    .toList();
        }
    }

    private Map<String, String> resourceSummaryFor(K8sRow row) {
        Map<String, String> summary = new LinkedHashMap<>(resourceInfoFor(row));
        summary.put("kubectl", "kubectl get " + row.kind().kubectlType()
                + (row.kind().namespaced() ? " -n " + rowNamespace(row) : "")
                + " " + rowName(row) + " -o yaml");
        return summary;
    }

    private String resourceTemplate(ResourceKind kind, String namespace) {
        String name = kind.namePrefix() + "-example";
        String nsLine = kind.namespaced() ? "  namespace: " + firstNonBlank(namespace, "default") + "\n" : "";
        return switch (kind) {
            case PODS -> """
                    apiVersion: v1
                    kind: Pod
                    metadata:
                      name: %s
                    %sspec:
                      containers:
                        - name: app
                          image: nginx:latest
                    """.formatted(name, nsLine);
            case DEPLOYMENTS -> """
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: %s
                    %sspec:
                      replicas: 1
                      selector:
                        matchLabels:
                          app: %s
                      template:
                        metadata:
                          labels:
                            app: %s
                        spec:
                          containers:
                            - name: app
                              image: nginx:latest
                    """.formatted(name, nsLine, name, name);
            case SERVICES -> """
                    apiVersion: v1
                    kind: Service
                    metadata:
                      name: %s
                    %sspec:
                      selector:
                        app: %s
                      ports:
                        - port: 80
                          targetPort: 80
                    """.formatted(name, nsLine, name);
            case CONFIG_MAPS -> """
                    apiVersion: v1
                    kind: ConfigMap
                    metadata:
                      name: %s
                    %sdata:
                      config.yaml: |
                        key: value
                    """.formatted(name, nsLine);
            default -> """
                    apiVersion: %s
                    kind: %s
                    metadata:
                      name: %s
                    %s
                    """.formatted(apiVersionFor(kind), manifestKindFor(kind), name, nsLine);
        };
    }

    private String apiVersionFor(ResourceKind kind) {
        return switch (kind) {
            case CRON_JOBS, JOBS -> "batch/v1";
            case DAEMON_SETS, DEPLOYMENTS, REPLICA_SETS, STATEFUL_SETS -> "apps/v1";
            case INGRESSES, INGRESS_CLASSES, NETWORK_POLICIES -> "networking.k8s.io/v1";
            case STORAGE_CLASSES -> "storage.k8s.io/v1";
            case CLUSTER_ROLE_BINDINGS, CLUSTER_ROLES, ROLE_BINDINGS, ROLES -> "rbac.authorization.k8s.io/v1";
            default -> "v1";
        };
    }

    private String manifestKindFor(ResourceKind kind) {
        return switch (kind) {
            case CRON_JOBS -> "CronJob";
            case DAEMON_SETS -> "DaemonSet";
            case DEPLOYMENTS -> "Deployment";
            case JOBS -> "Job";
            case PODS -> "Pod";
            case REPLICA_SETS -> "ReplicaSet";
            case REPLICATION_CONTROLLERS -> "ReplicationController";
            case STATEFUL_SETS -> "StatefulSet";
            case INGRESSES -> "Ingress";
            case INGRESS_CLASSES -> "IngressClass";
            case SERVICES -> "Service";
            case CONFIG_MAPS -> "ConfigMap";
            case PERSISTENT_VOLUME_CLAIMS -> "PersistentVolumeClaim";
            case SECRETS -> "Secret";
            case STORAGE_CLASSES -> "StorageClass";
            case CLUSTER_ROLE_BINDINGS -> "ClusterRoleBinding";
            case CLUSTER_ROLES -> "ClusterRole";
            case EVENTS -> "Event";
            case NAMESPACES -> "Namespace";
            case NETWORK_POLICIES -> "NetworkPolicy";
            case NODES -> "Node";
            case PERSISTENT_VOLUMES -> "PersistentVolume";
            case ROLE_BINDINGS -> "RoleBinding";
            case ROLES -> "Role";
            case SERVICE_ACCOUNTS -> "ServiceAccount";
        };
    }

    private String namespaceSubtitle(K8sRow row) {
        String namespace = row.value("命名空间");
        if (namespace == null || namespace.isBlank() || "-".equals(namespace)) {
            return "";
        }
        return " / " + namespace;
    }

    private Map<String, String> metadataFor(K8sRow row) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("名称", row.value("名称"));
        if (row.kind().namespaced()) {
            metadata.put("命名空间", row.value("命名空间"));
        }
        metadata.put("标签", firstNonBlank(row.value("标签"), "-"));
        metadata.put("注解", annotationsFor(row.raw()));
        metadata.put("创建时间", firstNonBlank(nodeText(row.raw(), "metadata", "creationTimestamp"),
                firstNonBlank(row.value("创建时间"), row.value("Age"))));
        return metadata;
    }

    private Map<String, String> resourceInfoFor(K8sRow row) {
        if (row.kind() == ResourceKind.PODS) {
            return row(
                    "Node", firstNonBlank(row.value("节点"), "node-1"),
                    "Status", firstNonBlank(row.value("状态"), "Running"),
                    "IP", firstNonBlank(nodeText(row.raw(), "status", "podIP"), "-"),
                    "QoS Class", firstNonBlank(nodeText(row.raw(), "status", "qosClass"), "-"),
                    "Restarts", firstNonBlank(row.value("重启次数"), "0"),
                    "Service Account", firstNonBlank(nodeText(row.raw(), "spec", "serviceAccountName"), "default"),
                    "Image", firstNonBlank(row.value("镜像"), "-")
            );
        }
        if (row.kind() == ResourceKind.NODES) {
            return row(
                    "Ready", firstNonBlank(row.value("Ready"), row.value("状态")),
                    "CPU 可分配", firstNonBlank(row.value("CPU 可分配"), "-"),
                    "CPU 容量", firstNonBlank(row.value("CPU 容量"), "-"),
                    "内存 可分配", firstNonBlank(row.value("内存 可分配"), "-"),
                    "内存 容量", firstNonBlank(row.value("内存 容量"), "-"),
                    "Pods 容量", firstNonBlank(row.value("Pods 容量"), "-")
            );
        }
        Map<String, String> info = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : row.values().entrySet()) {
            String key = entry.getKey();
            if ("名称".equals(key) || "命名空间".equals(key) || "标签".equals(key) || "创建时间".equals(key)) {
                continue;
            }
            info.put(key, entry.getValue());
        }
        if (info.isEmpty()) {
            info.put("资源类型", row.kind().label());
        }
        return info;
    }

    private Map<String, String> row(String... keyValues) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            row.put(keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private String kubectlHint(Action action, K8sRow row) {
        String namespace = row.value("命名空间");
        String ns = row.kind().namespaced() && namespace != null && !namespace.isBlank() && !"-".equals(namespace)
                ? " -n " + namespace
                : "";
        String type = row.kind().kubectlType();
        String name = row.value("名称");
        return switch (action) {
            case DETAIL -> "kubectl get " + type + ns + " " + name + " -o yaml";
            case LOGS -> "kubectl logs" + ns + " " + name;
            case EXEC -> "kubectl exec -it" + ns + " " + name + " -- /bin/sh";
            case EDIT -> "kubectl edit " + type + ns + " " + name;
            case DELETE -> "kubectl delete " + type + ns + " " + name;
            case SCALE -> "kubectl scale " + type + ns + " " + name + " --replicas=<数量>";
            case RESTART -> "kubectl rollout restart " + type + ns + " " + name;
            case TRIGGER -> "kubectl create job" + ns + " --from=cronjob/" + name + " " + name + "-manual";
        };
    }

    private void configurePageButton(Button button, String tooltipText, String iconLiteral, Runnable action) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(12);
        icon.getStyleClass().add("docker-tool-icon");
        button.setGraphic(icon);
        Tooltip tooltip = new Tooltip(tooltipText);
        tooltip.setShowDelay(new Duration(200));
        button.setTooltip(tooltip);
        button.setMinSize(30, 28);
        button.setPrefSize(30, 28);
        button.setMaxSize(30, 28);
        button.setOnAction(event -> action.run());
    }

    private Button makeToolbarButton(String iconLiteral, String tooltipText, Runnable action) {
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
        button.setOnAction(event -> action.run());
        return button;
    }

    private Map<String, String> valuesFor(ResourceKind kind, JsonNode item) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String column : kind.columns()) {
            values.put(column, valueFor(kind, column, item));
        }
        return values;
    }

    private String valueFor(ResourceKind kind, String column, JsonNode item) {
        return switch (column) {
            case "状态" -> statusFor(kind, item);
            case "名称" -> nodeText(item, "metadata", "name");
            case "命名空间" -> firstNonBlank(nodeText(item, "metadata", "namespace"), "-");
            case "镜像" -> imagesFor(item);
            case "标签" -> labelsFor(item);
            case "调度规则" -> firstNonBlank(nodeText(item, "spec", "schedule"), "-");
            case "暂停" -> nodeBoolean(item, "spec", "suspend") ? "是" : "否";
            case "活跃任务" -> String.valueOf(item.path("status").path("active").size());
            case "最后调度" -> firstNonBlank(ageSince(nodeText(item, "status", "lastScheduleTime")), "-");
            case "Pods" -> podsFor(kind, item);
            case "节点" -> firstNonBlank(nodeText(item, "spec", "nodeName"), "-");
            case "重启次数" -> String.valueOf(restartCountFor(item));
            case "Endpoints" -> firstNonBlank(ingressEndpoints(item), externalEndpointFor(item));
            case "Hosts" -> ingressHosts(item);
            case "Controller" -> firstNonBlank(nodeText(item, "spec", "controller"), "-");
            case "类型" -> typeFor(kind, item);
            case "Cluster IP" -> firstNonBlank(nodeText(item, "spec", "clusterIP"), "-");
            case "内部端点" -> portsFor(item);
            case "外部端点" -> externalEndpointFor(item);
            case "Volume" -> firstNonBlank(nodeText(item, "spec", "volumeName"), "-");
            case "容量" -> capacityFor(item);
            case "访问模式" -> joinArray(item.path("spec").path("accessModes"));
            case "Storage Class" -> firstNonBlank(nodeText(item, "spec", "storageClassName"), "-");
            case "Provisioner" ->
                    firstNonBlank(nodeText(item, "provisioner"), firstNonBlank(nodeText(item, "spec", "provisioner"), "-"));
            case "参数" ->
                    objectToPairs(firstNonMissing(item.path("parameters"), item.path("spec").path("parameters")), 4);
            case "Role Ref" -> roleRefFor(item);
            case "Subjects" -> subjectsFor(item);
            case "Source" -> firstNonBlank(nodeText(item, "source", "component"),
                    firstNonBlank(nodeText(item, "reportingController"), "-"));
            case "Age", "创建时间" -> firstNonBlank(ageSince(nodeText(item, "metadata", "creationTimestamp")), "-");
            case "Reason" -> firstNonBlank(nodeText(item, "reason"), "-");
            case "Message" -> firstNonBlank(nodeText(item, "message"), "-");
            case "Object" -> involvedObjectFor(item);
            case "Count" -> firstNonBlank(nodeText(item, "count"), "1");
            case "First Seen" -> firstNonBlank(ageSince(firstNonBlank(nodeText(item, "firstTimestamp"),
                    nodeText(item, "metadata", "creationTimestamp"))), "-");
            case "Last Seen" -> firstNonBlank(ageSince(firstNonBlank(nodeText(item, "lastTimestamp"),
                    nodeText(item, "eventTime"))), "-");
            case "Phase" -> firstNonBlank(nodeText(item, "status", "phase"), statusFor(kind, item));
            case "Ready" -> readyFor(item);
            case "CPU 可分配" -> firstNonBlank(nodeText(item, "status", "allocatable", "cpu"), "-");
            case "CPU 容量" -> firstNonBlank(nodeText(item, "status", "capacity", "cpu"), "-");
            case "内存 可分配" -> firstNonBlank(nodeText(item, "status", "allocatable", "memory"), "-");
            case "内存 容量" ->
                    firstNonBlank(nodeText(item, "status", "capacity", "memory"), "-");
            case "Pods 容量" -> firstNonBlank(nodeText(item, "status", "capacity", "pods"), "-");
            case "Claim" -> claimFor(item);
            case "绑定状态" -> firstNonBlank(nodeText(item, "status", "phase"), statusFor(kind, item));
            case "Reclaim Policy" -> firstNonBlank(nodeText(item, "spec", "persistentVolumeReclaimPolicy"), "-");
            default -> "-";
        };
    }

    private String statusFor(ResourceKind kind, JsonNode item) {
        if (kind == ResourceKind.NODES) {
            return "True".equalsIgnoreCase(readyFor(item)) ? "就绪" : "未就绪";
        }
        if (kind == ResourceKind.EVENTS) {
            return firstNonBlank(nodeText(item, "type"), "-");
        }
        String phase = nodeText(item, "status", "phase");
        if (!phase.isBlank()) {
            return phase;
        }
        int desired = item.path("status").path("replicas").asInt(-1);
        int ready = item.path("status").path("readyReplicas").asInt(item.path("status").path("availableReplicas").asInt(-1));
        if (desired >= 0 || ready >= 0) {
            return Math.max(ready, 0) + "/" + Math.max(desired, 0);
        }
        if (item.path("status").has("succeeded")) {
            return item.path("status").path("succeeded").asInt() > 0 ? "Complete" : "Running";
        }
        if (kind == ResourceKind.CRON_JOBS) {
            return nodeBoolean(item, "spec", "suspend") ? "Suspended" : "Active";
        }
        return "-";
    }

    private String readyFor(JsonNode item) {
        JsonNode conditions = item.path("status").path("conditions");
        if (conditions.isArray()) {
            String target = "Ready";
            for (JsonNode condition : conditions) {
                if (target.equalsIgnoreCase(condition.path("type").asText())) {
                    return firstNonBlank(condition.path("status").asText(), "-");
                }
            }
        }
        return "-";
    }

    private String podsFor(ResourceKind kind, JsonNode item) {
        if (kind == ResourceKind.NODES) {
            return firstNonBlank(nodeText(item, "status", "capacity", "pods"), "-");
        }
        int ready = item.path("status").path("readyReplicas").asInt(item.path("status").path("availableReplicas").asInt(-1));
        int desired = item.path("status").path("replicas").asInt(item.path("status").path("desiredNumberScheduled").asInt(-1));
        if (desired < 0 && item.path("status").has("succeeded")) {
            int succeeded = item.path("status").path("succeeded").asInt(0);
            int active = item.path("status").path("active").asInt(0);
            int failed = item.path("status").path("failed").asInt(0);
            return "succeeded=" + succeeded + ",active=" + active + ",failed=" + failed;
        }
        if (ready >= 0 || desired >= 0) {
            return Math.max(ready, 0) + "/" + Math.max(desired, 0);
        }
        return "-";
    }

    private String imagesFor(JsonNode item) {
        JsonNode containers = item.path("spec").path("template").path("spec").path("containers");
        if (!containers.isArray()) {
            containers = item.path("spec").path("containers");
        }
        List<String> images = new ArrayList<>();
        if (containers.isArray()) {
            for (JsonNode container : containers) {
                String image = container.path("image").asText("");
                if (!image.isBlank()) {
                    images.add(image);
                }
            }
        }
        return images.isEmpty() ? "-" : String.join(", ", images);
    }

    private int restartCountFor(JsonNode item) {
        int count = 0;
        JsonNode statuses = item.path("status").path("containerStatuses");
        if (statuses.isArray()) {
            for (JsonNode status : statuses) {
                count += status.path("restartCount").asInt(0);
            }
        }
        return count;
    }

    private String labelsFor(JsonNode item) {
        return objectToPairs(item.path("metadata").path("labels"), 6);
    }

    private String annotationsFor(JsonNode item) {
        return objectToPairs(item == null ? null : item.path("metadata").path("annotations"), 4);
    }

    private String typeFor(ResourceKind kind, JsonNode item) {
        if (kind == ResourceKind.SECRETS) {
            return firstNonBlank(nodeText(item, "type"), "-");
        }
        if (kind == ResourceKind.EVENTS) {
            return firstNonBlank(nodeText(item, "type"), "-");
        }
        return firstNonBlank(nodeText(item, "spec", "type"), "-");
    }

    private String portsFor(JsonNode item) {
        JsonNode ports = item.path("spec").path("ports");
        if (!ports.isArray()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode port : ports) {
            String protocol = firstNonBlank(port.path("protocol").asText(""), "TCP");
            String target = firstNonBlank(port.path("targetPort").asText(""), port.path("port").asText(""));
            values.add(port.path("port").asText("-") + ":" + target + "/" + protocol);
        }
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private String externalEndpointFor(JsonNode item) {
        JsonNode ingress = item.path("status").path("loadBalancer").path("ingress");
        if (!ingress.isArray()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode endpoint : ingress) {
            String value = firstNonBlank(endpoint.path("ip").asText(""), endpoint.path("hostname").asText(""));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private String ingressHosts(JsonNode item) {
        JsonNode rules = item.path("spec").path("rules");
        if (!rules.isArray()) {
            return "-";
        }
        List<String> hosts = new ArrayList<>();
        for (JsonNode rule : rules) {
            String host = rule.path("host").asText("");
            if (!host.isBlank()) {
                hosts.add(host);
            }
        }
        return hosts.isEmpty() ? "-" : String.join(", ", hosts);
    }

    private String ingressEndpoints(JsonNode item) {
        return externalEndpointFor(item);
    }

    private String capacityFor(JsonNode item) {
        return firstNonBlank(nodeText(item, "spec", "resources", "requests", "storage"),
                firstNonBlank(nodeText(item, "spec", "capacity", "storage"),
                        firstNonBlank(nodeText(item, "status", "capacity", "storage"), "-")));
    }

    private String roleRefFor(JsonNode item) {
        JsonNode roleRef = item.path("roleRef");
        String kind = roleRef.path("kind").asText("");
        String name = roleRef.path("name").asText("");
        return kind.isBlank() && name.isBlank() ? "-" : kind + "/" + name;
    }

    private String subjectsFor(JsonNode item) {
        JsonNode subjects = item.path("subjects");
        if (!subjects.isArray()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode subject : subjects) {
            String value = subject.path("kind").asText("") + "/" + subject.path("name").asText("");
            String namespace = subject.path("namespace").asText("");
            if (!namespace.isBlank()) {
                value = namespace + "/" + value;
            }
            values.add(value);
            if (values.size() >= 4) {
                break;
            }
        }
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private String involvedObjectFor(JsonNode item) {
        JsonNode involved = item.path("involvedObject");
        String kind = involved.path("kind").asText("");
        String name = involved.path("name").asText("");
        return kind.isBlank() && name.isBlank() ? "-" : kind + "/" + name;
    }

    private String claimFor(JsonNode item) {
        JsonNode claim = item.path("spec").path("claimRef");
        String namespace = claim.path("namespace").asText("");
        String name = claim.path("name").asText("");
        if (name.isBlank()) {
            return "-";
        }
        return namespace.isBlank() ? name : namespace + "/" + name;
    }

    private JsonNode firstNonMissing(JsonNode first, JsonNode second) {
        return first != null && !first.isMissingNode() && !first.isNull() ? first : second;
    }

    private String objectToPairs(JsonNode object, int limit) {
        if (object == null || !object.isObject()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext() && values.size() < limit) {
            Map.Entry<String, JsonNode> entry = fields.next();
            values.add(entry.getKey() + "=" + entry.getValue().asText());
        }
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private String joinArray(JsonNode array) {
        if (array == null || !array.isArray()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            String value = node.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private boolean nodeBoolean(JsonNode root, String... path) {
        JsonNode node = nodeAt(root, path);
        return node != null && node.asBoolean(false);
    }

    private String nodeText(JsonNode root, String... path) {
        JsonNode node = nodeAt(root, path);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    private JsonNode nodeAt(JsonNode root, String... path) {
        if (root == null) {
            return null;
        }
        JsonNode current = root;
        for (String part : path) {
            current = current.path(part);
        }
        return current;
    }

    private int compareByCreationTimeDesc(K8sRow left, K8sRow right) {
        long leftTime = creationTimestampMillis(left == null ? null : left.raw());
        long rightTime = creationTimestampMillis(right == null ? null : right.raw());
        if (leftTime == rightTime) {
            String leftName = left == null ? "" : firstNonBlank(nodeText(left.raw(), "metadata", "name"), "");
            String rightName = right == null ? "" : firstNonBlank(nodeText(right.raw(), "metadata", "name"), "");
            return leftName.compareToIgnoreCase(rightName);
        }
        if (leftTime == Long.MIN_VALUE) {
            return 1;
        }
        if (rightTime == Long.MIN_VALUE) {
            return -1;
        }
        return Long.compare(rightTime, leftTime);
    }

    private long creationTimestampMillis(JsonNode item) {
        Instant instant = parseTimestamp(nodeText(item, "metadata", "creationTimestamp"));
        return instant == null ? Long.MIN_VALUE : instant.toEpochMilli();
    }

    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String ageSince(String timestamp) {
        Instant instant = parseTimestamp(timestamp);
        if (instant == null) {
            return "";
        }
        java.time.Duration duration = java.time.Duration.between(instant, Instant.now());
        if (duration.isNegative()) {
            return "刚刚";
        }

        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return Math.max(0, seconds) + "秒前";
        }

        long minutes = duration.toMinutes();
        if (minutes < 60) {
            return minutes + "分钟前";
        }

        long hours = duration.toHours();
        if (hours < 24) {
            return hours + "小时前";
        }

        long days = duration.toDays();
        if (days < 30) {
            return days + "天前";
        }

        if (days < 365) {
            long months = Math.max(1L, days / 30);
            return months + "个月前";
        }

        long years = Math.max(1L, days / 365);
        return years + "年前";
    }

    private void setVisibleManaged(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private enum Category {
        WORKLOADS("工作负载", "fas-layer-group"),
        SERVICE("服务发现", "fas-network-wired"),
        CONFIG_STORAGE("配置与存储", "fas-database"),
        CLUSTER("集群", "fas-project-diagram");

        private final String label;
        private final String iconLiteral;

        Category(String label, String iconLiteral) {
            this.label = label;
            this.iconLiteral = iconLiteral;
        }

        private String label() {
            return label;
        }

        private String iconLiteral() {
            return iconLiteral;
        }

        private List<ResourceKind> children() {
            List<ResourceKind> result = new ArrayList<>();
            for (ResourceKind kind : ResourceKind.values()) {
                if (kind.category() == this) {
                    result.add(kind);
                }
            }
            return result;
        }
    }

    private enum Action {
        DETAIL("查看详情"),
        LOGS("查看日志"),
        EXEC("执行"),
        EDIT("编辑资源"),
        DELETE("删除资源"),
        SCALE("扩缩容"),
        RESTART("重启"),
        TRIGGER("触发执行");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }

        private static Action fromLabel(String label) {
            for (Action action : values()) {
                if (Objects.equals(action.label, label)) {
                    return action;
                }
            }
            return null;
        }
    }

    private enum ResourceKind {
        CRON_JOBS(Category.WORKLOADS, "定时任务", "cronjob", "backup", true,
                List.of("状态", "名称", "命名空间", "镜像", "标签", "调度规则", "暂停", "活跃任务", "最后调度", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE, Action.TRIGGER)),
        DAEMON_SETS(Category.WORKLOADS, "守护进程集", "daemonset", "node-agent", true,
                List.of("状态", "名称", "命名空间", "镜像", "标签", "Pods", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE, Action.LOGS)),
        DEPLOYMENTS(Category.WORKLOADS, "部署", "deployment", "deploy", true,
                List.of("状态", "名称", "命名空间", "镜像", "标签", "Pods", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE, Action.SCALE, Action.RESTART)),
        JOBS(Category.WORKLOADS, "任务", "job", "migration", true,
                List.of("状态", "名称", "命名空间", "镜像", "标签", "Pods", "创建时间"),
                List.of(Action.DETAIL, Action.LOGS, Action.EDIT, Action.DELETE)),
        PODS(Category.WORKLOADS, "Pod", "pod", "api", true,
                List.of("状态", "名称", "命名空间", "镜像", "标签", "节点", "重启次数", "CPU 使用率", "内存使用率", "创建时间"),
                List.of(Action.DETAIL, Action.LOGS, Action.EXEC, Action.EDIT, Action.DELETE)),
        REPLICA_SETS(Category.WORKLOADS, "副本集", "replicaset", "frontend", true,
                List.of("状态", "名称", "命名空间", "镜像", "标签", "Pods", "创建时间"),
                List.of(Action.DETAIL, Action.LOGS, Action.EDIT, Action.DELETE, Action.SCALE)),
        REPLICATION_CONTROLLERS(Category.WORKLOADS, "副本控制器", "replicationcontroller", "legacy-web", true,
                List.of("状态", "名称", "命名空间", "镜像", "标签", "Pods", "创建时间"),
                List.of(Action.DETAIL, Action.LOGS, Action.EDIT, Action.DELETE, Action.SCALE)),
        STATEFUL_SETS(Category.WORKLOADS, "有状态集", "statefulset", "mysql", true,
                List.of("状态", "名称", "命名空间", "镜像", "标签", "Pods", "创建时间"),
                List.of(Action.DETAIL, Action.LOGS, Action.EDIT, Action.DELETE, Action.SCALE)),
        INGRESSES(Category.SERVICE, "入口", "ingress", "public", true,
                List.of("名称", "命名空间", "标签", "Endpoints", "Hosts", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        INGRESS_CLASSES(Category.SERVICE, "入口类", "ingressclass", "nginx", false,
                List.of("名称", "Controller", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        SERVICES(Category.SERVICE, "服务", "service", "svc", true,
                List.of("状态", "名称", "命名空间", "标签", "类型", "Cluster IP", "内部端点", "外部端点", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        CONFIG_MAPS(Category.CONFIG_STORAGE, "配置映射", "configmap", "config", true,
                List.of("名称", "命名空间", "标签", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        PERSISTENT_VOLUME_CLAIMS(Category.CONFIG_STORAGE, "持久卷声明", "persistentvolumeclaim", "data", true,
                List.of("状态", "名称", "命名空间", "标签", "绑定状态", "Volume", "容量", "访问模式", "Storage Class", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        SECRETS(Category.CONFIG_STORAGE, "密钥", "secret", "secret", true,
                List.of("名称", "命名空间", "标签", "类型", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        STORAGE_CLASSES(Category.CONFIG_STORAGE, "存储类", "storageclass", "storage", false,
                List.of("名称", "Provisioner", "参数", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        CLUSTER_ROLE_BINDINGS(Category.CLUSTER, "集群角色绑定", "clusterrolebinding", "cluster-binding", false,
                List.of("名称", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        CLUSTER_ROLES(Category.CLUSTER, "集群角色", "clusterrole", "cluster-role", false,
                List.of("名称", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        EVENTS(Category.CLUSTER, "事件", "event", "event", true,
                List.of("名称", "命名空间", "Reason", "Message", "Source", "Object", "Count", "First Seen", "Last Seen"),
                List.of(Action.DETAIL)),
        NAMESPACES(Category.CLUSTER, "命名空间", "namespace", "namespace", false,
                List.of("状态", "名称", "标签", "Phase", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        NETWORK_POLICIES(Category.CLUSTER, "网络策略", "networkpolicy", "policy", true,
                List.of("名称", "命名空间", "标签", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        NODES(Category.CLUSTER, "节点", "node", "node", false,
                List.of("状态", "名称", "标签", "Ready", "CPU 可分配", "CPU 容量",
                        "内存 可分配", "内存 容量", "Pods 容量", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        PERSISTENT_VOLUMES(Category.CLUSTER, "持久卷", "persistentvolume", "pv", false,
                List.of("状态", "名称", "容量", "访问模式", "Reclaim Policy", "绑定状态", "Claim", "Storage Class", "Reason", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        ROLE_BINDINGS(Category.CLUSTER, "角色绑定", "rolebinding", "role-binding", true,
                List.of("名称", "命名空间", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        ROLES(Category.CLUSTER, "角色", "role", "role", true,
                List.of("名称", "命名空间", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        SERVICE_ACCOUNTS(Category.CLUSTER, "服务账户", "serviceaccount", "service-account", true,
                List.of("名称", "命名空间", "标签", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE));

        private final Category category;
        private final String label;
        private final String kubectlType;
        private final String namePrefix;
        private final boolean namespaced;
        private final List<String> columns;
        private final List<Action> actions;

        ResourceKind(Category category, String label, String kubectlType, String namePrefix, boolean namespaced,
                     List<String> columns, List<Action> actions) {
            this.category = category;
            this.label = label;
            this.kubectlType = kubectlType;
            this.namePrefix = namePrefix;
            this.namespaced = namespaced;
            this.columns = columns;
            this.actions = actions;
        }

        private Category category() {
            return category;
        }

        private String label() {
            return label;
        }

        private String kubectlType() {
            return kubectlType;
        }

        private String namePrefix() {
            return namePrefix;
        }

        private boolean namespaced() {
            return namespaced;
        }

        private List<String> columns() {
            return columns;
        }

        private List<Action> actions() {
            return actions;
        }

        private static ResourceKind fromLabel(String label) {
            for (ResourceKind kind : values()) {
                if (Objects.equals(kind.label, label)) {
                    return kind;
                }
            }
            return CRON_JOBS;
        }
    }

    private record ConnectionContext(String connId, ConnInfo connInfo) {
    }

    private enum ResourceEditFormat {
        YAML,
        JSON
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

    private record K8sRow(ResourceKind kind, Map<String, String> values, JsonNode raw) {

        private String value(String column) {
            return values.getOrDefault(column, "");
        }

        private boolean contains(String query) {
            for (String value : values.values()) {
                if (value != null && value.toLowerCase(Locale.ROOT).contains(query)) {
                    return true;
                }
            }
            return false;
        }
    }
}

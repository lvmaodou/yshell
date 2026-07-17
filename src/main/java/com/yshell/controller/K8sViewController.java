package com.yshell.controller;

import com.yshell.ui.DialogHelper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.*;

public class K8sViewController {
    private static final int PAGE_SIZE = 100;
    private static final String ALL_NAMESPACES = "全部 namespace";

    private final Map<Category, Button> categoryButtons = new LinkedHashMap<>();
    private final Map<Category, VBox> childContainers = new LinkedHashMap<>();
    private final ObservableList<K8sRow> allRows = FXCollections.observableArrayList();
    private final ObservableList<K8sRow> visibleRows = FXCollections.observableArrayList();
    private ResourceKind activeKind = ResourceKind.CRON_JOBS;
    private Category expandedCategory;
    private int currentPageIndex;

    @FXML
    private VBox navGroups;
    @FXML
    private Label lblK8sVersion;
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
        updateClusterStatus();
        expandedCategory = Category.WORKLOADS;
        updateSidebarStyle();
        renderRows();
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
        Button createButton = makeToolbarButton(this::createResource);
        toolbarActions.getChildren().setAll(createButton);
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
                ALL_NAMESPACES,
                "default",
                "kube-system",
                "database",
                "monitoring",
                "ingress-nginx"
        ));
        namespaceCombo.getSelectionModel().select(ALL_NAMESPACES);
        namespaceCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            currentPageIndex = 0;
            applyFilters();
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
            case "CPU requests", "CPU limits", "Memory requests", "Memory limits", "Memory capacity" -> 160;
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
        allRows.setAll(sampleRows(activeKind));
        currentPageIndex = 0;
        applyFilters();
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
        DialogHelper.showInfo("Kubernetes", "新建资源功能等待 Kubernetes 后端接入。");
    }

    private void showResourceAction(Action action, K8sRow row) {
        if (action == Action.DETAIL) {
            showDetailWindow(row);
            return;
        }
        DialogHelper.showInfo("Kubernetes",
                action.label() + ": " + row.kind().label() + " / " + row.value("名称")
                        + "\n\n等效操作: " + kubectlHint(action, row));
    }

    private void showDetailWindow(K8sRow row) {
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
        actionSpecs.add(new K8sDetailController.DetailActionSpec(
                "固定到侧边栏",
                "将 " + row.kind().label() + " / " + row.value("名称") + " 固定到侧边栏",
                false
        ));

        K8sDetailController.DetailPageData data = new K8sDetailController.DetailPageData(
                row.kind().label(),
                row.kind().label() + "详情",
                row.value("名称") + namespaceSubtitle(row),
                metadataFor(row),
                resourceInfoFor(row),
                detailSectionsFor(row),
                actionSpecs,
                eventsFor(row),
                yamlFor(row),
                spec -> DialogHelper.showInfo("Kubernetes", spec.label() + "\n\n等效操作: " + spec.hint())
        );

        K8sDetailController.show(resourceTable.getScene() == null ? null : resourceTable.getScene().getWindow(), data);
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
        metadata.put("标签", firstNonBlank(row.value("标签"), "app=" + row.value("名称")));
        metadata.put("注解", "app.kubernetes.io/managed-by=YShell");
        metadata.put("创建时间", firstNonBlank(row.value("创建时间"), row.value("Age")));
        return metadata;
    }

    private Map<String, String> resourceInfoFor(K8sRow row) {
        if (row.kind() == ResourceKind.PODS) {
            return row(
                    "Node", firstNonBlank(row.value("节点"), "node-1"),
                    "Status", firstNonBlank(row.value("状态"), "Running"),
                    "IP", "10.244.0." + positiveHash(row.value("名称")),
                    "QoS Class", "Burstable",
                    "Restarts", firstNonBlank(row.value("重启次数"), "0"),
                    "Service Account", "default",
                    "Image Pull Secrets", "registry-secret"
            );
        }
        if (row.kind() == ResourceKind.NODES) {
            return row(
                    "Ready", firstNonBlank(row.value("Ready"), row.value("状态")),
                    "CPU requests", firstNonBlank(row.value("CPU requests"), "-"),
                    "CPU limits", firstNonBlank(row.value("CPU limits"), "-"),
                    "CPU capacity", firstNonBlank(row.value("CPU capacity"), "-"),
                    "Memory requests", firstNonBlank(row.value("Memory requests"), "-"),
                    "Memory limits", firstNonBlank(row.value("Memory limits"), "-"),
                    "Memory capacity", firstNonBlank(row.value("Memory capacity"), "-"),
                    "Pods", firstNonBlank(row.value("Pods"), "-")
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

    private List<K8sDetailController.DetailSectionSpec> detailSectionsFor(K8sRow row) {
        List<K8sDetailController.DetailSectionSpec> sections = new ArrayList<>();
        switch (row.kind()) {
            case PODS -> {
                sections.add(metricsCard(row));
                sections.add(conditionTable());
                sections.add(K8sDetailController.DetailSectionSpec.kv("创建者信息", row(
                        "Kind", "ReplicaSet",
                        "Name", row.value("名称") + "-rs",
                        "Namespace", row.value("命名空间")
                )));
                sections.add(K8sDetailController.DetailSectionSpec.table("PVC 列表",
                        List.of("名称", "状态", "容量"),
                        List.of(row("名称", "data-" + row.value("名称"), "状态", "Bound", "容量", "20Gi"))));
                sections.add(eventTable(row));
                sections.add(K8sDetailController.DetailSectionSpec.cardGroup("Containers",
                        List.of(containerCard(row.value("名称"), firstNonBlank(row.value("镜像"), "registry.local/app:latest")))));
            }
            case DEPLOYMENTS -> {
                sections.add(K8sDetailController.DetailSectionSpec.kv("滚动更新策略", row(
                        "Max surge", "25%",
                        "Max unavailable", "25%"
                )));
                sections.add(K8sDetailController.DetailSectionSpec.kv("Pods 状态", row(
                        "Updated", "3",
                        "Total", "3",
                        "Available", "3",
                        "Unavailable", "0"
                )));
                sections.add(conditionTable());
                sections.add(K8sDetailController.DetailSectionSpec.table("新副本集",
                        List.of("Name", "Namespace", "Age", "Pods", "Labels", "Images"),
                        List.of(row("Name", row.value("名称") + "-rs", "Namespace", row.value("命名空间"),
                                "Age", "2d", "Pods", firstNonBlank(row.value("Pods"), "3/3"),
                                "Labels", row.value("标签"), "Images", row.value("镜像")))));
                sections.add(K8sDetailController.DetailSectionSpec.list("旧副本集", List.of(row.value("名称") + "-rs-old")));
                sections.add(K8sDetailController.DetailSectionSpec.list("HPA 列表", List.of(row.value("名称") + "-hpa")));
                sections.add(eventTable(row));
            }
            case DAEMON_SETS, STATEFUL_SETS, REPLICA_SETS, REPLICATION_CONTROLLERS -> sections.addAll(List.of(
                    K8sDetailController.DetailSectionSpec.kv("Pod 状态", row(
                            "Running / Desired", firstNonBlank(row.value("Pods"), "1/1"),
                            "状态百分比", "100%"
                    )),
                    K8sDetailController.DetailSectionSpec.table("Pod 列表",
                            List.of("名称", "状态", "节点"),
                            List.of(row("名称", row.value("名称") + "-pod", "状态", "Running", "节点", "node-1"))),
                    K8sDetailController.DetailSectionSpec.table("Service 列表",
                            List.of("名称", "类型", "Cluster IP"),
                            List.of(row("名称", row.value("名称") + "-svc", "类型", "ClusterIP", "Cluster IP", "10.96.0.10"))),
                    eventTable(row)
            ));
            case JOBS -> sections.addAll(List.of(
                    K8sDetailController.DetailSectionSpec.kv("Pod 状态", row(
                            "Running / Desired", firstNonBlank(row.value("Pods"), "1/1"),
                            "Succeeded", row.value("状态").equals("已完成") ? "1" : "0",
                            "Failed", row.value("状态").equals("失败") ? "1" : "0"
                    )),
                    conditionTable(),
                    K8sDetailController.DetailSectionSpec.table("Pod 列表",
                            List.of("名称", "状态", "节点"),
                            List.of(row("名称", row.value("名称") + "-pod", "状态", "Running", "节点", "node-1"))),
                    eventTable(row)
            ));
            case CRON_JOBS -> sections.addAll(List.of(
                    K8sDetailController.DetailSectionSpec.table("Job 列表",
                            List.of("名称", "状态", "开始时间", "完成时间"),
                            List.of(row("名称", row.value("名称") + "-001", "状态", "Complete", "开始时间", "10m 前", "完成时间", "9m 前"))),
                    eventTable(row)
            ));
            case SERVICES -> {
                sections.add(K8sDetailController.DetailSectionSpec.cardGroup("端点列表",
                        List.of(row("_标题", "端点 1", "内部端点", firstNonBlank(row.value("内部端点"), "-"),
                                "外部端点", firstNonBlank(row.value("外部端点"), "-"), "端口", "80/TCP"))));
                sections.add(K8sDetailController.DetailSectionSpec.table("Pod 列表",
                        List.of("名称", "状态"), List.of(row("名称", row.value("名称") + "-pod", "状态", "Running"))));
                sections.add(K8sDetailController.DetailSectionSpec.list("Ingress 列表", List.of("public-" + row.value("名称"))));
                sections.add(eventTable(row));
            }
            case INGRESSES -> {
                sections.add(K8sDetailController.DetailSectionSpec.table("Ingress 规则",
                        List.of("Host", "Path", "Service", "Port"),
                        List.of(row("Host", firstNonBlank(row.value("Hosts"), "-"), "Path", "/",
                                "Service", "svc-" + row.value("名称"), "Port", "80"))));
                sections.add(K8sDetailController.DetailSectionSpec.kv("外部端点", row("Endpoint", firstNonBlank(row.value("Endpoints"), "-"))));
                sections.add(K8sDetailController.DetailSectionSpec.kv("TLS 配置", row("Secret", "tls-" + row.value("名称"))));
                sections.add(eventTable(row));
            }
            case INGRESS_CLASSES -> sections.add(K8sDetailController.DetailSectionSpec.kv("Controller 信息",
                    row("Controller", firstNonBlank(row.value("Controller"), "k8s.io/ingress-nginx"))));
            case CONFIG_MAPS -> sections.add(K8sDetailController.DetailSectionSpec.text("数据",
                    "{\n  \"config.yaml\": \"key: value\"\n}"));
            case PERSISTENT_VOLUME_CLAIMS -> sections.add(K8sDetailController.DetailSectionSpec.kv("存储信息", row(
                    "Status", firstNonBlank(row.value("绑定状态"), row.value("状态")),
                    "Volume", firstNonBlank(row.value("Volume"), "-"),
                    "Capacity", firstNonBlank(row.value("容量"), "-"),
                    "Access Modes", firstNonBlank(row.value("访问模式"), "-"),
                    "Storage Class", firstNonBlank(row.value("Storage Class"), "-")
            )));
            case SECRETS -> sections.add(K8sDetailController.DetailSectionSpec.table("数据",
                    List.of("Key", "Value"),
                    List.of(row("Key", "password", "Value", "8 bytes hidden"),
                            row("Key", "certificate", "Value", "1024 bytes hidden"))));
            case NODES -> {
                sections.add(metricsCard(row));
                sections.add(K8sDetailController.DetailSectionSpec.kv("系统信息", Map.of(
                        "Kernel version", "5.15.0",
                        "OS Image", "Ubuntu 22.04",
                        "Container runtime version", "containerd://1.7.0",
                        "kubelet version", "v1.28.0"
                )));
                sections.add(conditionTable());
                sections.add(K8sDetailController.DetailSectionSpec.table("Pod 列表",
                        List.of("名称", "命名空间", "状态"),
                        List.of(row("名称", "api-001", "命名空间", "default", "状态", "Running"))));
                sections.add(eventTable(row));
            }
            case ROLES, CLUSTER_ROLES -> sections.add(policyRuleTable());
            case ROLE_BINDINGS, CLUSTER_ROLE_BINDINGS ->
                    sections.add(K8sDetailController.DetailSectionSpec.table("主体列表",
                            List.of("Kind", "Name", "Namespace"),
                            List.of(row("Kind", "ServiceAccount", "Name", "default",
                                    "Namespace", firstNonBlank(row.value("命名空间"), "default")))));
            case SERVICE_ACCOUNTS -> {
                sections.add(K8sDetailController.DetailSectionSpec.list("Secret 列表", List.of("default-token")));
                sections.add(K8sDetailController.DetailSectionSpec.list("Image Pull Secret 列表", List.of("image-pull-secret")));
            }
            case NETWORK_POLICIES -> {
                sections.add(K8sDetailController.DetailSectionSpec.kv("Pod Selector",
                        row("matchLabels", firstNonBlank(row.value("标签"), "app=" + row.value("名称")))));
                sections.add(K8sDetailController.DetailSectionSpec.text("Ingress / Egress 规则",
                        "policyTypes:\n  - Ingress\n  - Egress\npodSelector:\n  matchLabels:\n    app: " + row.value("名称")));
            }
            case PERSISTENT_VOLUMES -> {
                sections.add(K8sDetailController.DetailSectionSpec.kv("PV 源", row("类型", "HostPath", "Path", "/var/lib/data")));
                sections.add(K8sDetailController.DetailSectionSpec.table("容量",
                        List.of("Resource name", "Quantity"),
                        List.of(row("Resource name", "storage", "Quantity", firstNonBlank(row.value("容量"), "20Gi")))));
            }
            case STORAGE_CLASSES -> {
                sections.add(K8sDetailController.DetailSectionSpec.kv("参数", row(
                        "Provisioner", firstNonBlank(row.value("Provisioner"), "-"),
                        "Reclaim policy", "Delete",
                        "Volume binding mode", "WaitForFirstConsumer",
                        "Allow volume expansion", "true"
                )));
                sections.add(K8sDetailController.DetailSectionSpec.list("持久卷列表", List.of("pv-001", "pv-002")));
            }
            case NAMESPACES -> {
                sections.add(K8sDetailController.DetailSectionSpec.table("资源配额列表",
                        List.of("资源", "使用量", "限制"),
                        List.of(row("资源", "pods", "使用量", "12", "限制", "100"))));
                sections.add(K8sDetailController.DetailSectionSpec.table("资源限制列表",
                        List.of("资源", "默认值"),
                        List.of(row("资源", "cpu", "默认值", "500m"))));
                sections.add(eventTable(row));
            }
            case EVENTS -> sections.add(K8sDetailController.DetailSectionSpec.text("消息",
                    firstNonBlank(row.value("Message"), "Successfully assigned resource")));
            default -> {
            }
        }
        return sections;
    }

    private K8sDetailController.DetailSectionSpec conditionTable() {
        return K8sDetailController.DetailSectionSpec.table("条件列表",
                List.of("Type", "Status", "Reason", "Message", "Last Probe Time"),
                List.of(row("Type", "Ready", "Status", "True", "Reason", "MinimumReplicasAvailable",
                        "Message", "resource is ready", "Last Probe Time", "30s")));
    }

    private K8sDetailController.DetailSectionSpec policyRuleTable() {
        return K8sDetailController.DetailSectionSpec.table("策略规则列表",
                List.of("Resources", "Non-Resource URLs", "Resource Names", "Verbs", "API Groups"),
                List.of(row("Resources", "pods, services", "Non-Resource URLs", "-",
                        "Resource Names", "*", "Verbs", "get, list, watch", "API Groups", "*")));
    }

    private K8sDetailController.DetailSectionSpec metricsCard(K8sRow row) {
        return K8sDetailController.DetailSectionSpec.kv("指标",
                row("CPU 使用率", firstNonBlank(row.value("CPU 使用率"), firstNonBlank(row.value("CPU requests"), "-")),
                        "内存使用率", firstNonBlank(row.value("内存使用率"), firstNonBlank(row.value("Memory requests"), "-"))));
    }

    private K8sDetailController.DetailSectionSpec eventTable(K8sRow row) {
        return K8sDetailController.DetailSectionSpec.table("事件列表",
                List.of("类型", "原因", "时间", "消息"),
                List.of(
                        row("类型", "Normal", "原因", "Created", "时间", "2m",
                                "消息", row.kind().label() + " " + row.value("名称") + " created"),
                        row("类型", "Normal", "原因", "Synced", "时间", "1m",
                                "消息", "Resource observed by YShell")
                ));
    }

    private Map<String, String> containerCard(String name, String image) {
        return row(
                "_标题", name,
                "Image", image,
                "Ready", "true",
                "Started", "true",
                "Restart Count", "0",
                "Resource Requests", "cpu=100m, memory=128Mi",
                "Resource Limits", "cpu=500m, memory=512Mi",
                "Mounts", "/var/run/secrets/kubernetes.io/serviceaccount"
        );
    }

    private Map<String, String> row(String... keyValues) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            row.put(keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private int positiveHash(String value) {
        return Math.floorMod(value == null ? 0 : value.hashCode(), 220) + 1;
    }

    private List<String> eventsFor(K8sRow row) {
        return List.of(
                "Normal  Created  2m  " + row.kind().label() + " " + row.value("名称") + " created",
                "Normal  Synced   1m  Resource observed by YShell",
                "Normal  Ready    30s Resource detail data prepared"
        );
    }

    private String yamlFor(K8sRow row) {
        StringBuilder builder = new StringBuilder();
        builder.append("apiVersion: v1\n")
                .append("kind: ").append(row.kind().kubectlType()).append("\n")
                .append("metadata:\n")
                .append("  name: ").append(row.value("名称")).append('\n');
        if (row.kind().namespaced()) {
            builder.append("  namespace: ").append(row.value("命名空间")).append('\n');
        }
        builder.append("  labels:\n")
                .append("    app: ").append(row.value("名称")).append("\n")
                .append("  annotations:\n")
                .append("    app.kubernetes.io/managed-by: YShell\n")
                .append("spec:\n");
        for (Map.Entry<String, String> entry : row.values().entrySet()) {
            if ("名称".equals(entry.getKey()) || "命名空间".equals(entry.getKey())) {
                continue;
            }
            builder.append("  ").append(toYamlKey(entry.getKey())).append(": \"")
                    .append(entry.getValue()).append("\"\n");
        }
        return builder.toString();
    }

    private String toYamlKey(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace(" ", "_")
                .replace("/", "_")
                .replace("：", "_");
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

    private Button makeToolbarButton(Runnable action) {
        FontIcon icon = new FontIcon("fas-plus");
        icon.setIconSize(13);
        icon.getStyleClass().add("docker-tool-icon");

        Button button = new Button();
        button.getStyleClass().add("tool-icon-btn");
        button.setGraphic(icon);
        Tooltip tooltip = new Tooltip("新建资源");
        tooltip.setShowDelay(new Duration(200));
        button.setTooltip(tooltip);
        button.setMinSize(30, 28);
        button.setPrefSize(30, 28);
        button.setMaxSize(30, 28);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void updateClusterStatus() {
        lblK8sVersion.setText("v1.28.0");
    }

    private List<K8sRow> sampleRows(ResourceKind kind) {
        int count = kind == ResourceKind.PODS ? 126 : sampleCount(kind);
        List<K8sRow> rows = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            rows.add(new K8sRow(kind, valuesFor(kind, i)));
        }
        return rows;
    }

    private int sampleCount(ResourceKind kind) {
        return switch (kind) {
            case EVENTS -> 118;
            case SERVICES, INGRESS_CLASSES -> 18;
            case CONFIG_MAPS -> 24;
            case SECRETS -> 32;
            case NODES -> 5;
            case NAMESPACES -> 6;
            case PERSISTENT_VOLUMES -> 12;
            case STORAGE_CLASSES -> 4;
            default -> 14;
        };
    }

    private Map<String, String> valuesFor(ResourceKind kind, int index) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String column : kind.columns()) {
            values.put(column, valueFor(kind, column, index));
        }
        return values;
    }

    private String valueFor(ResourceKind kind, String column, int index) {
        return switch (column) {
            case "状态" -> statusFor(kind, index);
            case "名称" -> sampleName(kind, index);
            case "命名空间" -> namespaceFor(index, kind);
            case "镜像" -> "registry.local/" + sampleName(kind, index) + ":1." + (index % 9);
            case "标签" -> "app=" + sampleName(kind, index) + ",env=" + envFor(index);
            case "调度规则" -> "*/" + (5 + index % 20) + " * * * *";
            case "暂停" -> index % 7 == 0 ? "是" : "否";
            case "活跃任务" -> String.valueOf(index % 3);
            case "最后调度" -> index + "h 前";
            case "Pods" -> (1 + index % 4) + "/" + (2 + index % 4);
            case "节点" -> "node-" + (1 + index % 5);
            case "重启次数" -> String.valueOf(index % 5);
            case "CPU 使用率" -> (8 + index % 70) + "%";
            case "内存使用率" -> (12 + index % 64) + "%";
            case "Endpoints" -> "https://app" + index + ".example.local";
            case "Hosts" -> "app" + index + ".example.local";
            case "Controller" -> "k8s.io/ingress-nginx";
            case "类型" -> serviceOrEventType(kind, index);
            case "Cluster IP" -> "10.96." + (index % 40) + "." + (10 + index);
            case "内部端点" -> "10.244." + (index % 8) + "." + index + ":80";
            case "外部端点" -> index % 3 == 0 ? "192.168.1." + index + ":80" : "-";
            case "Volume" -> "pvc-" + String.format("%03d", index);
            case "容量" -> (10 + index) + "Gi";
            case "访问模式" -> index % 2 == 0 ? "RWO" : "RWX";
            case "Storage Class" -> index % 2 == 0 ? "standard" : "fast-ssd";
            case "Provisioner" -> index % 2 == 0 ? "kubernetes.io/no-provisioner" : "csi.example.com";
            case "参数" -> "type=ssd,zone=local";
            case "Role Ref" -> "ClusterRole/admin";
            case "Subjects" -> "ServiceAccount/default/app-" + index;
            case "Source" -> kind == ResourceKind.EVENTS ? "kubelet/node-" + (index % 5 + 1) : "-";
            case "Age" -> ageFor(index);
            case "Message" -> index % 9 == 0 ? "Back-off restarting failed container" : "Successfully assigned pod";
            case "Phase" -> index % 6 == 0 ? "Terminating" : "Active";
            case "Ready" -> index % 5 == 0 ? "False" : "True";
            case "CPU requests" -> (1 + index % 8) + " cores / " + (10 + index % 60) + "%";
            case "CPU limits" -> (2 + index % 12) + " cores / " + (20 + index % 60) + "%";
            case "CPU capacity" -> (4 + index % 12) + " cores";
            case "Memory requests" -> (512 + index * 32) + "Mi / " + (10 + index % 60) + "%";
            case "Memory limits" -> (1024 + index * 64) + "Mi / " + (20 + index % 60) + "%";
            case "Memory capacity" -> (8 + index % 64) + "Gi";
            case "Claim" -> "default/data-" + String.format("%03d", index);
            case "绑定状态" -> index % 7 == 0 ? "Released" : "Bound";
            case "创建时间" -> ageFor(index) + " 前";
            default -> "-";
        };
    }

    private String statusFor(ResourceKind kind, int index) {
        if (kind == ResourceKind.EVENTS) {
            return index % 9 == 0 ? "警告" : "正常";
        }
        if (kind == ResourceKind.NODES) {
            return index % 5 == 0 ? "未就绪" : "就绪";
        }
        if (kind == ResourceKind.PERSISTENT_VOLUME_CLAIMS || kind == ResourceKind.PERSISTENT_VOLUMES) {
            return index % 7 == 0 ? "释放" : "绑定";
        }
        if (index % 17 == 0) {
            return "失败";
        }
        if (index % 11 == 0) {
            return "等待中";
        }
        return switch (kind) {
            case JOBS -> index % 4 == 0 ? "运行中" : "已完成";
            case CRON_JOBS -> "活跃";
            case NAMESPACES -> "Active";
            default -> "运行中";
        };
    }

    private String serviceOrEventType(ResourceKind kind, int index) {
        if (kind == ResourceKind.EVENTS) {
            return index % 9 == 0 ? "Warning" : "Normal";
        }
        if (kind == ResourceKind.SECRETS) {
            return index % 2 == 0 ? "Opaque" : "kubernetes.io/tls";
        }
        return switch (index % 3) {
            case 0 -> "LoadBalancer";
            case 1 -> "ClusterIP";
            default -> "NodePort";
        };
    }

    private String sampleName(ResourceKind kind, int index) {
        return kind.namePrefix() + "-" + String.format("%03d", index);
    }

    private String namespaceFor(int index, ResourceKind kind) {
        if (!kind.namespaced()) {
            return "-";
        }
        return switch (index % 5) {
            case 0 -> "kube-system";
            case 1 -> "default";
            case 2 -> "database";
            case 3 -> "monitoring";
            default -> "ingress-nginx";
        };
    }

    private String envFor(int index) {
        return switch (index % 3) {
            case 0 -> "prod";
            case 1 -> "test";
            default -> "dev";
        };
    }

    private String ageFor(int index) {
        if (index < 24) {
            return index + "h";
        }
        return Math.max(1, index / 3) + "d";
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
        EXEC("执行命令"),
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
                List.of("状态", "名称", "标签", "Ready", "CPU requests", "CPU limits", "CPU capacity",
                        "Memory requests", "Memory limits", "Memory capacity", "Pods", "创建时间"),
                List.of(Action.DETAIL, Action.EDIT, Action.DELETE)),
        PERSISTENT_VOLUMES(Category.CLUSTER, "持久卷", "persistentvolume", "pv", false,
                List.of("名称", "容量", "访问模式", "Reclaim Policy", "绑定状态", "Claim", "Storage Class", "Reason", "创建时间"),
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

    private record K8sRow(ResourceKind kind, Map<String, String> values) {
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

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
    private TableColumn<K8sRow, String> colName;
    @FXML
    private TableColumn<K8sRow, String> colNamespace;
    @FXML
    private TableColumn<K8sRow, String> colStatus;
    @FXML
    private TableColumn<K8sRow, String> colDetail;
    @FXML
    private TableColumn<K8sRow, String> colAge;
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
        Button createButton = makeToolbarButton("新建资源", "fas-plus", this::createResource);
        toolbarActions.getChildren().setAll(createButton);
        configurePageButton(firstPageButton, "头页", "fas-angle-double-left", () -> goToPage(0));
        configurePageButton(prevPageButton, "上一页", "fas-angle-left", () -> goToPage(currentPageIndex - 1));
        configurePageButton(nextPageButton, "下一页", "fas-angle-right", () -> goToPage(currentPageIndex + 1));
        configurePageButton(lastPageButton, "尾页", "fas-angle-double-right", () -> goToPage(pageCount(filteredRows()) - 1));
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

        colName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        colNamespace.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().namespace()));
        colStatus.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().status()));
        colDetail.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().detail()));
        colAge.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().age()));

        colName.setCellFactory(column -> createCopyableCell());
        colNamespace.setCellFactory(column -> createCopyableCell());
        colStatus.setCellFactory(column -> createCopyableCell());
        colDetail.setCellFactory(column -> createCopyableCell());
        colAge.setCellFactory(column -> createCopyableCell());

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
                    showResourceAction("查看详情", row.getItem());
                }
            });
            return row;
        });
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
            NodeUtil.setVisibleManaged(children, expanded);
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
        allRows.setAll(sampleRows(activeKind));
        currentPageIndex = 0;
        updateColumns();
        applyFilters();
    }

    private void updateColumns() {
        colName.setText("名称");
        colNamespace.setText("命名空间");
        colStatus.setText("状态");
        colDetail.setText(detailTitle(activeKind));
        colAge.setText("存活时间");
        boolean namespaced = activeKind.namespaced();
        colNamespace.setVisible(namespaced);
    }

    private String detailTitle(ResourceKind kind) {
        return switch (kind) {
            case PODS -> "就绪 / 重启";
            case SERVICES -> "类型 / Cluster IP";
            case INGRESSES -> "域名";
            case NODES -> "角色 / 版本";
            case EVENTS -> "原因";
            case PERSISTENT_VOLUMES, PERSISTENT_VOLUME_CLAIMS -> "容量 / 访问模式";
            case STORAGE_CLASSES -> "Provisioner";
            default -> "详情";
        };
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
            if (namespaceFiltered && row.kind().namespaced() && !Objects.equals(row.namespace(), namespace)) {
                continue;
            }
            if (query.isEmpty()
                    || contains(row.name(), query)
                    || contains(row.namespace(), query)
                    || contains(row.status(), query)
                    || contains(row.detail(), query)
                    || contains(row.age(), query)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private void updatePage() {
        updatePage(filteredRows());
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
        MenuItem describe = new MenuItem("查看详情");
        describe.setOnAction(event -> showResourceAction("查看详情", row));
        MenuItem yaml = new MenuItem("查看 YAML");
        yaml.setOnAction(event -> showResourceAction("查看 YAML", row));
        MenuItem edit = new MenuItem("编辑");
        edit.setOnAction(event -> showResourceAction("编辑", row));
        MenuItem copyName = new MenuItem("复制名称");
        copyName.setOnAction(event -> copyText(row.name()));
        MenuItem delete = new MenuItem("删除");
        delete.setOnAction(event -> showResourceAction("删除", row));
        return new ContextMenu(describe, yaml, edit, copyName, new SeparatorMenuItem(), delete);
    }

    private void copySelectedRowsToClipboard() {
        List<K8sRow> selected = new ArrayList<>(resourceTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (K8sRow row : selected) {
            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator());
            }
            builder.append(row.name()).append('\t')
                    .append(row.namespace()).append('\t')
                    .append(row.status()).append('\t')
                    .append(row.detail()).append('\t')
                    .append(row.age());
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

    private void showResourceAction(String action, K8sRow row) {
        DialogHelper.showInfo("Kubernetes", action + ": " + row.kind().label() + " / " + row.name());
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

    private Button makeToolbarButton(String tooltipText, String iconLiteral, Runnable action) {
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

    private void updateClusterStatus() {
        lblK8sVersion.setText("v1.28.0");
    }

    private List<K8sRow> sampleRows(ResourceKind kind) {
        int count = kind == ResourceKind.PODS ? 126 : sampleCount(kind);
        List<K8sRow> rows = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String namespace = namespaceFor(i, kind);
            rows.add(new K8sRow(
                    kind,
                    sampleName(kind, i),
                    kind.namespaced() ? namespace : "-",
                    sampleStatus(kind, i),
                    sampleDetail(kind, i),
                    ageFor(i)
            ));
        }
        return rows;
    }

    private int sampleCount(ResourceKind kind) {
        return switch (kind) {
            case EVENTS -> 118;
            case SERVICES -> 18;
            case CONFIG_MAPS -> 24;
            case SECRETS -> 32;
            case NODES -> 5;
            case NAMESPACES -> 6;
            case PERSISTENT_VOLUMES -> 12;
            case STORAGE_CLASSES -> 4;
            default -> 14;
        };
    }

    private String sampleName(ResourceKind kind, int index) {
        String prefix = switch (kind) {
            case CRON_JOBS -> "backup";
            case DAEMON_SETS -> "node-agent";
            case JOBS -> "migration";
            case PODS -> "api";
            case REPLICA_SETS -> "frontend";
            case REPLICATION_CONTROLLERS -> "legacy-web";
            case STATEFUL_SETS -> "mysql";
            case INGRESSES -> "public";
            case SERVICES -> "svc";
            case CONFIG_MAPS -> "config";
            case PERSISTENT_VOLUME_CLAIMS -> "data";
            case SECRETS -> "secret";
            case STORAGE_CLASSES -> "storage";
            case CLUSTER_ROLE_BINDINGS -> "cluster-binding";
            case CLUSTER_ROLES -> "cluster-role";
            case EVENTS -> "event";
            case NAMESPACES -> "namespace";
            case NETWORK_POLICIES -> "policy";
            case NODES -> "node";
            case PERSISTENT_VOLUMES -> "pv";
            case ROLE_BINDINGS -> "role-binding";
            case ROLES -> "role";
            case SERVICE_ACCOUNTS -> "service-account";
        };
        return prefix + "-" + String.format("%03d", index);
    }

    private String sampleStatus(ResourceKind kind, int index) {
        if (kind == ResourceKind.EVENTS) {
            return index % 9 == 0 ? "警告" : "正常";
        }
        if (kind == ResourceKind.NODES) {
            return index % 5 == 0 ? "未就绪" : "就绪";
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
            default -> "运行中";
        };
    }

    private String sampleDetail(ResourceKind kind, int index) {
        return switch (kind) {
            case PODS -> (index % 5 == 0 ? "0/1" : "1/1") + " / " + (index % 4);
            case SERVICES -> (index % 3 == 0 ? "LoadBalancer" : "ClusterIP") + " / 10.96." + (index % 30) + "." + index;
            case INGRESSES -> "app" + index + ".example.local";
            case NODES -> (index == 1 ? "control-plane" : "worker") + " / v1.28.0";
            case EVENTS -> index % 9 == 0 ? "BackOff" : "Scheduled";
            case PERSISTENT_VOLUMES, PERSISTENT_VOLUME_CLAIMS -> (10 + index) + "Gi / RWO";
            case STORAGE_CLASSES -> index % 2 == 0 ? "kubernetes.io/no-provisioner" : "csi.example.com";
            case SECRETS -> index % 2 == 0 ? "Opaque" : "kubernetes.io/tls";
            case CONFIG_MAPS -> (2 + index % 6) + " 个键";
            default -> "副本 " + (1 + index % 4);
        };
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

    private String ageFor(int index) {
        if (index < 24) {
            return index + "h";
        }
        return Math.max(1, index / 3) + "d";
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private enum Category {
        WORKLOADS("Workloads", "fas-layer-group"),
        SERVICE("Service", "fas-network-wired"),
        CONFIG_STORAGE("Config and Storage", "fas-database"),
        CLUSTER("Cluster", "fas-project-diagram");

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

    private enum ResourceKind {
        CRON_JOBS(Category.WORKLOADS, "Cron Jobs", true),
        DAEMON_SETS(Category.WORKLOADS, "Daemon Sets", true),
        JOBS(Category.WORKLOADS, "Jobs", true),
        PODS(Category.WORKLOADS, "Pods", true),
        REPLICA_SETS(Category.WORKLOADS, "Replica Sets", true),
        REPLICATION_CONTROLLERS(Category.WORKLOADS, "Replication Controllers", true),
        STATEFUL_SETS(Category.WORKLOADS, "Stateful Sets", true),
        INGRESSES(Category.SERVICE, "Ingresses", true),
        SERVICES(Category.SERVICE, "Services", true),
        CONFIG_MAPS(Category.CONFIG_STORAGE, "Config Maps", true),
        PERSISTENT_VOLUME_CLAIMS(Category.CONFIG_STORAGE, "Persistent Volume Claims", true),
        SECRETS(Category.CONFIG_STORAGE, "Secrets", true),
        STORAGE_CLASSES(Category.CONFIG_STORAGE, "Storage Classes", false),
        CLUSTER_ROLE_BINDINGS(Category.CLUSTER, "Cluster Role Bindings", false),
        CLUSTER_ROLES(Category.CLUSTER, "Cluster Roles", false),
        EVENTS(Category.CLUSTER, "Events", true),
        NAMESPACES(Category.CLUSTER, "Namespaces", false),
        NETWORK_POLICIES(Category.CLUSTER, "Network Policies", true),
        NODES(Category.CLUSTER, "Nodes", false),
        PERSISTENT_VOLUMES(Category.CLUSTER, "Persistent Volumes", false),
        ROLE_BINDINGS(Category.CLUSTER, "Role Bindings", true),
        ROLES(Category.CLUSTER, "Roles", true),
        SERVICE_ACCOUNTS(Category.CLUSTER, "Service Accounts", true);

        private final Category category;
        private final String label;
        private final boolean namespaced;

        ResourceKind(Category category, String label, boolean namespaced) {
            this.category = category;
            this.label = label;
            this.namespaced = namespaced;
        }

        private Category category() {
            return category;
        }

        private String label() {
            return label;
        }

        private boolean namespaced() {
            return namespaced;
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

    private record K8sRow(ResourceKind kind, String name, String namespace, String status, String detail, String age) {
    }

    private static final class NodeUtil {
        private NodeUtil() {
        }

        private static void setVisibleManaged(javafx.scene.Node node, boolean visible) {
            node.setVisible(visible);
            node.setManaged(visible);
        }
    }
}

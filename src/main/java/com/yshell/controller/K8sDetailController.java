package com.yshell.controller;

import com.yshell.theme.ThemeManager;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class K8sDetailController {
    @FXML
    private BorderPane root;
    @FXML
    private HBox actionBar;
    @FXML
    private Button btnClose;
    @FXML
    private Label kindLabel;
    @FXML
    private Label titleLabel;
    @FXML
    private Label subtitleLabel;
    @FXML
    private VBox overviewHost;

    private Stage stage;

    @FXML
    public void initialize() {
        WindowDragResize.apply(root, 52, actionBar, btnClose);
        btnClose.setOnAction(e -> close());
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public void setData(DetailPageData data) {
        kindLabel.setText(data.kindLabel());
        titleLabel.setText(data.title());
        subtitleLabel.setText(data.subtitle());
        renderActions(data.actions(), data.primaryActionHandler());
        renderOverview(data);
    }

    private void renderActions(List<DetailActionSpec> actions, DetailActionHandler handler) {
        actionBar.getChildren().clear();
        for (DetailActionSpec spec : actions) {
            Button button = new Button(spec.label());
            button.getStyleClass().add("detail-action-btn");
            if (spec.primary()) {
                button.getStyleClass().add("button-primary");
            } else {
                button.getStyleClass().add("button-cancel");
            }
            button.setOnAction(e -> handler.handle(spec));
            actionBar.getChildren().add(button);
        }
    }

    private void renderOverview(DetailPageData data) {
        overviewHost.getChildren().clear();
        overviewHost.getChildren().add(createKeyValueCard("元数据", data.metadata()));
        overviewHost.getChildren().add(createKeyValueCard("资源信息", data.resourceInfo()));
        for (DetailSectionSpec section : data.sections()) {
            switch (section.type()) {
                case KV -> overviewHost.getChildren().add(createKeyValueCard(section.title(), section.values()));
                case TEXT -> overviewHost.getChildren().add(createTextCard(section.title(), section.text()));
                case TABLE ->
                        overviewHost.getChildren().add(createTableCard(section.title(), section.columns(), section.rows()));
                case LIST -> overviewHost.getChildren().add(createListCard(section.title(), section.items()));
                case CARD_GROUP -> overviewHost.getChildren().add(createCardGroup(section.title(), section.rows()));
            }
        }
    }

    private VBox createKeyValueCard(String title, Map<String, String> values) {
        VBox card = createCardShell(title);
        GridPane grid = new GridPane();
        grid.getStyleClass().add("detail-kv-grid");
        grid.setHgap(12);
        grid.setVgap(10);

        int row = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith("_")) {
                continue;
            }
            Label key = new Label(entry.getKey());
            key.getStyleClass().add("detail-kv-key");
            Label value = new Label(entry.getValue() == null || entry.getValue().isBlank() ? "-" : entry.getValue());
            value.setWrapText(true);
            value.getStyleClass().add("detail-kv-value");
            grid.addRow(row++, key, value);
        }
        if (row == 0) {
            Label empty = new Label("暂无内容");
            empty.getStyleClass().add("detail-empty");
            grid.add(empty, 0, 0, 2, 1);
        }

        ColumnConstraints keyCol = new ColumnConstraints();
        keyCol.setMinWidth(140);
        keyCol.setPrefWidth(160);
        keyCol.setHgrow(Priority.NEVER);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(keyCol, valueCol);

        card.getChildren().add(grid);
        return card;
    }

    private VBox createTextCard(String title, String text) {
        VBox card = createCardShell(title);
        TextArea area = new TextArea(text == null || text.isBlank() ? "暂无内容" : text);
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("detail-text-area");
        area.setPrefRowCount(8);
        card.getChildren().add(area);
        return card;
    }

    private VBox createTableCard(String title, List<String> columns, List<Map<String, String>> rows) {
        VBox card = createCardShell(title);
        TableView<Map<String, String>> table = new TableView<>();
        table.getStyleClass().add("detail-table");
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(Math.min(280, Math.max(120, (rows.size() + 1) * 32)));

        for (String columnName : columns) {
            TableColumn<Map<String, String>, String> column = new TableColumn<>(columnName);
            column.setPrefWidth(Math.max(120, Math.min(240, columnName.length() * 16.0)));
            column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                    data.getValue().getOrDefault(columnName, "-")));
            table.getColumns().add(column);
        }

        table.setItems(FXCollections.observableArrayList(rows));
        card.getChildren().add(table);
        return card;
    }

    private VBox createListCard(String title, List<String> items) {
        VBox card = createCardShell(title);
        ListView<String> listView = new ListView<>(FXCollections.observableArrayList(items));
        listView.getStyleClass().add("detail-list");
        listView.setPrefHeight(Math.min(260, Math.max(96, items.size() * 28 + 16)));
        card.getChildren().add(listView);
        return card;
    }

    private VBox createCardGroup(String title, List<Map<String, String>> cards) {
        VBox group = createCardShell(title);
        if (cards.isEmpty()) {
            Label empty = new Label("暂无内容");
            empty.getStyleClass().add("detail-empty");
            group.getChildren().add(empty);
            return group;
        }
        for (Map<String, String> values : cards) {
            VBox item = new VBox();
            item.getStyleClass().add("detail-object-block");
            String itemTitle = values.get("_标题");
            if (itemTitle != null && !itemTitle.isBlank()) {
                Label itemHeader = new Label(itemTitle);
                itemHeader.getStyleClass().add("detail-object-title");
                item.getChildren().add(itemHeader);
            }

            GridPane grid = new GridPane();
            grid.getStyleClass().add("detail-kv-grid");
            grid.setHgap(12);
            grid.setVgap(8);
            int row = 0;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey().startsWith("_")) {
                    continue;
                }
                Label key = new Label(entry.getKey());
                key.getStyleClass().add("detail-kv-key");
                Label value = new Label(entry.getValue() == null || entry.getValue().isBlank() ? "-" : entry.getValue());
                value.setWrapText(true);
                value.getStyleClass().add("detail-kv-value");
                grid.addRow(row++, key, value);
            }

            ColumnConstraints keyCol = new ColumnConstraints();
            keyCol.setMinWidth(140);
            keyCol.setPrefWidth(160);
            keyCol.setHgrow(Priority.NEVER);
            ColumnConstraints valueCol = new ColumnConstraints();
            valueCol.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(keyCol, valueCol);

            item.getChildren().add(grid);
            group.getChildren().add(item);
        }
        return group;
    }

    private VBox createCardShell(String title) {
        VBox card = new VBox();
        card.getStyleClass().add("detail-card");

        Label header = new Label(title);
        header.getStyleClass().add("detail-card-title");

        card.getChildren().add(header);
        return card;
    }

    private void close() {
        if (stage != null) {
            stage.close();
        }
    }

    public static void show(Window owner, DetailPageData data) {
        try {
            FXMLLoader loader = new FXMLLoader(K8sDetailController.class.getResource("/fxml/K8sDetailView.fxml"));
            Parent root = loader.load();
            K8sDetailController controller = loader.getController();

            Stage stage = new Stage();
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setTitle(data.title());
            stage.setMinWidth(920);
            stage.setMinHeight(640);
            stage.setWidth(1180);
            stage.setHeight(820);
            stage.setResizable(true);

            Scene scene = new Scene(root);
            ThemeManager.getInstance().registerScene(scene);
            stage.setScene(scene);
            controller.setStage(stage);
            controller.setData(data);

            stage.setOnHidden(e -> ThemeManager.getInstance().unregisterScene(scene));
            if (owner != null) {
                stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
                stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
            }
            stage.show();
        } catch (IOException e) {
            DialogHelper.showError("错误", "无法打开详情窗口: " + e.getMessage());
        }
    }

    public record DetailPageData(
            String kindLabel,
            String title,
            String subtitle,
            Map<String, String> metadata,
            Map<String, String> resourceInfo,
            List<DetailSectionSpec> sections,
            List<DetailActionSpec> actions,
            List<String> events,
            String yaml,
            DetailActionHandler primaryActionHandler
    ) {
    }

    public record DetailSectionSpec(
            String title,
            SectionType type,
            Map<String, String> values,
            String text,
            List<String> columns,
            List<Map<String, String>> rows,
            List<String> items
    ) {
        public static DetailSectionSpec kv(String title, Map<String, String> values) {
            return new DetailSectionSpec(title, SectionType.KV, values, null, List.of(), List.of(), List.of());
        }

        public static DetailSectionSpec text(String title, String text) {
            return new DetailSectionSpec(title, SectionType.TEXT, Map.of(), text, List.of(), List.of(), List.of());
        }

        public static DetailSectionSpec table(String title, List<String> columns, List<Map<String, String>> rows) {
            return new DetailSectionSpec(title, SectionType.TABLE, Map.of(), null, columns, rows, List.of());
        }

        public static DetailSectionSpec list(String title, List<String> items) {
            return new DetailSectionSpec(title, SectionType.LIST, Map.of(), null, List.of(), List.of(), items);
        }

        public static DetailSectionSpec cardGroup(String title, List<Map<String, String>> cards) {
            return new DetailSectionSpec(title, SectionType.CARD_GROUP, Map.of(), null, List.of(), cards, List.of());
        }
    }

    public enum SectionType {
        KV,
        TEXT,
        TABLE,
        LIST,
        CARD_GROUP
    }

    public record DetailActionSpec(String label, String hint, boolean primary) {
    }

    @FunctionalInterface
    public interface DetailActionHandler {
        void handle(DetailActionSpec actionSpec);
    }
}

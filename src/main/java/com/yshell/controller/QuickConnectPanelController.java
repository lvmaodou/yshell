package com.yshell.controller;

import com.yshell.model.ConnInfo;
import com.yshell.service.ConnectionManager;
import com.yshell.service.RecentConnectionRepository;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import org.kordamp.ikonli.fontawesome5.FontAwesomeBrands;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

public class QuickConnectPanelController {

    @FXML
    private ListView<ConnInfo> connectionList;

    @FXML
    private Button btnClear;

    @FXML
    public void initialize() {
        setupListView();
        btnClear.setOnAction(e -> clearHistory());
        RecentConnectionRepository.getInstance().addChangeListener(() -> Platform.runLater(this::loadConnections));
        loadConnections();
    }

    /**
     * 刷新连接列表（供外部调用）
     */
    public void refresh() {
        loadConnections();
    }

    private void setupListView() {
        connectionList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ConnInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                setGraphic(buildConnectionRow(item));
            }
        });

        // 单击即连接
        connectionList.setOnMouseClicked(e -> {
            ConnInfo selected = connectionList.getSelectionModel().getSelectedItem();
            if (selected != null && e.getClickCount() >= 1) {
                connect(selected);
            }
        });
    }

    private GridPane buildConnectionRow(ConnInfo connInfo) {
        GridPane row = new GridPane();
        row.getStyleClass().add("qc-row");
        row.setMaxWidth(Double.MAX_VALUE);
        row.prefWidthProperty().bind(connectionList.widthProperty().subtract(42));
        row.getColumnConstraints().addAll(
                column(3),
                column(28),
                column(30),
                column(12),
                column(22)
        );

        FontIcon osIcon = new FontIcon(resolveOsIcon(connInfo));
        osIcon.setIconSize(16);
        osIcon.getStyleClass().addAll(
                isWindowsConnection(connInfo) ? "icon-connection-windows" : "icon-connection-linux"
        );

        Label nameLabel = new Label(valueOrBlank(connInfo.getName()));
        nameLabel.getStyleClass().add("qc-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);

        Label hostLabel = new Label(valueOrBlank(connInfo.getHost()));
        hostLabel.getStyleClass().add("qc-host");
        hostLabel.setMaxWidth(Double.MAX_VALUE);

        Label portLabel = new Label(String.valueOf(connInfo.getPort()));
        portLabel.getStyleClass().add("qc-port");
        portLabel.setMaxWidth(Double.MAX_VALUE);

        Label userLabel = new Label(valueOrBlank(connInfo.getUserName()));
        userLabel.getStyleClass().add("qc-username");
        userLabel.setMaxWidth(Double.MAX_VALUE);

        GridPane.setHgrow(nameLabel, Priority.ALWAYS);
        GridPane.setHgrow(hostLabel, Priority.ALWAYS);
        GridPane.setHgrow(portLabel, Priority.ALWAYS);
        GridPane.setHgrow(userLabel, Priority.ALWAYS);

        row.add(osIcon, 0, 0);
        row.add(nameLabel, 1, 0);
        row.add(hostLabel, 2, 0);
        row.add(portLabel, 3, 0);
        row.add(userLabel, 4, 0);
        return row;
    }

    private FontAwesomeBrands resolveOsIcon(ConnInfo connInfo) {
        return isWindowsConnection(connInfo) ? FontAwesomeBrands.WINDOWS : FontAwesomeBrands.LINUX;
    }

    private boolean isWindowsConnection(ConnInfo connInfo) {
        return connInfo != null && (connInfo.getConnectionType() == 200 || "rdp".equalsIgnoreCase(connInfo.getType()));
    }

    private ColumnConstraints column(double percentWidth) {
        ColumnConstraints constraints = new ColumnConstraints();
        constraints.setPercentWidth(percentWidth);
        constraints.setHgrow(Priority.ALWAYS);
        constraints.setFillWidth(true);
        return constraints;
    }

    private void connect(ConnInfo connInfo) {
        ConnectionManager.getInstance().connect(connInfo);
    }

    private void clearHistory() {
        RecentConnectionRepository.getInstance().clear();
        connectionList.getItems().clear();
    }

    private void loadConnections() {
        List<ConnInfo> conns = RecentConnectionRepository.getInstance().load();
        connectionList.getItems().setAll(conns);
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value;
    }
}

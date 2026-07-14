package com.yshell.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class VisualPanelController {

    @FXML
    private Label tabFiles;

    @FXML
    private Label tabDocker;

    @FXML
    private Label tabK8s;

    @FXML
    private Label tabAi;

    @FXML
    private StackPane contentArea;

    @FXML
    private VBox filesView;

    @FXML
    private VBox dockerView;

    @FXML
    private DockerViewController dockerViewController;

    @FXML
    private VBox k8sView;

    @FXML
    private VBox aiView;

    @FXML
    public void initialize() {
        setupViewConstraints();

        if (tabFiles != null) {
            tabFiles.setOnMouseClicked(e -> switchTab("files"));
        }
        if (tabDocker != null) {
            tabDocker.setOnMouseClicked(e -> switchTab("docker"));
        }
        if (tabK8s != null) {
            tabK8s.setOnMouseClicked(e -> switchTab("k8s"));
        }
        if (tabAi != null) {
            tabAi.setOnMouseClicked(e -> switchTab("ai"));
        }

        setViewVisible(dockerView, false);
        setViewVisible(k8sView, false);
        setViewVisible(aiView, false);
        if (dockerViewController != null) {
            dockerViewController.setTabVisible(false);
        }
    }

    private void setupViewConstraints() {
        bindWidthHeight(filesView);
        bindWidthHeight(dockerView);
        bindWidthHeight(k8sView);
        bindWidthHeight(aiView);
    }

    private void bindWidthHeight(VBox vBox) {
        if (vBox != null) {
            vBox.prefWidthProperty().bind(contentArea.widthProperty());
            vBox.prefHeightProperty().bind(contentArea.heightProperty());
        }
    }

    private void switchTab(String tabName) {
        if (tabFiles != null) tabFiles.getStyleClass().remove("active");
        if (tabDocker != null) tabDocker.getStyleClass().remove("active");
        if (tabK8s != null) tabK8s.getStyleClass().remove("active");
        if (tabAi != null) tabAi.getStyleClass().remove("active");

        setViewVisible(filesView, false);
        setViewVisible(dockerView, false);
        setViewVisible(k8sView, false);
        setViewVisible(aiView, false);
        if (dockerViewController != null) {
            dockerViewController.setTabVisible("docker".equals(tabName));
        }

        switch (tabName) {
            case "files":
                if (tabFiles != null) tabFiles.getStyleClass().add("active");
                setViewVisible(filesView, true);
                break;
            case "docker":
                if (tabDocker != null) tabDocker.getStyleClass().add("active");
                setViewVisible(dockerView, true);
                break;
            case "k8s":
                if (tabK8s != null) tabK8s.getStyleClass().add("active");
                setViewVisible(k8sView, true);
                break;
            case "ai":
                if (tabAi != null) tabAi.getStyleClass().add("active");
                setViewVisible(aiView, true);
                break;
        }
    }

    private void setViewVisible(VBox view, boolean visible) {
        if (view == null) {
            return;
        }
        view.setVisible(visible);
        view.setManaged(visible);
    }
}

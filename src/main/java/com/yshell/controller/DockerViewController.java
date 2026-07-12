package com.yshell.controller;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

public class DockerViewController {

    @FXML
    private HBox navContainers;

    @FXML
    private HBox navImages;

    @FXML
    private HBox navNetworks;

    @FXML
    private HBox navVolumes;

    @FXML
    private TextField searchBox;

    @FXML
    public void initialize() {
        if (navContainers != null) {
            navContainers.setOnMouseClicked(e -> switchNav("containers"));
        }
        if (navImages != null) {
            navImages.setOnMouseClicked(e -> switchNav("images"));
        }
        if (navNetworks != null) {
            navNetworks.setOnMouseClicked(e -> switchNav("networks"));
        }
        if (navVolumes != null) {
            navVolumes.setOnMouseClicked(e -> switchNav("volumes"));
        }
    }

    private void switchNav(String navName) {
        if (navContainers != null) navContainers.getStyleClass().remove("active");
        if (navImages != null) navImages.getStyleClass().remove("active");
        if (navNetworks != null) navNetworks.getStyleClass().remove("active");
        if (navVolumes != null) navVolumes.getStyleClass().remove("active");

        switch (navName) {
            case "containers":
                if (navContainers != null) navContainers.getStyleClass().add("active");
                break;
            case "images":
                if (navImages != null) navImages.getStyleClass().add("active");
                break;
            case "networks":
                if (navNetworks != null) navNetworks.getStyleClass().add("active");
                break;
            case "volumes":
                if (navVolumes != null) navVolumes.getStyleClass().add("active");
                break;
        }
    }
}
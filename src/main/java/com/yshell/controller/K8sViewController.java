package com.yshell.controller;

import javafx.fxml.FXML;
import javafx.scene.layout.HBox;

public class K8sViewController {

    @FXML
    private HBox navPods;

    @FXML
    private HBox navDeployments;

    @FXML
    private HBox navServices;

    @FXML
    private HBox navConfigMaps;

    @FXML
    private HBox navSecrets;

    @FXML
    private HBox navNamespaces;

    @FXML
    public void initialize() {
        if (navPods != null) {
            navPods.setOnMouseClicked(e -> switchNav("pods"));
        }
        if (navDeployments != null) {
            navDeployments.setOnMouseClicked(e -> switchNav("deployments"));
        }
        if (navServices != null) {
            navServices.setOnMouseClicked(e -> switchNav("services"));
        }
        if (navConfigMaps != null) {
            navConfigMaps.setOnMouseClicked(e -> switchNav("configmaps"));
        }
        if (navSecrets != null) {
            navSecrets.setOnMouseClicked(e -> switchNav("secrets"));
        }
        if (navNamespaces != null) {
            navNamespaces.setOnMouseClicked(e -> switchNav("namespaces"));
        }
    }

    private void switchNav(String navName) {
        if (navPods != null) navPods.getStyleClass().remove("active");
        if (navDeployments != null) navDeployments.getStyleClass().remove("active");
        if (navServices != null) navServices.getStyleClass().remove("active");
        if (navConfigMaps != null) navConfigMaps.getStyleClass().remove("active");
        if (navSecrets != null) navSecrets.getStyleClass().remove("active");
        if (navNamespaces != null) navNamespaces.getStyleClass().remove("active");

        switch (navName) {
            case "pods":
                if (navPods != null) navPods.getStyleClass().add("active");
                break;
            case "deployments":
                if (navDeployments != null) navDeployments.getStyleClass().add("active");
                break;
            case "services":
                if (navServices != null) navServices.getStyleClass().add("active");
                break;
            case "configmaps":
                if (navConfigMaps != null) navConfigMaps.getStyleClass().add("active");
                break;
            case "secrets":
                if (navSecrets != null) navSecrets.getStyleClass().add("active");
                break;
            case "namespaces":
                if (navNamespaces != null) navNamespaces.getStyleClass().add("active");
                break;
        }
    }
}
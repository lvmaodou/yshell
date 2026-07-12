package com.yshell.controller;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

public class AiViewController {

    @FXML
    private ComboBox<String> aiModelSelector;

    @FXML
    private VBox chatMessages;

    @FXML
    private TextArea inputArea;

    @FXML
    public void initialize() {
        if (aiModelSelector != null) {
            aiModelSelector.setItems(FXCollections.observableArrayList(
                    "GPT-4o",
                    "GPT-4",
                    "GPT-3.5",
                    "Claude 3.5",
                    "Gemini 1.5"
            ));
            aiModelSelector.getSelectionModel().selectFirst();
        }
    }
}
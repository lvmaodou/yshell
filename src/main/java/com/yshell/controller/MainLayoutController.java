package com.yshell.controller;

import com.yshell.ui.PanelManager;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.SplitPane;

import java.net.URL;
import java.util.ResourceBundle;

public class MainLayoutController implements Initializable {

    @FXML
    private SplitPane mainSplitPane;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        PanelManager.getInstance().setMainSplitPane(mainSplitPane);
    }
}

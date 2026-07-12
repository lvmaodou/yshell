package com.yshell.model;

import com.yshell.controller.TerminalPanelController;
import javafx.scene.Node;

public class ConnectionTabInfo {

    private ConnInfo connInfo;
    private String connId;
    private Node terminalPanelNode;
    private TerminalPanelController terminalPanelController;

    public ConnectionTabInfo() {
    }

    public ConnectionTabInfo(ConnInfo connInfo, String connId) {
        this.connInfo = connInfo;
        this.connId = connId;
    }

    public ConnInfo getConnInfo() {
        return connInfo;
    }

    public void setConnInfo(ConnInfo connInfo) {
        this.connInfo = connInfo;
    }

    public String getConnId() {
        return connId;
    }

    public void setConnId(String connId) {
        this.connId = connId;
    }

    public Node getTerminalPanelNode() {
        return terminalPanelNode;
    }

    public void setTerminalPanelNode(Node terminalPanelNode) {
        this.terminalPanelNode = terminalPanelNode;
    }

    public TerminalPanelController getTerminalPanelController() {
        return terminalPanelController;
    }

    public void setTerminalPanelController(TerminalPanelController terminalPanelController) {
        this.terminalPanelController = terminalPanelController;
    }
}

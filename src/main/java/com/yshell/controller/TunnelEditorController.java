package com.yshell.controller;

import com.yshell.model.TunnelInfo;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.util.function.Consumer;

public class TunnelEditorController {

    @FXML
    private Label dialogTitle;
    @FXML
    private Button btnClose;
    @FXML
    private Parent root;
    @FXML
    private TextField fieldName;
    @FXML
    private ComboBox<String> fieldType;
    @FXML
    private TextField fieldListenPort;
    @FXML
    private TextField fieldBindIp;
    @FXML
    private TextField fieldTargetHost;
    @FXML
    private TextField fieldTargetPort;
    @FXML
    private HBox rowTarget;
    @FXML
    private HBox rowTargetPort;

    private Stage dialogStage;
    private boolean isEditMode = false;
    private TunnelInfo editingTunnel;
    private Consumer<TunnelInfo> saveHandler;

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    public void setEditMode(boolean isEdit) {
        this.isEditMode = isEdit;
        dialogTitle.setText(isEdit ? "编辑隧道" : "新建隧道");
    }

    public void setSaveHandler(Consumer<TunnelInfo> handler) {
        this.saveHandler = handler;
    }

    /**
     * 加载已有隧道数据到表单（编辑模式）
     */
    public void loadTunnel(TunnelInfo tunnel) {
        this.editingTunnel = tunnel;
        fieldName.setText(tunnel.getName() != null ? tunnel.getName() : "");
        fieldType.setValue(tunnel.getTypeDisplayName());
        fieldListenPort.setText(tunnel.getListenPort() > 0 ? String.valueOf(tunnel.getListenPort()) : "");
        fieldBindIp.setText(tunnel.getBindIp() != null ? tunnel.getBindIp() : "127.0.0.1");
        fieldTargetHost.setText(tunnel.getTargetHost() != null ? tunnel.getTargetHost() : "");
        fieldTargetPort.setText(tunnel.getTargetPort() > 0 ? String.valueOf(tunnel.getTargetPort()) : "");
        onTypeChanged();
    }

    @FXML
    public void initialize() {
        WindowDragResize.apply(root, -1, btnClose);
        fieldType.getItems().addAll("本地", "远程", "SOCKS5");
        fieldType.setValue("本地");
        fieldType.setOnAction(e -> onTypeChanged());
    }

    /**
     * 根据隧道类型切换字段显示/隐藏
     * - 本地(local): 显示全部字段
     * - 远程(remote): 显示全部字段
     * - SOCKS5(dynamic): 仅显示名称、类型、监听端口、绑定IP，隐藏目标地址和目标端口
     */
    private void onTypeChanged() {
        String type = fieldType.getValue();
        boolean showTarget = !"SOCKS5".equals(type);

        rowTarget.setVisible(showTarget);
        rowTarget.setManaged(showTarget);
        rowTargetPort.setVisible(showTarget);
        rowTargetPort.setManaged(showTarget);
    }

    @FXML
    private void onOk() {
        // 校验必填字段
        if (fieldName.getText() == null || fieldName.getText().trim().isEmpty()) {
            DialogHelper.showError("隧道名称不能为空");
            return;
        }
        if (fieldListenPort.getText() == null || fieldListenPort.getText().trim().isEmpty()) {
            DialogHelper.showError("监听端口不能为空");
            return;
        }
        try {
            int port = Integer.parseInt(fieldListenPort.getText().trim());
            if (port < 1 || port > 65535) {
                DialogHelper.showError("监听端口必须在 1-65535 之间");
                return;
            }
        } catch (NumberFormatException e) {
            DialogHelper.showError("请输入有效的端口号");
            return;
        }

        TunnelInfo tunnel = getTunnelInfo();

        if (saveHandler != null) {
            saveHandler.accept(tunnel);
        }
        closeDialog();
    }

    private TunnelInfo getTunnelInfo() {
        String typeName = fieldType.getValue();
        String typeValue = switch (typeName) {
            case "远程" -> "remote";
            case "SOCKS5" -> "dynamic";
            default -> "local";
        };

        TunnelInfo tunnel;
        if (isEditMode && editingTunnel != null) {
            tunnel = editingTunnel;
        } else {
            tunnel = new TunnelInfo();
        }

        tunnel.setName(fieldName.getText().trim());
        tunnel.setType(typeValue);
        tunnel.setListenPort(Integer.parseInt(fieldListenPort.getText().trim()));
        tunnel.setBindIp(fieldBindIp.getText() != null ? fieldBindIp.getText().trim() : "127.0.0.1");

        if (!"dynamic".equals(typeValue)) {
            tunnel.setTargetHost(fieldTargetHost.getText() != null ? fieldTargetHost.getText().trim() : "");
            try {
                tunnel.setTargetPort(Integer.parseInt(fieldTargetPort.getText().trim()));
            } catch (NumberFormatException e) {
                tunnel.setTargetPort(0);
            }
        } else {
            tunnel.setTargetHost("");
            tunnel.setTargetPort(0);
        }
        return tunnel;
    }

    @FXML
    private void closeDialog() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }
}

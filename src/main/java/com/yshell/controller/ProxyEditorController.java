package com.yshell.controller;

import com.yshell.model.ProxyInfo;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.util.function.Consumer;

public class ProxyEditorController {

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
    private TextField fieldHost;
    @FXML
    private TextField fieldPort;
    @FXML
    private TextField fieldUsername;
    @FXML
    private PasswordField fieldPassword;

    private Stage dialogStage;
    private boolean isEditMode = false;
    private ProxyInfo editingProxy;
    private Consumer<ProxyInfo> saveHandler;

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    public void setEditMode(boolean isEdit) {
        this.isEditMode = isEdit;
        dialogTitle.setText(isEdit ? "编辑代理服务器" : "新建代理服务器");
    }

    public void setSaveHandler(Consumer<ProxyInfo> handler) {
        this.saveHandler = handler;
    }

    /**
     * 加载已有代理数据到表单（编辑模式）
     */
    public void loadProxy(ProxyInfo proxy) {
        this.editingProxy = proxy;
        fieldName.setText(proxy.getName() != null ? proxy.getName() : "");
        fieldType.setValue(proxy.getTypeDisplayName());
        fieldHost.setText(proxy.getHost() != null ? proxy.getHost() : "");
        fieldPort.setText(proxy.getPort() > 0 ? String.valueOf(proxy.getPort()) : "");
        fieldUsername.setText(proxy.getUsername() != null ? proxy.getUsername() : "");
        fieldPassword.setText(proxy.getPassword() != null ? proxy.getPassword() : "");
    }

    @FXML
    public void initialize() {
        WindowDragResize.apply(root, -1, btnClose);
        fieldType.getItems().addAll("SOCKS5", "HTTP");
        fieldType.setValue("SOCKS5");
    }

    @FXML
    private void onOk() {
        // 校验必填字段
        if (fieldName.getText() == null || fieldName.getText().trim().isEmpty()) {
            DialogHelper.showError("代理名称不能为空");
            return;
        }
        if (fieldHost.getText() == null || fieldHost.getText().trim().isEmpty()) {
            DialogHelper.showError("主机地址不能为空");
            return;
        }
        if (fieldPort.getText() == null || fieldPort.getText().trim().isEmpty()) {
            DialogHelper.showError("端口不能为空");
            return;
        }
        try {
            int port = Integer.parseInt(fieldPort.getText().trim());
            if (port < 1 || port > 65535) {
                DialogHelper.showError("端口必须在 1-65535 之间");
                return;
            }
        } catch (NumberFormatException e) {
            DialogHelper.showError("请输入有效的端口号");
            return;
        }

        ProxyInfo proxy = getProxyInfo();

        if (saveHandler != null) {
            saveHandler.accept(proxy);
        }
        closeDialog();
    }

    private ProxyInfo getProxyInfo() {
        String typeName = fieldType.getValue();
        String typeValue = "HTTP".equals(typeName) ? "http" : "socks5";
        ProxyInfo proxy;
        if (isEditMode && editingProxy != null) {
            proxy = editingProxy;
        } else {
            proxy = new ProxyInfo();
        }

        proxy.setName(fieldName.getText().trim());
        proxy.setType(typeValue);
        proxy.setHost(fieldHost.getText().trim());
        proxy.setPort(Integer.parseInt(fieldPort.getText().trim()));
        proxy.setUsername(fieldUsername.getText() != null ? fieldUsername.getText().trim() : "");
        proxy.setPassword(fieldPassword.getText() != null ? fieldPassword.getText() : "");
        return proxy;
    }

    @FXML
    private void closeDialog() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }
}

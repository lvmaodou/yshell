package com.yshell.controller;

import com.yshell.model.SshKeyInfo;
import com.yshell.service.ConnectionManager;
import com.yshell.service.SshKeyRepository;
import com.yshell.service.SshKeyService;
import com.yshell.service.SshService;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

public class KeyManagerController {

    @FXML
    private Parent root;

    @FXML
    private Button btnClose;

    @FXML
    private Button btnImport;

    @FXML
    private Button btnProperties;

    @FXML
    private Button btnEdit;

    @FXML
    private Button btnGenerate;

    @FXML
    private Button btnDelete;

    @FXML
    private Button btnCopyPublic;

    @FXML
    private Button btnInstallPublic;

    @FXML
    private TableView<SshKeyInfo> keyTableView;

    @FXML
    private TableColumn<SshKeyInfo, String> colName;

    @FXML
    private TableColumn<SshKeyInfo, String> colType;

    @FXML
    private TableColumn<SshKeyInfo, String> colLength;

    private final ObservableList<SshKeyInfo> keys = FXCollections.observableArrayList();
    private static final List<Integer> ED25519_BITS = List.of(256);
    private static final List<Integer> RSA_BITS = List.of(2048, 3072, 4096);
    private Stage dialogStage;

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    @FXML
    public void initialize() {
        WindowDragResize.apply(root, 40, btnClose);
        setupTableView();
        setupEventHandlers();
        loadData();
    }

    private void setupTableView() {
        colName.setCellValueFactory(cellData -> new ReadOnlyStringWrapper(nullToEmpty(cellData.getValue().getName())));
        colType.setCellValueFactory(cellData -> new ReadOnlyStringWrapper(nullToEmpty(cellData.getValue().getType())));
        colLength.setCellValueFactory(cellData -> new ReadOnlyStringWrapper(
                cellData.getValue().getBits() > 0 ? String.valueOf(cellData.getValue().getBits()) : ""));
        keyTableView.setItems(keys);
        keyTableView.setRowFactory(table -> {
            TableRow<SshKeyInfo> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                    showKeyProperties();
                }
            });
            return row;
        });
    }

    private void setupEventHandlers() {
        btnClose.setOnAction(e -> closeDialog());
        btnImport.setOnAction(e -> importKey());
        btnProperties.setOnAction(e -> showKeyProperties());
        btnEdit.setOnAction(e -> editKey());
        btnGenerate.setOnAction(e -> generateKey());
        btnDelete.setOnAction(e -> deleteKey());
        btnCopyPublic.setOnAction(e -> copyPublicKey());
        btnInstallPublic.setOnAction(e -> installPublicKeyToCurrentSession());
    }

    private void loadData() {
        keys.setAll(SshKeyRepository.getInstance().load());
    }

    @FXML
    public void closeDialog() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }

    private void importKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入 SSH 私钥");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SSH 私钥", "id_*", "*.pem", "*.key", "*"),
                new FileChooser.ExtensionFilter("所有文件", "*")
        );
        File file = chooser.showOpenDialog(dialogStage);
        if (file == null) {
            return;
        }

        Optional<KeyFormData> form = showKeyForm("导入密钥", file.getName(), "", false, false);
        if (form.isEmpty()) {
            return;
        }

        try {
            SshKeyService.getInstance().importKey(file.toPath(),
                    form.get().name(),
                    form.get().passphrase(),
                    form.get().savePassphrase());
            loadData();
        } catch (Exception e) {
            DialogHelper.showError("导入失败", e.getMessage());
        }
    }

    private void editKey() {
        SshKeyInfo selected = selectedKey();
        if (selected == null) {
            DialogHelper.showWarning("请选择要编辑的密钥");
            return;
        }

        Optional<KeyFormData> form = showKeyForm("编辑密钥",
                selected.getName(),
                selected.getPassphrase(),
                selected.isSavePassphrase(),
                false);
        if (form.isEmpty()) {
            return;
        }

        selected.setName(form.get().name());
        selected.setPassphrase(form.get().savePassphrase() ? form.get().passphrase() : "");
        selected.setSavePassphrase(form.get().savePassphrase());
        selected.setModifiedTime(System.currentTimeMillis());
        SshKeyRepository.getInstance().upsert(selected);
        loadData();
    }

    private void generateKey() {
        Optional<KeyFormData> form = showKeyForm("生成密钥", "id_ed25519", "", false, true);
        if (form.isEmpty()) {
            return;
        }

        try {
            SshKeyService.getInstance().generateKey(form.get().name(),
                    form.get().type(),
                    form.get().bits(),
                    form.get().passphrase(),
                    form.get().savePassphrase());
            loadData();
        } catch (Exception e) {
            DialogHelper.showError("生成失败", e.getMessage());
        }
    }

    private void deleteKey() {
        SshKeyInfo selected = selectedKey();
        if (selected == null) {
            DialogHelper.showWarning("请选择要删除的密钥");
            return;
        }
        if (!DialogHelper.showConfirm("确认删除", "确定删除密钥 \"" + selected.getName() + "\" 吗？\n不会删除磁盘上的私钥文件。")) {
            return;
        }
        SshKeyRepository.getInstance().delete(selected.getId());
        loadData();
    }

    private void copyPublicKey() {
        SshKeyInfo selected = selectedKey();
        if (selected == null) {
            DialogHelper.showWarning("请选择要复制的密钥");
            return;
        }
        try {
            String publicKey = SshKeyService.getInstance().readPublicKey(selected);
            ClipboardContent content = new ClipboardContent();
            content.putString(publicKey);
            Clipboard.getSystemClipboard().setContent(content);
            DialogHelper.showInfo("复制成功", "公钥已复制到剪贴板。");
        } catch (Exception e) {
            DialogHelper.showError("复制失败", e.getMessage());
        }
    }

    private void installPublicKeyToCurrentSession() {
        SshKeyInfo selected = selectedKey();
        if (selected == null) {
            DialogHelper.showWarning("请选择要安装的密钥");
            return;
        }
        SshService service = ConnectionManager.getInstance().getCurrentSshService();
        if (service == null || !service.isConnected() || !service.isShellOpen()) {
            DialogHelper.showWarning("安装失败", "当前没有可写入命令的 SSH 会话。");
            return;
        }

        try {
            String publicKey = SshKeyService.getInstance().readPublicKey(selected);
            String command = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && "
                    + "{ grep -qxF " + shellQuote(publicKey) + " ~/.ssh/authorized_keys || "
                    + "echo " + shellQuote(publicKey) + " >> ~/.ssh/authorized_keys; } && "
                    + "chmod 600 ~/.ssh/authorized_keys\n";
            service.writeToShell(command.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            DialogHelper.showError("安装失败", e.getMessage());
        }
    }

    private Optional<KeyFormData> showKeyForm(String title,
                                              String defaultName,
                                              String defaultPassphrase,
                                              boolean defaultSavePassphrase,
                                              boolean includeGenerationOptions) {
        TextField nameField = new TextField(defaultName != null ? defaultName : "");
        PasswordField passphraseField = new PasswordField();
        passphraseField.setText(defaultPassphrase != null ? defaultPassphrase : "");
        CheckBox savePassphrase = new CheckBox("保存 passphrase");
        savePassphrase.setSelected(defaultSavePassphrase);
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("ED25519", "RSA");
        typeCombo.setValue("ED25519");
        ComboBox<Integer> bitsCombo = new ComboBox<>();
        bitsCombo.getItems().setAll(ED25519_BITS);
        bitsCombo.setValue(256);
        bitsCombo.setDisable(true);
        typeCombo.valueProperty().addListener((obs, oldType, newType) -> updateBitsOptions(newType, bitsCombo));

        GridPane grid = new GridPane();
        grid.getStyleClass().add("key-form-grid");
        grid.addRow(0, new Label("名称"), nameField);
        if (includeGenerationOptions) {
            grid.addRow(1, new Label("类型"), typeCombo);
            grid.addRow(2, new Label("长度"), bitsCombo);
            grid.addRow(3, new Label("Passphrase"), passphraseField);
            grid.addRow(4, new Label(""), savePassphrase);
        } else {
            grid.addRow(1, new Label("Passphrase"), passphraseField);
            grid.addRow(2, new Label(""), savePassphrase);
        }

        return DialogHelper.showCustomDialog(title, grid, button -> {
            if (button.getButtonData() != ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            return new KeyFormData(
                    nameField.getText() != null ? nameField.getText().trim() : "",
                    typeCombo.getValue(),
                    bitsCombo.getValue() != null ? bitsCombo.getValue() : 0,
                    passphraseField.getText(),
                    savePassphrase.isSelected()
            );
        }, "custom-dialog-content-body", "key-form-dialog");
    }

    private void showKeyProperties() {
        SshKeyInfo selected = selectedKey();
        if (selected == null) {
            DialogHelper.showWarning("请选择要查看的密钥");
            return;
        }

        GridPane grid = new GridPane();
        grid.getStyleClass().add("key-properties-grid");
        addPropertyRow(grid, 0, "名称", selected.getName());
        addPropertyRow(grid, 1, "类型", selected.getType());
        addPropertyRow(grid, 2, "长度", selected.getBits() > 0 ? String.valueOf(selected.getBits()) : "");
        addPropertyRow(grid, 3, "指纹", selected.getFingerprint());
        addPropertyRow(grid, 4, "私钥路径", selected.getPrivateKeyPath());
        addPropertyRow(grid, 5, "公钥路径", selected.getPublicKeyPath());
        addPropertyRow(grid, 6, "保存 passphrase", selected.isSavePassphrase() ? "是" : "否");

        DialogHelper.showCustomDialog("密钥属性", grid, button -> null, "custom-dialog-content-body", "key-properties-dialog");
    }

    private void addPropertyRow(GridPane grid, int row, String label, String value) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("key-property-label");

        TextField valueField = new TextField(nullToEmpty(value));
        valueField.setEditable(false);
        valueField.getStyleClass().add("key-property-value");

        grid.addRow(row, labelNode, valueField);
    }

    private SshKeyInfo selectedKey() {
        return keyTableView.getSelectionModel().getSelectedItem();
    }

    private void updateBitsOptions(String keyType, ComboBox<Integer> bitsCombo) {
        if ("RSA".equals(keyType)) {
            bitsCombo.getItems().setAll(RSA_BITS);
            bitsCombo.setValue(4096);
            bitsCombo.setDisable(false);
        } else {
            bitsCombo.getItems().setAll(ED25519_BITS);
            bitsCombo.setValue(256);
            bitsCombo.setDisable(true);
        }
    }

    private String shellQuote(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private record KeyFormData(String name, String type, int bits, String passphrase, boolean savePassphrase) {
    }
}

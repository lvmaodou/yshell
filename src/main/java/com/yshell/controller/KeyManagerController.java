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
import java.util.Locale;
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
    private static final List<Integer> ECDSA_BITS = List.of(256, 384, 521);
    private static final List<Integer> RSA_BITS = List.of(2048, 3072, 4096);
    private static final List<Integer> DSA_BITS = List.of(1024);
    private static final String DEFAULT_AUTHORIZED_KEYS_PATH = "~/.ssh/authorized_keys";
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

        Optional<KeyFormData> form = showKeyForm("导入密钥", file.getName(), false, true);
        if (form.isEmpty()) {
            return;
        }

        try {
            SshKeyService.getInstance().importKey(file.toPath(),
                    form.get().name(),
                    form.get().passphrase());
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

        Optional<KeyFormData> form = showKeyForm("编辑密钥", selected.getName(), false, false);
        if (form.isEmpty()) {
            return;
        }

        selected.setName(form.get().name());
        selected.setPassphrase("");
        selected.setSavePassphrase(false);
        selected.setModifiedTime(System.currentTimeMillis());
        SshKeyRepository.getInstance().upsert(selected);
        loadData();
    }

    private void generateKey() {
        Optional<KeyFormData> form = showKeyForm("生成密钥", "", true, true);
        if (form.isEmpty()) {
            return;
        }

        try {
            SshKeyService.getInstance().generateKey(form.get().name(),
                    form.get().type(),
                    form.get().bits(),
                    form.get().passphrase());
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
        if (!DialogHelper.showConfirm("确认删除", "确定删除密钥 \"" + selected.getName() + "\" 吗？\n对应的私钥和公钥文件也会被永久删除。")) {
            return;
        }
        try {
            SshKeyService.getInstance().deleteKey(selected);
            loadData();
        } catch (Exception e) {
            DialogHelper.showError("删除失败", e.getMessage());
        }
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

        String targetPath = DialogHelper.showTextInput(
                "安装 SSH 公钥",
                "请输入服务端实际读取公钥的授权文件路径。默认路径适用于 OpenSSH 标准配置；不自动检测服务端配置。",
                "目标 authorized_keys 路径（支持 ~/... 或绝对路径）",
                DEFAULT_AUTHORIZED_KEYS_PATH
        );
        if (targetPath == null) {
            return;
        }

        try {
            String publicKey = SshKeyService.getInstance().readPublicKey(selected);
            String command = buildAuthorizedKeysInstallCommand(publicKey, targetPath);
            service.writeToShell(command.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            DialogHelper.showError("安装失败", e.getMessage());
        }
    }

    private String buildAuthorizedKeysInstallCommand(String publicKey, String targetPath) {
        String createDirectory = DEFAULT_AUTHORIZED_KEYS_PATH.equals(targetPath)
                ? "mkdir -p -- \"$target_dir\" && chmod 700 -- \"$target_dir\" && "
                : "mkdir -p -- \"$target_dir\" && ";
        return remotePathAssignment(targetPath)
                + "target_dir=$(dirname -- \"$target_path\")\n"
                + createDirectory
                + "touch -- \"$target_path\" && "
                + "{ grep -qxF " + shellQuote(publicKey) + " \"$target_path\" || "
                + "printf '%s\\n' " + shellQuote(publicKey) + " >> \"$target_path\"; } && "
                + "chmod 600 -- \"$target_path\"\n";
    }

    private String remotePathAssignment(String targetPath) {
        if (targetPath.startsWith("~/")) {
            return "target_path=\"$HOME/\"" + shellQuote(targetPath.substring(2)) + "\n";
        }
        return "target_path=" + shellQuote(targetPath) + "\n";
    }

    private Optional<KeyFormData> showKeyForm(String title,
                                              String defaultName,
                                              boolean includeGenerationOptions,
                                              boolean includePassphrase) {
        TextField nameField = new TextField(defaultName != null ? defaultName : "");
        PasswordField passphraseField = new PasswordField();
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("ED25519", "ECDSA", "RSA", "DSA");
        typeCombo.setValue("ED25519");
        ComboBox<Integer> bitsCombo = new ComboBox<>();
        bitsCombo.getItems().setAll(ED25519_BITS);
        bitsCombo.setValue(256);
        bitsCombo.setDisable(true);
        if (includeGenerationOptions) {
            boolean[] autoGeneratedName = {defaultName == null || defaultName.isBlank()};
            boolean[] updatingDefaultName = {false};
            Runnable updateDefaultName = () -> {
                if (!autoGeneratedName[0]) {
                    return;
                }
                Integer bits = bitsCombo.getValue();
                updatingDefaultName[0] = true;
                nameField.setText(defaultKeyName(typeCombo.getValue(), bits != null ? bits : 0));
                updatingDefaultName[0] = false;
            };
            nameField.textProperty().addListener((obs, oldName, newName) -> {
                if (!updatingDefaultName[0]) {
                    autoGeneratedName[0] = false;
                }
            });
            typeCombo.valueProperty().addListener((obs, oldType, newType) -> {
                updateBitsOptions(newType, bitsCombo);
                updateDefaultName.run();
            });
            bitsCombo.valueProperty().addListener((obs, oldBits, newBits) -> updateDefaultName.run());
            updateDefaultName.run();
        }

        GridPane grid = new GridPane();
        grid.getStyleClass().add("key-form-grid");
        grid.addRow(0, new Label("名称"), nameField);
        if (includeGenerationOptions) {
            grid.addRow(1, new Label("类型"), typeCombo);
            grid.addRow(2, new Label("长度"), bitsCombo);
            if (includePassphrase) {
                grid.addRow(3, new Label("Passphrase"), passphraseField);
            }
        } else if (includePassphrase) {
            grid.addRow(1, new Label("Passphrase"), passphraseField);
        }

        return DialogHelper.showCustomDialog(title, grid, button -> {
            if (button.getButtonData() != ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            return new KeyFormData(
                    nameField.getText() != null ? nameField.getText().trim() : "",
                    typeCombo.getValue(),
                    bitsCombo.getValue() != null ? bitsCombo.getValue() : 0,
                    passphraseField.getText()
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
        addPropertyRow(grid, 6, "是否使用口令", passphraseProtectionStatus(selected));

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

    private String passphraseProtectionStatus(SshKeyInfo keyInfo) {
        Boolean passphraseProtected = keyInfo.getPassphraseProtected();
        if (passphraseProtected == null) {
            return "未知（重新导入后可识别）";
        }
        return passphraseProtected ? "是" : "否";
    }

    private SshKeyInfo selectedKey() {
        return keyTableView.getSelectionModel().getSelectedItem();
    }

    private void updateBitsOptions(String keyType, ComboBox<Integer> bitsCombo) {
        switch (keyType) {
            case "RSA" -> {
                bitsCombo.getItems().setAll(RSA_BITS);
                bitsCombo.setValue(4096);
                bitsCombo.setDisable(false);
            }
            case "ECDSA" -> {
                bitsCombo.getItems().setAll(ECDSA_BITS);
                bitsCombo.setValue(256);
                bitsCombo.setDisable(false);
            }
            case "DSA" -> {
                bitsCombo.getItems().setAll(DSA_BITS);
                bitsCombo.setValue(1024);
                bitsCombo.setDisable(true);
            }
            default -> {
                bitsCombo.getItems().setAll(ED25519_BITS);
                bitsCombo.setValue(256);
                bitsCombo.setDisable(true);
            }
        }
    }

    private String defaultKeyName(String keyType, int bits) {
        String normalizedType = keyType == null || keyType.isBlank()
                ? "key"
                : keyType.toLowerCase(Locale.ROOT);
        return "id_" + normalizedType + "_" + bits;
    }

    private String shellQuote(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private record KeyFormData(String name, String type, int bits, String passphrase) {
    }
}

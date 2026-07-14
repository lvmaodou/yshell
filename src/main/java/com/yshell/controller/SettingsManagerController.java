package com.yshell.controller;

import com.yshell.config.AppConfig;
import com.yshell.config.AppSettings;
import com.yshell.config.ShortcutRegistry;
import com.yshell.logging.LogDirectoryPropertyDefiner;
import com.yshell.theme.ThemeManager;
import com.yshell.transfer.CompressedTransferManager;
import com.yshell.transfer.TransferManager;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.LayoutConfig;
import com.yshell.ui.WindowDragResize;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

import java.awt.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class SettingsManagerController {
    private final AppSettings settings = AppSettings.getInstance();
    private final List<Button> navButtons = new ArrayList<>();

    @FXML
    private Parent root;
    @FXML
    private Button btnClose;
    @FXML
    private Button navGeneral;
    @FXML
    private Button navUpdate;
    @FXML
    private Button navTerminal;
    @FXML
    private Button navEditor;
    @FXML
    private Button navTransfer;
    @FXML
    private Button navDockerRegistry;
    @FXML
    private Button navShortcut;
    @FXML
    private Button navMaintenance;
    @FXML
    private Button navAI;
    @FXML
    private TabPane settingsTabPane;
    @FXML
    private ComboBox<String> themeCombo;
    @FXML
    private CheckBox startupUpdateCheck;
    @FXML
    private Spinner<Integer> terminalFontSizeSpinner;
    @FXML
    private Spinner<Integer> scrollbackSpinner;
    @FXML
    private ComboBox<String> terminalEncodingCombo;
    @FXML
    private ComboBox<String> backspaceCombo;
    @FXML
    private ComboBox<String> deleteCombo;
    @FXML
    private Spinner<Integer> editorFontSizeSpinner;
    @FXML
    private TextField downloadDirectoryField;
    @FXML
    private Button btnChooseDownloadDirectory;
    @FXML
    private ComboBox<String> duplicateStrategyCombo;
    @FXML
    private CheckBox closeQueueWhenFinished;
    @FXML
    private CheckBox clearFinishedWhenDone;
    @FXML
    private TableView<AppConfig.DockerRegistry> dockerRegistryTable;
    @FXML
    private TableColumn<AppConfig.DockerRegistry, String> registryNameColumn;
    @FXML
    private TableColumn<AppConfig.DockerRegistry, String> registryAddressColumn;
    @FXML
    private TableColumn<AppConfig.DockerRegistry, String> registryUsernameColumn;
    @FXML
    private TableColumn<AppConfig.DockerRegistry, String> registryPasswordColumn;
    @FXML
    private Button btnAddRegistry;
    @FXML
    private Button btnEditRegistry;
    @FXML
    private Button btnDeleteRegistry;
    @FXML
    private TableView<ShortcutRegistry.Shortcut> shortcutTable;
    @FXML
    private TableColumn<ShortcutRegistry.Shortcut, String> shortcutGroupColumn;
    @FXML
    private TableColumn<ShortcutRegistry.Shortcut, String> shortcutActionColumn;
    @FXML
    private TableColumn<ShortcutRegistry.Shortcut, String> shortcutKeyColumn;
    @FXML
    private Button btnOpenConfigDir;
    @FXML
    private Button btnOpenLogDir;
    @FXML
    private Button btnClearRecentFiles;
    @FXML
    private Button btnClearTransferQueue;
    @FXML
    private Button btnResetLayout;
    @FXML
    private CheckBox aiEnabled;
    @FXML
    private TextField aiModelField;
    @FXML
    private PasswordField aiApiKeyField;
    @FXML
    private TextField aiBaseUrlField;

    private Stage dialogStage;
    private final ObservableList<AppConfig.DockerRegistry> dockerRegistries = FXCollections.observableArrayList();

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    @FXML
    public void initialize() {
        WindowDragResize.apply(root, 40, btnClose);
        navButtons.addAll(List.of(navGeneral, navUpdate, navTerminal, navEditor, navTransfer, navDockerRegistry,
                navShortcut, navMaintenance, navAI));
        setupNavigation();
        setupControls();
        loadSettings();
        setupPersistence();
    }

    private void setupNavigation() {
        btnClose.setOnAction(e -> closeDialog());
        navGeneral.setOnAction(e -> selectTab(0));
        navUpdate.setOnAction(e -> selectTab(1));
        navTerminal.setOnAction(e -> selectTab(2));
        navEditor.setOnAction(e -> selectTab(3));
        navTransfer.setOnAction(e -> selectTab(4));
        navDockerRegistry.setOnAction(e -> selectTab(5));
        navShortcut.setOnAction(e -> selectTab(6));
        navMaintenance.setOnAction(e -> selectTab(7));
        navAI.setOnAction(e -> selectTab(8));
        settingsTabPane.getSelectionModel().selectedIndexProperty().addListener((obs, oldIndex, newIndex) ->
                updateNavSelection(newIndex.intValue()));
        settingsTabPane.getSelectionModel().select(0);
    }

    private void setupControls() {
        themeCombo.setItems(FXCollections.observableArrayList("深色主题", "浅色主题"));

        terminalFontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(6, 22));
        scrollbackSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(100, 100000, 1000, 100));
        editorFontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(8, 40));

        List<String> encodings = new ArrayList<>(Charset.availableCharsets().keySet());
        encodings.remove("UTF-8");
        encodings.add(0, "UTF-8");
        terminalEncodingCombo.setItems(FXCollections.observableArrayList(encodings));

        backspaceCombo.setItems(FXCollections.observableArrayList("ASCII - Backspace", "VT220 - Delete", "ASCII - Delete"));
        deleteCombo.setItems(FXCollections.observableArrayList("VT220 - Delete", "ASCII - Delete", "ASCII - Backspace"));
        duplicateStrategyCombo.setItems(FXCollections.observableArrayList("询问", "覆盖", "跳过", "自动重命名"));

        shortcutGroupColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().group()));
        shortcutActionColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().action()));
        shortcutKeyColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().keyText()));
        shortcutTable.setItems(FXCollections.observableArrayList(ShortcutRegistry.all()));

        registryNameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name));
        registryAddressColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().address));
        registryUsernameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().username));
        registryPasswordColumn.setCellValueFactory(data -> new SimpleStringProperty(maskPassword(data.getValue().password)));
        dockerRegistryTable.setItems(dockerRegistries);

        btnChooseDownloadDirectory.setOnAction(e -> chooseDownloadDirectory());
        btnOpenConfigDir.setOnAction(e -> openDirectory(configDirectory()));
        btnOpenLogDir.setOnAction(e -> openDirectory(Paths.get(new LogDirectoryPropertyDefiner().getPropertyValue())));
        btnClearRecentFiles.setOnAction(e -> clearRecentFiles());
        btnClearTransferQueue.setOnAction(e -> clearTransferQueue());
        btnResetLayout.setOnAction(e -> resetLayout());
        btnAddRegistry.setOnAction(e -> addDockerRegistry());
        btnEditRegistry.setOnAction(e -> editDockerRegistry());
        btnDeleteRegistry.setOnAction(e -> deleteDockerRegistry());
    }

    private void loadSettings() {
        String currentTheme = ThemeManager.getInstance().getCurrentTheme();
        themeCombo.setValue("vs-dark".equals(currentTheme) ? "深色主题" : "浅色主题");
        startupUpdateCheck.setSelected(settings.isStartupUpdateCheckEnabled());
        terminalFontSizeSpinner.getValueFactory().setValue(settings.getTerminalDefaultFontSize());
        scrollbackSpinner.getValueFactory().setValue(settings.getTerminalScrollbackLines());
        terminalEncodingCombo.setValue(settings.getTerminalDefaultEncoding());
        backspaceCombo.setValue(mapBackspaceSequence(settings.getTerminalDefaultBackspaceSequence()));
        deleteCombo.setValue(mapDeleteSequence(settings.getTerminalDefaultDeleteSequence()));
        editorFontSizeSpinner.getValueFactory().setValue(settings.getEditorDefaultFontSize());
        downloadDirectoryField.setText(settings.getTransferDefaultDownloadDirectory().toString());
        duplicateStrategyCombo.setValue(settings.getTransferDuplicateStrategy().getLabel());
        closeQueueWhenFinished.setSelected(settings.isTransferCloseQueueWhenFinished());
        clearFinishedWhenDone.setSelected(settings.isTransferClearFinishedWhenDone());

        aiEnabled.setSelected(settings.isAiEnabled());
        aiModelField.setText(settings.getAiModel());
        aiApiKeyField.setText(settings.getAiApiKey());
        aiBaseUrlField.setText(settings.getAiBaseUrl());
        refreshDockerRegistries();
    }

    private void setupPersistence() {
        themeCombo.setOnAction(e -> {
            String selectedTheme = themeCombo.getValue();
            if (selectedTheme != null) {
                ThemeManager.getInstance().setTheme("深色主题".equals(selectedTheme) ? "vs-dark" : "vs-light");
            }
        });
        startupUpdateCheck.selectedProperty().addListener((obs, old, value) -> settings.setStartupUpdateCheckEnabled(value));
        terminalFontSizeSpinner.valueProperty().addListener((obs, old, value) -> settings.setTerminalDefaultFontSize(value));
        scrollbackSpinner.valueProperty().addListener((obs, old, value) -> settings.setTerminalScrollbackLines(value));
        terminalEncodingCombo.valueProperty().addListener((obs, old, value) -> settings.setTerminalDefaultEncoding(value));
        backspaceCombo.valueProperty().addListener((obs, old, value) -> settings.setTerminalDefaultBackspaceSequence(parseBackspaceSequence(value)));
        deleteCombo.valueProperty().addListener((obs, old, value) -> settings.setTerminalDefaultDeleteSequence(parseDeleteSequence(value)));
        editorFontSizeSpinner.valueProperty().addListener((obs, old, value) -> {
            settings.setEditorDefaultFontSize(value);
            EditorViewController.setGlobalFontSize(value);
        });
        duplicateStrategyCombo.valueProperty().addListener((obs, old, value) ->
                settings.setTransferDuplicateStrategy(AppSettings.DuplicateStrategy.fromLabel(value)));
        closeQueueWhenFinished.selectedProperty().addListener((obs, old, value) -> settings.setTransferCloseQueueWhenFinished(value));
        clearFinishedWhenDone.selectedProperty().addListener((obs, old, value) -> settings.setTransferClearFinishedWhenDone(value));

        aiEnabled.selectedProperty().addListener((obs, old, value) -> settings.setAiEnabled(value));
        aiModelField.textProperty().addListener((obs, old, value) -> settings.setAiModel(value));
        aiApiKeyField.textProperty().addListener((obs, old, value) -> settings.setAiApiKey(value));
        aiBaseUrlField.textProperty().addListener((obs, old, value) -> settings.setAiBaseUrl(value));
    }

    private void refreshDockerRegistries() {
        dockerRegistries.setAll(settings.getDockerRegistries());
        dockerRegistryTable.refresh();
    }

    private void addDockerRegistry() {
        AppConfig.DockerRegistry registry = editDockerRegistryDialog(null);
        if (registry == null) {
            return;
        }
        dockerRegistries.add(registry);
        settings.setDockerRegistries(new ArrayList<>(dockerRegistries));
    }

    private void editDockerRegistry() {
        AppConfig.DockerRegistry selected = dockerRegistryTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            DialogHelper.showWarning("Docker 仓库", "请先选择一条仓库配置");
            return;
        }
        AppConfig.DockerRegistry edited = editDockerRegistryDialog(selected);
        if (edited == null) {
            return;
        }
        int index = dockerRegistries.indexOf(selected);
        if (index >= 0) {
            dockerRegistries.set(index, edited);
            settings.setDockerRegistries(new ArrayList<>(dockerRegistries));
        }
    }

    private void deleteDockerRegistry() {
        AppConfig.DockerRegistry selected = dockerRegistryTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            DialogHelper.showWarning("Docker 仓库", "请先选择一条仓库配置");
            return;
        }
        if (!DialogHelper.showConfirmYesNo("删除仓库", "确定要删除 \"" + selected.name + "\" 吗？")) {
            return;
        }
        dockerRegistries.remove(selected);
        settings.setDockerRegistries(new ArrayList<>(dockerRegistries));
    }

    private AppConfig.DockerRegistry editDockerRegistryDialog(AppConfig.DockerRegistry source) {
        TextField nameField = new TextField(source == null ? "" : source.name);
        TextField addressField = new TextField(source == null ? "" : source.address);
        TextField usernameField = new TextField(source == null ? "" : source.username);
        PasswordField passwordField = new PasswordField();
        passwordField.setText(source == null ? "" : source.password);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 18, 8, 18));
        grid.addRow(0, new Label("名称"), nameField);
        grid.addRow(1, new Label("地址"), addressField);
        grid.addRow(2, new Label("用户名"), usernameField);
        grid.addRow(3, new Label("密码"), passwordField);
        GridPane.setFillWidth(nameField, true);
        GridPane.setFillWidth(addressField, true);
        GridPane.setFillWidth(usernameField, true);
        GridPane.setFillWidth(passwordField, true);
        grid.getColumnConstraints().addAll(createLabelColumn(), createFieldColumn());

        boolean ok = DialogHelper.showCustomDialog(source == null ? "新增仓库" : "编辑仓库", grid,
                        button -> button != null && button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? Boolean.TRUE : null)
                .isPresent();
        if (!ok) {
            return null;
        }
        String name = trimOrNull(nameField.getText());
        String address = trimOrNull(addressField.getText());
        if (name == null || address == null) {
            DialogHelper.showWarning("Docker 仓库", "名称和地址不能为空");
            return null;
        }
        AppConfig.DockerRegistry registry = new AppConfig.DockerRegistry();
        registry.name = name;
        registry.address = normalizeRegistryAddress(address);
        registry.username = trimToEmpty(usernameField.getText());
        registry.password = trimToEmpty(passwordField.getText());
        return registry;
    }

    private javafx.scene.layout.ColumnConstraints createLabelColumn() {
        javafx.scene.layout.ColumnConstraints labelColumn = new javafx.scene.layout.ColumnConstraints();
        labelColumn.setMinWidth(72);
        labelColumn.setPrefWidth(72);
        return labelColumn;
    }

    private javafx.scene.layout.ColumnConstraints createFieldColumn() {
        javafx.scene.layout.ColumnConstraints fieldColumn = new javafx.scene.layout.ColumnConstraints();
        fieldColumn.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        return fieldColumn;
    }

    private String normalizeRegistryAddress(String value) {
        String text = trimToEmpty(value);
        if (text.startsWith("http://")) {
            return text.substring("http://".length());
        }
        if (text.startsWith("https://")) {
            return text.substring("https://".length());
        }
        return text;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimOrNull(String value) {
        String text = trimToEmpty(value);
        return text.isEmpty() ? null : text;
    }

    private String maskPassword(String value) {
        return value == null || value.isBlank() ? "" : "••••••";
    }

    private void chooseDownloadDirectory() {
        Path selected = DialogHelper.chooseDirectory(root.getScene().getWindow(), "选择默认下载目录",
                settings.getTransferDefaultDownloadDirectory());
        if (selected != null) {
            settings.setTransferDefaultDownloadDirectory(selected);
            downloadDirectoryField.setText(selected.toString());
        }
    }

    private void clearRecentFiles() {
        if (DialogHelper.showConfirmYesNo("清空最近文件", "确定要清空编辑器最近文件列表吗？")) {
            EditorViewController.clearRecentFiles();
            DialogHelper.showInfo("完成", "最近文件已清空。");
        }
    }

    private void clearTransferQueue() {
        if (!DialogHelper.showConfirmYesNo("清空传输队列", "确定要清空当前连接的传输队列吗？")) {
            return;
        }
        String connId = com.yshell.service.ConnectionManager.getInstance().getCurrentConnectionId();
        if (connId != null) {
            TransferManager.getInstance().clearTasks(connId);
            CompressedTransferManager.getInstance().clearFinished(connId);
            DialogHelper.showInfo("完成", "当前连接的传输队列已清理。");
        } else {
            DialogHelper.showInfo("提示", "当前没有选中的连接。");
        }
    }

    private void resetLayout() {
        if (!DialogHelper.showConfirmYesNo("重置布局", "确定要重置窗口和面板布局吗？重启后生效。")) {
            return;
        }
        LayoutConfig.getInstance().reset();
        DialogHelper.showInfo("完成", "布局已重置，重启应用后生效。");
    }

    private void openDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(directory.toFile());
            }
        } catch (Exception e) {
            DialogHelper.showError("打开目录失败", e.getMessage());
        }
    }

    private Path configDirectory() {
        return Paths.get(System.getProperty("user.home"), ".yshell");
    }

    private void selectTab(int index) {
        settingsTabPane.getSelectionModel().select(index);
    }

    private void updateNavSelection(int index) {
        for (Button button : navButtons) {
            button.getStyleClass().remove("active");
        }
        if (index >= 0 && index < navButtons.size()) {
            navButtons.get(index).getStyleClass().add("active");
        }
    }

    private static String mapBackspaceSequence(int value) {
        return switch (value) {
            case 1 -> "ASCII - Backspace";
            case 2 -> "VT220 - Delete";
            default -> "ASCII - Delete";
        };
    }

    private static int parseBackspaceSequence(String text) {
        if (text == null) return 1;
        return switch (text) {
            case "ASCII - Backspace" -> 1;
            case "VT220 - Delete" -> 2;
            default -> 0;
        };
    }

    private static String mapDeleteSequence(int value) {
        return switch (value) {
            case 1 -> "ASCII - Delete";
            case 2 -> "ASCII - Backspace";
            default -> "VT220 - Delete";
        };
    }

    private static int parseDeleteSequence(String text) {
        if (text == null) return 0;
        return switch (text) {
            case "ASCII - Delete" -> 1;
            case "ASCII - Backspace" -> 2;
            default -> 0;
        };
    }

    @FXML
    public void closeDialog() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }
}

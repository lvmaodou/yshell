package com.yshell.controller;

import com.yshell.config.AppConfig;
import com.yshell.config.AppSettings;
import com.yshell.config.ShortcutRegistry;
import com.yshell.logging.LogDirectoryPropertyDefiner;
import com.yshell.service.AiConversationRepository;
import com.yshell.service.KnownHostsRepository;
import com.yshell.theme.ThemeManager;
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
import java.io.IOException;
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
    private Button navSshSecurity;
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
    private TableView<KnownHostsRepository.HostKeyEntry> knownHostsTable;
    @FXML
    private TableColumn<KnownHostsRepository.HostKeyEntry, String> knownHostsHostColumn;
    @FXML
    private TableColumn<KnownHostsRepository.HostKeyEntry, String> knownHostsKeyTypeColumn;
    @FXML
    private TableColumn<KnownHostsRepository.HostKeyEntry, String> knownHostsFingerprintColumn;
    @FXML
    private Button btnReloadKnownHosts;
    @FXML
    private Button btnDeleteKnownHost;
    @FXML
    private Button btnClearKnownHosts;
    @FXML
    private Button btnOpenConfigDir;
    @FXML
    private Button btnOpenLogDir;
    @FXML
    private Button btnClearAiHistory;
    @FXML
    private Button btnResetLayout;
    @FXML
    private CheckBox aiEnabled;
    @FXML
    private TableView<AppConfig.AiModelConnection> aiConnectionTable;
    @FXML
    private TableColumn<AppConfig.AiModelConnection, String> aiConnectionNameColumn;
    @FXML
    private TableColumn<AppConfig.AiModelConnection, String> aiConnectionModelColumn;
    @FXML
    private Button btnAddAiConnection;
    @FXML
    private Button btnEditAiConnection;
    @FXML
    private Button btnDeleteAiConnection;

    private Stage dialogStage;
    private final ObservableList<AppConfig.DockerRegistry> dockerRegistries = FXCollections.observableArrayList();
    private final ObservableList<AppConfig.AiModelConnection> aiConnections = FXCollections.observableArrayList();
    private final ObservableList<KnownHostsRepository.HostKeyEntry> knownHostEntries = FXCollections.observableArrayList();

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    @FXML
    public void initialize() {
        WindowDragResize.apply(root, 40, btnClose);
        navButtons.addAll(List.of(navGeneral, navUpdate, navTerminal, navEditor, navTransfer, navDockerRegistry,
                navShortcut, navMaintenance, navAI, navSshSecurity));
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
        navSshSecurity.setOnAction(e -> {
            refreshKnownHosts();
            selectTab(9);
        });
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

        aiConnectionNameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name));
        aiConnectionModelColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().model));
        aiConnectionTable.setItems(aiConnections);

        knownHostsHostColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().host()));
        knownHostsKeyTypeColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().keyType()));
        knownHostsFingerprintColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().fingerprint()));
        knownHostsTable.setItems(knownHostEntries);

        btnChooseDownloadDirectory.setOnAction(e -> chooseDownloadDirectory());
        btnOpenConfigDir.setOnAction(e -> openDirectory(configDirectory()));
        btnOpenLogDir.setOnAction(e -> openDirectory(Paths.get(new LogDirectoryPropertyDefiner().getPropertyValue())));
        btnClearAiHistory.setOnAction(e -> clearAiHistory());
        btnResetLayout.setOnAction(e -> resetLayout());
        btnAddRegistry.setOnAction(e -> addDockerRegistry());
        btnEditRegistry.setOnAction(e -> editDockerRegistry());
        btnDeleteRegistry.setOnAction(e -> deleteDockerRegistry());
        btnAddAiConnection.setOnAction(e -> addAiConnection());
        btnEditAiConnection.setOnAction(e -> editAiConnection());
        btnDeleteAiConnection.setOnAction(e -> deleteAiConnection());
        btnReloadKnownHosts.setOnAction(e -> refreshKnownHosts());
        btnDeleteKnownHost.setOnAction(e -> deleteKnownHost());
        btnClearKnownHosts.setOnAction(e -> clearKnownHosts());
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
        refreshAiConnections();
        refreshDockerRegistries();
        refreshKnownHosts();
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
    }

    private void refreshDockerRegistries() {
        dockerRegistries.setAll(settings.getDockerRegistries());
        dockerRegistryTable.refresh();
    }

    private void refreshAiConnections() {
        aiConnections.setAll(settings.getAiConnections());
        aiConnectionTable.refresh();
    }

    private void refreshKnownHosts() {
        try {
            knownHostEntries.setAll(KnownHostsRepository.getInstance().load());
            knownHostsTable.refresh();
        } catch (IOException e) {
            DialogHelper.showError("读取 SSH 主机密钥失败", e.getMessage());
        }
    }

    private void deleteKnownHost() {
        KnownHostsRepository.HostKeyEntry selected = knownHostsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            DialogHelper.showWarning("SSH 主机密钥", "请先选择一条主机密钥记录");
            return;
        }
        if (!DialogHelper.showConfirmYesNo("删除 SSH 主机密钥",
                "确定删除 \"" + selected.host() + "\" 的主机密钥吗？下次连接将要求重新确认指纹。")) {
            return;
        }
        try {
            KnownHostsRepository.getInstance().delete(selected);
            refreshKnownHosts();
        } catch (IOException e) {
            DialogHelper.showError("删除 SSH 主机密钥失败", e.getMessage());
        }
    }

    private void clearKnownHosts() {
        if (knownHostEntries.isEmpty()) {
            DialogHelper.showInfo("SSH 主机密钥", "当前没有已保存的主机密钥");
            return;
        }
        if (!DialogHelper.showConfirmYesNo("清空 SSH 主机密钥",
                "确定清空所有 SSH 主机密钥吗？下次连接每台服务器都将要求重新确认指纹。")) {
            return;
        }
        try {
            KnownHostsRepository.getInstance().clear();
            refreshKnownHosts();
        } catch (IOException e) {
            DialogHelper.showError("清空 SSH 主机密钥失败", e.getMessage());
        }
    }

    private void addAiConnection() {
        AppConfig.AiModelConnection edited = editAiConnectionDialog(null);
        if (edited == null) {
            return;
        }
        aiConnections.add(edited);
        settings.setAiConnections(new ArrayList<>(aiConnections));
        if (settings.getSelectedAiConnectionId().isBlank()) {
            settings.setSelectedAiConnectionId(edited.id);
        }
        refreshAiConnections();
    }

    private void editAiConnection() {
        AppConfig.AiModelConnection selected = aiConnectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            DialogHelper.showWarning("AI 连接", "请先选择一条模型连接配置");
            return;
        }
        AppConfig.AiModelConnection edited = editAiConnectionDialog(selected);
        if (edited == null) {
            return;
        }
        int index = aiConnections.indexOf(selected);
        if (index >= 0) {
            aiConnections.set(index, edited);
            settings.setAiConnections(new ArrayList<>(aiConnections));
            refreshAiConnections();
        }
    }

    private void deleteAiConnection() {
        AppConfig.AiModelConnection selected = aiConnectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            DialogHelper.showWarning("AI 连接", "请先选择一条模型连接配置");
            return;
        }
        if (aiConnections.size() <= 1) {
            DialogHelper.showWarning("AI 连接", "至少保留一条模型连接配置");
            return;
        }
        if (!DialogHelper.showConfirmYesNo("删除模型连接", "确定要删除 \"" + selected.name + "\" 吗？")) {
            return;
        }
        aiConnections.remove(selected);
        settings.setAiConnections(new ArrayList<>(aiConnections));
        if (selected.id.equals(settings.getSelectedAiConnectionId())) {
            settings.setSelectedAiConnectionId(aiConnections.get(0).id);
        }
        refreshAiConnections();
    }

    private AppConfig.AiModelConnection editAiConnectionDialog(AppConfig.AiModelConnection source) {
        AppConfig.AiModelConnection initial = source == null ? settings.defaultAiConnection() : copyAiConnection(source);
        TextField nameField = new TextField(initial.name);
        ComboBox<String> formatCombo = new ComboBox<>(FXCollections.observableArrayList(
                "OpenAI Chat Completions",
                "OpenAI Responses v1",
                "Anthropic Messages",
                "Gemini Native"
        ));
        formatCombo.setValue(apiFormatLabel(initial.apiFormat));
        TextField baseUrlField = new TextField(initial.baseUrl);
        baseUrlField.setPromptText(baseUrlExample(initial.apiFormat));
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setText(initial.apiKey);
        TextField modelField = new TextField(initial.model);
        modelField.setPromptText("请输入模型 ID");
        CheckBox imageInputSupported = new CheckBox("允许上传、粘贴并发送图片");
        imageInputSupported.setSelected(initial.imageInputSupported);
        formatCombo.valueProperty().addListener((obs, old, value) -> {
            String oldCode = apiFormatCode(old);
            String newCode = apiFormatCode(value);
            baseUrlField.setPromptText(baseUrlExample(newCode));
            if (baseUrlField.getText() == null || baseUrlField.getText().isBlank()
                    || defaultBaseUrlForFormat(oldCode).equals(baseUrlField.getText().trim())) {
                baseUrlField.setText(defaultBaseUrlForFormat(newCode));
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 18, 8, 18));
        grid.addRow(0, new Label("名称"), nameField);
        grid.addRow(1, new Label("接口格式"), formatCombo);
        grid.addRow(2, new Label("Base URL"), baseUrlField);
        grid.addRow(3, new Label("API Key"), apiKeyField);
        grid.addRow(4, new Label("模型"), modelField);
        grid.addRow(5, new Label("图片输入"), imageInputSupported);
        GridPane.setFillWidth(nameField, true);
        GridPane.setFillWidth(formatCombo, true);
        GridPane.setFillWidth(baseUrlField, true);
        GridPane.setFillWidth(apiKeyField, true);
        GridPane.setFillWidth(modelField, true);
        grid.getColumnConstraints().addAll(createLabelColumn(), createFieldColumn());

        boolean ok = DialogHelper.showCustomDialog(source == null ? "新增模型连接" : "编辑模型连接", grid,
                        button -> button != null && button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? Boolean.TRUE : null)
                .isPresent();
        if (!ok) {
            return null;
        }
        String name = trimOrNull(nameField.getText());
        String baseUrl = trimOrNull(baseUrlField.getText());
        String model = trimOrNull(modelField.getText());
        if (name == null || baseUrl == null || model == null) {
            DialogHelper.showWarning("AI 连接", "名称、Base URL 和模型不能为空");
            return null;
        }
        AppConfig.AiModelConnection connection = new AppConfig.AiModelConnection();
        connection.id = initial.id == null || initial.id.isBlank() ? java.util.UUID.randomUUID().toString() : initial.id;
        connection.name = name;
        connection.apiFormat = apiFormatCode(formatCombo.getValue());
        connection.baseUrl = baseUrl;
        connection.apiKey = trimToEmpty(apiKeyField.getText());
        connection.model = model;
        connection.imageInputSupported = imageInputSupported.isSelected();
        return connection;
    }

    private AppConfig.AiModelConnection copyAiConnection(AppConfig.AiModelConnection source) {
        AppConfig.AiModelConnection copy = new AppConfig.AiModelConnection();
        copy.id = source.id;
        copy.name = source.name;
        copy.apiFormat = source.apiFormat;
        copy.baseUrl = source.baseUrl;
        copy.apiKey = source.apiKey;
        copy.model = source.model;
        copy.imageInputSupported = source.imageInputSupported;
        return copy;
    }

    private String apiFormatLabel(String value) {
        return switch (value) {
            case "OPENAI_RESPONSES" -> "OpenAI Responses v1";
            case "ANTHROPIC_MESSAGES" -> "Anthropic Messages";
            case "GEMINI_NATIVE" -> "Gemini Native";
            default -> "OpenAI Chat Completions";
        };
    }

    private String apiFormatCode(String label) {
        return switch (label) {
            case "OpenAI Responses v1" -> "OPENAI_RESPONSES";
            case "Anthropic Messages" -> "ANTHROPIC_MESSAGES";
            case "Gemini Native" -> "GEMINI_NATIVE";
            default -> "OPENAI_CHAT_COMPLETIONS";
        };
    }

    private String defaultBaseUrlForFormat(String apiFormat) {
        return switch (apiFormat) {
            case "ANTHROPIC_MESSAGES" -> AppConfig.AiModelConnection.ANTHROPIC_BASE_URL;
            case "GEMINI_NATIVE" -> AppConfig.AiModelConnection.GEMINI_BASE_URL;
            default -> AppConfig.AiModelConnection.OPENAI_BASE_URL;
        };
    }

    private String baseUrlExample(String apiFormat) {
        return "例如：" + defaultBaseUrlForFormat(apiFormat);
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

    private void clearAiHistory() {
        if (DialogHelper.showConfirmYesNo("清空AI历史会话", "确定要清空所有AI历史会话吗？此操作无法撤销。")) {
            AiConversationRepository.getInstance().clear();
            DialogHelper.showInfo("完成", "AI历史会话已清空。");
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

    public void selectAiSettings() {
        selectTab(8);
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

package com.yshell.controller;

import com.yshell.config.AppSettings;
import com.yshell.model.ConnInfo;
import com.yshell.model.ProxyInfo;
import com.yshell.model.SshKeyInfo;
import com.yshell.model.TunnelInfo;
import com.yshell.service.ProxyRepository;
import com.yshell.service.SshKeyRepository;
import com.yshell.theme.ThemeManager;
import com.yshell.ui.ApplicationIcons;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ConnectionEditorController {

    private static final String DEFAULT_TERMINAL_ENCODING = "UTF-8";
    private final AppSettings appSettings = AppSettings.getInstance();

    // ===== General =====
    @FXML
    private Label dialogTitle;
    @FXML
    private Button btnClose;
    @FXML
    private Button tabSsh;
    @FXML
    private Button tabRdp;
    @FXML
    private VBox sshSettings;
    @FXML
    private VBox rdpSettings;
    @FXML
    private Button btnDelete;
    @FXML
    private Button btnCancel;
    @FXML
    private Button btnSave;

    @FXML
    private Parent root;

    // ===== SSH Settings =====
    @FXML
    private TextField sshConnName;
    @FXML
    private TextField sshConnPort;
    @FXML
    private TextField sshConnHost;
    @FXML
    private TextField sshConnNotes;
    @FXML
    private ComboBox<String> sshAuthMethod;
    @FXML
    private TextField sshConnUsername;
    @FXML
    private PasswordField sshConnPassword;
    @FXML
    private VBox sshPasswordRow;
    @FXML
    private VBox sshPrivateKeyRow;
    @FXML
    private TextField sshPrivateKeyPath;
    @FXML
    private Button sshBtnBrowseKey;
    @FXML
    private CheckBox sshSmartAccel;
    @FXML
    private CheckBox sshExecChannel;
    @FXML
    private CheckBox sshForwardingAutoReconnect;
    @FXML
    private ComboBox<String> sshCharEncoding;
    @FXML
    private ComboBox<String> sshBackspace;
    @FXML
    private ComboBox<String> sshDelete;

    // ===== SSH Proxy & Tunnel =====
    @FXML
    private Button sshEditProxy;
    @FXML
    private Button sshAddProxy;
    @FXML
    private Button sshDeleteProxy;
    @FXML
    private TableView<ProxyInfo> sshProxyTable;
    @FXML
    private TableColumn<ProxyInfo, Boolean> proxyColCheck;
    @FXML
    private TableColumn<ProxyInfo, String> proxyColName;
    @FXML
    private TableColumn<ProxyInfo, String> proxyColType;
    @FXML
    private TableColumn<ProxyInfo, String> proxyColHost;
    @FXML
    private TableColumn<ProxyInfo, Number> proxyColPort;

    @FXML
    private Button sshEditTunnel;
    @FXML
    private Button sshAddTunnel;
    @FXML
    private Button sshDeleteTunnel;
    @FXML
    private TableView<TunnelInfo> sshTunnelTable;
    @FXML
    private TableColumn<TunnelInfo, String> tunnelColName;
    @FXML
    private TableColumn<TunnelInfo, String> tunnelColType;
    @FXML
    private TableColumn<TunnelInfo, Number> tunnelColListen;
    @FXML
    private TableColumn<TunnelInfo, String> tunnelColTargetAddr;
    @FXML
    private TableColumn<TunnelInfo, Number> tunnelColTargetPort;

    // ===== RDP Settings =====
    @FXML
    private TextField rdpConnName;
    @FXML
    private TextField rdpConnPort;
    @FXML
    private TextField rdpConnHost;
    @FXML
    private TextField rdpConnNotes;
    @FXML
    private ComboBox<String> rdpAuthMethod;
    @FXML
    private TextField rdpConnUsername;
    @FXML
    private PasswordField rdpConnPassword;
    @FXML
    private ComboBox<String> rdpResolution;
    @FXML
    private HBox rdpCustomSizeRow;
    @FXML
    private TextField rdpCustomWidth;
    @FXML
    private TextField rdpCustomHeight;
    @FXML
    private CheckBox rdpFullscreen;
    @FXML
    private CheckBox rdpDriveMapping;
    @FXML
    private CheckBox rdpSmartAccel;

    // ===== RDP Proxy =====
    @FXML
    private Button rdpEditProxy;
    @FXML
    private Button rdpAddProxy;
    @FXML
    private Button rdpDeleteProxy;
    @FXML
    private TableView<ProxyInfo> rdpProxyTable;
    @FXML
    private TableColumn<ProxyInfo, Boolean> rdpProxyColCheck;
    @FXML
    private TableColumn<ProxyInfo, String> rdpProxyColName;
    @FXML
    private TableColumn<ProxyInfo, String> rdpProxyColType;
    @FXML
    private TableColumn<ProxyInfo, String> rdpProxyColHost;
    @FXML
    private TableColumn<ProxyInfo, Number> rdpProxyColPort;

    // ===== 内部状态 =====
    private Stage dialogStage;
    private String connectionType = "ssh";
    private boolean isEditMode = false;
    private ConnInfo editingConnection;
    private Consumer<ConnInfo> saveHandler;
    private Consumer<ConnInfo> deleteHandler;

    // ===== 数据源（ObservableList 直接驱动 TableView）=====
    private final ObservableList<TunnelInfo> sshTunnels = FXCollections.observableArrayList();
    private final ObservableList<ProxyInfo> sshProxies = FXCollections.observableArrayList();
    private String selectedSshProxyId = "0";
    private String selectedSshKeyValue = "";
    private boolean updatingSshKeyDisplay = false;

    private final ObservableList<ProxyInfo> rdpProxies = FXCollections.observableArrayList();
    private String selectedRdpProxyId = "0";

    // ==================== 公开 API ====================

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    public void setEditMode(boolean isEdit) {
        this.isEditMode = isEdit;
        btnDelete.setVisible(isEdit);
        dialogTitle.setText(isEdit ? "编辑连接" : "新建连接");
    }

    public void setSaveHandler(Consumer<ConnInfo> handler) {
        this.saveHandler = handler;
    }

    public void setDeleteHandler(Consumer<ConnInfo> handler) {
        this.deleteHandler = handler;
    }

    public void loadConnection(ConnInfo conn) {
        this.editingConnection = conn;
        connectionType = conn.getType() != null ? conn.getType() : "ssh";

        if ("rdp".equals(connectionType)) {
            switchConnectionType("rdp");
        } else {
            switchConnectionType("ssh");
        }

        if ("ssh".equals(connectionType)) {
            sshConnName.setText(conn.getName() != null ? conn.getName() : "");
            sshConnHost.setText(conn.getHost() != null ? conn.getHost() : "");
            sshConnPort.setText(conn.getPort() > 0 ? String.valueOf(conn.getPort()) : "22");
            sshConnUsername.setText(conn.getUserName() != null ? conn.getUserName() : "");
            sshConnPassword.setText(conn.getPassword() != null ? conn.getPassword() : "");
            sshConnNotes.setText(conn.getDescription() != null ? conn.getDescription() : "");
            setSelectedSshKey(conn.getSecretKeyId() != null ? conn.getSecretKeyId() : "");

            // 认证方式：authenticationType 2=私钥，否则密码
            if (conn.getAuthenticationType() == 2 || (conn.getSecretKeyId() != null && !conn.getSecretKeyId().isEmpty())) {
                sshAuthMethod.setValue("私钥");
            } else if (conn.getAuthenticationType() == 3) {
                sshAuthMethod.setValue("键盘交互");
            } else {
                sshAuthMethod.setValue("密码");
            }
            toggleSshAuthMethod();

            // 高级设置
            sshSmartAccel.setSelected(conn.isAccelerate());
            sshExecChannel.setSelected(conn.isExecChannelEnable());
            sshForwardingAutoReconnect.setSelected(conn.isForwardingAutoReconnect());

            // 终端设置
            if (conn.getTerminalEncoding() != null) {
                setSshCharEncodingValue(conn.getTerminalEncoding());
            }
            sshBackspace.setValue(mapBackspaceSequence(conn.getBackspaceKeySequence()));
            sshDelete.setValue(mapDeleteSequence(conn.getDeleteKeySequence()));

            // 恢复代理和隧道数据
            selectedSshProxyId = conn.getProxyId() != null ? conn.getProxyId() : "0";
            restoreTunnelsFromConnInfo(conn);
            restoreProxiesFromConnInfo(conn, true);
        } else {
            rdpConnName.setText(conn.getName() != null ? conn.getName() : "");
            rdpConnHost.setText(conn.getHost() != null ? conn.getHost() : "");
            rdpConnPort.setText(conn.getPort() > 0 ? String.valueOf(conn.getPort()) : "3389");
            rdpConnUsername.setText(conn.getUserName() != null ? conn.getUserName() : "");
            rdpConnPassword.setText(conn.getPassword() != null ? conn.getPassword() : "");
            rdpConnNotes.setText(conn.getDescription() != null ? conn.getDescription() : "");
            if (!rdpAuthMethod.getItems().isEmpty()) {
                rdpAuthMethod.setValue(rdpAuthMethod.getItems().get(0));
            }

            // 显示设置
            rdpFullscreen.setSelected(conn.isFullscreen());
            if (conn.isCustomSize() && conn.getWidth() > 0 && conn.getHeight() > 0) {
                rdpResolution.setValue("自定义");
                rdpCustomWidth.setText(String.valueOf(conn.getWidth()));
                rdpCustomHeight.setText(String.valueOf(conn.getHeight()));
            } else {
                // 尝试匹配预设分辨率
                String match = findResolutionOption(conn.getWidth(), conn.getHeight());
                rdpResolution.setValue(Objects.requireNonNullElse(match, "1280 x 720"));
            }
            onRdpResolutionChanged();

            // 映射设置
            rdpDriveMapping.setSelected(conn.isDriveStoreDirect());

            // 高级设置
            rdpSmartAccel.setSelected(conn.isAccelerate());

            selectedRdpProxyId = conn.getProxyId() != null ? conn.getProxyId() : "0";
            restoreProxiesFromConnInfo(conn, false);
        }
    }

    // ==================== 初始化 ====================

    @FXML
    public void initialize() {
        WindowDragResize.apply(root, 40, btnClose);
        setupComboBoxes();
        setupTunnelTable();
        setupSshProxyTable();
        setupRdpProxyTable();
        setupEventHandlers();
        loadGlobalProxies();
    }

    /**
     * 从全局 ProxyRepository 加载代理列表到 SSH 和 RDP 的代理表格
     * 代理是全局共用的，每个连接通过 proxyId 引用其中一个
     */
    private void loadGlobalProxies() {
        List<ProxyInfo> globalProxies = ProxyRepository.getInstance().load();
        sshProxies.setAll(globalProxies);
        rdpProxies.setAll(globalProxies);
    }

    /**
     * 将当前代理列表同步到全局 ProxyRepository（删除操作后调用）
     */
    private void syncGlobalProxies() {
        ProxyRepository.getInstance().save(new ArrayList<>(sshProxies));
        rdpProxies.setAll(sshProxies);
        // 如果被删除的代理正好是当前选中的，重置选中状态
        if (selectedSshProxyId != null && sshProxies.stream().noneMatch(p -> p.getId().equals(selectedSshProxyId))) {
            selectedSshProxyId = "0";
            sshProxyTable.refresh();
        }
        if (selectedRdpProxyId != null && rdpProxies.stream().noneMatch(p -> p.getId().equals(selectedRdpProxyId))) {
            selectedRdpProxyId = "0";
            rdpProxyTable.refresh();
        }
    }

    private void setupComboBoxes() {
        sshAuthMethod.getItems().addAll("密码", "私钥", "键盘交互");
        sshAuthMethod.setValue("密码");

        sshCharEncoding.getItems().setAll(availableTerminalEncodings());
        setSshCharEncodingValue(appSettings.getTerminalDefaultEncoding());

        sshBackspace.getItems().addAll("ASCII - Backspace", "VT220 - Delete", "ASCII - Delete");
        sshBackspace.setValue(mapBackspaceSequence(appSettings.getTerminalDefaultBackspaceSequence()));

        sshDelete.getItems().addAll("VT220 - Delete", "ASCII - Delete", "ASCII - Backspace");
        sshDelete.setValue(mapDeleteSequence(appSettings.getTerminalDefaultDeleteSequence()));

        rdpAuthMethod.getItems().addAll("密码");
        rdpAuthMethod.setValue("密码");

        rdpResolution.getItems().addAll("800 x 600", "1024 x 768", "1280 x 720",
                "1280 x 1024", "1366 x 768", "1440 x 900", "1600 x 900",
                "1680 x 1050", "1920 x 1080", "自定义");
        rdpResolution.setValue("1280 x 720");
        rdpResolution.setOnAction(e -> onRdpResolutionChanged());
    }

    /**
     * 配置隧道 TableView：绑定列 + 双击编辑 + 行选中联动按钮
     */
    private void setupTunnelTable() {
        sshTunnelTable.setItems(sshTunnels);

        tunnelColName.setCellValueFactory(new PropertyValueFactory<>("name"));
        tunnelColType.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getTypeDisplayName()));
        tunnelColListen.setCellValueFactory(new PropertyValueFactory<>("listenPort"));
        tunnelColTargetAddr.setCellValueFactory(new PropertyValueFactory<>("targetHost"));
        tunnelColTargetPort.setCellValueFactory(new PropertyValueFactory<>("targetPort"));

        // 双击行 → 编辑
        sshTunnelTable.setRowFactory(tv -> {
            TableRow<TunnelInfo> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    openTunnelDialog(row.getItem());
                }
            });
            return row;
        });
    }

    /**
     * 配置 SSH 代理 TableView：勾选列单选模式，选中=使用该代理
     */
    private void setupSshProxyTable() {
        sshProxyTable.setItems(sshProxies);

        // 勾选列：CheckBox 单选互斥（Cell 内部根据 selectedSshProxyId 判断勾选状态）
        proxyColCheck.setCellFactory(col -> new ProxyCheckCell(
                id -> {
                    selectedSshProxyId = id;
                    sshProxyTable.refresh();
                },
                () -> selectedSshProxyId
        ));

        proxyColName.setCellValueFactory(new PropertyValueFactory<>("name"));
        proxyColType.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getTypeDisplayName()));
        proxyColHost.setCellValueFactory(new PropertyValueFactory<>("host"));
        proxyColPort.setCellValueFactory(new PropertyValueFactory<>("port"));

        // 双击行 → 编辑
        sshProxyTable.setRowFactory(tv -> {
            TableRow<ProxyInfo> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    openProxyDialog(row.getItem(), true);
                }
            });
            return row;
        });
    }

    /**
     * 配置 RDP 代理 TableView：同上逻辑
     */
    private void setupRdpProxyTable() {
        rdpProxyTable.setItems(rdpProxies);

        rdpProxyColCheck.setCellFactory(col -> new ProxyCheckCell(
                id -> {
                    selectedRdpProxyId = id;
                    rdpProxyTable.refresh();
                },
                () -> selectedRdpProxyId
        ));

        rdpProxyColName.setCellValueFactory(new PropertyValueFactory<>("name"));
        rdpProxyColType.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getTypeDisplayName()));
        rdpProxyColHost.setCellValueFactory(new PropertyValueFactory<>("host"));
        rdpProxyColPort.setCellValueFactory(new PropertyValueFactory<>("port"));

        rdpProxyTable.setRowFactory(tv -> {
            TableRow<ProxyInfo> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    openProxyDialog(row.getItem(), false);
                }
            });
            return row;
        });
    }

    private void setupEventHandlers() {
        btnClose.setOnAction(e -> closeDialog());
        btnCancel.setOnAction(e -> closeDialog());
        btnSave.setOnAction(e -> saveConnection());
        btnDelete.setOnAction(e -> deleteConnection());

        tabSsh.setOnAction(e -> switchConnectionType("ssh"));
        tabRdp.setOnAction(e -> switchConnectionType("rdp"));

        sshAuthMethod.setOnAction(e -> toggleSshAuthMethod());
        sshBtnBrowseKey.setOnAction(e -> browseSshPrivateKey());
        sshPrivateKeyPath.setEditable(false);
        sshPrivateKeyPath.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!updatingSshKeyDisplay) {
                selectedSshKeyValue = newValue != null ? newValue.trim() : "";
            }
        });

        // SSH 代理按钮
        sshAddProxy.setOnAction(e -> openProxyDialog(null, true));
        sshEditProxy.setOnAction(e -> editSelected(sshProxyTable));
        sshDeleteProxy.setOnAction(e -> deleteSelected(sshProxyTable, sshProxies, this::syncGlobalProxies));

        // SSH 隧道按钮
        sshAddTunnel.setOnAction(e -> openTunnelDialog(null));
        sshEditTunnel.setOnAction(e -> editSelected(sshTunnelTable));
        sshDeleteTunnel.setOnAction(e -> deleteSelected(sshTunnelTable, sshTunnels, null));

        // RDP 代理按钮
        rdpAddProxy.setOnAction(e -> openProxyDialog(null, false));
        rdpEditProxy.setOnAction(e -> editSelected(rdpProxyTable));
        rdpDeleteProxy.setOnAction(e -> deleteSelected(rdpProxyTable, rdpProxies, this::syncGlobalProxies));
    }

    // ==================== 隧道操作（纯数据操作）====================

    private void openTunnelDialog(TunnelInfo existing) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/TunnelEditor.fxml"));
            Parent root = loader.load();
            TunnelEditorController controller = loader.getController();

            Stage stage = new Stage();
            ApplicationIcons.applyTo(stage);
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setTitle(existing != null ? "编辑隧道" : "新建隧道");
            stage.initOwner(dialogStage);

            Scene scene = new Scene(root, 360, 320);
            ThemeManager.getInstance().registerScene(scene);
            stage.setScene(scene);

            controller.setDialogStage(stage);
            controller.setEditMode(existing != null);
            if (existing != null) {
                controller.loadTunnel(existing);
            }
            controller.setSaveHandler(tunnel -> {
                if (existing != null) {
                    int idx = sshTunnels.indexOf(existing);
                    if (idx >= 0) sshTunnels.set(idx, tunnel);
                } else {
                    sshTunnels.add(tunnel);
                }
                // ObservableList 自动通知 TableView 刷新，无需手动 refresh
            });

            centerOnParent(stage, 320);
            stage.showAndWait();
        } catch (IOException e) {
            DialogHelper.showError("错误", "无法打开隧道编辑器: " + e.getMessage());
        }
    }

    // ==================== 代理操作（纯数据操作）====================

    private void openProxyDialog(ProxyInfo existing, boolean isSsh) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ProxyEditor.fxml"));
            Parent root = loader.load();
            ProxyEditorController controller = loader.getController();

            Stage stage = new Stage();
            ApplicationIcons.applyTo(stage);
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setTitle(existing != null ? "编辑代理服务器" : "新建代理服务器");
            stage.initOwner(dialogStage);

            Scene scene = new Scene(root, 360, 300);
            ThemeManager.getInstance().registerScene(scene);
            stage.setScene(scene);

            controller.setDialogStage(stage);
            controller.setEditMode(existing != null);
            if (existing != null) {
                controller.loadProxy(existing);
            }

            final boolean forSsh = isSsh;
            final ProxyInfo editTarget = existing;
            final ObservableList<ProxyInfo> targetList = forSsh ? sshProxies : rdpProxies;
            controller.setSaveHandler(proxy -> {
                if (editTarget != null) {
                    int idx = targetList.indexOf(editTarget);
                    if (idx >= 0) targetList.set(idx, proxy);
                } else {
                    targetList.add(proxy);
                }
                // 同步到全局代理仓库（SSH和RDP共用同一份代理数据）
                ProxyRepository.getInstance().upsert(proxy);
                // 保持两个表格的数据同步
                if (forSsh) {
                    rdpProxies.setAll(sshProxies);
                } else {
                    sshProxies.setAll(rdpProxies);
                }
                // 新增时自动选中该代理
                String newId = proxy.getId();
                if (forSsh) {
                    selectedSshProxyId = newId;
                    sshProxyTable.refresh();
                } else {
                    selectedRdpProxyId = newId;
                    rdpProxyTable.refresh();
                }
            });

            centerOnParent(stage, 300);
            stage.showAndWait();
        } catch (IOException e) {
            DialogHelper.showError("错误", "无法打开代理编辑器: " + e.getMessage());
        }
    }

    // ==================== 通用编辑/删除（基于 TableView 选择模型）====================

    /**
     * 编辑当前选中的行
     */
    @SuppressWarnings("unchecked")
    private <T> void editSelected(TableView<?> table) {
        T selected = (T) table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            DialogHelper.showInfo("提示", "请先选择一条记录");
            return;
        }
        if (selected instanceof TunnelInfo) {
            openTunnelDialog((TunnelInfo) selected);
        } else if (selected instanceof ProxyInfo) {
            // 根据表格判断是 SSH 还是 RDP 的代理
            boolean isSsh = (table == sshProxyTable);
            openProxyDialog((ProxyInfo) selected, isSsh);
        }
    }

    /**
     * 删除当前选中的行
     */
    @SuppressWarnings("unchecked")
    private <T> void deleteSelected(TableView<?> table, ObservableList<T> dataList, Runnable onDeleted) {
        T selected = (T) table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            DialogHelper.showInfo("提示", "请先选择一条记录");
            return;
        }
        String name = "";
        if (selected instanceof TunnelInfo) name = ((TunnelInfo) selected).getName();
        else if (selected instanceof ProxyInfo) name = ((ProxyInfo) selected).getName();

        if (DialogHelper.showConfirmYesNo("确认删除", "确定要删除 \"" + name + "\" 吗？")) {
            dataList.remove(selected);
            if (onDeleted != null) onDeleted.run();
        }
    }

    // ==================== 数据恢复（从 ConnInfo 反序列化）====================

    private void restoreTunnelsFromConnInfo(ConnInfo conn) {
        sshTunnels.clear();
        List<Object> raw = conn.getPortForwardingList();
        if (raw != null) {
            for (Object item : raw) {
                if (item instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) item;
                    TunnelInfo t = new TunnelInfo();
                    if (m.containsKey("id")) t.setId(String.valueOf(m.get("id")));
                    if (m.containsKey("name")) t.setName(String.valueOf(m.get("name")));
                    if (m.containsKey("type")) t.setType(String.valueOf(m.get("type")));
                    if (m.containsKey("listenPort")) t.setListenPort(((Number) m.get("listenPort")).intValue());
                    if (m.containsKey("bindIp")) t.setBindIp(String.valueOf(m.get("bindIp")));
                    if (m.containsKey("targetHost")) t.setTargetHost(String.valueOf(m.get("targetHost")));
                    if (m.containsKey("targetPort")) t.setTargetPort(((Number) m.get("targetPort")).intValue());
                    sshTunnels.add(t);
                }
            }
        }
    }

    private void restoreProxiesFromConnInfo(ConnInfo conn, boolean isSsh) {
        // 代理列表已在 initialize() 时从全局 ProxyRepository 加载
        // 此处只需根据 conn.proxyId 设置选中状态
        String proxyId = conn.getProxyId();
        String number = (proxyId != null && !proxyId.isEmpty()) ? proxyId : "0";
        if (isSsh) {
            selectedSshProxyId = number;
            sshProxyTable.refresh();
        } else {
            selectedRdpProxyId = number;
            rdpProxyTable.refresh();
        }
    }

    // ==================== 原有逻辑 ====================

    @FXML
    private void closeDialog() {
        if (dialogStage != null) dialogStage.close();
    }

    private void saveConnection() {
        ConnInfo conn;
        if (isEditMode && editingConnection != null) {
            conn = editingConnection;
        } else {
            conn = new ConnInfo(connectionType);
        }

        if ("ssh".equals(connectionType)) {
            if (validateRequired(sshConnName, "SSH连接名称")
                    || validateRequired(sshConnHost, "SSH主机")
                    || validateRequired(sshConnUsername, "SSH用户名")) {
                return;
            }
            Integer port = parseRequiredPort(sshConnPort, "SSH端口");
            if (port == null) return;
            String authMethod = sshAuthMethod.getValue();
            if ("私钥".equals(authMethod)) {
                String keyValue = selectedSshKeyValue != null && !selectedSshKeyValue.isBlank()
                        ? selectedSshKeyValue
                        : trim(sshPrivateKeyPath);
                if (keyValue.isBlank()) {
                    DialogHelper.showError("错误", "SSH私钥不能为空");
                    return;
                }
            } else if (isEmpty(sshConnPassword)) {
                DialogHelper.showError("错误", "SSH密码不能为空");
                return;
            }
            conn.setType("ssh");
            conn.setConnectionType(100);
            conn.setName(trim(sshConnName));
            conn.setHost(trim(sshConnHost));
            conn.setPort(port);
            conn.setUserName(trim(sshConnUsername));
            conn.setPassword(textOf(sshConnPassword));
            conn.setDescription(trim(sshConnNotes));

            // 认证方式
            if ("私钥".equals(authMethod)) {
                conn.setAuthenticationType(2);
                String keyValue = selectedSshKeyValue != null && !selectedSshKeyValue.isBlank()
                        ? selectedSshKeyValue
                        : trim(sshPrivateKeyPath);
                conn.setSecretKeyId(keyValue);
            } else if ("键盘交互".equals(authMethod)) {
                conn.setAuthenticationType(3);
                conn.setSecretKeyId("");
            } else {
                conn.setAuthenticationType(1);
                conn.setSecretKeyId("");
            }

            // 高级设置
            conn.setAccelerate(sshSmartAccel.isSelected());
            conn.setExecChannelEnable(sshExecChannel.isSelected());
            conn.setForwardingAutoReconnect(sshForwardingAutoReconnect.isSelected());

            // 终端设置
            conn.setTerminalEncoding(sshCharEncoding.getValue());
            conn.setBackspaceKeySequence(parseBackspaceSequence(sshBackspace.getValue()));
            conn.setDeleteKeySequence(parseDeleteSequence(sshDelete.getValue()));

            conn.setProxyId(selectedSshProxyId);

            // 隧道列表序列化为 Map 存入 portForwardingList
            List<Object> tunnelData = getTunnelData();
            conn.setPortForwardingList(tunnelData);
        } else {
            if (validateRequired(rdpConnName, "RDP连接名称")
                    || validateRequired(rdpConnHost, "RDP主机")
                    || validateRequired(rdpConnUsername, "RDP用户名")
                    || validateRequired(rdpConnPassword, "RDP密码")) {
                return;
            }
            Integer port = parseRequiredPort(rdpConnPort, "RDP端口");
            if (port == null) return;
            conn.setType("rdp");
            conn.setConnectionType(200);
            conn.setName(trim(rdpConnName));
            conn.setHost(trim(rdpConnHost));
            conn.setPort(port);
            conn.setUserName(trim(rdpConnUsername));
            conn.setPassword(textOf(rdpConnPassword));
            conn.setDescription(trim(rdpConnNotes));
            conn.setAuthenticationType(1);

            // 显示设置
            conn.setFullscreen(rdpFullscreen.isSelected());
            String resolution = rdpResolution.getValue();
            if ("自定义".equals(resolution)) {
                conn.setCustomSize(true);
                conn.setWidth(parseIntOrDefault(rdpCustomWidth, 1280));
                conn.setHeight(parseIntOrDefault(rdpCustomHeight, 720));
            } else if (resolution != null) {
                conn.setCustomSize(false);
                // 解析 "W x H" 格式
                try {
                    String[] dims = resolution.split(" x ");
                    conn.setWidth(Integer.parseInt(dims[0].trim()));
                    conn.setHeight(Integer.parseInt(dims[1].trim()));
                } catch (Exception e) {
                    conn.setWidth(1280);
                    conn.setHeight(720);
                }
            } else {
                conn.setCustomSize(false);
                conn.setWidth(1280);
                conn.setHeight(720);
            }

            // 映射设置
            conn.setDriveStoreDirect(rdpDriveMapping.isSelected());

            // 高级设置
            conn.setAccelerate(rdpSmartAccel.isSelected());

            conn.setProxyId(selectedRdpProxyId);
        }

        if (saveHandler != null) saveHandler.accept(conn);
        closeDialog();
    }

    private List<Object> getTunnelData() {
        List<Object> tunnelData = new ArrayList<>();
        for (TunnelInfo t : sshTunnels) {
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("type", t.getType());
            m.put("listenPort", t.getListenPort());
            m.put("bindIp", t.getBindIp());
            m.put("targetHost", t.getTargetHost());
            m.put("targetPort", t.getTargetPort());
            tunnelData.add(m);
        }
        return tunnelData;
    }

    private void deleteConnection() {
        if (deleteHandler != null && editingConnection != null) {
            deleteHandler.accept(editingConnection);
            closeDialog();
        }
    }

    private void switchConnectionType(String type) {
        connectionType = type;
        tabSsh.getStyleClass().remove("active");
        tabRdp.getStyleClass().remove("active");
        if ("ssh".equals(type)) {
            tabSsh.getStyleClass().add("active");
            sshSettings.setVisible(true);
            sshSettings.setManaged(true);
            rdpSettings.setVisible(false);
            rdpSettings.setManaged(false);
        } else {
            tabRdp.getStyleClass().add("active");
            sshSettings.setVisible(false);
            sshSettings.setManaged(false);
            rdpSettings.setVisible(true);
            rdpSettings.setManaged(true);
        }
    }

    private void toggleSshAuthMethod() {
        boolean showKey = "私钥".equals(sshAuthMethod.getValue());
        sshPasswordRow.setVisible(!showKey);
        sshPasswordRow.setManaged(!showKey);
        sshPrivateKeyRow.setVisible(showKey);
        sshPrivateKeyRow.setManaged(showKey);
    }

    private void setSshCharEncodingValue(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            sshCharEncoding.setValue(DEFAULT_TERMINAL_ENCODING);
            return;
        }
        if (!sshCharEncoding.getItems().contains(encoding)) {
            sshCharEncoding.getItems().add(encoding);
        }
        sshCharEncoding.setValue(encoding);
    }

    private static List<String> availableTerminalEncodings() {
        List<String> encodings = new ArrayList<>(Charset.availableCharsets().keySet());
        encodings.remove(DEFAULT_TERMINAL_ENCODING);
        encodings.add(0, DEFAULT_TERMINAL_ENCODING);
        return encodings;
    }

    /**
     * 分辨率切换：选择"自定义"时显示宽高输入框，否则隐藏
     */
    private void onRdpResolutionChanged() {
        boolean isCustom = "自定义".equals(rdpResolution.getValue());
        rdpCustomSizeRow.setVisible(isCustom);
        rdpCustomSizeRow.setManaged(isCustom);
    }

    private void browseSshPrivateKey() {
        List<SshKeyInfo> keys = SshKeyRepository.getInstance().load();
        if (keys.isEmpty()) {
            chooseLocalSshPrivateKey();
            return;
        }

        ListView<KeySelectionOption> listView = new ListView<>();
        listView.getStyleClass().add("ssh-key-selection-list");
        listView.setPrefWidth(380);
        listView.setMaxWidth(380);
        ObservableList<KeySelectionOption> options = FXCollections.observableArrayList();
        keys.forEach(key -> options.add(KeySelectionOption.managed(key)));
        options.add(KeySelectionOption.browse());
        listView.setItems(options);
        listView.getSelectionModel().selectFirst();
        listView.setPrefHeight(Math.min(300, Math.max(120, options.size() * 46 + 8)));
        listView.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(KeySelectionOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label title = new Label(item.title());
                title.getStyleClass().add("ssh-key-option-title");
                title.setMaxWidth(340);
                title.setTextOverrun(OverrunStyle.ELLIPSIS);
                Label detail = new Label(item.detail());
                detail.getStyleClass().add("ssh-key-option-detail");
                detail.setMaxWidth(340);
                detail.setTextOverrun(OverrunStyle.ELLIPSIS);
                VBox box = new VBox(title, detail);
                box.getStyleClass().add("ssh-key-option");
                setGraphic(box);
            }
        });
        VBox listContainer = new VBox(listView);

        DialogHelper.showCustomDialog("选择 SSH 私钥", listContainer, button ->
                button.getButtonData() == ButtonBar.ButtonData.OK_DONE
                        ? listView.getSelectionModel().getSelectedItem()
                        : null, "ssh-key-selection-dialog"
        ).ifPresent(option -> {
            if (option.browseLocal()) {
                chooseLocalSshPrivateKey();
            } else {
                setSelectedSshKey(option.value());
            }
        });
    }

    private void chooseLocalSshPrivateKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 SSH 私钥");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SSH 私钥", "id_*", "*.pem", "*.key", "*"),
                new FileChooser.ExtensionFilter("所有文件", "*")
        );
        File file = chooser.showOpenDialog(dialogStage);
        if (file != null) {
            setSelectedSshKey(file.getAbsolutePath());
        }
    }

    private void setSelectedSshKey(String value) {
        selectedSshKeyValue = value != null ? value.trim() : "";
        updatingSshKeyDisplay = true;
        try {
            sshPrivateKeyPath.setText(formatSshKeyDisplay(selectedSshKeyValue));
        } finally {
            updatingSshKeyDisplay = false;
        }
    }

    private String formatSshKeyDisplay(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return SshKeyRepository.getInstance()
                .findById(value)
                .map(key -> {
                    String name = key.getName() != null && !key.getName().isBlank() ? key.getName() : "未命名密钥";
                    String path = key.getPrivateKeyPath() != null ? key.getPrivateKeyPath() : "";
                    return path.isBlank() ? name : name + " - " + path;
                })
                .orElse(value);
    }

    // ==================== 工具方法 ====================

    /**
     * 子弹窗居中于父窗口
     */
    private void centerOnParent(Stage child, double h) {
        child.setX(dialogStage.getX() + (dialogStage.getWidth() - (double) 360) / 2);
        child.setY(dialogStage.getY() + (dialogStage.getHeight() - h) / 2);
    }

    private static boolean isEmpty(TextField tf) {
        return tf.getText() == null || tf.getText().trim().isEmpty();
    }

    private static String trim(TextField tf) {
        return tf.getText() != null ? tf.getText().trim() : "";
    }

    private static String textOf(PasswordField pf) {
        return pf.getText() != null ? pf.getText() : "";
    }

    private static int parseIntOrDefault(TextField tf, int def) {
        try {
            return Integer.parseInt(tf.getText().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean validateRequired(TextField tf, String label) {
        if (isEmpty(tf)) {
            DialogHelper.showError("错误", label + "不能为空");
            return true;
        }
        return false;
    }

    private static Integer parseRequiredPort(TextField tf, String label) {
        if (isEmpty(tf)) {
            DialogHelper.showError("错误", label + "不能为空");
            return null;
        }
        try {
            int port = Integer.parseInt(tf.getText().trim());
            if (port < 1 || port > 65535) {
                DialogHelper.showError("错误", label + "必须在 1-65535 之间");
                return null;
            }
            return port;
        } catch (NumberFormatException e) {
            DialogHelper.showError("错误", label + "必须是数字");
            return null;
        }
    }

    // ===== 按键序列映射 =====

    /**
     * Backspace 序号值 → ComboBox 显示文本
     * 1=ASCII - Backspace, 2=VT220 - Delete, 0=ASCII - Delete
     */
    private static String mapBackspaceSequence(int value) {
        return switch (value) {
            case 1 -> "ASCII - Backspace";
            case 2 -> "VT220 - Delete";
            default -> "ASCII - Delete";
        };
    }

    /**
     * ComboBox 显示文本 → Backspace 序号值
     */
    private static int parseBackspaceSequence(String text) {
        if (text == null) return 2; // default
        return switch (text) {
            case "ASCII - Backspace" -> 1;
            case "VT220 - Delete" -> 2;
            default -> 0; // ASCII - Delete
        };
    }

    /**
     * Delete 序号值 → ComboBox 显示文本
     * 0=VT220 - Delete, 1=ASCII - Delete, 2=ASCII - Backspace
     */
    private static String mapDeleteSequence(int value) {
        return switch (value) {
            case 0 -> "VT220 - Delete";
            case 1 -> "ASCII - Delete";
            default -> "ASCII - Backspace";
        };
    }

    /**
     * ComboBox 显示文本 → Delete 序号值
     */
    private static int parseDeleteSequence(String text) {
        if (text == null) return 0; // default
        return switch (text) {
            case "VT220 - Delete" -> 0;
            case "ASCII - Delete" -> 1;
            default -> 2; // ASCII - Backspace
        };
    }

    /**
     * 根据宽高匹配预设分辨率选项，匹配不到返回 null
     */
    private static String findResolutionOption(int width, int height) {
        String key = width + " x " + height;
        java.util.Set<String> options = java.util.Set.of(
                "800 x 600", "1024 x 768", "1280 x 720",
                "1280 x 1024", "1366 x 768", "1440 x 900", "1600 x 900",
                "1680 x 1050", "1920 x 1080");
        return options.contains(key) ? key : null;
    }

    private record KeySelectionOption(SshKeyInfo key, boolean browseLocal) {
        static KeySelectionOption managed(SshKeyInfo key) {
            return new KeySelectionOption(key, false);
        }

        static KeySelectionOption browse() {
            return new KeySelectionOption(null, true);
        }

        String value() {
            return key != null ? key.getId() : "";
        }

        String title() {
            if (browseLocal) {
                return "浏览本地私钥文件...";
            }
            return key.getName() != null && !key.getName().isBlank() ? key.getName() : "未命名密钥";
        }

        String detail() {
            if (browseLocal) {
                return "使用未纳入密钥管理的本地私钥路径";
            }
            String type = key.getType() != null && !key.getType().isBlank() ? key.getType() : "SSH Key";
            String bits = key.getBits() > 0 ? " / " + key.getBits() : "";
            String path = key.getPrivateKeyPath() != null ? key.getPrivateKeyPath() : "";
            return type + bits + (path.isBlank() ? "" : " / " + path);
        }
    }

    // ==================== 代理勾选列 Cell（单选互斥 CheckBox）====================

    /**
     * 代理表格的勾选列单元格：CheckBox 表现出 RadioButton 的单选互斥行为
     * 点击勾选某个代理 → 自动取消其他行的勾选 → 更新 selectedId 回调
     */
    private static class ProxyCheckCell extends TableCell<ProxyInfo, Boolean> {

        private final CheckBox checkBox = new CheckBox();
        private final Supplier<String> selectedIdSupplier;

        ProxyCheckCell(Consumer<String> onSelected,
                       Supplier<String> selectedIdSupplier) {
            this.selectedIdSupplier = selectedIdSupplier;
            checkBox.getStyleClass().add("check-box-interactive");
            setContentDisplay(ContentDisplay.CENTER);
            setGraphic(checkBox);

            checkBox.setOnAction(e -> {
                if (!isEmpty()) {
                    ProxyInfo item = getTableView().getItems().get(getIndex());
                    if (item != null) {
                        String selectedId = selectedIdSupplier.get();
                        if (item.getId().equals(selectedId)) {
                            onSelected.accept("0");
                        } else {
                            onSelected.accept(item.getId());
                        }
                    }
                }
            });
        }

        @Override
        protected void updateItem(Boolean checked, boolean empty) {
            super.updateItem(checked, empty);
            if (empty) {
                setGraphic(null);
            } else {
                setGraphic(checkBox);
                // 根据 selectedId 实时判断当前行是否应勾选
                ProxyInfo item = getTableView().getItems().get(getIndex());
                boolean shouldBeChecked = item != null && item.getId().equals(selectedIdSupplier.get());
                checkBox.setSelected(shouldBeChecked);
            }
        }
    }
}

package com.yshell.controller;

import com.yshell.config.AppSettings;
import com.yshell.config.ShortcutRegistry;
import com.yshell.service.ConnectionManager;
import com.yshell.service.SshService;
import com.yshell.service.SshService.RemoteFileStat;
import com.yshell.theme.ThemeManager;
import com.yshell.ui.ApplicationIcons;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EditorViewController {
    private static final Logger LOGGER = LoggerFactory.getLogger(EditorViewController.class);
    // =================================================================
    // 静态：单窗口管理 + 最近打开文件列表
    // =================================================================
    private static Stage activeStage;
    private static EditorViewController activeController;

    /**
     * 最近打开文件列表（最多 20 条）
     */
    private static final Deque<RecentFileEntry> recentFiles = new LinkedList<>();
    private static final int RECENT_MAX = 20;

    private record RecentFileEntry(String sshConnectionId, String path) {
    }

    /**
     * 全局字体大小（共享给所有 Tab）
     */
    private static final SimpleIntegerProperty globalFontSize = new SimpleIntegerProperty(
            AppSettings.getInstance().getEditorDefaultFontSize());

    /**
     * 全局主题（vs-dark / vs-light）：读取 ThemeManager 的当前值作为默认
     */
    private static final SimpleStringProperty globalTheme = new SimpleStringProperty(
            ThemeManager.getInstance().getCurrentTheme()
    );

    /**
     * 全局自动换行
     */
    private static final SimpleBooleanProperty globalWordWrap = new SimpleBooleanProperty(false);

    private static final long LARGE_FILE_THRESHOLD_BYTES = 2 * 1024 * 1024L;

    // =================================================================
    // 每个 Tab 的独立状态
    // =================================================================
    private static class TabState {
        final Tab tab;
        final WebView webView;
        final WebEngine engine;
        String filePath;
        String language = "plaintext";
        final SimpleBooleanProperty editorReady = new SimpleBooleanProperty(false);
        final SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);
        final SimpleBooleanProperty readOnly = new SimpleBooleanProperty(false);
        /**
         * 远程文件是否实际可写（来自 ssh.isWritable 或 stat 权限检查）
         */
        boolean remoteWritable = true;
        /**
         * 最近一次上报的光标位置（用于状态栏刷新）
         */
        int lastLine = 1;
        int lastCol = 1;
        long initialFileMtime = 0L;
        long initialFileSize = -1L;
        /**
         * 检测到的原文件行尾：LF / CRLF / CR
         */
        String detectedLineEnding = "LF";
        boolean hasBom = false;
        /**
         * JS 侧回调用的桥接对象。
         * 必须由 TabState 强引用，否则 JVM 可能把它 GC 掉，
         * 导致 WebView 里 window.editorBridge.* 调用看起来 "没报错但 Java 端收不到"。
         */
        Object javaBridge;
        /**
         * 绑定的 SSH 连接 ID（来自 ConnectionManager）。
         * - 若 Tab 是"本地新建文件 / 纯文本"，则为 null；
         * - 若 Tab 是从某个 SSH 连接打开的文件，则保存该连接的 connectionId；
         * - 当对应连接被关闭时，本 Tab 会被自动关闭。
         */
        String sshConnectionId;
        /**
         * 当 JS 侧发现 editorBridge 丢失时会尝试回调
         * `window.editorBridgePing.requestReinject()`；Java 端在此处理重新注入。
         */
        boolean newFileMode = false;
        String defaultDirectory = null;
        RemoteFileStat lastStat = null;
        /**
         * 防止 setContent 触发 dirty 的抑制标志位。
         * 用法：在调用 editorAPI.setContent(...) 之前设为 true，
         * setContent 返回后立即设为 false。
         * 不再依赖"事件次数恰好 = 1"的脆弱假设，
         * 也避免了 get() 与 decrement() 之间的竞态。
         */
        volatile boolean suppressDirty = false;

        TabState(Tab tab, WebView webView) {
            this.tab = tab;
            this.webView = webView;
            this.engine = webView.getEngine();
        }
    }

    // =================================================================
    // 实例字段
    // =================================================================
    private final Map<Tab, TabState> tabStates = new IdentityHashMap<>();
    private TabState activeTab;
    private Stage stage;

    // FXML 注入
    @FXML
    private VBox root;
    @FXML
    private Label titleLabel;
    @FXML
    private Button btnMinimize;
    @FXML
    private Button btnMaximize;
    @FXML
    private Button btnClose;
    @FXML
    private HBox readOnlyBar;
    @FXML
    private Button btnNew;
    @FXML
    private Button btnSave;
    @FXML
    private Button btnSaveAs;
    @FXML
    private Button btnFind;
    @FXML
    private Button btnGotoLine;
    @FXML
    private Button btnComment;
    @FXML
    private Button btnUndo;
    @FXML
    private Button btnRedo;
    @FXML
    private Button btnFormat;
    @FXML
    private Button btnWordWrap;
    @FXML
    private Button btnFontIncrease;
    @FXML
    private Button btnFontDecrease;
    @FXML
    private Button btnTheme;
    @FXML
    private Button btnReadOnlyLock;
    @FXML
    private Button btnReload;
    @FXML
    private Button btnCopyPath;
    @FXML
    private Button btnJumpTerm;
    @FXML
    private Button btnRecent;
    @FXML
    private Button btnHelp;
    @FXML
    private Button btnCloseTab;
    @FXML
    private TabPane tabPane;

    @FXML
    private Label statusFileInfo;
    @FXML
    private Label statusPermission;
    @FXML
    private Label statusOwner;
    @FXML
    private Label statusModified;
    @FXML
    private Label statusEncoding;
    @FXML
    private Label statusLineEnd;
    @FXML
    private Label statusLanguage;
    @FXML
    private Label statusSize;
    @FXML
    private Label statusWordWrap;
    @FXML
    private Label statusCursor;
    @FXML
    private Label statusReadonly;

    // =================================================================
    // 静态入口
    // =================================================================

    public static void open(String filePath) {
        open(filePath, null);
    }

    public static void open(String filePath, String sshConnectionId) {
        Platform.runLater(() -> {
            if (activeStage == null || !activeStage.isShowing()) {
                createNewWindow();
            }
            if (activeController != null) {
                activeController.openInTab(filePath, sshConnectionId);
                bringEditorWindowToFront();
            }
        });
    }

    private static void bringEditorWindowToFront() {
        if (activeStage == null || !activeStage.isShowing()) {
            return;
        }
        if (activeStage.isIconified()) {
            activeStage.setIconified(false);
        }
        activeStage.toFront();
        activeStage.requestFocus();
    }

    public static void setGlobalFontSize(int fontSize) {
        Platform.runLater(() -> globalFontSize.set(Math.max(8, Math.min(40, fontSize))));
    }

    private static void createNewWindow() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    EditorViewController.class.getResource("/fxml/EditorView.fxml"));
            VBox rootNode = loader.load();

            EditorViewController controller = loader.getController();
            activeController = controller;

            Stage stage = new Stage();
            ApplicationIcons.applyTo(stage);
            stage.initStyle(StageStyle.UNDECORATED);
            controller.stage = stage;
            activeStage = stage;

            Scene scene = new Scene(rootNode, 1280, 800);
            ThemeManager.getInstance().registerScene(scene);
            stage.setScene(scene);

            // 标题栏（高度 32）用于拖动和边缘缩放
            WindowDragResize.apply(rootNode, 32, controller.btnClose);

            controller.bindWindowButtons();

            stage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
                if (!controller.confirmCloseAllTabs()) {
                    event.consume();
                    return;
                }
                for (TabState s : controller.tabStates.values()) {
                    try {
                        s.engine.load("about:blank");
                    } catch (Exception ignored) {
                    }
                }
                activeStage = null;
                activeController = null;
            });

            stage.show();

            controller.createBlankTab();
        } catch (IOException e) {
            DialogHelper.showError("错误", "无法创建编辑器窗口: " + e.getMessage());
            LOGGER.error("创建编辑器窗口失败: {}", e.getMessage());
        }
    }

    // =================================================================
    // 初始化
    // =================================================================

    @FXML
    public void initialize() {
        // Tab 切换监听器
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == null) {
                activeTab = null;
                return;
            }
            activeTab = tabStates.get(newTab);
            ensureBridgeInjected(activeTab);
            refreshTitleAndStatus();
        });

        // 当所有 Tab 都关闭后，直接关闭编辑器窗口，
        // 不再保留空白的新建页签。
        tabPane.getTabs().addListener((javafx.collections.ListChangeListener.Change<? extends javafx.scene.control.Tab> c) -> {
            while (c.next()) {
                if (c.wasRemoved() && tabPane.getTabs().isEmpty()) {
                    if (stage != null) {
                        stage.close();
                    }
                    break;
                }
            }
        });

        // 订阅 SSH 连接关闭事件。当 ConnectionManager.disconnect(connId)
        // 被调用后，直接关闭本窗口中绑定到该 connId 的所有 Tab。
        // 相比定时轮询，这种方式是确定性的，不会出现"连接已断开但
        // Tab 还在"的短暂时间窗口，也没有轮询开销。
        ConnectionManager.getInstance().addOnConnectionClosedListener(connId -> {
            if (connId == null) return;
            Platform.runLater(() -> closeTabsForConnection(connId));
        });

        // 按钮事件 —— 文件组
        btnClose.setOnAction(e -> closeWindow());
        btnNew.setOnAction(e -> createBlankTab());
        btnSave.setOnAction(e -> saveActiveTab());
        btnSaveAs.setOnAction(e -> saveActiveTabAs());
        btnReload.setOnAction(e -> reloadActiveTab());

        // 编辑组
        btnFind.setOnAction(e -> callEditorApiOnActive("find"));
        btnGotoLine.setOnAction(e -> gotoLine());
        btnComment.setOnAction(e -> toggleComment());
        btnUndo.setOnAction(e -> callEditorApiOnActive("undo"));
        btnRedo.setOnAction(e -> callEditorApiOnActive("redo"));
        btnFormat.setOnAction(e -> formatActiveTab());

        // 显示/偏好组
        btnWordWrap.setOnAction(e -> toggleWordWrap());
        btnFontIncrease.setOnAction(e -> changeFontSize(1));
        btnFontDecrease.setOnAction(e -> changeFontSize(-1));
        btnTheme.setOnAction(e -> toggleTheme());
        btnReadOnlyLock.setOnAction(e -> toggleReadOnlyLock());

        // 辅助组
        btnCopyPath.setOnAction(e -> copyPath());
        btnJumpTerm.setOnAction(e -> jumpToTerminalDirectory());
        btnRecent.setOnAction(e -> showRecentFilesMenu());
        btnHelp.setOnAction(e -> showShortcutHelp());
        btnCloseTab.setOnAction(e -> closeActiveTab());

        // 全局偏好同步监听（当 Tab 新创建完成后同步一次）
        globalFontSize.addListener((obs, old, n) -> syncAllTabsOptions());
        globalTheme.addListener((obs, old, n) -> syncAllTabsTheme());
        globalWordWrap.addListener((obs, old, n) -> syncAllTabsOptions());

        // 快捷键
        Platform.runLater(() -> {
            Scene scene = root.getScene();
            if (scene == null) return;
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), this::saveActiveTab);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), this::saveActiveTabAs);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), () -> callEditorApiOnActive("find"));
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.G, KeyCombination.CONTROL_DOWN), this::gotoLine);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.SLASH, KeyCombination.CONTROL_DOWN), this::toggleComment);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::reloadActiveTab);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.W, KeyCombination.CONTROL_DOWN), this::closeActiveTab);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), this::createBlankTab);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN), () -> callEditorApiOnActive("undo"));
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.Y, KeyCombination.CONTROL_DOWN), () -> callEditorApiOnActive("redo"));
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), () -> callEditorApiOnActive("replace"));
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.ALT_DOWN, KeyCombination.SHIFT_DOWN), this::formatActiveTab);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.Z, KeyCombination.ALT_DOWN), this::toggleWordWrap);
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.CONTROL_DOWN), () -> changeFontSize(1));
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.MINUS, KeyCombination.CONTROL_DOWN), () -> changeFontSize(-1));
            scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F1), this::showShortcutHelp);
        });
    }

    private void bindWindowButtons() {
        if (stage == null) return;
        btnMinimize.setOnAction(e -> stage.setIconified(true));
        btnMaximize.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
    }

    // =================================================================
    // Tab 管理
    // =================================================================

    private void createBlankTab() {
        createBlankTab(null);
    }

    private Tab createBlankTab(String sshConnectionId) {
        WebView webView = new WebView();
        webView.setPrefSize(1000, 600);
        webView.setContextMenuEnabled(true);
        StackPane container = new StackPane(webView);

        Tab tab = new Tab("新文件", container);
        tab.setClosable(true);

        TabState state = new TabState(tab, webView);
        if (sshConnectionId != null) {
            state.sshConnectionId = sshConnectionId;
        } else {
            state.sshConnectionId = ConnectionManager.getInstance().getCurrentConnectionId();
        }
        state.newFileMode = true;
        state.defaultDirectory = null;
        state.language = "plaintext";
        state.detectedLineEnding = "LF";
        tabStates.put(tab, state);

        WebEngine engine = state.engine;
        engine.setJavaScriptEnabled(true);

        // ==== 剪贴板桥接 ====
        // JavaFX WebView 把 JS 侧的剪贴板与系统剪贴板隔离开，
        // 导致 Monaco 内部的 Ctrl+C 复制不到系统剪贴板、
        // Ctrl+V 也拿不到其他程序复制的文本。
        // 这里在事件派发最前端拦截 Ctrl+C / Ctrl+V / Ctrl+X，
        // 由 Java 侧直接读写系统剪贴板，并通过 Monaco API
        // (getSelection / insertText) 与编辑器内容交互。
        installClipboardBridge(state);

        // 注入 JavaBridge（在 HTML 加载成功后，确保 window 对象存在）
        engine.getLoadWorker().stateProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == Worker.State.SUCCEEDED) {
                ensureBridgeInjected(state);
            }
        });

        URL htmlUrl = getClass().getResource("/web/editor.html");
        if (htmlUrl != null) engine.load(htmlUrl.toExternalForm());

        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);

        // Tab 的关闭请求
        tab.setOnCloseRequest(event -> {
            if (state.dirty.get()) {
                if (!DialogHelper.showConfirm("未保存的修改",
                        "该文件有未保存的修改，是否关闭此标签页？未保存的修改将丢失。",
                        "关闭", "取消")) {
                    event.consume();
                    return;
                }
            }
            try {
                state.engine.load("about:blank");
            } catch (Exception ignored) {
            }
            tabStates.remove(tab);
        });

        state.dirty.addListener((obs, old, newVal) -> updateTabLabel(tab, state));

        return tab;
    }

    private void openInTab(String filePath, String sshConnectionId) {
        if (filePath == null || filePath.isEmpty()) return;

        String targetConnectionId = sshConnectionId != null
                ? sshConnectionId
                : ConnectionManager.getInstance().getCurrentConnectionId();

        // 已有相同连接、相同路径的 Tab → 激活
        for (Map.Entry<Tab, TabState> entry : tabStates.entrySet()) {
            TabState s = entry.getValue();
            if (filePath.equals(s.filePath) && Objects.equals(targetConnectionId, s.sshConnectionId)) {
                tabPane.getSelectionModel().select(entry.getKey());
                return;
            }
        }

        // 当前活动 Tab 是"空白且未编辑"的新建文件 → 复用它
        TabState current = activeTab;
        if (current != null && current.newFileMode && !current.dirty.get()
                && (current.filePath == null || current.filePath.isEmpty())) {
            current.sshConnectionId = targetConnectionId;
            openFileInTab(current, filePath);
            return;
        }

        Tab newTab = createBlankTab(targetConnectionId);
        TabState newState = tabStates.get(newTab);
        openFileInTab(newState, filePath);
    }

    private void openFileInTab(TabState state, String filePath) {
        state.filePath = filePath;
        state.language = detectLanguage(filePath);
        state.newFileMode = false;
        state.readOnly.set(false);

        // 若传入的 state 没有明确的连接 ID，则回退到当前活动连接
        if (state.sshConnectionId == null) {
            state.sshConnectionId = ConnectionManager.getInstance().getCurrentConnectionId();
        }

        Platform.runLater(() -> {
            state.tab.setText(fileNameOf(filePath));
            refreshTitleAndStatus();
            if (statusFileInfo != null) statusFileInfo.setText(filePath + "  (读取中...)");
        });

        // 后台执行：stat + binary check + read content
        CompletableFuture.supplyAsync(() -> {
            SshService ssh = resolveSshService(state);
            if (ssh == null || !ssh.isConnected()) {
                return new OpenResult(null, false, -1L, 0L, false, null);
            }
            RemoteFileStat stat = ssh.statFile(filePath);
            long size = stat != null ? stat.sizeBytes() : -1L;
            long mtime = stat != null ? stat.mtimeEpochSec() : 0L;
            boolean isBinary = size >= 0 && ssh.isBinaryFile(filePath);
            boolean writable = ssh.isWritable(filePath);
            String content = null;
            if (!isBinary || size < LARGE_FILE_THRESHOLD_BYTES) {
                content = ssh.getFileContent(filePath);
            }
            return new OpenResult(stat, isBinary, size, mtime, writable, content);
        }).thenAccept(result -> Platform.runLater(() -> {
            if (result.isBinary && result.content == null) {
                if (!DialogHelper.showConfirm("打开警告",
                        "该文件疑似二进制文件，打开后可能显示乱码或不可读。\n\n是否仍然打开？",
                        "仍然打开", "取消")) {
                    if (state.newFileMode || state.filePath == null) {
                        tabPane.getTabs().remove(state.tab);
                    }
                    return;
                }
                CompletableFuture.supplyAsync(() -> {
                    SshService ssh2 = resolveSshService(state);
                    return ssh2 != null ? ssh2.getFileContent(filePath) : null;
                }).thenAccept(content2 -> Platform.runLater(() -> injectContent(state, content2, filePath, result)));
                return;
            }
            injectContent(state, result.content, filePath, result);
        }));
    }

    /**
     * 按 state.sshConnectionId 解析对应的 SshService；
     * 若 state.sshConnectionId 为空则回退到当前活动连接。
     */
    private static SshService resolveSshService(TabState state) {
        if (state == null) return null;
        ConnectionManager cm = ConnectionManager.getInstance();
        if (state.sshConnectionId != null) {
            SshService s = cm.getConnectionById(state.sshConnectionId);
            if (s != null) return s;
        }
        return cm.getCurrentSshService();
    }

    /**
     * 承载 openFile 预检查结果
     */
    private record OpenResult(RemoteFileStat stat, boolean isBinary, long size, long mtime, boolean writable,
                              String content) {
    }

    /**
     * 将内容注入 Monaco（同时设置 suppressDirty 标志）
     */
    private void injectContent(TabState state, String content, String filePath, OpenResult result) {
        if (state == null) return;

        state.initialFileMtime = result != null ? result.mtime : 0L;
        state.initialFileSize = result != null ? result.size : -1L;
        state.lastStat = result != null ? result.stat : null;
        // 记录远程文件是否可写：若不可写 => readOnly=true 且不允许用户再切换
        boolean remoteWritable = result == null || result.writable;
        state.remoteWritable = remoteWritable;
        state.readOnly.set(!remoteWritable);

        final String finalContent = content == null ? "" : content;
        if (!finalContent.isEmpty() && finalContent.charAt(0) == '\uFEFF') state.hasBom = true;
        if (finalContent.contains("\r\n")) state.detectedLineEnding = "CRLF";
        else if (finalContent.contains("\r")) state.detectedLineEnding = "CR";
        else state.detectedLineEnding = "LF";

        // setContent 之前置位抑制标志；setContent 返回后立即清零。
        // 注：JSObject.call 在 WebView 中以同步方式执行 JS 代码，
        // 所以在 editor.setValue(...) 期间触发的 onDidChangeModelContent
        // 都能看到 suppressDirty=true，从而被正确抑制。
        state.suppressDirty = true;

        waitEditorReady(state).thenRun(() -> Platform.runLater(() -> {
            try {
                JSObject window = (JSObject) state.engine.executeScript("window");
                if (window == null) return;
                JSObject editorAPI = (JSObject) window.getMember("editorAPI");
                if (editorAPI == null) return;
                try {
                    editorAPI.call("setContent", finalContent, state.language);
                } finally {
                    // 无论 setContent 是否成功，都必须清零抑制标志，
                    // 避免异常导致 suppressDirty 永远为 true，之后用户编辑也被抑制。
                    state.suppressDirty = false;
                }
                state.dirty.set(false);

                // 同步全局选项（字号/主题/自动换行）
                try {
                    editorAPI.call("setFontSize", globalFontSize.get());
                    editorAPI.call("setWordWrap", globalWordWrap.get() ? "on" : "off");
                    editorAPI.call("setTheme", globalTheme.get());
                } catch (Exception ignored) {
                }

                // 只读模式
                if (state.readOnly.get()) {
                    try {
                        editorAPI.call("setReadOnly", true);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }

            refreshTitleAndStatus();
            pushRecentFile(state.sshConnectionId, filePath);
        }));
    }

    // =================================================================
    // 文件保存（包含"无变化跳过" / "远程变更冲突检查" / "保留原行尾"）
    // =================================================================

    private void saveActiveTab() {
        TabState state = activeTab;
        if (state == null) return;
        if (state.readOnly.get()) {
            DialogHelper.showError("只读模式", "当前用户对该文件无写入权限，无法保存。");
            return;
        }
        if (state.newFileMode || state.filePath == null) {
            saveActiveTabAs();
            return;
        }
        // 内容没变化：跳过
        if (!state.dirty.get()) {
            if (statusFileInfo != null) {
                statusFileInfo.setText(state.filePath + "  (无变化，已跳过)");
                new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                    }
                    Platform.runLater(() -> {
                        if (state == activeTab && statusFileInfo != null) {
                            statusFileInfo.setText(state.filePath);
                        }
                    });
                }).start();
            }
            return;
        }
        doSave(state, state.filePath);
    }

    private void saveActiveTabAs() {
        TabState state = activeTab;
        if (state == null) return;
        if (state.readOnly.get()) {
            DialogHelper.showError("只读模式", "当前用户对该文件无写入权限，无法另存为。");
            return;
        }

        String suggested = "";
        if (state.newFileMode) {
            suggested = (state.defaultDirectory != null ? state.defaultDirectory + "/" : "") + "untitled.txt";
        } else if (state.filePath != null) {
            suggested = state.filePath;
        }

        String targetPath = DialogHelper.showTextInput(
                "另存为", "输入保存到远程服务器的绝对路径", "路径：", suggested);
        if (targetPath == null) return;

        doSave(state, targetPath);
    }

    /**
     * 真正执行写入到远程。
     * <p>
     * 为了避免破坏脚本（尤其是 shell / Linux 文本），这里：
     * 1) 按原始行尾（LF / CRLF / CR）标准化；
     * 2) 若原文件含 UTF-8 BOM，保存时也写回 BOM；
     * 3) 以 UTF-8 字节写入，避免平台默认字符集。
     */
    private void doSave(TabState state, String targetPath) {
        // 从 Monaco 拿内容，再按原行尾重写
        String rawContent = getEditorContent(state);
        if (rawContent == null) return;

        final String originalLineEnding = state.detectedLineEnding;
        // 若原文件存在 UTF-8 BOM，则在保存时写回
        final String contentToWrite;
        {
            String tmp = normalizeLineEnding(rawContent, originalLineEnding);
            if (state.hasBom && !tmp.isEmpty() && tmp.charAt(0) != '\uFEFF') {
                tmp = "\uFEFF" + tmp;
            }
            contentToWrite = tmp;
        }

        // 检查远程文件是否被他人修改
        final boolean needsConflictCheck = !state.newFileMode
                && state.initialFileMtime > 0
                && targetPath.equals(state.filePath);

        CompletableFuture<RemoteFileStat> statFuture = needsConflictCheck
                ? CompletableFuture.supplyAsync(() -> {
            SshService ssh = resolveSshService(state);
            return (ssh != null && ssh.isConnected()) ? ssh.statFile(targetPath) : null;
        })
                : CompletableFuture.completedFuture(null);

        statFuture.thenAccept(latest -> {
            if (latest != null && latest.mtimeEpochSec() != 0L
                    && latest.mtimeEpochSec() != state.initialFileMtime) {
                CompletableFuture<Integer> confirm = new CompletableFuture<>();
                Platform.runLater(() -> {
                    int choice = DialogHelper.showConfirmThree("远程文件已变更",
                            "本地保存将覆盖远程变更，请选择：\n\n• 覆盖：用本地内容覆盖远程\n• 重新加载：放弃本地修改\n• 取消：什么都不做",
                            "覆盖", "重新加载", "取消");
                    confirm.complete(choice);
                });
                int choice;
                try {
                    choice = confirm.get();
                } catch (Exception ignored) {
                    return;
                }
                if (choice == 1) {
                    Platform.runLater(() -> openFileInTab(state, state.filePath));
                    return;
                }
                if (choice != 0) return;
            }

            CompletableFuture.supplyAsync(() -> {
                SshService ssh = resolveSshService(state);
                if (ssh == null || !ssh.isConnected()) return "未连接到远程服务器";
                try {
                    ssh.writeFileContent(targetPath, contentToWrite);
                    return null;
                } catch (Exception e) {
                    return "保存失败: " + e.getMessage();
                }
            }).thenAccept(err -> Platform.runLater(() -> {
                if (err == null) {
                    state.filePath = targetPath;
                    state.newFileMode = false;
                    state.language = detectLanguage(targetPath);
                    // 新建文件首次保存：根据文件名后缀给一个合适的行尾默认值
                    // （已存在的远程文件保持原文件检测到的行尾，不做改动）
                    // 规则：.bat/.cmd/.ps1/.psm1 → CRLF；其它 → LF
                    if (state.detectedLineEnding == null
                            || state.detectedLineEnding.isEmpty()
                            || "LF".equals(state.detectedLineEnding)) {
                        state.detectedLineEnding = detectLineEndingForNewFile(targetPath);
                    }
                    // 新建文件默认不加 BOM（保持与 Linux/UTF-8 生态一致）；
                    // 若你需要 BOM，可在文件开头插入 BOM 并保存，后续保存将自动保持。
                    if (!state.hasBom) {
                        // 内容本身若是以 BOM 开头（用户手动插入或粘贴），则标记为 true
                        state.hasBom = (contentToWrite != null && !contentToWrite.isEmpty()
                                && contentToWrite.charAt(0) == '\uFEFF');
                    }
                    state.dirty.set(false);

                    // 同步更新 JS 侧的"基线内容"，使之后 undo 回到此处时能主动清除 dirty。
                    try {
                        JSObject win = (JSObject) state.engine.executeScript("window");
                        if (win != null) {
                            JSObject api = (JSObject) win.getMember("editorAPI");
                            if (api != null) api.call("markAsSaved");
                        }
                    } catch (Exception ignored) {
                    }

                    state.tab.setText(fileNameOf(targetPath));
                    updateTabLabel(state.tab, state);
                    refreshTitleAndStatus();
                    if (statusFileInfo != null) statusFileInfo.setText(targetPath + "  (已保存)");
                    ConnectionManager.getInstance().refreshFilesForSavedFile(state.sshConnectionId, targetPath);

                    CompletableFuture.supplyAsync(() -> {
                        SshService ssh2 = resolveSshService(state);
                        return (ssh2 != null && ssh2.isConnected()) ? ssh2.statFile(targetPath) : null;
                    }).thenAccept(stat -> Platform.runLater(() -> {
                        if (stat != null) {
                            state.lastStat = stat;
                            state.initialFileMtime = stat.mtimeEpochSec();
                            state.initialFileSize = stat.sizeBytes();
                            state.readOnly.set(!isWritableFast(stat));
                            refreshTitleAndStatus();
                        }
                        new Thread(() -> {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException ignored) {
                            }
                            Platform.runLater(() -> {
                                if (statusFileInfo != null && state == activeTab) {
                                    statusFileInfo.setText(targetPath);
                                }
                            });
                        }).start();
                    }));

                    pushRecentFile(state.sshConnectionId, targetPath);
                } else {
                    if (statusFileInfo != null) {
                        statusFileInfo.setText(targetPath + "  (" + err + ")");
                    }
                }
            }));
        });
    }

    private void reloadActiveTab() {
        TabState state = activeTab;
        if (state == null || state.filePath == null || state.filePath.isEmpty()) return;
        if (state.dirty.get()) {
            if (!DialogHelper.showConfirm("重新加载",
                    "该文件有未保存的修改，重新加载将丢失本地修改，是否继续？",
                    "重新加载", "取消")) return;
        }
        openFileInTab(state, state.filePath);
    }

    private void closeActiveTab() {
        TabState state = activeTab;
        if (state == null) return;
        if (state.dirty.get()) {
            if (!DialogHelper.showConfirm("未保存的修改",
                    "该文件有未保存的修改，是否关闭此标签页？未保存的修改将丢失。",
                    "关闭", "取消")) return;
        }
        try {
            state.engine.load("about:blank");
        } catch (Exception ignored) {
        }
        tabStates.remove(state.tab);
        tabPane.getTabs().remove(state.tab);
    }

    private boolean confirmCloseAllTabs() {
        List<String> dirtyFiles = new ArrayList<>();
        for (TabState s : tabStates.values()) {
            if (s.dirty.get()) {
                dirtyFiles.add(s.filePath != null ? s.filePath : "未保存的新文件");
            }
        }
        if (dirtyFiles.isEmpty()) return true;

        StringBuilder msg = new StringBuilder();
        msg.append("以下 ").append(dirtyFiles.size()).append(" 个文件有未保存的修改，\n")
                .append("关闭窗口将丢失这些修改，是否继续？\n\n");
        // 为避免对话框过长，最多显示前 10 个；超过部分用数量概括。
        int maxShow = Math.min(dirtyFiles.size(), 10);
        for (int i = 0; i < maxShow; i++) {
            msg.append("  • ").append(dirtyFiles.get(i)).append('\n');
        }
        if (dirtyFiles.size() > maxShow) {
            msg.append("  ... 另外 ").append(dirtyFiles.size() - maxShow).append(" 个文件\n");
        }
        return DialogHelper.showConfirm("未保存的修改", msg.toString(), "关闭", "取消");
    }

    private void closeWindow() {
        if (stage != null) stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    /**
     * 关闭本编辑器中绑定到指定 SSH 连接的所有 Tab。
     * 由外部（ConnectionToolbarController / ConnectionManager 监听器）调用，
     * 用于"连接页签关闭 / SSH 主动断开 -> 相关编辑器 Tab 自动关闭"的确定性同步。
     *
     * @param connId 要关闭的 SSH 连接 ID；传 null 或未绑定该 connId 的 Tab 不受影响。
     */
    public void closeTabsForConnection(String connId) {
        if (connId == null) return;
        List<TabState> toClose = new ArrayList<>();
        for (TabState s : tabStates.values()) {
            if (connId.equals(s.sshConnectionId)) {
                toClose.add(s);
            }
        }
        if (toClose.isEmpty()) return;
        for (TabState s : toClose) {
            if (!tabStates.containsKey(s.tab)) continue;
            try {
                s.engine.load("about:blank");
            } catch (Exception ignored) {
            }
            tabStates.remove(s.tab);
            tabPane.getTabs().remove(s.tab);
        }
    }

    /**
     * 静态入口：供外部在"非 JavaFX 主线程"中调用时，自动调度到 JavaFX 线程。
     * 只操作当前活动窗口的编辑器；如果编辑器窗口尚未打开则什么也不做。
     */
    public static void closeTabsForConnectionStatic(final String connId) {
        if (connId == null) return;
        Platform.runLater(() -> {
            if (activeController != null && activeStage != null && activeStage.isShowing()) {
                activeController.closeTabsForConnection(connId);
            }
        });
    }

    // =================================================================
    // 编辑相关
    // =================================================================

    private void gotoLine() {
        TabState state = activeTab;
        if (state == null || !state.editorReady.get()) return;
        String input = DialogHelper.showTextInput("跳转到指定行", "输入行号", "行号：", "1");
        if (input == null) return;
        try {
            int line = Integer.parseInt(input);
            callEditorApi(state, "gotoLine", line);
        } catch (NumberFormatException e) {
            DialogHelper.showError("无效输入", "请输入有效的行号。");
        }
    }

    private void toggleComment() {
        callEditorApiOnActive("toggleComment");
    }

    /**
     * JSON / YAML 格式化（纯 JS 侧实现）
     */
    private void formatActiveTab() {
        TabState state = activeTab;
        if (state == null) return;
        callEditorApiOnActive("format");
    }

    private void copyPath() {
        TabState state = activeTab;
        if (state == null || state.filePath == null || state.filePath.isEmpty()) {
            DialogHelper.showError("复制路径", "当前文件尚未保存，没有完整路径。");
            return;
        }
        try {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(state.filePath);
            clipboard.setContent(cc);
        } catch (Exception e) {
            DialogHelper.showError("复制失败", e.getMessage());
        }
    }

    private void jumpToTerminalDirectory() {
        TabState state = activeTab;
        if (state == null || state.filePath == null) return;
        int idx = state.filePath.lastIndexOf('/');
        String dir = idx > 0 ? state.filePath.substring(0, idx) : "/";
        SshService ssh = resolveSshService(state);
        if (ssh != null && ssh.isConnected() && ssh.isShellOpen()) {
            String command = "cd " + escapePath(dir) + " && pwd\n";
            ssh.writeToShell(command.getBytes());
        }
    }

    // =================================================================
    // 偏好：字体 / 主题 / 自动换行 / 只读锁定
    // =================================================================

    private void changeFontSize(int delta) {
        int newSize = Math.max(8, Math.min(40, globalFontSize.get() + delta));
        if (newSize == globalFontSize.get()) return;
        globalFontSize.set(newSize);
        AppSettings.getInstance().setEditorDefaultFontSize(newSize);
    }

    private void toggleWordWrap() {
        globalWordWrap.set(!globalWordWrap.get());
        refreshTitleAndStatus();
    }

    private void toggleTheme() {
        globalTheme.set("vs-dark".equals(globalTheme.get()) ? "vs-light" : "vs-dark");
        syncAllTabsTheme();
    }

    private void toggleReadOnlyLock() {
        TabState state = activeTab;
        // 文件自身无写权限：强制保持只读（按钮在 refreshTitleAndStatus 中已禁用）
        if (state == null || !state.remoteWritable) return;
        // 用户手动在"编辑"与"只读"之间切换
        boolean newVal = !state.readOnly.get();
        state.readOnly.set(newVal);
        callEditorApi(state, "setReadOnly", newVal);
        refreshTitleAndStatus();
    }

    /**
     * 同步字号/自动换行到所有 Tab
     */
    private void syncAllTabsOptions() {
        for (TabState s : tabStates.values()) {
            if (!s.editorReady.get()) continue;
            try {
                JSObject window = (JSObject) s.engine.executeScript("window");
                if (window == null) continue;
                JSObject editorAPI = (JSObject) window.getMember("editorAPI");
                if (editorAPI == null) continue;
                editorAPI.call("setFontSize", globalFontSize.get());
                editorAPI.call("setWordWrap", globalWordWrap.get() ? "on" : "off");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 同步主题到所有 Tab
     */
    private void syncAllTabsTheme() {
        for (TabState s : tabStates.values()) {
            if (!s.editorReady.get()) continue;
            try {
                JSObject window = (JSObject) s.engine.executeScript("window");
                if (window == null) continue;
                JSObject editorAPI = (JSObject) window.getMember("editorAPI");
                if (editorAPI == null) continue;
                editorAPI.call("setTheme", globalTheme.get());
            } catch (Exception ignored) {
            }
        }
    }

    // =================================================================
    // 最近打开文件
    // =================================================================

    private static synchronized void pushRecentFile(String sshConnectionId, String path) {
        if (path == null || path.isEmpty()) return;
        RecentFileEntry entry = new RecentFileEntry(sshConnectionId, path);
        recentFiles.remove(entry);
        recentFiles.addFirst(entry);
        while (recentFiles.size() > RECENT_MAX) recentFiles.removeLast();
    }

    private void showRecentFilesMenu() {
        List<RecentFileEntry> snapshot;
        synchronized (recentFiles) {
            snapshot = new ArrayList<>(recentFiles);
        }
        if (snapshot.isEmpty()) {
            DialogHelper.showInfo("最近打开", "暂无已打开文件记录。");
            return;
        }
        ContextMenu menu = new ContextMenu();
        for (RecentFileEntry entry : snapshot) {
            MenuItem item = new MenuItem(entry.path());
            item.setOnAction(e -> openInTab(entry.path(), entry.sshConnectionId()));
            menu.getItems().add(item);
        }
        MenuItem clear = new MenuItem("—— 清空记录 ——");
        clear.setOnAction(e -> {
            synchronized (recentFiles) {
                recentFiles.clear();
            }
        });
        menu.getItems().add(clear);
        menu.show(btnRecent.getScene().getWindow(),
                btnRecent.localToScreen(btnRecent.getBoundsInLocal()).getMinX(),
                btnRecent.localToScreen(btnRecent.getBoundsInLocal()).getMaxY());
    }

    // =================================================================
    // 快捷键帮助窗口
    // =================================================================

    private void showShortcutHelp() {
        DialogHelper.showInfoWithHeader("键盘快捷键", "编辑器快捷键汇总", ShortcutRegistry.editorHelpText());
    }

    // =================================================================
    // 统一调用 editor API
    // =================================================================

    /**
     * 调用 window.editorAPI[methodName](arg1, arg2, ...)。
     * <p>
     * 注意：JavaFX 的 JSObject.call(methodName, Object... args) 虽然声明为可变参数，
     * 但当你把一个 Object[] 变量传进去时，它会被当作单个参数传递，
     * 所以这里改用 executeScript 手动构造调用，确保参数被真正展开。
     * </p>
     */
    private void callEditorApi(TabState state, String methodName, Object... args) {
        if (state == null || !state.editorReady.get()) return;
        Platform.runLater(() -> {
            try {
                StringBuilder sb = new StringBuilder(64);
                sb.append("(function(){try{var a=window.editorAPI;if(!a)return null;");
                sb.append("var r=a.").append(methodName).append(".apply(a,arguments);");
                sb.append("return (r===undefined)?null:r;}catch(e){return null;}})(");
                if (args != null && args.length > 0) {
                    for (int i = 0; i < args.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(jsLiteral(args[i]));
                    }
                }
                sb.append(");");
                state.engine.executeScript(sb.toString());
            } catch (Exception ignored) {
            }
        });
    }

    private boolean isFindWidgetInputFocused(TabState state) {
        return Boolean.parseBoolean(callEditorApiSync(state, "isFindWidgetInputFocused"));
    }

    private void handleFindWidgetClipboardShortcut(TabState state, KeyEvent event) {
        final KeyCode code = event.getCode();
        if (code == KeyCode.C) {
            copyFindWidgetSelectionToSystemClipboard(state);
            event.consume();
        } else if (code == KeyCode.V) {
            String text = readSystemClipboardText();
            if (text != null) {
                callEditorApi(state, "replaceFindWidgetSelection", text);
                event.consume();
            }
        } else if (code == KeyCode.X) {
            copyFindWidgetSelectionToSystemClipboard(state);
            callEditorApi(state, "replaceFindWidgetSelection", "");
            event.consume();
        }
    }

    private void copyFindWidgetSelectionToSystemClipboard(TabState state) {
        String selected = callEditorApiSync(state, "getFindWidgetSelection");
        if (selected == null || selected.isEmpty()) return;

        try {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(selected);
            clipboard.setContent(content);
        } catch (Exception ex) {
            LOGGER.warn("clipboard: copy find-widget selection failed: {}", ex.toString());
        }
    }

    private String readSystemClipboardText() {
        try {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            return clipboard.hasString() ? clipboard.getString() : null;
        } catch (Exception ex) {
            LOGGER.warn("clipboard: read system clipboard failed: {}", ex.toString());
            return null;
        }
    }

    /**
     * 同步调用 window.editorAPI[methodName]，并把返回值转成字符串。
     * 主要用于剪贴板流程：需要"立即拿到 Monaco 当前选中文本"或"立即完成粘贴"。
     * 必须在 FX 应用线程中调用；若 editor 还未就绪则返回空串。
     */
    private String callEditorApiSync(TabState state, String methodName, Object... args) {
        if (state == null || !state.editorReady.get()) return "";
        try {
            StringBuilder sb = new StringBuilder(96);
            sb.append("(function(){try{var a=window.editorAPI;if(!a)return null;");
            sb.append("var r=a.").append(methodName).append(".apply(a,arguments);");
            sb.append("return (r===undefined)?null:r;}catch(e){return null;}})(");
            if (args != null && args.length > 0) {
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(jsLiteral(args[i]));
                }
            }
            sb.append(");");
            Object val = state.engine.executeScript(sb.toString());
            return val == null ? "" : val.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 把一个 Java 对象转成可嵌在 JS 源码中的字面量。
     * 支持：null / Boolean / Number / String，其它类型回退为 String(...) 或 true。
     */
    private static String jsLiteral(Object o) {
        if (o == null) return "null";
        if (o instanceof Boolean) return ((Boolean) o) ? "true" : "false";
        if (o instanceof Number) return o.toString();
        // String：转义
        String s = o.toString();
        StringBuilder out = new StringBuilder(s.length() + 4);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                default:
                    if (c < 0x20) {
                        out.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    private void callEditorApiOnActive(String methodName, Object... args) {
        callEditorApi(activeTab, methodName, args);
    }

    private String getEditorContent(TabState state) {
        if (state == null || !state.editorReady.get()) return null;
        try {
            JSObject window = (JSObject) state.engine.executeScript("window");
            if (window == null) return null;
            JSObject editorAPI = (JSObject) window.getMember("editorAPI");
            if (editorAPI == null) return null;
            Object val = editorAPI.call("getContent");
            return val == null ? "" : val.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private CompletableFuture<Void> waitEditorReady(TabState state) {
        if (state == null) return CompletableFuture.completedFuture(null);
        // 等待 editorAPI 同时，顺便确保 JS→Java 的桥接对象仍在 window 上
        ensureBridgeInjected(state);
        if (state.editorReady.get()) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 10000L) {
                if (state.editorReady.get()) {
                    future.complete(null);
                    return;
                }
                final boolean[] jsReady = {false};
                CountDownLatch latch = new CountDownLatch(1);
                Platform.runLater(() -> {
                    try {
                        Object r = state.engine.executeScript(
                                "typeof window.editorAPI !== 'undefined' && "
                                        + "typeof window.editorAPI.setContent === 'function' && "
                                        + "(window.monacoLoaded === true)");
                        jsReady[0] = Boolean.TRUE.equals(r);
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
                try {
                    latch.await(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }
                if (jsReady[0]) {
                    state.editorReady.set(true);
                    future.complete(null);
                    return;
                }
            }
            future.complete(null);
        });
        waiter.setDaemon(true);
        waiter.start();
        return future;
    }

    // =================================================================
    // UI 更新（标题栏 + 状态栏 + Tab 标题）
    // =================================================================

    private void refreshTitleAndStatus() {
        TabState state = activeTab;

        if (titleLabel != null) {
            if (state != null && state.filePath != null) {
                titleLabel.setText(state.filePath);
            } else if (state != null) {
                titleLabel.setText("新文件（未保存）");
            } else {
                titleLabel.setText("编辑器");
            }
        }

        if (statusFileInfo != null) {
            if (state != null && state.filePath != null) {
                statusFileInfo.setText(state.filePath);
            } else {
                statusFileInfo.setText("");
            }
        }

        if (state != null && state.lastStat != null) {
            RemoteFileStat stat = state.lastStat;
            if (statusPermission != null) {
                statusPermission.setText(stat.permissions() != null ? stat.permissions() : "");
                statusPermission.setVisible(true);
                statusPermission.setManaged(true);
            }
            if (statusOwner != null) {
                String ownerGroup = (stat.owner() != null ? stat.owner() : "-") + ":" + (stat.group() != null ? stat.group() : "-");
                statusOwner.setText(ownerGroup);
                statusOwner.setVisible(true);
                statusOwner.setManaged(true);
            }
            if (statusModified != null && stat.mtimeEpochSec() > 0) {
                try {
                    String t = Instant.ofEpochSecond(stat.mtimeEpochSec())
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    statusModified.setText("修改时间: " + t);
                    statusModified.setVisible(true);
                    statusModified.setManaged(true);
                } catch (Exception ignored) {
                }
            }
            if (statusSize != null) {
                statusSize.setText(formatSize(stat.sizeBytes()));
            }
        } else {
            if (statusPermission != null) {
                statusPermission.setVisible(false);
                statusPermission.setManaged(false);
            }
            if (statusOwner != null) {
                statusOwner.setVisible(false);
                statusOwner.setManaged(false);
            }
            if (statusModified != null) {
                statusModified.setVisible(false);
                statusModified.setManaged(false);
            }
            if (statusSize != null) statusSize.setText("");
        }

        // 编码 / 行尾 / 语言：基于当前 Tab 真实内容，不再写死默认值
        if (statusEncoding != null) {
            if (state == null) statusEncoding.setText("");
            else statusEncoding.setText(state.hasBom ? "UTF-8 BOM" : "UTF-8");
        }
        if (statusLineEnd != null) {
            if (state == null) statusLineEnd.setText("");
            else statusLineEnd.setText(state.detectedLineEnding);
        }
        if (statusLanguage != null) {
            if (state == null) statusLanguage.setText("");
            else statusLanguage.setText(state.language);
        }

        // 自动换行状态（全局偏好，与当前 Tab 无关，所以直接读 globalWordWrap）
        if (statusWordWrap != null) {
            statusWordWrap.setText(globalWordWrap.get() ? "自动换行：开" : "自动换行：关");
        }

        // 光标位置：用 state.lastLine / lastCol 显示
        if (statusCursor != null) {
            if (state == null || !state.editorReady.get()) {
                statusCursor.setText("");
            } else {
                statusCursor.setText("Ln " + state.lastLine + ", Col " + state.lastCol);
            }
        }

        // 只读模式状态（位于状态栏最右侧，一目了然）
        if (statusReadonly != null) {
            if (state != null && state.readOnly.get()) {
                if (!state.remoteWritable) {
                    statusReadonly.setText("只读（权限不足）");
                    statusReadonly.getStyleClass().remove("readonly-user");
                    if (!statusReadonly.getStyleClass().contains("readonly-permission")) {
                        statusReadonly.getStyleClass().add("readonly-permission");
                    }
                } else {
                    statusReadonly.setText("只读（已锁定）");
                    statusReadonly.getStyleClass().remove("readonly-permission");
                    if (!statusReadonly.getStyleClass().contains("readonly-user")) {
                        statusReadonly.getStyleClass().add("readonly-user");
                    }
                }
                statusReadonly.setVisible(true);
                statusReadonly.setManaged(true);
            } else {
                statusReadonly.setText("");
                statusReadonly.setVisible(false);
                statusReadonly.setManaged(false);
            }
        }

        // 只读模式警告条（全局 + 当前 Tab readOnly）
        boolean anyReadOnly = state != null && state.readOnly.get();
        if (readOnlyBar != null) {
            readOnlyBar.setVisible(anyReadOnly);
            readOnlyBar.setManaged(anyReadOnly);
        }

        // 保存按钮禁用状态：当前文件只读时禁用
        if (btnSave != null) btnSave.setDisable(state != null && state.readOnly.get());
        if (btnSaveAs != null) btnSaveAs.setDisable(state != null && state.readOnly.get());

        // 只读锁定按钮：若远程文件无写权限，禁用切换（用户不可以改为编辑模式）；允许在"编辑<->用户锁定的只读"之间切换
        if (btnReadOnlyLock != null) {
            btnReadOnlyLock.setDisable(state != null && !state.remoteWritable);
        }
    }

    private void updateTabLabel(Tab tab, TabState state) {
        String baseName;
        if (state.filePath != null) baseName = fileNameOf(state.filePath);
        else baseName = "新文件";
        tab.setText(state.dirty.get() ? baseName + "  ●" : baseName);
    }

    // =================================================================
    // 剪贴板桥接：把 JavaFX WebView 内部剪贴板与系统剪贴板打通
    // =================================================================

    /**
     * 为指定 Tab 的 WebView 安装 Ctrl+C / Ctrl+V / Ctrl+X 的剪贴板桥接。
     * 原因：JavaFX WebView 中的 JS 环境与系统剪贴板相互隔离，
     * Monaco 内部执行的 document.execCommand('copy') 根本写不到系统剪贴板，
     * 同样 JS 侧的粘贴事件也读不到其他进程复制到的文本。
     * 这里在 WebView 事件派发最前端拦截这三个组合键，由 Java 侧：
     * - 对 Ctrl+C / Ctrl+X：调用 Monaco API 拿到当前选中内容，写入系统 Clipboard；
     * - 对 Ctrl+V：读取系统 Clipboard 中的纯文本，调用 Monaco API 插入到光标/选区。
     * 为避免"拦截之后 JS 侧失败导致完全粘贴不了"，本函数采取"软拦截"策略：
     * 只有当系统剪贴板含字符串且 editor 确实能被同步调用到，才 consume 事件；
     * 否则事件会原样流入 JS 层，让 Monaco 自行尝试走它原来的粘贴路径。
     */
    private void installClipboardBridge(TabState state) {
        if (state == null) return;
        final WebView webView = state.webView;

        webView.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) return;
            // 只处理 "Ctrl + C/V/X"（不区分大小写），不处理 Shift 等其他修饰
            if (!e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShiftDown()) return;
            if (isFindWidgetInputFocused(state)) {
                handleFindWidgetClipboardShortcut(state, e);
                return;
            }
            // 只读模式下不允许"粘贴/剪切"
            final boolean readonly = state.readOnly.get();
            final KeyCode code = e.getCode();
            if (code == KeyCode.C) {
                // ========== 复制（读 Monaco 选区 → 写系统剪贴板） ==========
                copySelectionToSystemClipboard(state);
                e.consume();
            } else if (code == KeyCode.V && !readonly) {
                // ========== 粘贴（读系统剪贴板 → 插入 Monaco） ==========
                // 只要系统剪贴板有字符串，就明确由 Java 端接管并 consume 事件；
                // 否则不 consume，让 JS 层自行处理（比如粘贴图片等非文本内容）。
                boolean hasText;
                try {
                    Clipboard clipboard = Clipboard.getSystemClipboard();
                    hasText = clipboard.hasString() && clipboard.getString() != null
                            && !clipboard.getString().isEmpty();
                } catch (Exception ex) {
                    hasText = false;
                }
                if (hasText) {
                    pasteFromSystemClipboard(state);
                    e.consume();
                }
            } else if (code == KeyCode.X && !readonly) {
                // ========== 剪切（复制 + 删除选区） ==========
                copySelectionToSystemClipboard(state);
                callEditorApi(state, "insertText", "");
                e.consume();
            }
        });
    }

    /**
     * 从 Monaco 读取"当前选中文本"，并写入系统剪贴板。
     * 若没有选中内容，则不做任何事（用户按 Ctrl+C 复制不到东西，不会把剪贴板清掉）。
     */
    private void copySelectionToSystemClipboard(TabState state) {
        if (state == null) return;
        final String selected;
        try {
            selected = callEditorApiSync(state, "getSelection");
        } catch (Exception ex) {
            LOGGER.warn("clipboard: getSelection failed: {}", ex.toString());
            return;
        }
        if (selected == null || selected.isEmpty()) return;

        try {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent cc = new ClipboardContent();
            cc.putString(selected);
            clipboard.setContent(cc);
        } catch (Exception ex) {
            LOGGER.warn("clipboard: copy to system clipboard failed: {}", ex.toString());
        }
    }

    /**
     * 从系统剪贴板读取纯文本，再调用 Monaco 的 insertText 插入到当前光标/选区位置。
     * 若系统剪贴板不含纯文本或 Monaco 暂不可用，则返回 false，让 JS 层自行处理粘贴事件。
     */
    private void pasteFromSystemClipboard(TabState state) {
        if (state == null) return;
        String text;
        try {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (!clipboard.hasString()) return;
            text = clipboard.getString();
        } catch (Exception ex) {
            LOGGER.warn("clipboard: read system clipboard failed: {}", ex.toString());
            return;
        }
        if (text == null || text.isEmpty()) return;

        try {
            // 不管 editorReady 是否为 true，都直接执行脚本；
            // 如果 Monaco 还没初始化，executeScript 本身会抛异常（被 catch），
            // 返回 false，从而把事件继续交给 JS 层，不导致"完全粘贴不了"。
            callEditorApiScript(state, text);
        } catch (Exception ex) {
            LOGGER.warn("clipboard: insertText into monaco failed: {}", ex.toString());
        }
    }

    /**
     * 同步调用 window.editorAPI[methodName](args) 并把原始返回值直接返回，
     * 同时不依赖 editorReady —— 剪贴板相关操作需要即便 Monaco 刚加载也能尝试。
     */
    private void callEditorApiScript(TabState state, Object... args) {
        if (state == null || state.engine == null) return;
        try {
            StringBuilder sb = new StringBuilder(128);
            sb.append("(function(){try{var a=window.editorAPI;if(!a)return null;");
            sb.append("var r=a.").append("insertText").append(".apply(a,arguments);");
            sb.append("return (r===undefined)?null:r;}catch(e){return null;}})(");
            if (args != null && args.length > 0) {
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(jsLiteral(args[i]));
                }
            }
            sb.append(");");
            state.engine.executeScript(sb.toString());
        } catch (Exception ignored) {
        }
    }

    // =================================================================
    // 工具方法
    // =================================================================

    private static String fileNameOf(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private String detectLanguage(String path) {
        if (path == null) return "plaintext";
        String p = path.toLowerCase();
        if (p.endsWith(".java")) return "java";
        if (p.endsWith(".js") || p.endsWith(".mjs") || p.endsWith(".cjs")) return "javascript";
        if (p.endsWith(".ts") || p.endsWith(".tsx")) return "typescript";
        if (p.endsWith(".py")) return "python";
        if (p.endsWith(".sh") || p.endsWith(".bash") || p.endsWith(".zsh") || p.endsWith(".profile")) return "shell";
        if (p.endsWith(".yaml") || p.endsWith(".yml")) return "yaml";
        if (p.endsWith(".json")) return "json";
        if (p.endsWith(".xml")) return "xml";
        if (p.endsWith(".html") || p.endsWith(".htm")) return "html";
        if (p.endsWith(".css")) return "css";
        if (p.endsWith(".scss") || p.endsWith(".sass")) return "scss";
        if (p.endsWith(".less")) return "less";
        if (p.endsWith(".md") || p.endsWith(".markdown")) return "markdown";
        if (p.endsWith(".go")) return "go";
        if (p.endsWith(".c") || p.endsWith(".h")) return "c";
        if (p.endsWith(".cpp") || p.endsWith(".hpp") || p.endsWith(".cc")) return "cpp";
        if (p.endsWith(".rs")) return "rust";
        if (p.endsWith(".cs")) return "csharp";
        if (p.endsWith(".rb")) return "ruby";
        if (p.endsWith(".php")) return "php";
        if (p.endsWith(".kt") || p.endsWith(".kts")) return "kotlin";
        if (p.endsWith(".swift")) return "swift";
        if (p.endsWith(".sql")) return "sql";
        if (p.endsWith(".properties") || p.endsWith(".cfg") || p.endsWith(".conf")
                || p.endsWith(".ini") || p.endsWith(".toml")) return "ini";
        if (p.endsWith(".log") || p.endsWith(".txt")) return "plaintext";
        if (p.endsWith(".dockerfile") || p.endsWith("dockerfile")) return "dockerfile";
        return "plaintext";
    }

    /**
     * 新建文件首次保存时，根据文件名后缀给出一个"合理的默认行尾"。
     * - Windows 原生脚本（bat/cmd/ps1/psm1）→ CRLF；
     * - 其他全部 → LF（符合 Linux / UTF-8 文本生态）。
     */
    private String detectLineEndingForNewFile(String path) {
        if (path == null) return "LF";
        String p = path.toLowerCase();
        if (p.endsWith(".bat") || p.endsWith(".cmd")
                || p.endsWith(".ps1") || p.endsWith(".psm1")
                || p.endsWith(".ps1xml") || p.endsWith(".psc1")) {
            return "CRLF";
        }
        return "LF";
    }

    private String escapePath(String path) {
        if (path == null) return "";
        return path.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isWritableFast(RemoteFileStat stat) {
        if (stat == null) return true;
        if (stat.permissions() == null) return true;
        // 简化判断：若能解析出明显"无写权限"则返回 false，否则 true
        String perm = stat.permissions().trim();
        return !perm.startsWith("-r--") && !perm.startsWith("-r-x")
                && !perm.startsWith("---") && !perm.startsWith("--x")
                && !perm.startsWith("-r-?");
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 将任意行尾归一化到目标行尾（CRLF / LF / CR）
     */
    private static String normalizeLineEnding(String content, String target) {
        if (content == null || content.isEmpty()) return content;
        String step1 = content.replace("\r\n", "\n").replace("\r", "\n");
        if ("CRLF".equalsIgnoreCase(target)) return step1.replace("\n", "\r\n");
        if ("CR".equalsIgnoreCase(target)) return step1.replace("\n", "\r");
        return step1; // LF 默认
    }

    // =================================================================
    // JS ↔ Java 桥接：每个 Tab 有独立的 bridge 实例
    // =================================================================

    public class JavaBridge {
        private final TabState state;

        JavaBridge(TabState state) {
            this.state = state;
        }

        public void onReady() {
            Platform.runLater(() -> {
                state.editorReady.set(true);
                // 同步偏好
                try {
                    JSObject window = (JSObject) state.engine.executeScript("window");
                    if (window != null) {
                        JSObject editorAPI = (JSObject) window.getMember("editorAPI");
                        if (editorAPI != null) {
                            editorAPI.call("setFontSize", globalFontSize.get());
                            editorAPI.call("setWordWrap", globalWordWrap.get() ? "on" : "off");
                            editorAPI.call("setTheme", globalTheme.get());
                            if (state.readOnly.get()) editorAPI.call("setReadOnly", true);
                        }
                    }
                } catch (Exception ignored) {
                }

                if (state.filePath == null && statusFileInfo != null) {
                    statusFileInfo.setText("新文件");
                }
                refreshTitleAndStatus();
            });
        }

        public void onContentChange() {
            if (state.suppressDirty) return;
            Platform.runLater(() -> state.dirty.set(true));
        }

        /**
         * 允许 JS 端主动设置 dirty 状态。
         * 主要用途：当 undo/redo 把内容回退到"上一次 setContent 的基线内容"时，
         * 主动通知 Java 端消除 dirty 标记，避免 Tab 标题上残留的 ●。
         * 同样也在保存后重新对齐内容时调用。
         */
        public void onDirtyStateChange(boolean dirty) {
            if (state.suppressDirty && dirty) return;
            Platform.runLater(() -> state.dirty.set(dirty));
        }

        /**
         * JS 侧的 console.log/info/warn/error/debug 会经由 window.editorBridge.onLog(level, text) 回传至此。
         * 这样你在 Java 应用的日志终端里就能直接看到 JS 的调试信息，
         * 不需要依赖外部浏览器 DevTools（JavaFX WebView 默认没有打开 DevTools）。
         */
        public void onLog(String level, String text) {
            String safeLevel = level == null ? "info" : level.toLowerCase();
            String safeText = text == null ? "" : text;
            switch (safeLevel) {
                case "error":
                    LOGGER.error("[js] {}", safeText);
                    break;
                case "warn":
                    LOGGER.warn("[js] {}", safeText);
                    break;
                case "debug":
                    LOGGER.debug("[js] {}", safeText);
                    break;
                case "info":
                default:
                    LOGGER.info("[js] {}", safeText);
                    break;
            }
        }

        public void onCursorChange(int line, int col) {
            state.lastLine = Math.max(1, line);
            state.lastCol = Math.max(1, col);
            Platform.runLater(() -> {
                if (statusCursor != null && state == activeTab) {
                    statusCursor.setText("Ln " + state.lastLine + ", Col " + state.lastCol);
                }
            });
        }
    }

    // =========================================================================
    // JS → Java 桥接注入 & 保活
    // =========================================================================

    /**
     * 确保 JS 侧的 window.editorBridge / window.editorBridgePing 指向当前 Tab 的 Java 对象。
     * 之所以要反复调用：
     * 1) 若 Java 实例仅被 WebKit 内部持有，JVM 可能把它 GC 掉，结果就是"JS 能调用但 Java 收不到"。
     * 我们把 bridge 存到 state.javaBridge（强引用）避免被回收。
     * 2) 页面内部如果发生 window 重建 / 导航，editorBridge 成员会丢失，这里会重新注入。
     */
    private void ensureBridgeInjected(TabState state) {
        if (state == null || state.engine == null) return;
        // 已经持有 bridge 对象的，也仍然再 setMember 一次，避免 window 重建后丢失
        if (state.javaBridge == null) {
            state.javaBridge = new JavaBridge(state);
        }
        // 用于 JS 侧在发现 editorBridge 失效时主动请求重新注入
        Object ping = new Object() {
            @SuppressWarnings("unused")
            public void requestReinject() {
                LOGGER.info("[bridge] JS requested reinject for tab={}",
                        state.filePath != null ? state.filePath : "(new)");
                Platform.runLater(() -> ensureBridgeInjected(state));
            }
        };
        Platform.runLater(() -> {
            try {
                JSObject window = (JSObject) state.engine.executeScript("window");
                if (window == null) return;
                window.setMember("editorBridge", state.javaBridge);
                window.setMember("editorBridgePing", ping);
            } catch (Exception e) {
                LOGGER.warn("[bridge] inject failed: {}", e.toString());
            }
        });
    }
}

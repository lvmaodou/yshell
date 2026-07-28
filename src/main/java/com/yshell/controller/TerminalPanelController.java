package com.yshell.controller;

import com.yshell.model.ConnInfo;
import com.yshell.model.TreeNode;
import com.yshell.service.ConnectionManager;
import com.yshell.service.ConnectionRepository;
import com.yshell.service.SshService;
import com.yshell.terminal.JediTermFxTerminal;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.LayoutConfig;
import com.yshell.ui.PanelManager;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * 终端面板控制器：把 JediTermFxTerminal 与 SshService 的 shell 会话连起来。
 * <p>
 * 本控制器通过 ConnectionManager.OnConnectionStateChangedListener 订阅连接状态变化事件。
 * 无论是 ConnectionToolbar 还是本面板的 btnConnect 触发的操作，都会统一刷新颜色。
 * <p>
 * 关键路径：
 * SshService.connect() → callback.onConnected()
 * → openShell(callback)
 * → callback.onShellReady(in, out)
 * → terminal.connect(in, out)
 */
public class TerminalPanelController {

    private static final Map<String, Boolean> bottomPanelVisibleByConnId = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> interactivePanelVisibleByConnId = new ConcurrentHashMap<>();
    private static final Map<String, Double> contentDividerPositionByConnId = new ConcurrentHashMap<>();

    @FXML
    private VBox rootPane;

    @FXML
    private JediTermFxTerminal terminal;

    @FXML
    private Button btnConnect;

    @FXML
    private Button btnClear;

    @FXML
    private Button btnInterrupt;

    @FXML
    private Button btnFull;

    @FXML
    private Button btnClose;

    @FXML
    private FontIcon btnCloseIcon;

    @FXML
    private HBox toolbars;

    @FXML
    private FontIcon btnFullIcon;

    @FXML
    private Button btnSearch;

    @FXML
    private HBox searchBox;

    @FXML
    private TextField searchInput;

    @FXML
    private Label searchCount;

    @FXML
    private Button btnFindPrev;

    @FXML
    private Button btnFindNext;

    @FXML
    private Button btnSearchClose;

    @FXML
    private Button btnFontIncrease;

    @FXML
    private Button btnFontDecrease;

    @FXML
    private Button btnCopy;

    @FXML
    private Button btnPaste;

    @FXML
    private Button btnConnProps;

    private SshService currentShellService;
    private String connId;
    private ConnInfo connInfo;
    private boolean shellBindingStarted;
    private final Consumer<Boolean> bottomPanelVisibilityListener =
            visible -> Platform.runLater(() -> refreshBottomPanelButtonState(isBottomPanelVisibleForCurrentConnection()));
    private final Consumer<Boolean> terminalFullscreenListener =
            fullscreen -> Platform.runLater(() -> refreshFullScreenButtonState(fullscreen));

    private Stage boundStage;
    private final Runnable stageStateChangeListener = () -> Platform.runLater(() -> refreshToolbarStyle(null));

    /**
     * 记录最后一次连接的 connId 与 connInfo。
     * 断开后点击"连接"时，把它们回传给 ConnectionManager.connect(...)，
     * 这样 ConnectionToolbar 会识别到 connId 已存在，不会创建新的 Tab。
     */
    private String lastConnId;
    private ConnInfo lastConnInfo;

    private CompletableFuture<String[]> keyboardInteractiveResponse;
    private String[] keyboardInteractivePrompts = new String[0];
    private boolean[] keyboardInteractiveEcho = new boolean[0];
    private String[] keyboardInteractiveAnswers = new String[0];
    private int keyboardInteractivePromptIndex;
    private StringBuilder keyboardInteractiveInput = new StringBuilder();

    @FXML
    public void initialize() {
        ConnectionManager cm = ConnectionManager.getInstance();
        terminal.setShutdownOnSceneDetach(false);
        terminal.setOnTerminalResize((cols, rows) -> {
            SshService service = currentShellService;
            if (service != null && service.isShellOpen()) {
                service.resizeShell(cols, rows);
            }
        });

        // ====== 按钮功能 ======
        // 根据当前连接状态切换：非连接时发起连接；已连接时断开
        btnConnect.setOnAction(e -> toggleCurrentConnection());

        // Ctrl+L 清屏
        btnClear.setOnAction(e -> clearTerminalDisplay());

        // Ctrl+C 中断
        btnInterrupt.setOnAction(e -> sendCtrlC());
        btnClose.setOnAction(e -> toggleBottomPanel());

        // 全屏切换
        btnFull.setOnAction(e -> toggleFullScreen());

        // 搜索（后续实现）
        btnSearch.setOnAction(e -> showSearchBox());
        btnFindPrev.setFocusTraversable(false);
        btnFindNext.setFocusTraversable(false);
        btnSearchClose.setFocusTraversable(false);
        btnFindPrev.setOnAction(e -> findPrevious());
        btnFindNext.setOnAction(e -> findNext());
        btnSearchClose.setOnAction(e -> hideSearchBox());
        searchInput.textProperty().addListener((obs, old, text) -> {
            terminal.setSearchQuery(text);
            updateSearchCount();
        });
        searchInput.setOnAction(e -> findNext());
        searchInput.addEventFilter(KeyEvent.KEY_PRESSED, this::handleSearchHotkeys);
        terminal.setOnSearchResultChanged(() -> Platform.runLater(this::updateSearchCount));
        hideSearchBox();

        // 临时调整当前终端字体大小，不写入全局配置
        btnFontIncrease.setOnAction(e -> changeTerminalFontSize(1));
        btnFontDecrease.setOnAction(e -> changeTerminalFontSize(-1));

        // 复制/粘贴
        btnCopy.setOnAction(e -> terminal.copySelectionToClipboard());
        btnPaste.setOnAction(e -> terminal.pasteFromClipboard());

        // 连接属性：复用连接管理器中的连接编辑界面
        btnConnProps.setOnAction(e -> openConnectionProperties());

        // 订阅连接状态变化事件：无论是哪里触发的变化，都会刷新 btnConnect 的颜色
        cm.addOnConnectionStateChangedListener(
                () -> Platform.runLater(this::refreshConnectButtonState));

        // 终端关闭时回收资源
        terminal.setOnClose(() -> {
            SshService svc = currentShellService;
            if (svc != null) svc.closeShell();
            currentShellService = null;
            shellBindingStarted = false;
        });

        // 快捷键
        terminal.addEventFilter(KeyEvent.KEY_PRESSED, this::handleHotkeys);
        terminal.addEventFilter(ScrollEvent.SCROLL, this::handleScrollZoom);

        // 初始化一次按钮颜色
        refreshConnectButtonState();
        PanelManager pm = PanelManager.getInstance();
        pm.addBottomPanelVisibilityListener(bottomPanelVisibilityListener);
        pm.addTerminalFullscreenListener(terminalFullscreenListener);
        refreshBottomPanelButtonState(isBottomPanelVisibleForCurrentConnection());
        refreshFullScreenButtonState(pm.isTerminalFullscreen());
        terminal.requestFocus();

        Scene existingScene = rootPane.getScene();
        if (existingScene != null) {
            bindStageListener(existingScene);
        }
        rootPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null && boundStage != null) {
                boundStage.fullScreenProperty().removeListener((obs2, old, newVal) -> stageStateChangeListener.run());
                boundStage.maximizedProperty().removeListener((obs2, old, newVal) -> stageStateChangeListener.run());
                boundStage = null;
            }
            if (newScene != null) {
                bindStageListener(newScene);
            }
        });
    }

    private void bindStageListener(Scene scene) {
        Window existingWindow = scene.getWindow();
        if (existingWindow instanceof Stage stage) {
            boundStage = stage;
            boundStage.fullScreenProperty().addListener((obs, old, newVal) -> stageStateChangeListener.run());
            boundStage.maximizedProperty().addListener((obs, old, newVal) -> stageStateChangeListener.run());
        }
        scene.windowProperty().addListener((obs, oldWindow, newWindow) -> {
            if (boundStage != null) {
                boundStage.fullScreenProperty().removeListener((obs2, old, newVal) -> stageStateChangeListener.run());
                boundStage.maximizedProperty().removeListener((obs2, old, newVal) -> stageStateChangeListener.run());
            }
            if (newWindow instanceof Stage stage) {
                boundStage = stage;
                boundStage.fullScreenProperty().addListener((obs2, old, newVal) -> stageStateChangeListener.run());
                boundStage.maximizedProperty().addListener((obs2, old, newVal) -> stageStateChangeListener.run());
            }
        });
    }

    // ============================================================
    //  外部 API（被 ConnectionManager / SshService 回调）
    // ============================================================

    public void focusTerminal() {
        Platform.runLater(terminal::requestFocus);
    }

    public void configureConnection(String connId, ConnInfo connInfo) {
        this.connId = connId;
        this.connInfo = connInfo;
        if (connId != null) {
            lastConnId = connId;
        }
        if (connInfo != null) {
            lastConnInfo = connInfo;
        }
        if (connId != null) {
            bottomPanelVisibleByConnId.putIfAbsent(connId, defaultBottomPanelVisible());
            interactivePanelVisibleByConnId.putIfAbsent(connId, defaultInteractivePanelVisible());
            ensureAtLeastOneContentPanelVisible();
        }
        refreshConnectButtonState();
        refreshBottomPanelButtonState(isBottomPanelVisibleForCurrentConnection());
    }

    public String[] requestKeyboardInteractive(String name, String instruction, String[] prompts, boolean[] echo) {
        CompletableFuture<String[]> response = new CompletableFuture<>();
        Platform.runLater(() -> beginKeyboardInteractive(name, instruction, prompts, echo, response));
        try {
            return response.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    public void cancelKeyboardInteractive() {
        if (keyboardInteractiveResponse == null || keyboardInteractiveResponse.isDone()) {
            return;
        }
        appendOutput("\r\n[认证已取消]\r\n");
        completeKeyboardInteractive(null);
    }

    private void beginKeyboardInteractive(String name, String instruction, String[] prompts, boolean[] echo,
                                          CompletableFuture<String[]> response) {
        cancelKeyboardInteractive();
        keyboardInteractiveResponse = response;
        keyboardInteractivePrompts = prompts == null ? new String[0] : prompts.clone();
        keyboardInteractiveEcho = echo == null ? new boolean[0] : echo.clone();
        keyboardInteractiveAnswers = new String[keyboardInteractivePrompts.length];
        keyboardInteractivePromptIndex = 0;
        keyboardInteractiveInput = new StringBuilder();

        terminal.clearPendingInput();
        terminal.setLocalInputHandler(this::acceptKeyboardInteractiveInput);
        appendOutput("\r\n[键盘交互认证]\r\n");
        if (name != null && !name.isBlank()) {
            appendOutput(name + "\r\n");
        }
        if (instruction != null && !instruction.isBlank()) {
            appendOutput(instruction + "\r\n");
        }
        showNextKeyboardInteractivePrompt();
        terminal.requestFocus();
    }

    private void showNextKeyboardInteractivePrompt() {
        if (keyboardInteractivePromptIndex >= keyboardInteractivePrompts.length) {
            completeKeyboardInteractive(keyboardInteractiveAnswers);
            return;
        }
        String prompt = keyboardInteractivePrompts[keyboardInteractivePromptIndex];
        if (prompt != null && !prompt.isEmpty()) {
            appendOutput(prompt);
        }
    }

    private void acceptKeyboardInteractiveInput(byte[] data) {
        if (data == null || data.length == 0 || keyboardInteractiveResponse == null
                || keyboardInteractiveResponse.isDone()) {
            return;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\r' || character == '\n') {
                submitKeyboardInteractiveAnswer();
                continue;
            }
            if (character == 0x03) {
                cancelKeyboardInteractive();
                return;
            }
            if (character == 0x7F) {
                eraseKeyboardInteractiveCharacter();
                continue;
            } else if (character == '\b') {
                eraseKeyboardInteractiveCharacter();
                continue;
            }
            if (character < 0x20) {
                continue;
            }
            keyboardInteractiveInput.append(character);
            if (currentKeyboardInteractivePromptEchoes()) {
                appendOutput(String.valueOf(character));
            } else {
                appendOutput("*");
            }
        }
    }

    private void submitKeyboardInteractiveAnswer() {
        if (keyboardInteractiveResponse == null || keyboardInteractiveResponse.isDone()) {
            return;
        }
        keyboardInteractiveAnswers[keyboardInteractivePromptIndex] = keyboardInteractiveInput.toString();
        keyboardInteractiveInput = new StringBuilder();
        keyboardInteractivePromptIndex++;
        appendOutput("\r\n");
        showNextKeyboardInteractivePrompt();
    }

    private void eraseKeyboardInteractiveCharacter() {
        if (keyboardInteractiveInput.isEmpty()) {
            return;
        }
        int end = keyboardInteractiveInput.length();
        int start = keyboardInteractiveInput.offsetByCodePoints(end, -1);
        keyboardInteractiveInput.delete(start, end);
        appendOutput("\b \b");
    }

    private boolean currentKeyboardInteractivePromptEchoes() {
        return keyboardInteractivePromptIndex < keyboardInteractiveEcho.length
                && keyboardInteractiveEcho[keyboardInteractivePromptIndex];
    }

    private void completeKeyboardInteractive(String[] answers) {
        CompletableFuture<String[]> response = keyboardInteractiveResponse;
        keyboardInteractiveResponse = null;
        keyboardInteractivePrompts = new String[0];
        keyboardInteractiveEcho = new boolean[0];
        keyboardInteractiveAnswers = new String[0];
        keyboardInteractivePromptIndex = 0;
        keyboardInteractiveInput = new StringBuilder();
        terminal.setLocalInputHandler(null);
        terminal.clearPendingInput();
        if (response != null && !response.isDone()) {
            response.complete(answers);
        }
    }

    public boolean isBoundTo(SshService service) {
        return service != null && service == currentShellService && shellBindingStarted;
    }

    /**
     * 把终端挂接到指定 SshService 的 shell。
     * <p>
     * 本方法可多次调用，用于在多个 SSH 连接之间切换：
     * - 首次调用：打开新 shell 并把终端输入/输出流绑定上去；
     * - 后续调用：关闭旧 shell 的 reader，把终端重新挂接到 service 的 shell 流。
     * （SshService.openShell 本身是可重入的，会复用已有 shell。）
     */
    public void onShellReady(SshService service) {
        if (service == null) return;
        cancelKeyboardInteractive();
        if (isBoundTo(service)) {
            Platform.runLater(terminal::requestFocus);
            return;
        }
        this.currentShellService = service;
        this.shellBindingStarted = true;
        // 记录这次连接的 connId 与 connInfo，断开后仍然能在"同一个 Tab"上重新连接
        if (service.getConnInfo() != null) {
            lastConnInfo = service.getConnInfo();
            terminal.setTerminalEncoding(lastConnInfo.getTerminalEncoding());
            terminal.setKeySequences(lastConnInfo.getBackspaceKeySequence(), lastConnInfo.getDeleteKeySequence());
        }
        String currentConnId = ConnectionManager.getInstance().getCurrentConnectionId();
        if (currentConnId != null) {
            lastConnId = currentConnId;
        }
        service.openShell(new SshService.ShellCallback() {
            @Override
            public void onShellReady(InputStream in, OutputStream out) {
                Platform.runLater(() -> {
                    // connect 内部已支持多次调用时先停止旧 reader、再启动新 reader
                    terminal.connect(in, out);
                    service.resizeShell(terminal.getColumns(), terminal.getRows());
                    terminal.requestFocus();
                });
            }

            @Override
            public void onShellClosed() {
                shellBindingStarted = false;
                Platform.runLater(() -> terminal.writeString("\r\n[远端 shell 已关闭]\r\n"));
            }

            @Override
            public void onShellError(String error) {
                shellBindingStarted = false;
                Platform.runLater(() -> terminal.writeString("\r\n[shell 错误: " + error + "]\r\n"));
            }
        });
    }

    public void appendOutput(String output) {
        if (output == null || output.isEmpty()) return;
        // 直接把文字追加到 terminal 的 emulator 缓冲区，不走远端 shell。
        // 这样即便 shell 尚未就绪（切换 Tab 的瞬间），提示文字也能正确显示。
        terminal.appendBytesToScreen(output.getBytes(StandardCharsets.UTF_8));
    }

    public boolean executeShellCommand(String command) {
        if (command == null || command.isBlank() || currentShellService == null) {
            return false;
        }
        String text = command.endsWith("\n") || command.endsWith("\r") ? command : command + "\n";
        if (currentShellService.isShellOpen()) {
            writeCommandToShell(text);
            return true;
        }
        return false;
    }

    public boolean executeHiddenShellCommand(String command) {
        if (command == null || command.isBlank() || currentShellService == null) {
            return false;
        }
        String text = command.endsWith("\n") || command.endsWith("\r") ? command : command + "\n";
        if (currentShellService.isShellOpen()) {
            writeHiddenCommandToShell(text);
            return true;
        }
        return false;
    }

    private void writeCommandToShell(String command) {
        setInteractivePanelVisible(true);
        currentShellService.writeToShell(command.getBytes(StandardCharsets.UTF_8));
        Platform.runLater(terminal::requestFocus);
    }

    private void writeHiddenCommandToShell(String command) {
        setInteractivePanelVisible(true);
        SshService service = currentShellService;
        String bootstrap = "stty -echo 2>/dev/null; printf '\\033[A\\033[2K\\r'; "
                + "read -r __yshell_cmd; stty echo 2>/dev/null; eval \"$__yshell_cmd\"\n";
        service.writeToShell(bootstrap.getBytes(StandardCharsets.UTF_8));
        PauseTransition delay = new PauseTransition(Duration.millis(80));
        delay.setOnFinished(event -> {
            if (service == currentShellService && service.isShellOpen()) {
                service.writeToShell(command.getBytes(StandardCharsets.UTF_8));
            }
            terminal.requestFocus();
        });
        delay.play();
        Platform.runLater(terminal::requestFocus);
    }

    /**
     * 本地清屏。不再依赖远端 shell 支持 "clear\n"，因此切 Tab / 未连接时都能生效。
     */
    public void clearOutput() {
        terminal.clearScreenCompletely();
    }

    // ============================================================
    //  快捷键
    // ============================================================

    private void handleHotkeys(KeyEvent e) {
        if (new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN).match(e)) {
            showSearchBox();
            e.consume();
            return;
        }
        if (new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN).match(e)) {
            clearTerminalDisplay();
            e.consume();
        }
    }

    private void handleSearchHotkeys(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            hideSearchBox();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.ENTER) {
            if (e.isShiftDown()) {
                findPrevious();
            } else {
                findNext();
            }
            e.consume();
        }
    }

    private void handleScrollZoom(ScrollEvent e) {
        if (!e.isControlDown()) {
            return;
        }
        if (e.getDeltaY() > 0) {
            changeTerminalFontSize(1);
        } else if (e.getDeltaY() < 0) {
            changeTerminalFontSize(-1);
        }
        e.consume();
    }

    // ============================================================
    //  按钮功能实现
    // ============================================================

    /**
     * 切换"当前"连接的状态：
     * 1) 如果当前已经有连接（ConnectionManager.isConnected()），则断开
     * 2) 如果当前没有连接，但通过 ConnectionManager 或 lastConnInfo 能拿到配置，就重新连接
     * 该逻辑与 ConnectionToolbar 的右键"连接/断开"等价：两边都调用同一个 ConnectionManager，
     * 并且都通过 OnConnectionStateChangedListener 同步刷新按钮颜色。
     */
    private void toggleCurrentConnection() {
        ConnectionManager cm = ConnectionManager.getInstance();

        boolean connected = connId != null ? cm.isConnected(connId) : cm.isConnected();
        if (connected) {
            // 已连接：点击 = 断开
            // 先记录本次连接信息，避免 disconnectCurrent() 清空后拿不到
            if (connId != null) {
                lastConnId = connId;
            } else if (cm.getCurrentConnectionId() != null) {
                lastConnId = cm.getCurrentConnectionId();
            }
            if (connInfo != null) {
                lastConnInfo = connInfo;
            } else if (cm.getCurrentConnection() != null) {
                lastConnInfo = cm.getCurrentConnection();
            }
            if (connId != null) {
                cm.disconnect(connId);
            } else {
                cm.disconnectCurrent();
            }
            if (currentShellService != null) {
                currentShellService.closeShell();
                currentShellService = null;
            }
            shellBindingStarted = false;
            terminal.stopReader();
            return;
        }

        // 非连接态：点击 = 连接
        // 优先用 ConnectionManager 自身的连接信息（Tab 切换场景），否则用 lastConnId/lastConnInfo
        String targetConnId = connId != null ? connId : cm.getCurrentConnectionId();
        ConnInfo targetConnInfo = connInfo != null ? connInfo : cm.getCurrentConnection();

        if (targetConnInfo == null) {
            targetConnInfo = lastConnInfo;
        }
        if (targetConnId == null) {
            targetConnId = lastConnId;
        }

        if (targetConnInfo == null) {
            Platform.runLater(() ->
                    terminal.writeString("\r\n[提示] 请先在左侧选择一个连接配置\r\n"));
            return;
        }

        // 只要 connId 非 null，ConnectionManager 就不会触发 createConnectionTab，
        // 因此同一个 Tab 上重新连接不会新建 Tab
        cm.connect(targetConnInfo, targetConnId, true);
    }

    /**
     * 根据 ConnectionManager 的 isConnected 刷新 btnConnect 的颜色：
     * - 连接态 → 绿色 FontIcon
     * - 非连接态 → 红色 FontIcon
     * CSS 中仅通过 .connected / .disconnected 改变 FontIcon 颜色，不会影响整个按钮区域。
     */
    private void refreshConnectButtonState() {
        if (btnConnect == null) return;
        ConnectionManager cm = ConnectionManager.getInstance();
        boolean connected = connId != null ? cm.isConnected(connId) : cm.isConnected();
        ObservableList<String> styleClass = btnConnect.getStyleClass();
        styleClass.removeAll("connected", "disconnected", "icon-status-success", "icon-status-error");
        if (connected) {
            styleClass.addAll("connected", "icon-status-success");
        } else {
            styleClass.addAll("disconnected", "icon-status-error");
        }
    }

    private void clearTerminalDisplay() {
        terminal.clearScreen();
    }

    private void openConnectionProperties() {
        ConnInfo target = resolveEditableConnection();
        if (target == null) {
            DialogHelper.showInfo("连接属性", "当前终端没有绑定连接配置。");
            return;
        }

        Window owner = terminal.getScene() != null ? terminal.getScene().getWindow() : null;
        ConnectionManagerController.openConnectionEditor(
                owner,
                target,
                true,
                this::saveConnectionProperties,
                this::deleteConnectionProperties
        );
        refreshConnectButtonState();
    }

    private ConnInfo resolveEditableConnection() {
        ConnectionManager cm = ConnectionManager.getInstance();
        if (connInfo != null) {
            return connInfo;
        }
        if (currentShellService != null && currentShellService.getConnInfo() != null) {
            return currentShellService.getConnInfo();
        }
        ConnInfo current = cm.getCurrentConnection();
        if (current != null) {
            return current;
        }
        return lastConnInfo;
    }

    private void saveConnectionProperties(ConnInfo updated) {
        if (updated == null) return;
        updated.setModifiedTime(System.currentTimeMillis());

        List<TreeNode> nodes = ConnectionRepository.getInstance().load();
        boolean found = false;
        for (int i = 0; i < nodes.size(); i++) {
            TreeNode node = nodes.get(i);
            if (!node.isFolder() && updated.getId() != null && updated.getId().equals(node.getId())) {
                nodes.set(i, updated);
                found = true;
                break;
            }
        }
        if (!found) {
            if (updated.getParentId() == null || updated.getParentId().isEmpty()) {
                updated.setParentId("root");
            }
            nodes.add(updated);
        }
        ConnectionRepository.getInstance().save(nodes);

        connInfo = updated;
        lastConnInfo = updated;
        if (connId != null) {
            ConnectionManager.getInstance().refreshConnectionTab(updated, connId);
        }
    }

    private void deleteConnectionProperties(ConnInfo deleted) {
        if (deleted == null || deleted.getId() == null) return;
        List<TreeNode> nodes = ConnectionRepository.getInstance().load();
        nodes.removeIf(node -> !node.isFolder() && deleted.getId().equals(node.getId()));
        ConnectionRepository.getInstance().save(nodes);
        if (connInfo != null && deleted.getId().equals(connInfo.getId())) {
            connInfo = null;
        }
        if (lastConnInfo != null && deleted.getId().equals(lastConnInfo.getId())) {
            lastConnInfo = null;
        }
    }

    private void showSearchBox() {
        if (searchBox == null || searchInput == null) return;
        searchBox.setVisible(true);
        searchBox.setManaged(true);
        String selected = normalizeSearchText(terminal.getSelectedText());
        if (!selected.isEmpty()) {
            searchInput.setText(selected);
        }
        searchInput.requestFocus();
        searchInput.selectAll();
        terminal.setSearchQuery(searchInput.getText());
        updateSearchCount();
    }

    private void hideSearchBox() {
        if (searchBox != null) {
            searchBox.setVisible(false);
            searchBox.setManaged(false);
        }
        terminal.clearSearch();
        updateSearchCount();
        terminal.requestFocus();
    }

    private void findNext() {
        terminal.findNextSearchMatch();
        updateSearchCount();
    }

    private void findPrevious() {
        terminal.findPreviousSearchMatch();
        updateSearchCount();
    }

    private void updateSearchCount() {
        if (searchCount == null) return;
        int total = terminal.getSearchMatchCount();
        int current = terminal.getCurrentSearchMatchOrdinal();
        searchCount.setText(total <= 0 ? "0/0" : current + "/" + total);
        boolean disabled = total <= 0;
        if (btnFindPrev != null) btnFindPrev.setDisable(disabled);
        if (btnFindNext != null) btnFindNext.setDisable(disabled);
    }

    private String normalizeSearchText(String text) {
        if (text == null) return "";
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        int lineBreak = normalized.indexOf('\n');
        if (lineBreak >= 0) {
            normalized = normalized.substring(0, lineBreak).trim();
        }
        return normalized;
    }

    private void changeTerminalFontSize(int delta) {
        terminal.setTerminalFontSize(terminal.getTerminalFontSize() + delta);
        if (currentShellService != null && currentShellService.isShellOpen()) {
            currentShellService.resizeShell(terminal.getColumns(), terminal.getRows());
        }
    }

    private void sendCtrlC() {
        if (currentShellService != null && currentShellService.isShellOpen()) {
            currentShellService.writeToShell(new byte[]{0x03});
        }
    }

    private void toggleBottomPanel() {
        setBottomPanelVisible(!isBottomPanelVisibleForCurrentConnection());
    }

    public void setBottomPanelVisible(boolean visible) {
        if (!visible && !isInteractivePanelVisibleForCurrentConnection()) {
            visible = true;
        }
        if (!visible) {
            captureCurrentBottomPanelLayout();
        }
        setBottomPanelVisibleForCurrentConnection(visible);
        PanelManager.getInstance().toggleBottomPanel(visible);
        refreshBottomPanelButtonState(visible);
        if (visible) {
            restoreCurrentBottomPanelLayout();
        }
    }

    public void applyBottomPanelState() {
        ensureAtLeastOneContentPanelVisible();
        boolean visible = isBottomPanelVisibleForCurrentConnection();
        PanelManager pm = PanelManager.getInstance();
        if (pm.isBottomPanelVisible() != visible) {
            pm.toggleBottomPanel(visible);
        } else {
            refreshBottomPanelButtonState(visible);
        }
        if (visible) {
            restoreCurrentBottomPanelLayout();
        }
    }

    public boolean isBottomPanelVisible() {
        return isBottomPanelVisibleForCurrentConnection();
    }

    public boolean isInteractivePanelVisible() {
        return isInteractivePanelVisibleForCurrentConnection();
    }

    public void setInteractivePanelVisible(boolean visible) {
        if (!visible && !isBottomPanelVisibleForCurrentConnection()) {
            visible = true;
        }
        if (!visible) {
            captureCurrentBottomPanelLayout();
        }
        setInteractivePanelVisibleForCurrentConnection(visible);
        PanelManager.getInstance().toggleInteractivePanel(visible);
        if (visible) {
            restoreCurrentBottomPanelLayout();
        }
    }

    public void applyInteractivePanelState() {
        ensureAtLeastOneContentPanelVisible();
        boolean visible = isInteractivePanelVisibleForCurrentConnection();
        PanelManager pm = PanelManager.getInstance();
        if (pm.isInteractivePanelVisible() != visible) {
            pm.toggleInteractivePanel(visible);
        }
        if (visible) {
            restoreCurrentBottomPanelLayout();
        }
    }

    public void toggleFullScreen() {
        if (rootPane != null) {
            PanelManager.getInstance().toggleTerminalFullscreen(rootPane);
        }
    }

    public boolean isFullScreen() {
        if (terminal == null) {
            return false;
        }
        var scene = terminal.getScene();
        if (scene == null) {
            return false;
        }
        var window = scene.getWindow();
        if (window instanceof Stage stage) {
            return stage.isFullScreen() || stage.isMaximized();
        }
        return false;
    }

    private boolean isBottomPanelVisibleForCurrentConnection() {
        String key = bottomPanelStateKey();
        if (key == null) {
            return PanelManager.getInstance().isBottomPanelVisible();
        }
        return bottomPanelVisibleByConnId.getOrDefault(key, defaultBottomPanelVisible());
    }

    private void setBottomPanelVisibleForCurrentConnection(boolean visible) {
        String key = bottomPanelStateKey();
        if (key != null) {
            bottomPanelVisibleByConnId.put(key, visible);
        }
    }

    private boolean isInteractivePanelVisibleForCurrentConnection() {
        String key = bottomPanelStateKey();
        if (key == null) {
            return PanelManager.getInstance().isInteractivePanelVisible();
        }
        return interactivePanelVisibleByConnId.getOrDefault(key, defaultInteractivePanelVisible());
    }

    private void setInteractivePanelVisibleForCurrentConnection(boolean visible) {
        String key = bottomPanelStateKey();
        if (key != null) {
            interactivePanelVisibleByConnId.put(key, visible);
        }
    }

    private void ensureAtLeastOneContentPanelVisible() {
        if (isBottomPanelVisibleForCurrentConnection() || isInteractivePanelVisibleForCurrentConnection()) {
            return;
        }
        setInteractivePanelVisibleForCurrentConnection(true);
    }

    public void captureCurrentBottomPanelLayout() {
        String key = bottomPanelStateKey();
        var contentSplitPane = PanelManager.getInstance().getContentSplitPane();
        if (key == null || contentSplitPane == null || contentSplitPane.getItems().size() < 2) {
            return;
        }
        double[] positions = contentSplitPane.getDividerPositions();
        if (positions.length > 0) {
            contentDividerPositionByConnId.put(key, positions[0]);
        }
    }

    private void restoreCurrentBottomPanelLayout() {
        String key = bottomPanelStateKey();
        var contentSplitPane = PanelManager.getInstance().getContentSplitPane();
        if (key == null || contentSplitPane == null || contentSplitPane.getItems().size() < 2) {
            return;
        }
        Double position = contentDividerPositionByConnId.get(key);
        if (position != null) {
            applyContentDividerPosition(contentSplitPane, position);
            Platform.runLater(() -> applyContentDividerPosition(contentSplitPane, position));
        } else {
            PanelManager.getInstance().restoreContentDivider();
        }
    }

    private void applyContentDividerPosition(javafx.scene.control.SplitPane contentSplitPane, double position) {
        if (contentSplitPane != null && contentSplitPane.getItems().size() >= 2) {
            contentSplitPane.setDividerPositions(position);
        }
    }

    private String bottomPanelStateKey() {
        return connId != null ? connId : lastConnId;
    }

    private boolean defaultBottomPanelVisible() {
        return LayoutConfig.getInstance().isBottomPanelVisible();
    }

    private boolean defaultInteractivePanelVisible() {
        return LayoutConfig.getInstance().isInteractivePanelVisible();
    }

    private void refreshToolbarStyle(Boolean fullScreen) {
        if (toolbars != null) {
            ObservableList<String> styleClass = toolbars.getStyleClass();
            styleClass.removeAll("toolbars-full", "toolbars");
            if (fullScreen == null) {
                fullScreen = isFullScreen();
                boolean bottomPanelVisible = isBottomPanelVisibleForCurrentConnection();
                styleClass.add(!bottomPanelVisible && fullScreen ? "toolbars-full" : "toolbars");
            } else {
                styleClass.add(fullScreen ? "toolbars-full" : "toolbars");
            }
        }
    }

    private void refreshBottomPanelButtonState(boolean visible) {
        if (btnCloseIcon != null) {
            btnCloseIcon.setIconLiteral(visible ? "fas-chevron-down" : "fas-chevron-up");
            refreshToolbarStyle(null);
        }
    }

    private void refreshFullScreenButtonState(boolean fullscreen) {
        if (btnFullIcon != null) {
            btnFullIcon.setIconLiteral(fullscreen ? "fas-compress" : "fas-expand");
        }
        refreshToolbarStyle(fullscreen);
    }

    public void shutdownTerminal() {
        cancelKeyboardInteractive();
        PanelManager pm = PanelManager.getInstance();
        pm.exitTerminalFullscreen(rootPane);
        pm.removeBottomPanelVisibilityListener(bottomPanelVisibilityListener);
        pm.removeTerminalFullscreenListener(terminalFullscreenListener);
        if (boundStage != null) {
            boundStage.fullScreenProperty().removeListener((obs, old, newVal) -> stageStateChangeListener.run());
            boundStage.maximizedProperty().removeListener((obs, old, newVal) -> stageStateChangeListener.run());
            boundStage = null;
        }
        shellBindingStarted = false;
        currentShellService = null;
        String key = bottomPanelStateKey();
        if (key != null) {
            bottomPanelVisibleByConnId.remove(key);
            interactivePanelVisibleByConnId.remove(key);
            contentDividerPositionByConnId.remove(key);
        }
        terminal.shutdown();
    }
}

package com.yshell.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.*;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.*;
import com.yshell.config.AppSettings;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.css.CssMetaData;
import javafx.css.Styleable;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.css.converter.ColorConverter;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.*;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * JavaFX 原生终端组件：JediTerm-core 解析 + JavaFX Canvas 渲染。
 *
 * <p>数据流：</p>
 * <pre>
 *   SSH Shell InputStream ──→ 读线程 ──→ byteBuf ──→ JavaFX 线程
 *                                                        │
 *                                                        ├─→ pump() (UTF-8 解码)
 *                                                        │
 *                                                        └─→ JediEmulator.next() (解析 VT/ANSI)
 *                                                                   │
 *                                                                   ▼
 *                                                             TerminalTextBuffer
 *                                                                   │
 *                                                                   ▼
 *                                                             Canvas 重绘
 * </pre>
 */
public class JediTermFxTerminal extends Region implements TerminalDisplay {
    private static final Logger LOGGER = LoggerFactory.getLogger(JediTermFxTerminal.class);
    // ===== 字号 / 字体 =====
    // 注：JavaFX 的 Font.font(family, size) 不能识别 CSS 风格的多个 fallback 字体名
    // 这里用一个 JavaFX 内置的等宽字体名字列表，由 loadMonospaceFont() 逐个尝试
    private static final String[] MONOSPACE_FAMILIES = {
            "Consolas", "Cascadia Mono", "JetBrains Mono",
            "Source Code Pro", "Courier New", "Menlo", "Monaco", "monospace"
    };

    // ===== 默认配色兜底值；实际颜色由 CSS 主题注入 =====
    private static final Color FALLBACK_FG = Color.rgb(220, 220, 220);
    private static final Color FALLBACK_BG = Color.valueOf("#161b22");
    private static final Color FALLBACK_CURSOR = Color.rgb(220, 220, 220, 0.55);
    private static final Color FALLBACK_SELECTION_BG = Color.rgb(0, 120, 215, 0.4);
    private static final Color FALLBACK_SEARCH_MATCH_BG = Color.rgb(1, 200, 180, 0.88);
    private static final Color FALLBACK_SEARCH_CURRENT_BG = Color.rgb(255, 59, 48, 0.88);
    private static final double CONTENT_PADDING_LEFT = 8.0;
    private static final double CONTENT_PADDING_TOP = 6.0;
    private static final double CONTENT_PADDING_RIGHT = 6.0;
    private static final double CONTENT_PADDING_BOTTOM = 6.0;
    private static final double SCROLL_BAR_WIDTH = 12.0;
    private static final double SELECTION_AUTO_SCROLL_STEP = 1.0;
    private static final Pattern PROMPT_PATTERN = Pattern.compile("\\[[^]\\r\\n]+@[^]\\r\\n]+\\s+[^]\\r\\n]+]\\s*[#$]\\s*$");
    private static final Pattern PROMPT_EXTRACT_PATTERN = Pattern.compile("\\[[^]\\r\\n]+@[^]\\r\\n]+\\s+[^]\\r\\n]+]\\s*[#$]\\s*");

    private final TerminalTextBuffer terminalTextBuffer;
    private final JediTerminal jediTerminal;
    private final MutableDataStream dataStream = new MutableDataStream();
    private final JediEmulator emulator;

    private final StyleableObjectProperty<Color> terminalForeground =
            newTerminalColorProperty("terminalForeground", FALLBACK_FG);
    private final StyleableObjectProperty<Color> terminalBackground =
            newTerminalColorProperty("terminalBackground", FALLBACK_BG);
    private final StyleableObjectProperty<Color> terminalCursor =
            newTerminalColorProperty("terminalCursor", FALLBACK_CURSOR);
    private final StyleableObjectProperty<Color> terminalSelectionBackground =
            newTerminalColorProperty("terminalSelectionBackground", FALLBACK_SELECTION_BG);
    private final StyleableObjectProperty<Color> terminalSearchMatchBackground =
            newTerminalColorProperty("terminalSearchMatchBackground", FALLBACK_SEARCH_MATCH_BG);
    private final StyleableObjectProperty<Color> terminalSearchCurrentBackground =
            newTerminalColorProperty("terminalSearchCurrentBackground", FALLBACK_SEARCH_CURRENT_BG);

    // ===== 渲染 =====
    private final Canvas canvas;
    private final ScrollBar scrollBar;
    private Font terminalFont;
    private double fontSize = AppSettings.getInstance().getTerminalDefaultFontSize();
    private double charWidth = 8;
    private double charHeight = 16;
    private int cols = 80;
    private int rows = 24;
    private int outputColumn = 0;
    // 用于跟踪最近处理的字符,检测跨批次的换行符
    private final StringBuilder recentOutput = new StringBuilder(100);

    // ===== 选中区 =====
    private int selStartX = -1, selStartY = -1;
    private int selEndX = -1, selEndY = -1;
    private boolean selecting = false;
    private double selectionDragX = Double.NaN;
    private double selectionDragY = Double.NaN;

    // ===== 查找 =====
    private String searchQuery = "";
    private boolean searchDirty = false;
    private final List<SearchMatch> searchMatches = new ArrayList<>();
    private final Map<Integer, List<SearchMatch>> searchMatchesByLine = new HashMap<>();
    private int currentSearchMatchIndex = -1;
    private Runnable onSearchResultChanged;
    private boolean preserveSearchScrollOnce = false;

    // ===== 光标闪烁 =====
    private volatile long cursorBlinkStartNanos = 0L;
    private static final long CURSOR_BLINK_HALF_PERIOD_NS = 350_000_000L; // 350ms 半周期

    // ===== 字节收发 =====
    private InputStream remoteInput;
    private OutputStream remoteOutput;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final ByteArrayOutputStream pendingInput = new ByteArrayOutputStream();
    private final Timeline renderTimer;
    private volatile boolean dirty = true;
    private boolean shutdownOnSceneDetach = true;
    private Charset terminalCharset = StandardCharsets.UTF_8;
    private int backspaceKeySequence = 0;
    private int deleteKeySequence = 0;
    private boolean applicationCursorKeys = false;
    private boolean alternateScreenBuffer = false;
    private String pendingModeScanTail = "";
    private BiConsumer<Integer, Integer> onTerminalResize;
    private Consumer<byte[]> localInputHandler;

    // ===== 回调 =====
    private Runnable onCloseCallback;

    public JediTermFxTerminal() {
        StyleState styleState = getStyleState();

        this.terminalTextBuffer = new TerminalTextBuffer(cols, rows, styleState,
                AppSettings.getInstance().getTerminalScrollbackLines());
        this.jediTerminal = new JediTerminal(this, terminalTextBuffer, styleState);
        this.emulator = new JediEmulator(dataStream, jediTerminal);

        // 监听模型变更
        terminalTextBuffer.addChangesListener(new TextBufferChangesListener() {
            @Override
            public void linesChanged(int y) {
                searchDirty = true;
                dirty = true;
            }

            @Override
            public void linesDiscardedFromHistory(@NotNull List<TerminalLine> lines) {
                searchDirty = true;
                dirty = true;
            }

            @Override
            public void historyCleared() {
                searchDirty = true;
                dirty = true;
            }

            @Override
            public void widthResized() {
                searchDirty = true;
                dirty = true;
            }
        });

        this.canvas = new Canvas();
        getStyleClass().add("terminal-canvas");
        canvas.getStyleClass().add("terminal-canvas");
        this.scrollBar = new ScrollBar();
        scrollBar.setOrientation(Orientation.VERTICAL);
        scrollBar.setMin(0);
        scrollBar.setUnitIncrement(1);
        scrollBar.valueProperty().addListener((o, a, b) -> dirty = true);
        getChildren().addAll(canvas, scrollBar);

        // 字体：加载系统存在的等宽字体，避免 CSS 风格字体名导致回退到默认非等宽字体
        terminalFont = loadMonospaceFont(fontSize);
        measureFont();

        // 尺寸
        widthProperty().addListener((o, a, b) -> resizeCanvas());
        heightProperty().addListener((o, a, b) -> resizeCanvas());

        // 键盘
        setFocusTraversable(true);
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyTyped(this::handleKeyTyped);

        // 鼠标选择
        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseDragged(this::handleMouseDragged);
        canvas.setOnMouseReleased(e -> stopSelectionDrag());
        canvas.setOnScroll(e -> {
            double delta = e.getDeltaY() > 0 ? -3 : 3;
            scrollBar.setValue(clamp(scrollBar.getValue() + delta, scrollBar.getMin(), scrollBar.getMax()));
            e.consume();
        });

        // 启动光标闪烁
        cursorBlinkStartNanos = System.nanoTime();

        // 30 FPS 渲染定时器
        renderTimer = new Timeline(new KeyFrame(Duration.millis(33), e -> tick()));
        renderTimer.setCycleCount(Timeline.INDEFINITE);
        renderTimer.play();

        // 组件销毁
        sceneProperty().addListener((o, a, b) -> {
            if (b == null && shutdownOnSceneDetach) shutdown();
        });

        // 设置默认 Canvas 尺寸，确保渲染能正常工作
        canvas.setWidth(800);
        canvas.setHeight(400);

        // 强制立即渲染一次，确保初始化时能看到内容
        dirty = true;
        render();
    }

    private StyleState getStyleState() {
        TextStyle defaultStyle = new TextStyle(null, null);
        // ===== JediTerm 屏幕模型 =====
        StyleState styleState = new StyleState();
        styleState.setCurrent(defaultStyle);
        return styleState;
    }

    private StyleableObjectProperty<Color> newTerminalColorProperty(String name, Color fallback) {
        return new StyleableObjectProperty<>(fallback) {
            @Override
            protected void invalidated() {
                dirty = true;
            }

            @Override
            public Object getBean() {
                return JediTermFxTerminal.this;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public CssMetaData<? extends Styleable, Color> getCssMetaData() {
                return switch (name) {
                    case "terminalForeground" -> StyleableProperties.TERMINAL_FOREGROUND;
                    case "terminalBackground" -> StyleableProperties.TERMINAL_BACKGROUND;
                    case "terminalCursor" -> StyleableProperties.TERMINAL_CURSOR;
                    case "terminalSelectionBackground" -> StyleableProperties.TERMINAL_SELECTION_BACKGROUND;
                    case "terminalSearchMatchBackground" -> StyleableProperties.TERMINAL_SEARCH_MATCH_BACKGROUND;
                    case "terminalSearchCurrentBackground" -> StyleableProperties.TERMINAL_SEARCH_CURRENT_BACKGROUND;
                    default -> throw new IllegalStateException("Unknown terminal color property: " + name);
                };
            }
        };
    }

    private Color terminalForegroundColor() {
        Color color = terminalForeground.get();
        return color == null ? FALLBACK_FG : color;
    }

    private Color terminalBackgroundColor() {
        Color color = terminalBackground.get();
        return color == null ? FALLBACK_BG : color;
    }

    private Color terminalCursorColor() {
        Color color = terminalCursor.get();
        return color == null ? FALLBACK_CURSOR : color;
    }

    private Color terminalSelectionBackgroundColor() {
        Color color = terminalSelectionBackground.get();
        return color == null ? FALLBACK_SELECTION_BG : color;
    }

    private Color terminalSearchMatchBackgroundColor() {
        Color color = terminalSearchMatchBackground.get();
        return color == null ? FALLBACK_SEARCH_MATCH_BG : color;
    }

    private Color terminalSearchCurrentBackgroundColor() {
        Color color = terminalSearchCurrentBackground.get();
        return color == null ? FALLBACK_SEARCH_CURRENT_BG : color;
    }

    private static final class StyleableProperties {
        private static final CssMetaData<JediTermFxTerminal, Color> TERMINAL_FOREGROUND =
                new CssMetaData<>("-ys-terminal-foreground", ColorConverter.getInstance(), FALLBACK_FG) {
                    @Override
                    public boolean isSettable(JediTermFxTerminal node) {
                        return !node.terminalForeground.isBound();
                    }

                    @Override
                    public StyleableProperty<Color> getStyleableProperty(JediTermFxTerminal node) {
                        return node.terminalForeground;
                    }
                };

        private static final CssMetaData<JediTermFxTerminal, Color> TERMINAL_BACKGROUND =
                new CssMetaData<>("-ys-terminal-background", ColorConverter.getInstance(), FALLBACK_BG) {
                    @Override
                    public boolean isSettable(JediTermFxTerminal node) {
                        return !node.terminalBackground.isBound();
                    }

                    @Override
                    public StyleableProperty<Color> getStyleableProperty(JediTermFxTerminal node) {
                        return node.terminalBackground;
                    }
                };

        private static final CssMetaData<JediTermFxTerminal, Color> TERMINAL_CURSOR =
                new CssMetaData<>("-ys-terminal-cursor", ColorConverter.getInstance(), FALLBACK_CURSOR) {
                    @Override
                    public boolean isSettable(JediTermFxTerminal node) {
                        return !node.terminalCursor.isBound();
                    }

                    @Override
                    public StyleableProperty<Color> getStyleableProperty(JediTermFxTerminal node) {
                        return node.terminalCursor;
                    }
                };

        private static final CssMetaData<JediTermFxTerminal, Color> TERMINAL_SELECTION_BACKGROUND =
                new CssMetaData<>("-ys-terminal-selection-background", ColorConverter.getInstance(), FALLBACK_SELECTION_BG) {
                    @Override
                    public boolean isSettable(JediTermFxTerminal node) {
                        return !node.terminalSelectionBackground.isBound();
                    }

                    @Override
                    public StyleableProperty<Color> getStyleableProperty(JediTermFxTerminal node) {
                        return node.terminalSelectionBackground;
                    }
                };

        private static final CssMetaData<JediTermFxTerminal, Color> TERMINAL_SEARCH_MATCH_BACKGROUND =
                new CssMetaData<>("-ys-terminal-search-match-background", ColorConverter.getInstance(), FALLBACK_SEARCH_MATCH_BG) {
                    @Override
                    public boolean isSettable(JediTermFxTerminal node) {
                        return !node.terminalSearchMatchBackground.isBound();
                    }

                    @Override
                    public StyleableProperty<Color> getStyleableProperty(JediTermFxTerminal node) {
                        return node.terminalSearchMatchBackground;
                    }
                };

        private static final CssMetaData<JediTermFxTerminal, Color> TERMINAL_SEARCH_CURRENT_BACKGROUND =
                new CssMetaData<>("-ys-terminal-search-current-background", ColorConverter.getInstance(), FALLBACK_SEARCH_CURRENT_BG) {
                    @Override
                    public boolean isSettable(JediTermFxTerminal node) {
                        return !node.terminalSearchCurrentBackground.isBound();
                    }

                    @Override
                    public StyleableProperty<Color> getStyleableProperty(JediTermFxTerminal node) {
                        return node.terminalSearchCurrentBackground;
                    }
                };

        private static final List<CssMetaData<? extends Styleable, ?>> STYLEABLES;

        static {
            List<CssMetaData<? extends Styleable, ?>> styleables =
                    new ArrayList<>(Region.getClassCssMetaData());
            styleables.add(TERMINAL_FOREGROUND);
            styleables.add(TERMINAL_BACKGROUND);
            styleables.add(TERMINAL_CURSOR);
            styleables.add(TERMINAL_SELECTION_BACKGROUND);
            styleables.add(TERMINAL_SEARCH_MATCH_BACKGROUND);
            styleables.add(TERMINAL_SEARCH_CURRENT_BACKGROUND);
            STYLEABLES = Collections.unmodifiableList(styleables);
        }
    }

    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return StyleableProperties.STYLEABLES;
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return getClassCssMetaData();
    }

    // ========================================================
    //  数据源连接
    // ========================================================

    /**
     * 绑定到远程 InputStream/OutputStream；可多次调用以切换到不同的 shell 会话。
     * <p>
     * 多次调用时：
     * - 停止旧 reader 线程（打断对旧 InputStream 的阻塞读取）；
     * - 重新把 remoteInput/remoteOutput 指向新的流；
     * - 以新的 remoteInput 为起点再次启动 reader；
     * - 若用户在 shell 未就绪期间敲入了内容，会一次性 flush 到新 shell。
     */
    public void connect(InputStream in, OutputStream out) {
        if (running.get()) {
            running.set(false);
            if (readerThread != null) {
                readerThread.interrupt();
                readerThread = null;
            }
            try {
                if (remoteInput != null) remoteInput.close();
            } catch (IOException ignored) {
            }
        }
        this.remoteInput = in;
        this.remoteOutput = out;
        if (running.compareAndSet(false, true)) {
            startReader(in);
        } else {
            running.set(true);
            startReader(in);
        }
        flushPendingInput();
    }

    public void setTerminalEncoding(String encoding) {
        Charset charset = StandardCharsets.UTF_8;
        if (encoding != null && !encoding.isBlank()) {
            try {
                charset = Charset.forName(encoding.trim());
            } catch (Exception e) {
                LOGGER.warn("Unsupported terminal encoding '{}', fallback to UTF-8", encoding);
            }
        }
        this.terminalCharset = charset;
        dataStream.setCharset(charset);
    }

    public void setKeySequences(int backspaceKeySequence, int deleteKeySequence) {
        this.backspaceKeySequence = backspaceKeySequence;
        this.deleteKeySequence = deleteKeySequence;
    }

    public void setOnTerminalResize(BiConsumer<Integer, Integer> onTerminalResize) {
        this.onTerminalResize = onTerminalResize;
    }

    public void setLocalInputHandler(Consumer<byte[]> localInputHandler) {
        this.localInputHandler = localInputHandler;
    }

    public void clearPendingInput() {
        synchronized (pendingInput) {
            pendingInput.reset();
        }
    }

    /**
     * 发送用户输入到远端 shell 的 stdin。
     * 若 shell 尚未绑定（例如刚切换 Tab 但 openShell 还未返回），
     * 则把输入暂存到 pendingInput，待下一次 connect 后再批量发出，
     * 同时把字节直接回显到屏幕，避免"看起来输不进去"。
     */
    public void writeBytes(byte[] data) {
        if (data == null || data.length == 0) return;
        Consumer<byte[]> inputHandler = localInputHandler;
        if (inputHandler != null) {
            inputHandler.accept(Arrays.copyOf(data, data.length));
            return;
        }
        if (remoteOutput != null) {
            try {
                remoteOutput.write(data);
                remoteOutput.flush();
            } catch (IOException e) {
                shutdown();
            }
        } else {
            synchronized (pendingInput) {
                pendingInput.write(data, 0, data.length);
            }
            echoBytesLocally(data);
        }
    }

    public void writeString(String text) {
        if (text == null || text.isEmpty()) return;
        writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 直接把字节追加到 emulator（作为"远端回显"走同一渲染路径）。
     * 不经过真正的 shell，用于本地提示文本或屏幕切换提示。
     */
    public void appendBytesToScreen(byte[] data) {
        if (data == null || data.length == 0) return;
        dataStream.append(data, 0, data.length);
        dirty = true;
    }

    /**
     * 本地清空屏幕。不走 ANSI 字节解析，避免 ESC 被拆包后触发 emulator warning。
     */
    public void clearScreen() {
        clearScreen(true);
    }

    public void clearScreenCompletely() {
        clearScreen(false);
    }

    private void clearScreen(boolean preserveCurrentLine) {
        String promptLine = preserveCurrentLine ? promptLineTextForClear() : "";

        terminalTextBuffer.clearScreenAndHistoryBuffers();
        jediTerminal.cursorPosition(1, 1);
        if (!promptLine.isEmpty()) {
            jediTerminal.writeString(promptLine);
        }
        clearSelection();
        scrollToBottom();
        dirty = true;
        requestFocus();
    }

    /**
     * 停止当前读线程，清除流引用。不影响 renderTimer。
     */
    public void stopReader() {
        if (!running.get()) return;
        running.set(false);
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        try {
            if (remoteInput != null) remoteInput.close();
        } catch (IOException ignored) {
        }
        remoteInput = null;
    }

    public void setOnClose(Runnable cb) {
        this.onCloseCallback = cb;
    }

    public void setShutdownOnSceneDetach(boolean shutdownOnSceneDetach) {
        this.shutdownOnSceneDetach = shutdownOnSceneDetach;
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        boolean wasRunning = running.getAndSet(false);
        if (renderTimer != null) renderTimer.stop();
        if (readerThread != null) readerThread.interrupt();
        if (wasRunning && onCloseCallback != null) {
            Platform.runLater(onCloseCallback);
        }
    }

    /**
     * 把用户在 shell 未就绪期间的输入一次性 flush 到新 shell。
     */
    private void flushPendingInput() {
        byte[] pending;
        synchronized (pendingInput) {
            pending = pendingInput.toByteArray();
            pendingInput.reset();
        }
        if (pending.length == 0 || remoteOutput == null) return;
        try {
            remoteOutput.write(pending);
            remoteOutput.flush();
        } catch (IOException e) {
            LOGGER.warn("flush pending input failed", e);
        }
    }

    /**
     * 把字节直接喂给 emulator，作为 shell 未就绪期间的本地回显。
     */
    private void echoBytesLocally(byte[] data) {
        dataStream.append(data, 0, data.length);
        dirty = true;
    }

    private void appendRemoteOutput(byte[] data, int length) {
        if (length <= 0) return;

        byte[] normalized = normalizePromptLineBreak(data, length);
        dataStream.append(normalized, 0, normalized.length);
        dirty = true;
    }

    private byte[] normalizePromptLineBreak(byte[] data, int length) {
        String text = new String(data, 0, length, StandardCharsets.ISO_8859_1);
        updateTerminalModes(text);
        text = stripUnsupportedPrivateModes(text);
        StringBuilder out = new StringBuilder(text.length() + 2);

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                outputColumn = 0;
                out.append(c);
                continue;
            }

            if (c == '\b') {
                outputColumn = Math.max(0, outputColumn - 1);
                out.append(c);
                continue;
            }

            if (outputColumn > 0 && c == '[') {
                int end = findPromptEnd(text, i);
                if (end > i && PROMPT_PATTERN.matcher(text.substring(i, end)).matches()) {
                    // 检查提示符之前(包括上一批次)是否有换行符,没有才加
                    if (!hasNewlineBefore(i, text)) {
                        out.append('\r').append('\n');
                    }
                    outputColumn = 0;
                }
            }

            out.append(c);
            if (c >= 0x20 && c != 0x7F) {
                outputColumn++;
            }
        }

        // 更新最近输出记录,用于跨批次检测
        recentOutput.append(out);
        if (recentOutput.length() > 200) {
            recentOutput.delete(0, recentOutput.length() - 200);
        }

        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 仅去掉当前实现明确不支持、且不影响 Vim/less/top 等全屏程序的私有模式。
     * 不要吞掉通用 ESC[? ... h/l；Vim 依赖其中的 ?1、?25、?1049 等模式。
     */
    private String stripUnsupportedPrivateModes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (text.startsWith("\u001b[?1034h", i) || text.startsWith("\u001b[?1034l", i)) {
                i += "\u001b[?1034h".length();
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private void updateTerminalModes(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String scan = pendingModeScanTail + text;
        for (int i = 0; i < scan.length(); i++) {
            if (scan.charAt(i) != '\u001b' || i + 3 >= scan.length()
                    || scan.charAt(i + 1) != '[' || scan.charAt(i + 2) != '?') {
                continue;
            }
            int end = i + 3;
            while (end < scan.length()) {
                char ch = scan.charAt(end);
                if (ch == 'h' || ch == 'l') {
                    applyPrivateModeSequence(scan.substring(i + 3, end), ch == 'h');
                    i = end;
                    break;
                }
                if (ch >= 0x40 && ch <= 0x7E) {
                    i = end;
                    break;
                }
                end++;
            }
            if (end >= scan.length()) {
                break;
            }
        }
        int keep = Math.min(scan.length(), 32);
        pendingModeScanTail = scan.substring(scan.length() - keep);
    }

    private void applyPrivateModeSequence(String parameters, boolean enabled) {
        if (parameters == null || parameters.isBlank()) {
            return;
        }
        for (String raw : parameters.split("[;:]")) {
            try {
                int mode = Integer.parseInt(raw.trim());
                switch (mode) {
                    case 1 -> applicationCursorKeys = enabled;
                    case 47, 1047, 1049 -> alternateScreenBuffer = enabled;
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private boolean hasNewlineBefore(int promptIndex, String text) {
        // 先去掉 ANSI 控制序列,再检查提示符前最后一个字符是否是换行符
        String stripped = stripAnsiSequences(text.substring(0, promptIndex));
        if (!stripped.isEmpty()) {
            char lastChar = stripped.charAt(stripped.length() - 1);
            return lastChar == '\r' || lastChar == '\n';
        }
        // 当前批次没有内容,检查上一批次末尾
        String recentStripped = stripAnsiSequences(recentOutput.toString());
        if (!recentStripped.isEmpty()) {
            char lastChar = recentStripped.charAt(recentStripped.length() - 1);
            return lastChar == '\r' || lastChar == '\n';
        }
        return false;
    }

    private String stripAnsiSequences(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (c == '\u001b') {
                int seqEnd = findAnsiSequenceEnd(text, i);
                if (seqEnd > i) {
                    i = seqEnd;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private int findAnsiSequenceEnd(String text, int escPos) {
        // 返回 ANSI 序列结束位置的后一个索引,不是完整序列则返回 escPos
        if (escPos + 1 >= text.length()) return escPos;
        char next = text.charAt(escPos + 1);
        if (next == '[') {
            // CSI 序列: ESC [ ... 结束字符 0x40-0x7E
            int i = escPos + 2;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c >= 0x40 && c <= 0x7E) {
                    return i + 1;
                }
                i++;
            }
        } else if (next == ']') {
            // OSC 序列: ESC ] ... BEL(0x07) 或 ESC \
            int i = escPos + 2;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '\u0007') {
                    return i + 1;
                }
                if (c == '\u001b' && i + 1 < text.length() && text.charAt(i + 1) == '\\') {
                    return i + 2;
                }
                i++;
            }
        } else if (next >= 0x40 && next <= 0x5F) {
            // 其他标准 ESC 序列,两个字符
            return escPos + 2;
        }
        // 非标准序列,不剥离
        return escPos;
    }

    private int findPromptEnd(String text, int start) {
        int max = Math.min(text.length(), start + 160);
        for (int i = start; i < max; i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                return i;
            }
            if ((c == '#' || c == '$') && i + 1 < max && text.charAt(i + 1) == ' ') {
                return i + 2;
            }
        }
        return -1;
    }

    private void startReader(InputStream in) {
        readerThread = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                while (running.get()) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n > 0) {
                        appendRemoteOutput(buf, n);
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    Platform.runLater(() -> writeString("\n[连接错误: " + msg + "]\n"));
                }
            } finally {
                if (running.compareAndSet(true, false)) {
                    if (onCloseCallback != null) {
                        Platform.runLater(onCloseCallback);
                    }
                }
            }
        }, "JediTermFx-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    // ========================================================
    //  主循环：解码 → 解析 → 渲染
    // ========================================================

    private void tick() {
        // 1. UTF-8 解码字节为 char
        dataStream.pump();

        // 2. 让 JediEmulator 解析 char，更新 TerminalTextBuffer
        try {
            while (emulator.hasNext()) {
                emulator.next();
            }
        } catch (IOException ignore) {
            // 流空了，下次再读
        } catch (Exception ex) {
            LOGGER.error("Emulator error: {}", ex.getMessage());
        }
        dataStream.trimBuffers();

        updateSelectionAutoScroll();

        // 3. 渲染
        if (dirty) {
            // 历史行数变化时可能需要显示/隐藏滚动条,触发重新布局
            int history = effectiveHistoryLinesCount();
            if ((!alternateScreenBuffer && history > 0) != scrollBar.isVisible()) {
                resizeCanvas();
            }
            rebuildSearchMatchesIfNeeded(true);
            updateScrollBar();
            render();
            dirty = false;
        } else {
            // 即使内容没有变化，也要更新光标位置（用于闪烁）
            renderCursor();
        }

        // 4. 缓存当前光标的像素位置（供 IME native 线程读取）
        updateCursorLocationCache();

        // 5. 光标闪烁：持续重绘以产生闪烁效果
        dirty = true;
    }

    /**
     * 从预定义的等宽字体列表中选择一个系统上实际存在的字体返回。
     * JavaFX 的 Font.font(family, size) 对不存在的 family 会回退为默认字体，
     * 所以这里用 Font.getFamilies() 做一次存在性检查。
     */
    private static Font loadMonospaceFont(double size) {
        List<String> families = Font.getFamilies();
        Set<String> familySet = new HashSet<>(families);
        for (String family : MONOSPACE_FAMILIES) {
            if (familySet.contains(family)) {
                Font f = Font.font(family, size);
                if (f != null && !f.getFamily().equals("System")) {
                    return f;
                }
            }
        }
        Font f = Font.font("monospace", size);
        return f != null ? f : Font.font(size);
    }

    public double getTerminalFontSize() {
        return fontSize;
    }

    public int getColumns() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    public void setTerminalFontSize(double size) {
        double newSize = clamp(size, 6, 22);
        if (Math.abs(newSize - fontSize) < 0.01) {
            return;
        }
        fontSize = newSize;
        terminalFont = Font.font(terminalFont.getFamily(), fontSize);
        measureFont();
        resizeCanvas();
        dirty = true;
        requestFocus();
    }

    /**
     * 用一个 Text 节点精确测量字体的字符宽度和行高。
     * 在等宽字体中，中文字符是英文字符的两倍宽。
     * 我们用中文测量基准宽度（一个中文 = 一个格子的宽度）。
     */
    private void measureFont() {
        // 用中文"中"测量：在等宽字体中，一个中文应该占两个英文格子
        // 所以我们把 charWidth 设为中文宽度的一半，确保：
        // - 英文：占 1 * charWidth
        // - 中文：占 2 * charWidth（一个中文字符 + 一个 0xE000 标记）
        Text t = new Text("中");
        t.setFont(terminalFont);
        double chineseWidth = t.getLayoutBounds().getWidth();
        if (chineseWidth <= 0) {
            chineseWidth = fontSize * 1.2;
        }
        charWidth = chineseWidth / 2.0;

        // 行高：用 bounding box 的高度
        charHeight = Math.max(fontSize + 2,
                t.getLayoutBounds().getMaxY() - t.getLayoutBounds().getMinY());
    }

    private void resizeCanvas() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 根据是否有历史行决定是否显示滚动条
        int history = effectiveHistoryLinesCount();
        boolean showScrollBar = !alternateScreenBuffer && history > 0;
        scrollBar.setVisible(showScrollBar);
        scrollBar.setManaged(showScrollBar);

        double canvasW = showScrollBar ? Math.max(0, w - SCROLL_BAR_WIDTH) : w;
        canvas.setLayoutX(0);
        canvas.setLayoutY(0);
        canvas.setWidth(canvasW);
        canvas.setHeight(h);
        if (showScrollBar) {
            scrollBar.resizeRelocate(canvasW, 0, SCROLL_BAR_WIDTH, h);
            scrollBar.setPrefHeight(h);
        }

        int newCols = Math.max(20, (int) Math.floor((canvasW - CONTENT_PADDING_LEFT - CONTENT_PADDING_RIGHT) / charWidth));
        int newRows = Math.max(5, (int) Math.floor((h - CONTENT_PADDING_TOP - CONTENT_PADDING_BOTTOM) / charHeight));
        if (newCols != cols || newRows != rows) {
            try {
                jediTerminal.resize(new TermSize(newCols, newRows), RequestOrigin.User);
                cols = newCols;
                rows = newRows;
                notifyTerminalResize();
                dirty = true;
            } catch (Exception ignore) {
            }
        }
        updateScrollBar();
    }

    private void notifyTerminalResize() {
        if (onTerminalResize != null) {
            onTerminalResize.accept(cols, rows);
        }
    }

    // ========================================================
    //  渲染
    // ========================================================

    private void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        // 背景 - 确保即使 w/h 为 0 也能看到
        if (w <= 0) w = 800;
        if (h <= 0) h = 400;
        gc.setFill(terminalBackgroundColor());
        gc.fillRect(0, 0, w, h);

        gc.setFont(terminalFont);

        int firstLine = firstVisibleLine();
        int cursorScreenY = jediTerminal.getCursorY() - 1;

        // 确保 firstLine 在有效范围内
        int history = effectiveHistoryLinesCount();
        firstLine = Math.max(-history, Math.min(0, firstLine));

        boolean cursorVisible = firstLine == 0 && cursorScreenY >= 0 && cursorScreenY < rows;

        terminalTextBuffer.processHistoryAndScreenLines(firstLine, rows, new StyledTextConsumer() {
            @Override
            public void consume(int x, int y, @NotNull TextStyle style, @NotNull CharBuffer buf, int rowOffset) {
                int screenY = y - rowOffset;
                if (screenY >= 0 && screenY < rows && !buf.isEmpty()) {
                    drawText(gc, x, screenY, style, buf, buf.length());
                }
            }

            @Override
            public void consumeNul(int x, int y, int count, @NotNull TextStyle style, @NotNull CharBuffer buf, int rowOffset) {
                // NUL 区域：仅画背景
                int screenY = y - rowOffset;
                if (screenY < 0 || screenY >= rows) {
                    return;
                }
                if (style.getBackground() != null) {
                    Color bg = AnsiPalette.toFxColorSafe(style.getBackground());
                    if (bg != null) {
                        gc.setFill(bg);
                        gc.fillRect(pixelX(x), pixelY(screenY), Math.max(0, count) * charWidth, charHeight);
                    }
                }
            }

            @Override
            public void consumeQueue(int x, int y, int count, int n1) {
                // type-ahead 队列占位，忽略
            }
        });

        // 画光标
        if (cursorVisible) {
            renderCursor(gc);
        }
    }

    private void renderCursor() {
        // 不直接调用，因为需要先清除旧光标
        // 应该通过完整的 render() 方法来更新光标
        dirty = true;
    }

    private void renderCursor(GraphicsContext gc) {
        int cx = jediTerminal.getCursorX();
        int cy = jediTerminal.getCursorY();
        int displayCursorX = Math.max(0, cx - 1);
        int displayY = cy - 1;

        // 获取当前行的内容，检查光标位置是否正确
        String currentLine = "";
        if (displayY >= 0 && displayY < rows) {
            try {
                com.jediterm.terminal.model.TerminalLine line = terminalTextBuffer.getLine(displayY);
                currentLine = line.getText();
            } catch (Exception e) {
                // 忽略异常
            }
        }

        // 计算可见字符长度（排除行尾的 \r\n）
        int visibleLength = currentLine.length();
        if (currentLine.endsWith("\r\n")) {
            visibleLength -= 2;
        } else if (currentLine.endsWith("\n") || currentLine.endsWith("\r")) {
            visibleLength -= 1;
        }

        // 光标位置不能超过当前行可见字符的末尾
        int displayX = Math.min(displayCursorX, visibleLength);

        // 光标闪烁：350ms 半周期，根据相位决定是否绘制
        long elapsed = System.nanoTime() - cursorBlinkStartNanos;
        long halfPeriods = elapsed / CURSOR_BLINK_HALF_PERIOD_NS;
        boolean shouldDrawCursor = (halfPeriods % 2) == 0;

        if (shouldDrawCursor && displayY >= 0 && displayY < rows && jediTerminal.isModelEnabled(TerminalMode.CursorVisible)) {
            gc.setFill(terminalCursorColor());
            double cursorWidth = Math.max(1.5, Math.min(2.0, charWidth / 4.0));
            gc.fillRect(pixelX(displayX), pixelY(displayY) + 1, cursorWidth, charHeight - 2);
        }
    }

    private void drawText(GraphicsContext gc, int x, int y,
                          TextStyle style, CharBuffer buf, int count) {
        if (style == null) style = TextStyle.EMPTY;

        // 边界检查：保持原有逻辑，同时处理 count > buf.length() 的情况
        if (count <= 0) {
            count = buf.length();
            if (count <= 0) {
                return;
            }
        }
        // 修复 count > buf.length() 导致的越界问题
        if (count > buf.length()) {
            count = buf.length();
        }

        // 1. 背景 —— 整块按 charWidth 绘制
        if (style.getBackground() != null) {
            Color bg = AnsiPalette.toFxColorSafe(style.getBackground());
            if (bg != null) {
                gc.setFill(bg);
                gc.fillRect(pixelX(x), pixelY(y), count * charWidth, charHeight);
            }
        }

        // 2. 选中区覆盖
        drawSelectionBackground(gc, x, y, count);

        // 2.5. 搜索命中覆盖
        drawSearchBackground(gc, x, y, count);

        // 3. 前景色
        Color fg = terminalForegroundColor();
        if (style.getForeground() != null) {
            Color tempFg = AnsiPalette.toFxColorSafe(style.getForeground());
            if (tempFg != null) {
                fg = tempFg;
            }
        }
        gc.setFill(fg);

        // 4. 逐字符绘制 —— 每个字符严格对齐到 (x + i) * charWidth，
        //    保证光标（按 cx * charWidth 计算）与文字位置绝对一致。
        //
        //    JediTerm emulator 对双宽字符的处理方式：
        //    - 将一个双宽字符拆成两个位置写入缓冲区：[实际字符][0xE000]
        //    - 0xE000 是 Unicode Private Use Area，用作"双宽字符第二格"标记
        //    - 光标位置按两个格子计算（cx += 2）
        //
        //    我们的绘制逻辑：
        //    - 遇到 0xE000：跳过不画，但 drawn++（占一个格子宽度）
        //    - 遇到双宽字符：画出来，但 drawn++（只占一个格子，因为 0xE000 会占第二个）
        //    - 遇到普通字符：画出来，drawn++
        String text = buf.subBuffer(0, count).toString();
        double baselineY = pixelY(y) + charHeight - 3;
        int drawn = 0;
        for (int i = 0; i < text.length() && drawn < count; i++) {
            char c = text.charAt(i);

            // 0xE000 = JediTerm 双宽字符第二个位置的标记
            // 跳过不画，但必须占一个格子宽度（保证光标位置正确）
            if (c == 0xE000) {
                drawn++;
                continue;
            }

            // 跳过其他零宽/无效字符
            if (c == '\u0000' || c == '\uFFFE' || c == '\uFFFF' ||
                    Character.isLowSurrogate(c) || c == '\u200B' || c == '\uFEFF' || c == '\u00AD') {
                drawn++;
                continue;
            }

            double drawX = pixelX(x + drawn);
            gc.fillText(String.valueOf(c), drawX, baselineY);

            // 双宽字符：只算 1 个格子，因为 0xE000 会处理第二个格子
            // 普通字符：算 1 个格子
            drawn++;
        }
    }

    private double pixelX(int col) {
        return CONTENT_PADDING_LEFT + col * charWidth;
    }

    private double pixelY(int row) {
        return CONTENT_PADDING_TOP + row * charHeight;
    }

    private int firstVisibleLine() {
        int history = effectiveHistoryLinesCount();
        int max = Math.max(0, history);
        int value = (int) Math.round(clamp(scrollBar.getValue(), 0, max));
        return value - history;
    }

    private void updateScrollBar() {
        int history = effectiveHistoryLinesCount();
        double oldMax = scrollBar.getMax();
        boolean wasAtBottom = scrollBar.getValue() >= oldMax - 0.5;

        scrollBar.setMax(Math.max(0, history));
        double ratio = rows * 1.0 / (history + rows);
        double visibleAmount = history * ratio;
        scrollBar.setVisibleAmount(visibleAmount);
        scrollBar.setBlockIncrement(Math.max(1, rows - 1));

        if (wasAtBottom || history == 0) {
            if (preserveSearchScrollOnce) {
                preserveSearchScrollOnce = false;
                scrollBar.setValue(clamp(scrollBar.getValue(), scrollBar.getMin(), scrollBar.getMax()));
            } else {
                scrollToBottom();
            }
        } else {
            preserveSearchScrollOnce = false;
            scrollBar.setValue(clamp(scrollBar.getValue(), scrollBar.getMin(), scrollBar.getMax()));
        }
    }

    private void scrollToBottom() {
        scrollBar.setValue(scrollBar.getMax());
    }

    private int effectiveHistoryLinesCount() {
        return alternateScreenBuffer ? 0 : terminalTextBuffer.getHistoryLinesCount();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ========================================================
    //  TerminalDisplay 实现（JediTerm 回调）
    // ========================================================

    @Override
    public void setCursor(int x, int y) {
        dirty = true;
    }

    @Override
    public void setCursorShape(CursorShape shape) {
        // 不同光标形状，简单忽略
    }

    @Override
    public void beep() {
        // 可选：播放系统蜂鸣
    }

    @Override
    public void onResize(@NotNull TermSize termSize, @NotNull RequestOrigin origin) {
        dirty = true;
    }

    @Override
    public void scrollArea(int y1, int y2, int n) {
        dirty = true;
    }

    @Override
    public void setCursorVisible(boolean visible) {
        dirty = true;
    }

    @Override
    public void useAlternateScreenBuffer(boolean use) {
        alternateScreenBuffer = use;
        if (use) {
            scrollBar.setValue(0);
        } else {
            applicationCursorKeys = false;
        }
        dirty = true;
    }

    @Override
    public String getWindowTitle() {
        return "";
    }

    @Override
    public void setWindowTitle(@NotNull String title) {
        Platform.runLater(() -> {
            if (getScene() != null && getScene().getWindow() instanceof Stage) {
                ((Stage) getScene().getWindow()).setTitle(title);
            }
        });
    }

    @Override
    public TerminalSelection getSelection() {
        // 简单实现：返回空选择
        return null;
    }

    @Override
    public void terminalMouseModeSet(@NotNull MouseMode mode) {
        // 不实现鼠标协议
    }

    @Override
    public void setMouseFormat(@NotNull MouseFormat format) {
        // ignore
    }

    @Override
    public boolean ambiguousCharsAreDoubleWidth() {
        return true;
    }

    // ========================================================
    //  键盘输入编码
    // ========================================================

    private void handleKeyTyped(KeyEvent e) {
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty()) return;
        char c = ch.charAt(0);
        // 跳过控制字符（已经被 handleKeyPressed 处理）
        if (c < 0x20) {
            e.consume();
            return;
        }
        if (c == 0x7F) {  // DEL
            e.consume();
            return;
        }
        writeBytes(ch.getBytes(terminalCharset));
        e.consume();
    }

    private void handleKeyPressed(KeyEvent e) {
        if (e.isControlDown() && !e.isAltDown()) {
            if (e.getCode() == KeyCode.C && hasSelection()) {
                copySelectionToClipboard();
                e.consume();
                return;
            }
            if (e.getCode() == KeyCode.V) {
                pasteFromClipboard();
                e.consume();
                return;
            }
        }

        byte[] bytes = encodeKey(e);
        if (bytes != null) {
            writeBytes(bytes);
            e.consume();
        }
    }

    private byte[] encodeKey(KeyEvent e) {
        KeyCode code = e.getCode();
        boolean ctrl = e.isControlDown();
        boolean alt = e.isAltDown();

        if (ctrl) {
            byte[] controlBytes = controlKey(code);
            if (controlBytes != null) {
                return alt ? withEscPrefix(controlBytes) : controlBytes;
            }
        }

        int modifier = xtermModifier(e);
        byte[] specialBytes = switch (code) {
            case ENTER -> "\r".getBytes(StandardCharsets.US_ASCII);
            case BACK_SPACE -> backspaceBytes();
            case TAB -> e.isShiftDown() ? csi("Z") : new byte[]{0x09};
            case ESCAPE -> new byte[]{0x1B};
            case UP -> cursorKey("A", modifier);
            case DOWN -> cursorKey("B", modifier);
            case RIGHT -> cursorKey("C", modifier);
            case LEFT -> cursorKey("D", modifier);
            case HOME -> homeEndKey("H", modifier);
            case END -> homeEndKey("F", modifier);
            case INSERT -> tildeKey(2, modifier);
            case DELETE -> deleteBytes();
            case PAGE_UP -> tildeKey(5, modifier);
            case PAGE_DOWN -> tildeKey(6, modifier);
            case F1 -> functionKey("P", 0, modifier);
            case F2 -> functionKey("Q", 0, modifier);
            case F3 -> functionKey("R", 0, modifier);
            case F4 -> functionKey("S", 0, modifier);
            case F5 -> functionKey(null, 15, modifier);
            case F6 -> functionKey(null, 17, modifier);
            case F7 -> functionKey(null, 18, modifier);
            case F8 -> functionKey(null, 19, modifier);
            case F9 -> functionKey(null, 20, modifier);
            case F10 -> functionKey(null, 21, modifier);
            case F11 -> functionKey(null, 23, modifier);
            case F12 -> functionKey(null, 24, modifier);
            default -> null;
        };
        if (specialBytes != null) {
            return specialBytes;
        }

        if (alt) {
            // Alt+key uses Meta-as-escape, matching common xterm behavior.
            String ch = e.getText();
            if (ch != null && !ch.isEmpty()) {
                byte[] text = ch.getBytes(terminalCharset);
                byte[] result = new byte[text.length + 1];
                result[0] = 0x1B;
                System.arraycopy(text, 0, result, 1, text.length);
                return result;
            }
        }

        return null;
    }

    private byte[] backspaceBytes() {
        return switch (backspaceKeySequence) {
            case 1 -> new byte[]{0x08};
            case 2 -> "\u001b[3~".getBytes(StandardCharsets.US_ASCII);
            default -> new byte[]{0x7F};
        };
    }

    private byte[] deleteBytes() {
        return switch (deleteKeySequence) {
            case 1 -> new byte[]{0x7F};
            case 2 -> new byte[]{0x08};
            default -> "\u001b[3~".getBytes(StandardCharsets.US_ASCII);
        };
    }

    private byte[] controlKey(KeyCode code) {
        return switch (code) {
            case A -> new byte[]{0x01};
            case B -> new byte[]{0x02};
            case C -> new byte[]{0x03};
            case D -> new byte[]{0x04};
            case E -> new byte[]{0x05};
            case F -> new byte[]{0x06};
            case G -> new byte[]{0x07};
            case H -> new byte[]{0x08};
            case I -> new byte[]{0x09};
            case J -> new byte[]{0x0A};
            case K -> new byte[]{0x0B};
            case L -> new byte[]{0x0C};
            case M -> new byte[]{0x0D};
            case N -> new byte[]{0x0E};
            case O -> new byte[]{0x0F};
            case P -> new byte[]{0x10};
            case Q -> new byte[]{0x11};
            case R -> new byte[]{0x12};
            case S -> new byte[]{0x13};
            case T -> new byte[]{0x14};
            case U -> new byte[]{0x15};
            case V -> new byte[]{0x16};
            case W -> new byte[]{0x17};
            case X -> new byte[]{0x18};
            case Y -> new byte[]{0x19};
            case Z -> new byte[]{0x1A};
            case OPEN_BRACKET -> new byte[]{0x1B};
            case BACK_SLASH -> new byte[]{0x1C};
            case CLOSE_BRACKET -> new byte[]{0x1D};
            case DIGIT6 -> new byte[]{0x1E};
            case MINUS, SLASH -> new byte[]{0x1F};
            case SPACE, DIGIT2 -> new byte[]{0x00};
            default -> null;
        };
    }

    private int xtermModifier(KeyEvent e) {
        int modifier = 1;
        if (e.isShiftDown()) modifier += 1;
        if (e.isAltDown()) modifier += 2;
        if (e.isControlDown()) modifier += 4;
        return modifier;
    }

    private byte[] cursorKey(String finalByte, int modifier) {
        if (modifier > 1) {
            return csi("1;" + modifier + finalByte);
        }
        String prefix = applicationCursorKeys ? "\u001bO" : "\u001b[";
        return (prefix + finalByte).getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] homeEndKey(String finalByte, int modifier) {
        return modifier > 1 ? csi("1;" + modifier + finalByte) : csi(finalByte);
    }

    private byte[] tildeKey(int keyNumber, int modifier) {
        return modifier > 1 ? csi(keyNumber + ";" + modifier + "~") : csi(keyNumber + "~");
    }

    private byte[] functionKey(String ss3FinalByte, int tildeKeyNumber, int modifier) {
        if (ss3FinalByte != null) {
            return modifier > 1 ? csi("1;" + modifier + ss3FinalByte) : ("\u001bO" + ss3FinalByte).getBytes(StandardCharsets.UTF_8);
        }
        return tildeKey(tildeKeyNumber, modifier);
    }

    private byte[] csi(String sequence) {
        return ("\u001b[" + sequence).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] withEscPrefix(byte[] bytes) {
        byte[] out = new byte[bytes.length + 1];
        out[0] = 0x1B;
        System.arraycopy(bytes, 0, out, 1, bytes.length);
        return out;
    }

    // ========================================================
    //  鼠标选择
    // ========================================================

    private void handleMousePressed(javafx.scene.input.MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        requestFocus();
        int col = mouseColumn(e.getX());
        int line = mouseBufferLine(e.getY());

        if (e.getClickCount() >= 3) {
            selectLine(line);
            selecting = false;
        } else if (e.getClickCount() == 2) {
            selectWord(line, col);
            selecting = false;
        } else {
            selecting = true;
            selStartX = col;
            selStartY = line;
            selEndX = col;
            selEndY = line;
            selectionDragX = e.getX();
            selectionDragY = e.getY();
        }
        dirty = true;
    }

    private void handleMouseDragged(javafx.scene.input.MouseEvent e) {
        if (!selecting || !e.isPrimaryButtonDown()) return;
        selectionDragX = e.getX();
        selectionDragY = e.getY();
        updateSelectionEnd();
        dirty = true;
    }

    private void updateSelectionAutoScroll() {
        if (!selecting || Double.isNaN(selectionDragY)) {
            return;
        }
        int direction = selectionAutoScrollDirection();
        if (direction == 0) {
            return;
        }
        double value = scrollBar.getValue();
        double nextValue = clamp(value + direction * SELECTION_AUTO_SCROLL_STEP,
                scrollBar.getMin(), scrollBar.getMax());
        if (nextValue == value) {
            return;
        }
        scrollBar.setValue(nextValue);
        updateSelectionEnd();
        dirty = true;
    }

    private int selectionAutoScrollDirection() {
        if (selectionDragY < CONTENT_PADDING_TOP) {
            return -1;
        }
        if (selectionDragY >= canvas.getHeight() - CONTENT_PADDING_BOTTOM) {
            return 1;
        }
        return 0;
    }

    private void updateSelectionEnd() {
        selEndX = mouseColumn(selectionDragX);
        selEndY = mouseBufferLine(selectionDragY);
    }

    private void stopSelectionDrag() {
        selecting = false;
        selectionDragX = Double.NaN;
        selectionDragY = Double.NaN;
    }

    private void drawSelectionBackground(GraphicsContext gc, int x, int y, int count) {
        SelectionIntersection intersection = selectionIntersection(x, y, count);
        if (intersection == null) {
            return;
        }
        gc.setFill(terminalSelectionBackgroundColor());
        gc.fillRect(pixelX(intersection.startX), pixelY(y),
                (intersection.endX - intersection.startX) * charWidth, charHeight);
    }

    private void drawSearchBackground(GraphicsContext gc, int x, int y, int count) {
        if (searchMatches.isEmpty() || count <= 0) {
            return;
        }
        int bufferY = firstVisibleLine() + y;
        List<SearchMatch> matches = searchMatchesByLine.get(bufferY);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        int end = x + count;
        for (SearchMatch match : matches) {
            int from = Math.max(x, match.startCol);
            int to = Math.min(end, match.endCol);
            if (to <= from) {
                continue;
            }
            gc.setFill(match.index == currentSearchMatchIndex
                    ? terminalSearchCurrentBackgroundColor()
                    : terminalSearchMatchBackgroundColor());
            gc.fillRect(pixelX(from), pixelY(y), (to - from) * charWidth, charHeight);
        }
    }

    public void copySelectionToClipboard() {
        String text = getSelectedText();
        if (text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        requestFocus();
    }

    public void pasteFromClipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            writeString(clipboard.getString());
        }
        requestFocus();
    }

    public void setOnSearchResultChanged(Runnable callback) {
        this.onSearchResultChanged = callback;
    }

    public void setSearchQuery(String query) {
        String next = query == null ? "" : query.trim();
        if (next.equals(searchQuery)) {
            return;
        }
        searchQuery = next;
        searchDirty = true;
        rebuildSearchMatchesIfNeeded(false);
        scrollToCurrentSearchMatch();
        dirty = true;
        notifySearchResultChanged();
    }

    public void clearSearch() {
        if (searchQuery.isEmpty() && searchMatches.isEmpty()) {
            return;
        }
        searchQuery = "";
        searchDirty = false;
        searchMatches.clear();
        searchMatchesByLine.clear();
        currentSearchMatchIndex = -1;
        dirty = true;
        notifySearchResultChanged();
    }

    public void findNextSearchMatch() {
        rebuildSearchMatchesIfNeeded(false);
        if (searchMatches.isEmpty()) {
            return;
        }
        if (currentSearchMatchIndex < 0) {
            currentSearchMatchIndex = 0;
        } else {
            currentSearchMatchIndex = (currentSearchMatchIndex + 1) % searchMatches.size();
        }
        scrollToCurrentSearchMatch();
        dirty = true;
        notifySearchResultChanged();
    }

    public void findPreviousSearchMatch() {
        rebuildSearchMatchesIfNeeded(false);
        if (searchMatches.isEmpty()) {
            return;
        }
        if (currentSearchMatchIndex < 0) {
            currentSearchMatchIndex = 0;
        } else {
            currentSearchMatchIndex = (currentSearchMatchIndex - 1 + searchMatches.size()) % searchMatches.size();
        }
        scrollToCurrentSearchMatch();
        dirty = true;
        notifySearchResultChanged();
    }

    public int getSearchMatchCount() {
        rebuildSearchMatchesIfNeeded(false);
        return searchMatches.size();
    }

    public int getCurrentSearchMatchOrdinal() {
        rebuildSearchMatchesIfNeeded(false);
        return searchMatches.isEmpty() || currentSearchMatchIndex < 0 ? 0 : currentSearchMatchIndex + 1;
    }

    public String getSelectedText() {
        if (!hasSelection()) {
            return "";
        }
        SelectionRange range = normalizedSelection();
        StringBuilder text = new StringBuilder();
        for (int line = range.startY; line <= range.endY; line++) {
            int startX = line == range.startY ? range.startX : 0;
            int endX = line == range.endY ? range.endX : lineCellLength(line);
            if (endX > startX) {
                text.append(lineSlice(line, startX, endX));
            }
            if (line < range.endY && !terminalTextBuffer.getLine(line).isWrapped()) {
                text.append(System.lineSeparator());
            }
        }
        return text.toString();
    }

    private boolean hasSelection() {
        return selStartX >= 0 && selStartY != Integer.MIN_VALUE &&
                (selStartY != selEndY || selStartX != selEndX);
    }

    private SelectionRange normalizedSelection() {
        if (selStartY < selEndY || (selStartY == selEndY && selStartX <= selEndX)) {
            return new SelectionRange(selStartX, selStartY, selEndX, selEndY);
        }
        return new SelectionRange(selEndX, selEndY, selStartX, selStartY);
    }

    private SelectionIntersection selectionIntersection(int x, int screenY, int count) {
        if (!hasSelection() || count <= 0) return null;
        SelectionRange range = normalizedSelection();
        int bufferY = firstVisibleLine() + screenY;
        if (bufferY < range.startY || bufferY > range.endY) return null;

        int minX = (bufferY == range.startY) ? range.startX : 0;
        int maxX = (bufferY == range.endY) ? range.endX : Integer.MAX_VALUE;
        int start = Math.max(x, minX);
        int end = Math.min(x + count, maxX);
        if (end <= start) return null;
        return new SelectionIntersection(start, end);
    }

    private void notifySearchResultChanged() {
        if (onSearchResultChanged == null) {
            return;
        }
        try {
            onSearchResultChanged.run();
        } catch (Exception ignored) {
        }
    }

    private void rebuildSearchMatchesIfNeeded(boolean notify) {
        if (!searchDirty) {
            return;
        }
        searchDirty = false;
        searchMatches.clear();
        searchMatchesByLine.clear();
        currentSearchMatchIndex = -1;

        String query = searchQuery;
        if (query == null || query.isEmpty()) {
            if (notify) notifySearchResultChanged();
            return;
        }

        String needle = query.toLowerCase(Locale.ROOT);
        int history = effectiveHistoryLinesCount();
        int index = 0;
        for (int line = -history; line < rows; line++) {
            String text;
            try {
                text = plainLineText(line);
            } catch (Exception ignored) {
                continue;
            }
            if (text.isEmpty()) {
                continue;
            }
            String haystack = text.toLowerCase(Locale.ROOT);
            int from = 0;
            while (from <= haystack.length()) {
                int start = haystack.indexOf(needle, from);
                if (start < 0) {
                    break;
                }
                int end = start + needle.length();
                SearchMatch match = new SearchMatch(line, start, end, index++);
                searchMatches.add(match);
                searchMatchesByLine.computeIfAbsent(line, key -> new ArrayList<>()).add(match);
                from = Math.max(start + 1, end);
            }
        }

        if (!searchMatches.isEmpty()) {
            currentSearchMatchIndex = 0;
        }
        if (notify) {
            notifySearchResultChanged();
        }
    }

    private void scrollToCurrentSearchMatch() {
        if (searchMatches.isEmpty() || currentSearchMatchIndex < 0 || currentSearchMatchIndex >= searchMatches.size()) {
            return;
        }
        SearchMatch match = searchMatches.get(currentSearchMatchIndex);
        int history = effectiveHistoryLinesCount();
        int desiredTop = match.line - Math.max(0, rows / 2);
        double value = clamp(desiredTop + history, 0, Math.max(0, history));
        scrollBar.setValue(value);
        preserveSearchScrollOnce = true;
    }

    private int mouseColumn(double mouseX) {
        return Math.max(0, (int) Math.floor((mouseX - CONTENT_PADDING_LEFT) / charWidth));
    }

    private int mouseBufferLine(double mouseY) {
        int screenY = (int) Math.floor((mouseY - CONTENT_PADDING_TOP) / charHeight);
        screenY = Math.max(0, Math.min(rows - 1, screenY));
        return firstVisibleLine() + screenY;
    }

    private void selectLine(int line) {
        selStartX = 0;
        selStartY = line;
        selEndX = lineCellLength(line);
        selEndY = line;
    }

    private void selectWord(int line, int col) {
        String text = plainLineText(line);
        if (text.isEmpty()) {
            clearSelection();
            return;
        }
        int target = Math.max(0, Math.min(col, Math.max(0, text.length() - 1)));
        if (!isWordChar(text.charAt(target)) && target > 0 && isWordChar(text.charAt(target - 1))) {
            target--;
        }
        if (!isWordChar(text.charAt(target))) {
            selStartX = target;
            selEndX = target + 1;
            selStartY = line;
            selEndY = line;
            return;
        }
        int start = target;
        int end = target + 1;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        selStartX = start;
        selEndX = end;
        selStartY = line;
        selEndY = line;
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == ':';
    }

    private String promptLineTextForClear() {
        int cursorLine = Math.max(0, Math.min(rows - 1, jediTerminal.getCursorY() - 1));
        String line = plainLineText(cursorLine);
        String prompt = extractPrompt(line);
        if (!prompt.isEmpty()) {
            return prompt;
        }
        String fallback = line;
        for (int y = cursorLine - 1; y >= 0; y--) {
            line = plainLineText(y);
            prompt = extractPrompt(line);
            if (!prompt.isEmpty()) {
                return prompt;
            }
            if (fallback.isEmpty() && !line.isEmpty()) {
                fallback = line;
            }
        }
        return fallback;
    }

    private String extractPrompt(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher matcher = PROMPT_EXTRACT_PATTERN.matcher(line);
        String prompt = "";
        while (matcher.find()) {
            prompt = matcher.group();
        }
        return prompt;
    }

    private String plainLineText(int line) {
        return stripTerminalMarkers(terminalTextBuffer.getLine(line).getText());
    }

    private int lineCellLength(int line) {
        String text = terminalTextBuffer.getLine(line).getText();
        int cells = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isInvalidTerminalChar(c)) {
                continue;
            }
            cells++;
        }
        return cells;
    }

    private String lineSlice(int line, int startCol, int endCol) {
        String text = terminalTextBuffer.getLine(line).getText();
        StringBuilder out = new StringBuilder();
        int cell = 0;
        for (int i = 0; i < text.length() && cell < endCol; i++) {
            char c = text.charAt(i);
            if (isInvalidTerminalChar(c)) {
                continue;
            }
            if (cell >= startCol && c != 0xE000) {
                out.append(c);
            }
            cell++;
        }
        return out.toString();
    }

    private String stripTerminalMarkers(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0xE000 || isInvalidTerminalChar(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private boolean isInvalidTerminalChar(char c) {
        return c == '\u0000' || c == '\uFFFE' || c == '\uFFFF' ||
                c == '\u200B' || c == '\uFEFF' || c == '\u00AD' ||
                Character.isLowSurrogate(c);
    }

    private void clearSelection() {
        selStartX = -1;
        selStartY = -1;
        selEndX = -1;
        selEndY = -1;
    }

    private record SearchMatch(int line, int startCol, int endCol, int index) {
    }

    private record SelectionRange(int startX, int startY, int endX, int endY) {
    }

    private record SelectionIntersection(int startX, int endX) {
    }

    /**
     * 缓存当前光标在屏幕坐标系中的像素位置。
     * 在 FX 线程（tick 循环）中计算并缓存，IME native 线程直接读这个 volatile 缓存。
     * 同时调用 Windows IMM32 API 设置输入法候选框位置。
     */
    private void updateCursorLocationCache() {
        try {
            if (getScene() == null || !isFocused()) {
                return;
            }
            int cx = jediTerminal.getCursorX();
            int cy = jediTerminal.getCursorY();
            int displayCursorX = Math.max(0, cx - 1);
            int displayY = cy - 1;

            // 与 renderCursor 保持完全一致的逻辑
            // 获取当前行的内容，计算可见字符长度
            int displayX = displayCursorX;
            if (displayY >= 0 && displayY < rows) {
                try {
                    TerminalLine line = terminalTextBuffer.getLine(displayY);
                    String currentLine = line.getText();
                    int visibleLength = currentLine.length();
                    if (currentLine.endsWith("\r\n")) {
                        visibleLength -= 2;
                    } else if (currentLine.endsWith("\n") || currentLine.endsWith("\r")) {
                        visibleLength -= 1;
                    }
                    displayX = Math.min(displayCursorX, visibleLength);
                } catch (Exception e) {
                    // 忽略异常，使用原始 cx
                }
            }

            // 光标位置计算（与 renderCursor 一致）
            double localX = pixelX(displayX);
            double localY = pixelY(displayY) + charHeight;

            // 转换为屏幕坐标
            Point2D scene = localToScene(localX, localY);
            double screenX = scene.getX();
            double screenY = scene.getY();

            // 考虑 DPI 缩放：localToScene 返回的是逻辑像素，需要乘以 outputScale 得到物理像素
            double outputScaleX = 1.0;
            double outputScaleY = 1.0;
            if (getScene() != null && getScene().getWindow() != null) {
                outputScaleX = getScene().getWindow().getOutputScaleX();
                outputScaleY = getScene().getWindow().getOutputScaleY();
            }

            // 调用 Windows IMM32 API 设置输入法候选框位置（需要物理像素坐标）
            if (com.sun.jna.Platform.isWindows()) {
                Imm32.setCompositionWindowPosition(
                        (int) (screenX * outputScaleX),
                        (int) (screenY * outputScaleY)
                );
            }
        } catch (Exception e) {
            LOGGER.error("IME cache update failed: {}", e.getMessage());
        }
    }
}

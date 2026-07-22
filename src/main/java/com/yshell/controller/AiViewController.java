package com.yshell.controller;

import com.yshell.config.AppConfig;
import com.yshell.config.AppSettings;
import com.yshell.model.ai.AiChatMessage;
import com.yshell.model.ai.AiConversation;
import com.yshell.model.ai.AiImageAttachment;
import com.yshell.service.AiChatService;
import com.yshell.service.AiConversationRepository;
import com.yshell.service.ConnectionManager;
import com.yshell.terminal.Imm32;
import com.yshell.theme.ThemeManager;
import com.yshell.ui.ApplicationIcons;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.NativeMarkdownView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public class AiViewController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiViewController.class);
    private static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AppSettings settings = AppSettings.getInstance();
    private final AiConversationRepository repository = AiConversationRepository.getInstance();
    private final ObservableList<AiConversation> histories = FXCollections.observableArrayList();
    private final List<AiImageAttachment> pendingImages = new ArrayList<>();

    private AiConversation currentConversation;
    private NativeMarkdownView activeAssistantView;
    private NativeMarkdownView activeThinkingView;
    private VBox activeThinkingBox;
    private AiChatMessage activeAssistantMessage;
    private AiChatService.ChatRequestHandle activeRequest;
    private long activeRequestId;
    private boolean sending;
    private boolean modelListenerInstalled;

    @FXML
    private ListView<AiConversation> historyList;
    @FXML
    private ComboBox<AppConfig.AiModelConnection> aiModelSelector;
    @FXML
    private ScrollPane chatScroll;
    @FXML
    private VBox chatMessages;
    @FXML
    private TextArea inputArea;
    @FXML
    private FlowPane attachmentBar;
    @FXML
    private CheckBox streamToggle;
    @FXML
    private ComboBox<String> thinkingModeSelector;
    @FXML
    private Button btnSend;
    @FXML
    private Button btnUploadImage;
    @FXML
    private Label statusLabel;

    @FXML
    public void initialize() {
        setupHistoryList();
        refreshModelOptions();
        streamToggle.setSelected(true);
        streamToggle.setTooltip(new Tooltip("四种接口格式均提供流式调用，但具体模型或兼容服务可能不支持。"));
        refreshThinkingModes(selectedConnection());
        refreshImageInputAvailability(selectedConnection(), false);
        configureInputAreaIme();
        configureInputPromptFont();
        inputArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleInputKeyPressed);
        reloadHistories();
        if (histories.isEmpty()) {
            currentConversation = repository.create();
            reloadHistories();
        } else {
            currentConversation = histories.get(0);
        }
        historyList.getSelectionModel().select(currentConversation);
        renderConversation(currentConversation);
        refreshAttachments();
    }

    @FXML
    public void newTopic() {
        if (sending) {
            cancelCurrentResponse();
        }
        currentConversation = repository.create();
        pendingImages.clear();
        inputArea.clear();
        reloadHistories();
        historyList.getSelectionModel().select(currentConversation);
        renderConversation(currentConversation);
        refreshAttachments();
        setStatus("新话题");
    }

    @FXML
    public void openAiSettings() {
        Scene scene = inputArea.getScene();
        if (scene == null) {
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsManager.fxml"));
            Parent dialogRoot = loader.load();
            SettingsManagerController controller = loader.getController();
            Stage dialogStage = new Stage();
            ApplicationIcons.applyTo(dialogStage);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.setTitle("设置");

            double dialogWidth = 760;
            double dialogHeight = 560;
            Scene dialogScene = new Scene(dialogRoot, dialogWidth, dialogHeight);
            ThemeManager.getInstance().registerScene(dialogScene);
            dialogStage.setScene(dialogScene);
            controller.setDialogStage(dialogStage);
            controller.selectAiSettings();

            Stage mainStage = (Stage) scene.getWindow();
            dialogStage.setY(mainStage.getY() + (mainStage.getHeight() - dialogHeight) / 2);
            dialogStage.setX(mainStage.getX() + (mainStage.getWidth() - dialogWidth) / 2);
            dialogStage.showAndWait();
            ThemeManager.getInstance().unregisterScene(dialogScene);
            refreshModelOptions();
            refreshThinkingModes(selectedConnection());
            refreshImageInputAvailability(selectedConnection(), true);
        } catch (Exception e) {
            LOGGER.error("open AI settings failed", e);
            DialogHelper.showError("设置", "无法打开 AI 设置：" + e.getMessage());
        }
    }

    @FXML
    public void uploadImage() {
        if (ensureImageInputSupported()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "图片文件", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.gif", "*.bmp"));
        List<File> files = chooser.showOpenMultipleDialog(inputArea.getScene().getWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        for (File file : files) {
            addImageFile(file);
        }
        refreshAttachments();
    }

    @FXML
    public void sendMessage() {
        if (sending) {
            cancelCurrentResponse();
            return;
        }
        String text = inputArea.getText() == null ? "" : inputArea.getText().trim();
        if (text.isBlank() && pendingImages.isEmpty()) {
            return;
        }
        if (currentConversation == null) {
            currentConversation = repository.create();
        }
        AppConfig.AiModelConnection connection = selectedConnection();
        if (!pendingImages.isEmpty() && !connection.imageInputSupported) {
            showImageInputUnsupported();
            return;
        }
        String modelLabel = settings.formatAiConnection(connection);
        AiChatMessage userMessage = AiChatService.getInstance().newUserMessage(text, modelLabel, pendingImages);
        currentConversation.messages.add(userMessage);
        currentConversation.title = titleForConversation(currentConversation, text);
        currentConversation.touch();
        repository.upsert(currentConversation);
        reloadHistories();
        historyList.getSelectionModel().select(currentConversation);
        renderMessage(userMessage);

        inputArea.clear();
        pendingImages.clear();
        refreshAttachments();
        setSending(true);
        setStatus("回答中...");

        AiChatMessage assistantMessage = AiChatService.getInstance().newAssistantMessage("", "", modelLabel);
        activeAssistantMessage = assistantMessage;
        renderAssistantPlaceholder(assistantMessage);
        String connId = ConnectionManager.getInstance().getCurrentConnectionId();
        long requestId = ++activeRequestId;
        AiChatService.ChatRequestHandle request = AiChatService.getInstance().chat(
                currentConversation,
                userMessage,
                connId,
                connection,
                streamToggle.isSelected(),
                selectedThinkingMode(),
                new AiChatService.ResponseCallback() {
                    @Override
                    public void onPartial(String value) {
                        runForActiveRequest(requestId, () -> appendToMarkdownView(activeAssistantView, value));
                    }

                    @Override
                    public void onThinking(String value) {
                        runForActiveRequest(requestId, () -> appendToMarkdownView(activeThinkingView, value));
                    }

                    @Override
                    public void onComplete(String value, String thinking) {
                        runForActiveRequest(requestId, () -> completeAssistant(assistantMessage, value, thinking));
                    }

                    @Override
                    public void onError(Throwable error) {
                        runForActiveRequest(requestId, () -> failAssistant(assistantMessage, error));
                    }
                });
        if (isActiveRequest(requestId)) {
            activeRequest = request;
        } else {
            request.cancel();
        }
    }

    private void setupHistoryList() {
        historyList.setItems(histories);
        historyList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(AiConversation item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label title = new Label(item.title == null ? "新话题" : item.title);
                title.getStyleClass().add("history-title");
                Instant updatedAt = item.updatedInstant();
                Label time = new Label(updatedAt == null ? "" : HISTORY_TIME_FORMAT.format(updatedAt));
                time.getStyleClass().add("history-time");
                VBox textBox = new VBox(title, time);
                textBox.setSpacing(2);
                HBox.setHgrow(textBox, Priority.ALWAYS);
                Button delete = new Button();
                delete.getStyleClass().addAll("button-cancel", "history-delete");
                delete.setGraphic(new FontIcon("fas-trash-alt"));
                delete.setOnAction(event -> {
                    event.consume();
                    deleteConversation(item);
                });
                HBox row = new HBox(textBox, delete);
                row.getStyleClass().add("history-cell");
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        historyList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && selected != currentConversation) {
                currentConversation = selected;
                renderConversation(selected);
            }
        });
    }

    private void reloadHistories() {
        histories.setAll(repository.list());
    }

    private void deleteConversation(AiConversation conversation) {
        if (conversation == null) {
            return;
        }
        repository.delete(conversation.id);
        reloadHistories();
        if (histories.isEmpty()) {
            currentConversation = repository.create();
            reloadHistories();
        } else if (currentConversation != null && currentConversation.id.equals(conversation.id)) {
            currentConversation = histories.get(0);
        }
        historyList.getSelectionModel().select(currentConversation);
        renderConversation(currentConversation);
    }

    private void renderConversation(AiConversation conversation) {
        chatMessages.getChildren().clear();
        if (conversation != null && conversation.messages != null) {
            for (AiChatMessage message : conversation.messages) {
                renderMessage(message);
            }
        }
        scrollToBottom();
    }

    private void renderMessage(AiChatMessage message) {
        Node row = createMessageRow(message, false);
        chatMessages.getChildren().add(row);
        scrollToBottom();
    }

    private void renderAssistantPlaceholder(AiChatMessage message) {
        Node row = createMessageRow(message, true);
        List<NativeMarkdownView> views = new ArrayList<>();
        if (row instanceof Pane pane) {
            collectMarkdownViews(pane, views);
        }
        if (views.size() >= 2) {
            activeThinkingView = views.get(0);
            activeAssistantView = views.get(1);
        } else {
            activeThinkingView = null;
            activeAssistantView = views.isEmpty() ? null : views.get(0);
        }
        activeThinkingBox = activeThinkingView != null && activeThinkingView.getParent() instanceof VBox box ? box : null;
        if (activeAssistantView != null) {
            activeAssistantView.setOnRendered(this::scrollToBottom);
        }
        if (activeThinkingView != null) {
            activeThinkingView.setOnRendered(this::scrollToBottom);
        }
        chatMessages.getChildren().add(row);
        scrollToBottom();
    }

    private Node createMessageRow(AiChatMessage message, boolean streamingAssistant) {
        boolean user = "user".equalsIgnoreCase(message.role);
        HBox row = new HBox();
        row.getStyleClass().add("message-row");
        row.setMaxWidth(Double.MAX_VALUE);
        if (user) {
            row.getStyleClass().add("user");
        }
        row.setAlignment(user ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        VBox messageBox = new VBox();
        messageBox.getStyleClass().add("message-bubble");
        if (user) {
            messageBox.getStyleClass().add("user");
            messageBox.setMaxWidth(760);
            messageBox.setAlignment(Pos.TOP_RIGHT);
        } else {
            messageBox.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(messageBox, Priority.ALWAYS);
        }

        HBox header = new HBox();
        header.getStyleClass().add("message-header");
        Label author = new Label(user ? "你" : "AI");
        author.getStyleClass().add("message-author");
        if (user) {
            Region headerSpacer = new Region();
            HBox.setHgrow(headerSpacer, Priority.ALWAYS);
            header.getChildren().addAll(headerSpacer, author);
        } else {
            Label meta = new Label(message.model == null || message.model.isBlank() ? "" : message.model);
            meta.getStyleClass().add("message-meta");
            Button copyMarkdown = new Button();
            copyMarkdown.getStyleClass().addAll("button-cancel", "message-copy-markdown");
            copyMarkdown.setGraphic(new FontIcon("fas-copy"));
            copyMarkdown.setTooltip(new Tooltip("复制完整 Markdown"));
            copyMarkdown.setOnAction(event -> copyToClipboard(message.content));
            header.getChildren().addAll(author, meta, copyMarkdown);
        }

        messageBox.getChildren().add(header);
        if (!user && message.thinking != null && !message.thinking.isBlank()) {
            messageBox.getChildren().add(createThinkingBox(message.thinking));
        } else if (!user && streamingAssistant && isThinkingEnabled()) {
            messageBox.getChildren().add(createThinkingBox(""));
        }
        if (user) {
            messageBox.getChildren().add(createMarkdownView(message.content, true));
        } else if (streamingAssistant) {
            messageBox.getChildren().add(createMarkdownView(message.content));
        } else {
            messageBox.getChildren().add(createMarkdownView(message.content));
        }
        if (user && message.images != null && !message.images.isEmpty()) {
            Label images = new Label("已附加图片 " + message.images.size() + " 张");
            images.getStyleClass().add("attachment-note");
            messageBox.getChildren().add(images);
        }
        if (user) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().addAll(spacer, messageBox);
        } else {
            row.getChildren().add(messageBox);
        }
        return row;
    }

    private NativeMarkdownView createMarkdownView(String markdown) {
        return createMarkdownView(markdown, false);
    }

    private NativeMarkdownView createMarkdownView(String markdown, boolean rightAligned) {
        NativeMarkdownView view = new NativeMarkdownView(markdown, rightAligned, this::executeCommand);
        view.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(view, Priority.NEVER);
        return view;
    }

    private VBox createThinkingBox(String text) {
        VBox box = new VBox();
        box.getStyleClass().add("thinking-box");
        Label title = new Label("思考");
        title.getStyleClass().add("thinking-title");
        box.getChildren().addAll(title, createMarkdownView(text));
        return box;
    }

    private void executeCommand(String command) {
        if (command.isBlank()) {
            return;
        }
        String connId = ConnectionManager.getInstance().getCurrentConnectionId();
        TerminalPanelController terminalPanel = ConnectionManager.getInstance().getTerminalPanelController(connId);
        if (terminalPanel == null || !terminalPanel.executeShellCommand(command)) {
            DialogHelper.showWarning("执行命令", "当前没有可用的交互终端");
            return;
        }
        setStatus("已发送到终端");
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void collectMarkdownViews(Pane parent, List<NativeMarkdownView> views) {
        for (javafx.scene.Node child : parent.getChildren()) {
            if (child instanceof NativeMarkdownView markdownView) {
                views.add(markdownView);
            } else if (child instanceof Pane pane) {
                collectMarkdownViews(pane, views);
            }
        }
    }

    private void appendToMarkdownView(NativeMarkdownView view, String value) {
        if (view == null || value == null || value.isEmpty()) {
            return;
        }
        view.appendMarkdown(value);
    }

    private void completeAssistant(AiChatMessage assistantMessage, String value, String thinking) {
        finishAssistant(assistantMessage, value, thinking, "完成");
    }

    private void failAssistant(AiChatMessage assistantMessage, Throwable error) {
        String message = error == null || error.getMessage() == null ? "请求失败" : error.getMessage();
        finishAssistant(assistantMessage, "请求失败：" + message, "", "请求失败");
    }

    private void cancelCurrentResponse() {
        if (!sending) {
            return;
        }
        activeRequestId++;
        if (activeRequest != null) {
            activeRequest.cancel();
        }
        String content = activeAssistantView == null ? "" : activeAssistantView.getMarkdown();
        String thinking = activeThinkingView == null ? "" : activeThinkingView.getMarkdown();
        content = content == null || content.isBlank()
                ? "_回答已终止。_"
                : content + "\n\n> 回答已终止";
        if (activeAssistantMessage != null) {
            finishAssistant(activeAssistantMessage, content, thinking, "已终止");
        } else {
            clearActiveRequest();
            setSending(false);
            setStatus("已终止");
        }
    }

    private void finishAssistant(AiChatMessage assistantMessage, String value, String thinking, String status) {
        assistantMessage.content = value == null ? "" : value;
        assistantMessage.thinking = thinking == null ? "" : thinking;
        completeAssistantView(assistantMessage.content);
        completeThinkingView(assistantMessage.thinking);
        currentConversation.messages.add(assistantMessage);
        currentConversation.touch();
        repository.upsert(currentConversation);
        reloadHistories();
        historyList.getSelectionModel().select(currentConversation);
        clearActiveRequest();
        setSending(false);
        setStatus(status);
        scrollToBottom();
    }

    private void completeAssistantView(String markdown) {
        if (activeAssistantView != null) {
            activeAssistantView.completeMarkdown(markdown);
        }
    }

    private void completeThinkingView(String thinking) {
        if (activeThinkingView == null) {
            return;
        }
        if (thinking == null || thinking.isBlank()) {
            if (activeThinkingBox != null && activeThinkingBox.getParent() instanceof Pane parent) {
                parent.getChildren().remove(activeThinkingBox);
            }
            return;
        }
        activeThinkingView.completeMarkdown(thinking);
    }

    private void clearActiveRequest() {
        activeRequest = null;
        activeAssistantMessage = null;
        activeAssistantView = null;
        activeThinkingView = null;
        activeThinkingBox = null;
    }

    private void runForActiveRequest(long requestId, Runnable action) {
        if (!isActiveRequest(requestId)) {
            return;
        }
        Platform.runLater(() -> {
            if (isActiveRequest(requestId)) {
                action.run();
            }
        });
    }

    private boolean isActiveRequest(long requestId) {
        return sending && activeRequestId == requestId;
    }

    private void handleInputKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            if (event.isControlDown() || event.isMetaDown()) {
                event.consume();
                inputArea.replaceSelection("\n");
            } else if (!event.isShiftDown() && !event.isAltDown()) {
                event.consume();
                sendMessage();
            }
            return;
        }
        if ((event.isControlDown() || event.isMetaDown()) && event.getCode() == KeyCode.V) {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (clipboard.hasImage()) {
                event.consume();
                if (ensureImageInputSupported()) {
                    return;
                }
                addClipboardImage(clipboard.getImage());
                refreshAttachments();
            } else if (clipboard.hasFiles()) {
                if (clipboardFilesContainImage(clipboard.getFiles()) && ensureImageInputSupported()) {
                    event.consume();
                    return;
                }
                boolean added = false;
                for (File file : clipboard.getFiles()) {
                    added |= addImageFile(file);
                }
                if (added) {
                    event.consume();
                    refreshAttachments();
                }
            }
        }
    }

    private void configureInputAreaIme() {
        inputArea.focusedProperty().addListener((obs, oldValue, focused) -> {
            if (focused) {
                Platform.runLater(this::updateInputAreaImePosition);
            }
        });
        inputArea.caretPositionProperty().addListener((obs, oldValue, value) ->
                Platform.runLater(this::updateInputAreaImePosition));
        inputArea.setOnMouseClicked(event -> Platform.runLater(this::updateInputAreaImePosition));
        inputArea.addEventHandler(KeyEvent.KEY_PRESSED,
                event -> Platform.runLater(this::updateInputAreaImePosition));
        inputArea.addEventHandler(KeyEvent.KEY_RELEASED,
                event -> Platform.runLater(this::updateInputAreaImePosition));
        inputArea.addEventHandler(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                event -> Platform.runLater(this::updateInputAreaImePosition));
    }

    private void configureInputPromptFont() {
        inputArea.sceneProperty().addListener((obs, old, scene) -> scheduleInputPromptFontUpdate());
        inputArea.skinProperty().addListener((obs, old, skin) -> scheduleInputPromptFontUpdate());
        inputArea.textProperty().addListener((obs, old, text) -> {
            if (text == null || text.isEmpty()) {
                scheduleInputPromptFontUpdate();
            }
        });
        scheduleInputPromptFontUpdate();
    }

    private void scheduleInputPromptFontUpdate() {
        Platform.runLater(this::applyInputPromptFont);
    }

    private void applyInputPromptFont() {
        if (inputArea.getPromptText() == null || inputArea.getPromptText().isBlank()
                || (inputArea.getText() != null && !inputArea.getText().isEmpty())) {
            return;
        }
        Font inputFont = inputArea.getFont();
        for (Node node : inputArea.lookupAll(".text")) {
            if (!(node instanceof Text prompt) || !inputArea.getPromptText().equals(prompt.getText())) {
                continue;
            }
            prompt.fontProperty().unbind();
            prompt.setFont(Font.font(inputFont.getFamily(), 11));
            return;
        }
    }

    private void updateInputAreaImePosition() {
        if (!com.sun.jna.Platform.isWindows()
                || inputArea == null
                || inputArea.getScene() == null
                || !inputArea.isFocused()) {
            return;
        }
        Point2D screenPoint = inputAreaCaretScreenPoint();
        if (screenPoint == null) {
            screenPoint = inputArea.localToScreen(12, 28);
        }
        if (screenPoint == null) {
            return;
        }
        double scaleX = inputArea.getScene().getWindow() == null
                ? 1.0
                : inputArea.getScene().getWindow().getOutputScaleX();
        double scaleY = inputArea.getScene().getWindow() == null
                ? 1.0
                : inputArea.getScene().getWindow().getOutputScaleY();
        Imm32.setCompositionWindowPosition(
                (int) (screenPoint.getX() * scaleX),
                (int) (screenPoint.getY() * scaleY)
        );
    }

    private Point2D inputAreaCaretScreenPoint() {
        Node caret = inputArea.lookup(".caret");
        if (caret == null) {
            return null;
        }
        Bounds bounds = caret.localToScreen(caret.getBoundsInLocal());
        if (bounds == null) {
            return null;
        }
        return new Point2D(bounds.getMinX(), bounds.getMaxY());
    }

    private boolean addImageFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            String mimeType = Files.probeContentType(file.toPath());
            if (mimeType == null || !mimeType.startsWith("image/")) {
                return false;
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            pendingImages.add(new AiImageAttachment(
                    UUID.randomUUID().toString(),
                    file.getName(),
                    mimeType,
                    Base64.getEncoder().encodeToString(bytes)));
            return true;
        } catch (Exception e) {
            LOGGER.warn("add image file failed: {}", file, e);
            return false;
        }
    }

    private boolean clipboardFilesContainImage(List<File> files) {
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            try {
                String mimeType = Files.probeContentType(file.toPath());
                if (mimeType != null && mimeType.startsWith("image/")) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void addClipboardImage(Image image) {
        if (image == null) {
            return;
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(toBufferedImage(image), "png", out);
            pendingImages.add(new AiImageAttachment(
                    UUID.randomUUID().toString(),
                    "clipboard.png",
                    "image/png",
                    Base64.getEncoder().encodeToString(out.toByteArray())));
        } catch (Exception e) {
            LOGGER.warn("paste clipboard image failed", e);
            DialogHelper.showWarning("图片", "无法读取剪贴板图片：" + e.getMessage());
        }
    }

    private BufferedImage toBufferedImage(Image image) {
        int width = Math.max(1, (int) Math.round(image.getWidth()));
        int height = Math.max(1, (int) Math.round(image.getHeight()));
        PixelReader reader = image.getPixelReader();
        if (reader == null) {
            throw new IllegalArgumentException("图片像素不可读");
        }
        int[] pixels = new int[width * height];
        reader.getPixels(0, 0, width, height, WritablePixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
        return buffered;
    }

    private void refreshAttachments() {
        attachmentBar.getChildren().clear();
        for (AiImageAttachment image : pendingImages) {
            HBox chip = new HBox();
            chip.getStyleClass().add("attachment-chip");
            Label name = new Label(image.name == null || image.name.isBlank() ? "image" : image.name);
            Button remove = new Button();
            remove.getStyleClass().addAll("button-cancel", "history-delete");
            remove.setGraphic(new FontIcon("fas-times"));
            remove.setOnAction(event -> {
                pendingImages.remove(image);
                refreshAttachments();
            });
            chip.getChildren().addAll(new FontIcon("fas-image"), name, remove);
            attachmentBar.getChildren().add(chip);
        }
        boolean visible = !pendingImages.isEmpty();
        attachmentBar.setVisible(visible);
        attachmentBar.setManaged(visible);
    }

    private void refreshModelOptions() {
        aiModelSelector.setItems(FXCollections.observableArrayList(settings.getAiConnections()));
        aiModelSelector.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AppConfig.AiModelConnection connection) {
                return settings.formatAiConnection(connection);
            }

            @Override
            public AppConfig.AiModelConnection fromString(String text) {
                return aiModelSelector.getItems().stream()
                        .filter(connection -> settings.formatAiConnection(connection).equals(text))
                        .findFirst()
                        .orElse(null);
            }
        });
        AppConfig.AiModelConnection selected = settings.getSelectedAiConnection();
        if (selected != null) {
            aiModelSelector.getSelectionModel().select(selected);
        } else if (!aiModelSelector.getItems().isEmpty()) {
            aiModelSelector.getSelectionModel().selectFirst();
        }
        if (!modelListenerInstalled) {
            aiModelSelector.valueProperty().addListener((obs, old, value) -> {
                if (value != null) {
                    settings.setSelectedAiConnectionId(value.id);
                    refreshThinkingModes(value);
                    refreshImageInputAvailability(value, true);
                }
            });
            modelListenerInstalled = true;
        }
        refreshImageInputAvailability(selectedConnection(), true);
    }

    private AppConfig.AiModelConnection selectedConnection() {
        AppConfig.AiModelConnection selected = aiModelSelector.getValue();
        if (selected != null) {
            return selected;
        }
        selected = settings.getSelectedAiConnection();
        if (selected != null) {
            return selected;
        }
        return settings.defaultAiConnection();
    }

    private boolean ensureImageInputSupported() {
        boolean supported = selectedConnection().imageInputSupported;
        if (!supported) {
            showImageInputUnsupported();
        }
        return !supported;
    }

    private void showImageInputUnsupported() {
        DialogHelper.showWarning("图片输入", "当前模型连接未启用图片输入，请在 AI 连接配置中确认模型支持多模态后开启。");
    }

    private void refreshImageInputAvailability(AppConfig.AiModelConnection connection, boolean clearAttachments) {
        boolean supported = connection != null && connection.imageInputSupported;
        btnUploadImage.setDisable(sending || !supported);
        btnUploadImage.setTooltip(new Tooltip(supported
                ? "上传图片"
                : "当前模型连接未启用图片输入"));
        if (clearAttachments && !supported && !pendingImages.isEmpty()) {
            pendingImages.clear();
            refreshAttachments();
            setStatus("当前模型不支持图片，已移除待发送附件");
        }
    }

    private void refreshThinkingModes(AppConfig.AiModelConnection connection) {
        String apiFormat = connection == null ? "" : connection.apiFormat;
        List<String> modes = "ANTHROPIC_MESSAGES".equals(apiFormat)
                ? List.of("关闭", "自适应")
                : List.of("关闭", "低", "中", "高");
        thinkingModeSelector.setItems(FXCollections.observableArrayList(modes));
        thinkingModeSelector.setValue("关闭");
        thinkingModeSelector.setTooltip(new Tooltip(thinkingModeHint(apiFormat)));
    }

    private String thinkingModeHint(String apiFormat) {
        return switch (apiFormat) {
            case "OPENAI_RESPONSES" -> "使用 reasoning effort，并请求可用的思考摘要；是否支持取决于模型。";
            case "ANTHROPIC_MESSAGES" -> "使用 Anthropic 自适应思考；是否支持取决于模型。";
            case "GEMINI_NATIVE" -> "使用 Gemini thinking level；是否支持取决于模型。";
            default -> "OpenAI 官方使用 Chat Completions reasoning_effort；部分兼容服务的布尔思考参数不通用。";
        };
    }

    private String selectedThinkingMode() {
        return switch (thinkingModeSelector.getValue()) {
            case "低" -> "LOW";
            case "中" -> "MEDIUM";
            case "高" -> "HIGH";
            case "自适应" -> "ADAPTIVE";
            default -> "OFF";
        };
    }

    private boolean isThinkingEnabled() {
        return !"OFF".equals(selectedThinkingMode());
    }

    private String titleForConversation(AiConversation conversation, String text) {
        if (conversation != null && conversation.messages != null && conversation.messages.size() > 1
                && conversation.title != null && !"新话题".equals(conversation.title)) {
            return conversation.title;
        }
        String source = text == null || text.isBlank() ? "图片问题" : text.replaceAll("\\s+", " ");
        if (source.length() > 24) {
            return source.substring(0, 24) + "...";
        }
        return source;
    }

    private void setSending(boolean value) {
        sending = value;
        btnSend.setDisable(false);
        btnSend.setText(value ? "停止回答" : "发送");
        btnSend.setGraphic(new FontIcon(value ? "fas-stop" : "fas-paper-plane"));
        btnSend.getStyleClass().removeAll("button-primary", "button-danger");
        btnSend.getStyleClass().add(value ? "button-danger" : "button-primary");
        refreshImageInputAvailability(selectedConnection(), false);
        inputArea.setDisable(value);
        aiModelSelector.setDisable(value);
        streamToggle.setDisable(value);
        thinkingModeSelector.setDisable(value);
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null ? "" : text);
    }

    private void scrollToBottom() {
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }
}

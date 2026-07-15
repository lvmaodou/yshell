package com.yshell.ui;

import com.yshell.theme.ThemeManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * 通用对话框工具类
 * <p>
 * 封装 JavaFX Alert，提供统一的错误、警告、信息提示和确认对话框，
 * 自动适配当前主题（明暗主题）样式。
 * </p>
 */
public final class DialogHelper {

    private DialogHelper() {
    }

    public record CustomDialogButton<T>(String text,
                                        ButtonBar.ButtonData buttonData,
                                        Function<Dialog<T>, T> action) {
    }

    // ========== 错误提示 ==========

    /**
     * 显示错误提示对话框
     *
     * @param message 错误信息内容
     */
    public static void showError(String message) {
        showError("错误", message);
    }

    /**
     * 显示错误提示对话框
     *
     * @param title   对话框标题
     * @param message 错误信息内容
     */
    public static void showError(String title, String message) {
        Alert alert = createAlert(Alert.AlertType.ERROR, title, message);
        alert.showAndWait();
    }

    // ========== 警告提示 ==========

    /**
     * 显示警告提示对话框
     *
     * @param message 警告信息内容
     */
    public static void showWarning(String message) {
        showWarning("警告", message);
    }

    /**
     * 显示警告提示对话框
     *
     * @param title   对话框标题
     * @param message 警告信息内容
     */
    public static void showWarning(String title, String message) {
        Alert alert = createAlert(Alert.AlertType.WARNING, title, message);
        alert.showAndWait();
    }

    // ========== 信息提示 ==========

    /**
     * 显示信息提示对话框
     *
     * @param message 信息内容
     */
    public static void showInfo(String message) {
        showInfo("提示", message);
    }

    /**
     * 显示信息提示对话框
     *
     * @param title   对话框标题
     * @param message 信息内容
     */
    public static void showInfo(String title, String message) {
        Alert alert = createAlert(Alert.AlertType.INFORMATION, title, message);
        alert.showAndWait();
    }

    // ========== 确认对话框 ==========

    /**
     * 显示确认对话框（确定/取消）
     *
     * @param message 确认信息内容
     * @return 用户是否点击了确定
     */
    public static boolean showConfirm(String message) {
        return showConfirm("确认", message);
    }

    /**
     * 显示确认对话框（确定/取消）
     *
     * @param title   对话框标题
     * @param message 确认信息内容
     * @return 用户是否点击了确定
     */
    public static boolean showConfirm(String title, String message) {
        Alert alert = createAlert(Alert.AlertType.CONFIRMATION, title, message);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    /**
     * 显示确认对话框（是/否）
     *
     * @param message 确认信息内容
     * @return 用户是否点击了"是"
     */
    public static boolean showConfirmYesNo(String message) {
        return showConfirmYesNo("确认", message);
    }

    /**
     * 显示确认对话框（是/否）
     *
     * @param title   对话框标题
     * @param message 确认信息内容
     * @return 用户是否点击了"是"
     */
    public static boolean showConfirmYesNo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        alert.setTitle(title);
        alert.setHeaderText(null);
        applyTheme(alert);
        styleDialogButtons(alert);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.YES;
    }

    // ========== 自定义按钮确认对话框 ==========

    /**
     * 显示带有自定义"确定/取消"按钮文字的确认对话框
     *
     * @param title        对话框标题
     * @param message      确认信息内容
     * @param okButtonText "确定"按钮的自定义文字
     * @param cancelText   "取消"按钮的自定义文字
     * @return 用户是否点击了"确定"
     */
    public static boolean showConfirm(String title, String message,
                                      String okButtonText, String cancelText) {
        return showConfirm(null, title, message, okButtonText, cancelText);
    }

    public static boolean showConfirm(Window owner, String title, String message,
                                      String okButtonText, String cancelText) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        if (owner != null) {
            alert.initOwner(owner);
        }
        applyTheme(alert);

        ButtonType btnOk = new ButtonType(okButtonText != null ? okButtonText : "确定",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType(cancelText != null ? cancelText : "取消",
                ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(btnOk, btnCancel);
        styleDialogButtons(alert);

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == btnOk;
    }

    /**
     * 显示三按钮确认对话框："确定 / 其它 / 取消"
     *
     * @param title        对话框标题
     * @param message      确认信息内容
     * @param okButtonText "确定"按钮的自定义文字
     * @param otherText    第三按钮（OTHER）的自定义文字
     * @param cancelText   "取消"按钮的自定义文字
     * @return 0 = 确定，1 = other，其它 = 取消
     */
    public static int showConfirmThree(String title, String message,
                                       String okButtonText, String otherText, String cancelText) {
        Dialog<Integer> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setResizable(false);

        Label messageLabel = new Label(message);
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(360);

        Button btnOk = new Button(okButtonText != null ? okButtonText : "确定");
        Button btnOther = new Button(otherText != null ? otherText : "其他");
        Button btnCancel = new Button(cancelText != null ? cancelText : "取消");
        btnOk.setDefaultButton(true);
        btnCancel.setCancelButton(true);
        btnOk.getStyleClass().add("button-primary");
        btnOther.getStyleClass().add("button-cancel");
        btnCancel.getStyleClass().add("button-cancel");
        btnOk.setOnAction(e -> {
            dialog.setResult(0);
            dialog.close();
        });
        btnOther.setOnAction(e -> {
            dialog.setResult(1);
            dialog.close();
        });
        btnCancel.setOnAction(e -> {
            dialog.setResult(-1);
            dialog.close();
        });

        HBox buttons = new HBox(10, btnOk, btnOther, btnCancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(16, messageLabel, buttons);
        content.setPadding(new Insets(16, 20, 16, 20));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().clear();
        applyTheme(dialog);

        return dialog.showAndWait().orElse(-1);
    }

    // ========== 文本输入对话框 ==========

    /**
     * 显示文本输入对话框
     *
     * @param title      对话框标题
     * @param headerText 提示内容（header）
     * @param label      输入框标签
     * @param defaultVal 默认值
     * @return 用户输入的字符串；若取消或输入为空则返回 null
     */
    public static String showTextInput(String title, String headerText,
                                       String label, String defaultVal) {
        TextInputDialog dialog = new TextInputDialog(defaultVal == null ? "" : defaultVal);
        dialog.setTitle(title);
        dialog.setHeaderText(headerText);
        if (label != null) dialog.setContentText(label);
        applyTheme(dialog);
        styleDialogButtons(dialog);
        dialog.getDialogPane().getStyleClass().add("text-input");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return null;
        String val = result.get();
        return val.trim().isEmpty() ? null : val.trim();
    }

    public static <T> Optional<T> showCustomDialog(String title,
                                                   Node content,
                                                   Function<ButtonType, T> resultConverter,
                                                   String... styleClasses) {
        ButtonType okButton = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = ButtonType.CANCEL;
        return showCustomDialog(title, content, List.of(
                new CustomDialogButton<>("确定", okButton.getButtonData(),
                        dialog -> resultConverter != null ? resultConverter.apply(okButton) : null),
                new CustomDialogButton<>("取消", cancelButton.getButtonData(),
                        dialog -> resultConverter != null ? resultConverter.apply(cancelButton) : null)
        ), styleClasses);
    }

    public static <T> Optional<T> showCustomDialog(String title,
                                                   Node content,
                                                   List<CustomDialogButton<T>> buttons,
                                                   String... styleClasses) {
        Dialog<T> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);

        Label titleLabel = new Label(title == null || title.isBlank() ? "对话框" : title);
        titleLabel.getStyleClass().add("custom-dialog-title");
        titleLabel.setMaxWidth(Double.MAX_VALUE);

        Node contentNode = content == null ? new VBox() : content;
        if (contentNode instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        VBox.setVgrow(contentNode, Priority.ALWAYS);
        VBox contentBody = new VBox(contentNode);
        contentBody.setFillWidth(true);
        if (styleClasses != null && styleClasses.length > 0) {
            Arrays.stream(styleClasses)
                    .filter(Objects::nonNull)
                    .filter(styleClass -> !styleClass.isBlank())
                    .forEach(contentBody.getStyleClass()::add);
        }

        ScrollPane contentScroll = new ScrollPane(contentBody);
        contentScroll.getStyleClass().add("custom-dialog-content");
        contentScroll.setFitToWidth(true);
        contentScroll.setFitToHeight(false);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(contentScroll, Priority.ALWAYS);

        HBox footer = new HBox(10);
        footer.getStyleClass().add("custom-dialog-footer");
        footer.setAlignment(Pos.CENTER_RIGHT);
        List<CustomDialogButton<T>> dialogButtons = buttons == null || buttons.isEmpty()
                ? List.of(new CustomDialogButton<>("确定", ButtonBar.ButtonData.OK_DONE, dialogRef -> null))
                : buttons;
        for (CustomDialogButton<T> buttonConfig : dialogButtons) {
            Button button = createCustomDialogButton(dialog, buttonConfig);
            footer.getChildren().add(button);
        }

        VBox root = new VBox(titleLabel, contentScroll, footer);
        root.getStyleClass().add("custom-dialog-root");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().clear();
        dialogPane.setContent(root);
        dialogPane.getStyleClass().add("custom-dialog-pane");

        applyTheme(dialog);
        applyCustomDialogStyles(dialog, styleClasses);
        return dialog.showAndWait();
    }

    private static <T> Button createCustomDialogButton(Dialog<T> dialog, CustomDialogButton<T> buttonConfig) {
        String text = buttonConfig == null || buttonConfig.text() == null || buttonConfig.text().isBlank()
                ? "确定"
                : buttonConfig.text();
        ButtonBar.ButtonData buttonData = buttonConfig == null || buttonConfig.buttonData() == null
                ? ButtonBar.ButtonData.OK_DONE
                : buttonConfig.buttonData();
        Button button = new Button(text);
        button.getStyleClass().add(isPrimaryButton(buttonData) ? "button-primary" : "button-cancel");
        button.setDefaultButton(isPrimaryButton(buttonData));
        button.setCancelButton(isCancelButton(buttonData));
        button.setOnAction(event -> {
            T result = buttonConfig == null || buttonConfig.action() == null
                    ? null
                    : buttonConfig.action().apply(dialog);
            dialog.setResult(result);
            Window window = button.getScene() == null ? null : button.getScene().getWindow();
            if (window != null) {
                window.hide();
            } else {
                dialog.hide();
            }
        });
        return button;
    }

    public static List<Path> chooseFiles(Window owner, String title, Path initialDirectory,
                                         FileChooser.ExtensionFilter... extensionFilters) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title == null || title.isBlank() ? "选择文件夹" : title);
        File initial = resolveInitialDirectory(initialDirectory);
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        if (extensionFilters != null && extensionFilters.length > 0) {
            chooser.getExtensionFilters().addAll(extensionFilters);
        }

        List<File> selected = chooser.showOpenMultipleDialog(owner);
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>(selected.size());
        for (File file : selected) {
            if (file != null) {
                paths.add(file.toPath());
            }
        }
        return paths;
    }

    public static Path chooseDirectory(Window owner, String title, Path initialDirectory) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title == null || title.isBlank() ? "选择文件夹" : title);
        File initial = resolveInitialDirectory(initialDirectory);
        if (initial != null) {
            chooser.setInitialDirectory(initial);
        }
        File selected = chooser.showDialog(owner);
        return selected == null ? null : selected.toPath();
    }

    // ========== 自定义信息对话框 ==========

    /**
     * 显示自定义标题的信息对话框（支持多行内容）
     */
    public static void showInfoWithHeader(String title, String headerText, String message) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);

        Label titleLabel = new Label(headerText == null || headerText.isBlank() ? title : headerText);
        titleLabel.getStyleClass().add("info-dialog-title");

        VBox messageBox = createInfoDialogContent(message);

        ScrollPane contentScroll = new ScrollPane(messageBox);
        contentScroll.getStyleClass().add("info-dialog-scroll");
        contentScroll.setFitToWidth(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentScroll.setPrefViewportHeight(calculateInfoDialogContentHeight(message));

        VBox contentCard = new VBox(contentScroll);
        contentCard.getStyleClass().add("info-dialog-content-card");
        VBox.setMargin(contentCard, new Insets(2, 2, 2, 2));

        Button okButton = new Button("确定");
        okButton.getStyleClass().add("button-primary");
        okButton.setDefaultButton(true);
        okButton.setOnAction(event -> {
            dialog.setResult(ButtonType.OK);
            dialog.hide();
        });

        HBox footer = new HBox(okButton);
        footer.getStyleClass().add("info-dialog-footer");

        VBox root = new VBox(titleLabel, contentCard, footer);
        root.getStyleClass().add("info-dialog-root");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().clear();
        dialogPane.setContent(root);
        dialogPane.getStyleClass().add("info-dialog");

        applyTheme(dialog);
        dialog.showAndWait();
    }

    // ========== 内部方法 ==========

    /**
     * 创建并配置 Alert，自动应用当前主题样式
     */
    private static double calculateInfoDialogContentHeight(String message) {
        if (message == null || message.isBlank()) {
            return 72;
        }

        long explicitLines = message.lines().count();
        int longestLine = message.lines()
                .mapToInt(String::length)
                .max()
                .orElse(0);
        double wrappedLines = Math.ceil(longestLine / 42.0);
        double estimatedLines = Math.max(explicitLines, explicitLines + wrappedLines - 1);
        return Math.max(72, Math.min(280, estimatedLines * 19 + 28));
    }

    private static VBox createInfoDialogContent(String message) {
        VBox content = new VBox(10);
        content.getStyleClass().add("info-dialog-message");
        String normalizedMessage = message == null ? "" : message.strip();
        if (normalizedMessage.isEmpty()) {
            return content;
        }

        String[] blocks = normalizedMessage.split("\\R\\s*\\R");
        for (String block : blocks) {
            String[] lines = block.strip().split("\\R");
            if (lines.length == 0) {
                continue;
            }

            if (isKeyValueBlock(lines)) {
                VBox rows = new VBox(5);
                rows.getStyleClass().add("info-dialog-kv-list");
                for (String line : lines) {
                    rows.getChildren().add(createInfoDialogLine(line));
                }
                content.getChildren().add(rows);
            } else if (lines.length > 1 && isSectionTitle(lines[0])) {
                VBox section = new VBox(4);
                section.getStyleClass().add("info-dialog-section");
                Label sectionTitle = new Label(lines[0].trim());
                sectionTitle.getStyleClass().add("info-dialog-section-title");
                section.getChildren().add(sectionTitle);
                String[] sectionLines = Arrays.copyOfRange(lines, 1, lines.length);
                if (isKeyValueBlock(sectionLines)) {
                    VBox rows = new VBox(5);
                    rows.getStyleClass().add("info-dialog-kv-list");
                    for (String line : sectionLines) {
                        rows.getChildren().add(createInfoDialogLine(line));
                    }
                    section.getChildren().add(rows);
                } else {
                    Label sectionBody = new Label(String.join("\n", sectionLines).trim());
                    sectionBody.getStyleClass().add("info-dialog-section-body");
                    sectionBody.setWrapText(true);
                    section.getChildren().add(sectionBody);
                }
                content.getChildren().add(section);
            } else {
                Label paragraph = new Label(block.strip());
                paragraph.getStyleClass().add("info-dialog-paragraph");
                paragraph.setWrapText(true);
                content.getChildren().add(paragraph);
            }
        }
        return content;
    }

    private static boolean isKeyValueBlock(String[] lines) {
        return Arrays.stream(lines)
                .filter(line -> !line.isBlank())
                .allMatch(line -> line.contains("：") || line.contains(":"));
    }

    private static boolean isSectionTitle(String line) {
        String trimmed = line == null ? "" : line.trim();
        return !trimmed.isEmpty()
                && trimmed.length() <= 24
                && !trimmed.contains("：")
                && !trimmed.contains(":");
    }

    private static Node createInfoDialogLine(String line) {
        String delimiter = line.contains("：") ? "：" : ":";
        int index = line.indexOf(delimiter);
        if (index <= 0) {
            Label label = new Label(line);
            label.getStyleClass().add("info-dialog-paragraph");
            label.setWrapText(true);
            return label;
        }

        Label keyLabel = new Label(line.substring(0, index + delimiter.length()));
        keyLabel.getStyleClass().add("info-dialog-kv-key");
        Label valueLabel = new Label(line.substring(index + delimiter.length()).trim());
        valueLabel.getStyleClass().add("info-dialog-kv-value");
        valueLabel.setWrapText(true);

        HBox row = new HBox(6, keyLabel, valueLabel);
        row.getStyleClass().add("info-dialog-kv-row");
        return row;
    }

    private static Alert createAlert(Alert.AlertType alertType, String title, String message) {
        Alert alert = new Alert(alertType);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        applyTheme(alert);
        styleDialogButtons(alert);

        // 根据 Alert 类型添加对应的样式类
        String styleClass = switch (alertType) {
            case ERROR -> "error";
            case WARNING -> "warning";
            default -> "";
        };
        if (!styleClass.isEmpty()) {
            alert.getDialogPane().getStyleClass().add(styleClass);
        }

        return alert;
    }

    /**
     * 将当前主题的样式表应用到对话框上（支持 Alert / TextInputDialog / Dialog<?>）。
     * <p>
     * JavaFX 的对话框会创建独立的 Stage 和 Scene，不会自动被 ThemeManager 管理，
     * 因此需要手动将当前主题的 CSS 注入到对话框的 Scene 中。
     * </p>
     */
    private static File resolveInitialDirectory(Path initialDirectory) {
        if (initialDirectory == null) {
            return null;
        }
        try {
            Path directory = initialDirectory;
            if (Files.isRegularFile(directory)) {
                directory = directory.getParent();
            }
            if (directory != null && Files.isDirectory(directory)) {
                return directory.toFile();
            }
        } catch (SecurityException ignored) {
        }
        return null;
    }

    private static void applyTheme(Dialog<?> dialog) {
        ThemeManager themeManager = ThemeManager.getInstance();
        initOwnerIfAbsent(dialog);

        // 获取对话框的 DialogPane 所属的 Scene
        Scene scene = dialog.getDialogPane().getScene();
        scene.getStylesheets().clear();

        // 设置无装饰样式，去除系统默认白色标题栏和窗口背景
        dialog.initStyle(StageStyle.UNDECORATED);
        ApplicationIcons.applyTo(dialog);
        scene.setFill(Color.TRANSPARENT);

        // 1. 加载当前主题文件
        String themeCss = themeManager.isDarkTheme()
                ? "/css/theme-dark.css"
                : "/css/theme-light.css";
        String themeUrl = Objects.requireNonNull(DialogHelper.class.getResource(themeCss)).toExternalForm();
        scene.getStylesheets().add(themeUrl);

        // 2. 加载主题变量文件
        String variablesUrl = Objects.requireNonNull(DialogHelper.class.getResource("/css/theme-variables.css")).toExternalForm();
        scene.getStylesheets().add(variablesUrl);

        // 3. 加载对话框专用样式
        String alertCssUrl = Objects.requireNonNull(DialogHelper.class.getResource("/css/alert.css")).toExternalForm();
        scene.getStylesheets().add(alertCssUrl);
    }

    private static void initOwnerIfAbsent(Dialog<?> dialog) {
        if (resolveUsableOwner(dialog.getOwner()) != null) {
            return;
        }
        Window owner = resolveFallbackOwner();
        if (owner != null) {
            dialog.initOwner(owner);
        }
    }

    private static Window resolveFallbackOwner() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(Window::isFocused)
                .findFirst()
                .orElseGet(() -> Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .reduce((first, second) -> second)
                        .orElse(null));
    }

    private static Window resolveUsableOwner(Window owner) {
        return owner != null && owner.isShowing() ? owner : null;
    }

    private static void styleDialogButtons(Dialog<?> dialog) {
        DialogPane dialogPane = dialog.getDialogPane();
        for (ButtonType buttonType : dialogPane.getButtonTypes()) {
            Node button = dialogPane.lookupButton(buttonType);
            if (button == null) {
                continue;
            }
            button.getStyleClass().removeAll("button-primary", "button-cancel");
            ButtonBar.ButtonData buttonData = buttonType.getButtonData();
            if (isPrimaryButton(buttonData)) {
                button.getStyleClass().add("button-primary");
            } else if (isCancelButton(buttonData)) {
                button.getStyleClass().add("button-cancel");
            }
        }
    }

    private static boolean isPrimaryButton(ButtonBar.ButtonData buttonData) {
        return buttonData == ButtonBar.ButtonData.OK_DONE
                || buttonData == ButtonBar.ButtonData.YES
                || buttonData == ButtonBar.ButtonData.APPLY
                || buttonData.isDefaultButton();
    }

    private static boolean isCancelButton(ButtonBar.ButtonData buttonData) {
        return buttonData == ButtonBar.ButtonData.CANCEL_CLOSE
                || buttonData == ButtonBar.ButtonData.NO
                || buttonData.isCancelButton();
    }

    private static void applyCustomDialogStyles(Dialog<?> dialog, String... styleClasses) {
        if (styleClasses == null || styleClasses.length == 0) {
            return;
        }
        Scene scene = dialog.getDialogPane().getScene();
        Arrays.stream(styleClasses)
                .filter(Objects::nonNull)
                .map(DialogHelper::resolveCustomDialogCss)
                .filter(Objects::nonNull)
                .distinct()
                .map(css -> Objects.requireNonNull(DialogHelper.class.getResource(css)).toExternalForm())
                .forEach(scene.getStylesheets()::add);
    }

    private static String resolveCustomDialogCss(String styleClass) {
        return switch (styleClass) {
            case "key-form-dialog", "key-properties-dialog" -> "/css/key-manager.css";
            case "ssh-key-selection-dialog" -> "/css/connection-editor.css";
            default -> null;
        };
    }
}

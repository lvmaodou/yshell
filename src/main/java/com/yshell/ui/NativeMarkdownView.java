package com.yshell.ui;

import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.event.EventHandler;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.*;
import org.commonmark.node.Image;
import org.commonmark.parser.Parser;

import java.awt.*;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.util.Duration;

public final class NativeMarkdownView extends VBox {
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();
    private static final Pattern FENCE_PATTERN = Pattern.compile("(?m)^\\s*(`{3,}|~{3,})[^\\r\\n]*$");
    private static Runnable activeSelectionClearer;

    private final boolean rightAligned;
    private final Consumer<String> executeCommand;
    private final StringBuilder streamedMarkdown = new StringBuilder();
    private String markdown = "";
    private PauseTransition streamRenderDelay;
    private boolean streaming;
    private Runnable onRendered;

    public NativeMarkdownView(String markdown, boolean rightAligned, Consumer<String> executeCommand) {
        this.rightAligned = rightAligned;
        this.executeCommand = executeCommand;
        getStyleClass().add("native-markdown");
        if (rightAligned) {
            getStyleClass().add("user");
            setAlignment(Pos.TOP_RIGHT);
        }
        setSpacing(8);
        configureContextMenu();
        setMarkdown(markdown);
    }

    public void setMarkdown(String markdown) {
        if (streamRenderDelay != null) {
            streamRenderDelay.stop();
        }
        streaming = false;
        streamedMarkdown.setLength(0);
        this.markdown = markdown == null ? "" : markdown;
        renderMarkdown(this.markdown);
    }

    public void appendMarkdown(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!streaming) {
            streaming = true;
            streamedMarkdown.setLength(0);
            streamedMarkdown.append(markdown);
        }
        streamedMarkdown.append(value);
        scheduleStreamingRender();
    }

    public void completeMarkdown(String markdown) {
        setMarkdown(markdown);
    }

    public String getMarkdown() {
        return streaming ? streamedMarkdown.toString() : markdown;
    }

    public void setOnRendered(Runnable onRendered) {
        this.onRendered = onRendered;
    }

    private void scheduleStreamingRender() {
        if (streamRenderDelay == null) {
            streamRenderDelay = new PauseTransition(Duration.millis(24));
            streamRenderDelay.setOnFinished(event -> renderMarkdown(renderableStreamingMarkdown()));
        }
        if (streamRenderDelay.getStatus() != Animation.Status.RUNNING) {
            streamRenderDelay.playFromStart();
        }
    }

    private void renderMarkdown(String value) {
        getChildren().clear();
        renderBlocks(PARSER.parse(value).getFirstChild(), this);
        if (onRendered != null) {
            onRendered.run();
        }
    }

    private String renderableStreamingMarkdown() {
        String value = streamedMarkdown.toString();
        String openFence = null;
        Matcher matcher = FENCE_PATTERN.matcher(value);
        while (matcher.find()) {
            String fence = matcher.group(1);
            if (openFence == null) {
                openFence = fence;
            } else if (fence.charAt(0) == openFence.charAt(0) && fence.length() >= openFence.length()) {
                openFence = null;
            }
        }
        if (openFence == null) {
            return value;
        }
        return value + (value.endsWith("\n") ? "" : "\n") + openFence;
    }

    private static void activateSelection(Runnable clearer) {
        if (activeSelectionClearer != null) {
            activeSelectionClearer.run();
        }
        activeSelectionClearer = clearer;
    }

    private void renderBlocks(org.commonmark.node.Node node, Pane container) {
        for (org.commonmark.node.Node current = node; current != null; current = current.getNext()) {
            renderBlock(current, container);
        }
    }

    private void renderBlock(org.commonmark.node.Node node, Pane container) {
        if (node instanceof Paragraph paragraph) {
            TextFlow flow = createTextFlow("markdown-paragraph");
            renderInline(paragraph.getFirstChild(), flow, InlineStyle.DEFAULT);
            container.getChildren().add(flow);
        } else if (node instanceof Heading heading) {
            TextFlow flow = createTextFlow("markdown-heading", "markdown-heading-" + heading.getLevel());
            renderInline(heading.getFirstChild(), flow, InlineStyle.DEFAULT);
            container.getChildren().add(flow);
        } else if (node instanceof FencedCodeBlock codeBlock) {
            container.getChildren().add(createCodeBlock(codeBlock.getInfo(), codeBlock.getLiteral()));
        } else if (node instanceof IndentedCodeBlock codeBlock) {
            container.getChildren().add(createCodeBlock("", codeBlock.getLiteral()));
        } else if (node instanceof BlockQuote quote) {
            container.getChildren().add(createBlockQuote(quote));
        } else if (node instanceof BulletList list) {
            container.getChildren().add(createList(list, false));
        } else if (node instanceof OrderedList list) {
            container.getChildren().add(createList(list, true));
        } else if (node instanceof TableBlock table) {
            container.getChildren().add(createTable(table));
        } else if (node instanceof ThematicBreak) {
            Separator separator = new Separator();
            separator.getStyleClass().add("markdown-rule");
            container.getChildren().add(separator);
        } else if (node instanceof HtmlBlock html) {
            TextFlow flow = createTextFlow("markdown-paragraph");
            flow.getChildren().add(createText(html.getLiteral(), InlineStyle.DEFAULT));
            container.getChildren().add(flow);
        }
    }

    private HBox createBlockQuote(BlockQuote quote) {
        Region marker = new Region();
        marker.getStyleClass().add("markdown-quote-marker");
        marker.setMinWidth(3);
        marker.setPrefWidth(3);
        marker.setMaxWidth(3);

        VBox content = new VBox(6);
        content.getStyleClass().add("markdown-quote-content");
        HBox.setHgrow(content, Priority.ALWAYS);
        renderBlocks(quote.getFirstChild(), content);

        HBox row = new HBox(9, marker, content);
        row.getStyleClass().add("markdown-quote");
        return row;
    }

    private VBox createList(ListBlock list, boolean ordered) {
        VBox listBox = new VBox(4);
        listBox.getStyleClass().add("markdown-list");
        int number = ordered && list instanceof OrderedList orderedList ? orderedList.getMarkerStartNumber() : 1;

        for (org.commonmark.node.Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem item)) {
                continue;
            }
            Label marker = new Label(ordered ? number++ + "." : "•");
            marker.getStyleClass().add("markdown-list-marker");
            marker.setMinWidth(24);
            marker.setAlignment(Pos.TOP_RIGHT);

            VBox content = new VBox(5);
            content.getStyleClass().add("markdown-list-content");
            HBox.setHgrow(content, Priority.ALWAYS);
            renderBlocks(item.getFirstChild(), content);

            HBox row = new HBox(8, marker, content);
            row.getStyleClass().add("markdown-list-item");
            listBox.getChildren().add(row);
        }
        return listBox;
    }

    private GridPane createTable(TableBlock table) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("markdown-table");
        grid.setMinWidth(0);
        grid.setMaxWidth(Double.MAX_VALUE);

        int rowIndex = 0;
        int columnCount = 0;
        for (org.commonmark.node.Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            boolean header = section instanceof TableHead;
            for (org.commonmark.node.Node rowNode = section.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                if (!(rowNode instanceof TableRow row)) {
                    continue;
                }
                int columnIndex = 0;
                for (org.commonmark.node.Node cellNode = row.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                    if (!(cellNode instanceof TableCell cell)) {
                        continue;
                    }
                    TextFlow flow = createTextFlow("markdown-table-text");
                    flow.setMinWidth(0);
                    flow.setMaxWidth(Double.MAX_VALUE);
                    renderInline(cell.getFirstChild(), flow, InlineStyle.DEFAULT);
                    VBox cellBox = new VBox(flow);
                    cellBox.getStyleClass().add("markdown-table-cell");
                    cellBox.setMinWidth(0);
                    cellBox.setMaxWidth(Double.MAX_VALUE);
                    if (header) {
                        cellBox.getStyleClass().add("header");
                    }
                    GridPane.setHgrow(cellBox, Priority.ALWAYS);
                    GridPane.setFillWidth(cellBox, true);
                    grid.add(cellBox, columnIndex++, rowIndex);
                }
                columnCount = Math.max(columnCount, columnIndex);
                rowIndex++;
            }
        }
        for (int index = 0; index < columnCount; index++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 / columnCount);
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            grid.getColumnConstraints().add(constraints);
        }
        return grid;
    }

    private VBox createCodeBlock(String info, String literal) {
        String code = literal == null ? "" : literal;
        String language = languageFor(info);

        Label languageLabel = new Label(language);
        languageLabel.getStyleClass().add("markdown-code-language");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button copy = new Button("复制");
        copy.getStyleClass().addAll("button-cancel", "markdown-code-action");
        copy.setOnAction(event -> copyToClipboard(code));

        HBox header = new HBox(6, languageLabel, spacer, copy);
        header.getStyleClass().add("markdown-code-header");
        if (executeCommand != null && !code.isBlank()) {
            Button run = new Button("执行");
            run.getStyleClass().addAll("button-cancel", "markdown-code-action");
            run.setOnAction(event -> executeCommand.accept(code));
            header.getChildren().add(run);
        }

        TextArea area = new TextArea(code);
        area.getStyleClass().add("markdown-code-area");
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(codeRows(code));
        area.setMaxHeight(300);
        area.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if ((event.isControlDown() || event.isMetaDown()) && event.getCode() == KeyCode.ENTER
                    && executeCommand != null && !code.isBlank()) {
                event.consume();
                String selected = area.getSelectedText();
                executeCommand.accept(selected == null || selected.isBlank() ? code : selected);
            }
        });
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                activateSelection(area::deselect);
            }
        });
        configureCodeAreaContextMenu(area);

        VBox block = new VBox(header, area);
        block.getStyleClass().add("markdown-code-block");
        return block;
    }

    private TextFlow createTextFlow(String... styleClasses) {
        TextFlow flow = new SelectableTextFlow();
        flow.getStyleClass().addAll(styleClasses);
        if (rightAligned) {
            flow.setTextAlignment(TextAlignment.RIGHT);
        }
        return flow;
    }

    private void renderInline(org.commonmark.node.Node node, TextFlow flow, InlineStyle style) {
        for (org.commonmark.node.Node current = node; current != null; current = current.getNext()) {
            if (current instanceof org.commonmark.node.Text text) {
                flow.getChildren().add(createText(text.getLiteral(), style));
            } else if (current instanceof StrongEmphasis strong) {
                renderInline(strong.getFirstChild(), flow, style.withStrong());
            } else if (current instanceof Emphasis emphasis) {
                renderInline(emphasis.getFirstChild(), flow, style.withEmphasis());
            } else if (current instanceof Code code) {
                Text inlineCode = createText(code.getLiteral(), style);
                inlineCode.getStyleClass().add("markdown-inline-code");
                flow.getChildren().add(inlineCode);
            } else if (current instanceof Link link) {
                renderInline(link.getFirstChild(), flow, style.withLink(link.getDestination()));
            } else if (current instanceof Image image) {
                Text imageText = createText("[图片: " + inlineText(image.getFirstChild()) + "]",
                        style.withLink(image.getDestination()));
                imageText.getStyleClass().add("markdown-image-placeholder");
                flow.getChildren().add(imageText);
            } else if (current instanceof SoftLineBreak || current instanceof HardLineBreak) {
                flow.getChildren().add(createText("\n", style));
            } else if (current instanceof HtmlInline html) {
                flow.getChildren().add(createText(html.getLiteral(), style));
            }
        }
    }

    private Text createText(String value, InlineStyle style) {
        Text text = new Text(value == null ? "" : value);
        text.getStyleClass().add("markdown-text");
        if (style.strong) {
            text.getStyleClass().add("markdown-strong");
        }
        if (style.emphasis) {
            text.getStyleClass().add("markdown-emphasis");
        }
        if (!style.linkDestination.isBlank()) {
            text.getStyleClass().add("markdown-link");
            text.setUnderline(true);
            text.setCursor(Cursor.HAND);
            text.setOnMouseClicked(event -> openLink(style.linkDestination));
        }
        return text;
    }

    private String inlineText(org.commonmark.node.Node node) {
        StringBuilder text = new StringBuilder();
        collectInlineText(node, text);
        return text.isEmpty() ? "图片" : text.toString();
    }

    private void collectInlineText(org.commonmark.node.Node node, StringBuilder output) {
        for (org.commonmark.node.Node current = node; current != null; current = current.getNext()) {
            if (current instanceof org.commonmark.node.Text text) {
                output.append(text.getLiteral());
            } else if (current instanceof Code code) {
                output.append(code.getLiteral());
            } else if (current instanceof SoftLineBreak || current instanceof HardLineBreak) {
                output.append(' ');
            } else {
                collectInlineText(current.getFirstChild(), output);
            }
        }
    }

    private void configureContextMenu() {
        MenuItem copy = new MenuItem("复制正文");
        copy.setOnAction(event -> copyToClipboard(markdown));
        setOnContextMenuRequested(event -> {
            ContextMenu menu = new ContextMenu(copy);
            menu.show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private void configureCodeAreaContextMenu(TextArea area) {
        area.setOnContextMenuRequested(event -> {
            String selected = area.getSelectedText();
            MenuItem copy = new MenuItem("复制(Ctrl+c)");
            copy.setDisable(selected == null || selected.isBlank());
            copy.setOnAction(action -> copyToClipboard(selected));

            MenuItem execute = new MenuItem("执行(Ctrl+Enter)");
            execute.setDisable(executeCommand == null || selected == null || selected.isBlank());
            execute.setOnAction(action -> {
                if (executeCommand != null) {
                    executeCommand.accept(selected);
                }
            });

            ContextMenu menu = new ContextMenu(copy, execute);
            menu.show(area, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    private void openLink(String destination) {
        try {
            URI uri = URI.create(destination == null ? "" : destination.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme))
                    && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (Exception ignored) {
        }
    }

    private void copyToClipboard(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value == null ? "" : value);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private int codeRows(String code) {
        if (code == null || code.isBlank()) {
            return 2;
        }
        return Math.max(2, Math.min(12, code.split("\\R", -1).length));
    }

    private String languageFor(String info) {
        if (info == null || info.isBlank()) {
            return "text";
        }
        String language = info.trim().split("\\s+", 2)[0];
        return language.isBlank() ? "text" : language;
    }

    private record InlineStyle(boolean strong, boolean emphasis, String linkDestination) {
        private static final InlineStyle DEFAULT = new InlineStyle(false, false, "");

        private InlineStyle withStrong() {
            return new InlineStyle(true, emphasis, linkDestination);
        }

        private InlineStyle withEmphasis() {
            return new InlineStyle(strong, true, linkDestination);
        }

        private InlineStyle withLink(String destination) {
            return new InlineStyle(strong, emphasis, destination == null ? "" : destination);
        }
    }

    private final class SelectableTextFlow extends TextFlow {
        private final Path selectionPath = new Path();
        private final EventHandler<KeyEvent> sceneShortcutHandler = this::handleSelectionShortcut;
        private int anchor = -1;
        private int caret = -1;
        private Scene shortcutScene;

        private SelectableTextFlow() {
            selectionPath.getStyleClass().add("markdown-selection");
            selectionPath.setMouseTransparent(true);
            selectionPath.setManaged(false);
            getChildren().add(selectionPath);
            setFocusTraversable(true);
            setPickOnBounds(true);
            addEventFilter(MouseEvent.MOUSE_PRESSED, this::beginSelection);
            addEventFilter(MouseEvent.MOUSE_DRAGGED, this::extendSelection);
            addEventFilter(MouseEvent.MOUSE_RELEASED, this::finishSelection);
            addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, this::showSelectionMenu);
            addEventFilter(KeyEvent.KEY_PRESSED, this::handleSelectionShortcut);
        }

        private void beginSelection(MouseEvent event) {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            requestFocus();
            activateSelection(this::clearSelection);
            installSceneShortcutHandler();
            anchor = hitTest(eventPoint(event)).getInsertionIndex();
            caret = anchor;
            updateSelection();
        }

        private void extendSelection(MouseEvent event) {
            if (!event.isPrimaryButtonDown() || anchor < 0) {
                return;
            }
            caret = hitTest(eventPoint(event)).getInsertionIndex();
            updateSelection();
        }

        private void finishSelection(MouseEvent event) {
            if (event.getButton() == MouseButton.PRIMARY && anchor >= 0) {
                caret = hitTest(eventPoint(event)).getInsertionIndex();
                updateSelection();
            }
        }

        private void updateSelection() {
            if (anchor < 0 || caret < 0 || anchor == caret) {
                selectionPath.getElements().clear();
                return;
            }
            int start = Math.min(anchor, caret);
            int end = Math.max(anchor, caret);
            selectionPath.getElements().setAll(rangeShape(start, end));
        }

        private void clearSelection() {
            anchor = -1;
            caret = -1;
            selectionPath.getElements().clear();
            uninstallSceneShortcutHandler();
        }

        private void installSceneShortcutHandler() {
            Scene scene = getScene();
            if (scene == shortcutScene) {
                return;
            }
            uninstallSceneShortcutHandler();
            if (scene != null) {
                scene.addEventFilter(KeyEvent.KEY_PRESSED, sceneShortcutHandler);
                shortcutScene = scene;
            }
        }

        private void uninstallSceneShortcutHandler() {
            if (shortcutScene != null) {
                shortcutScene.removeEventFilter(KeyEvent.KEY_PRESSED, sceneShortcutHandler);
                shortcutScene = null;
            }
        }

        private Point2D eventPoint(MouseEvent event) {
            return sceneToLocal(event.getSceneX(), event.getSceneY());
        }

        private String selectedText() {
            if (anchor < 0 || caret < 0 || anchor == caret) {
                return "";
            }
            String text = textContent();
            int start = Math.max(0, Math.min(Math.min(anchor, caret), text.length()));
            int end = Math.max(start, Math.min(Math.max(anchor, caret), text.length()));
            return text.substring(start, end);
        }

        private String textContent() {
            StringBuilder text = new StringBuilder();
            for (Node child : getChildren()) {
                if (child instanceof Text textNode) {
                    text.append(textNode.getText());
                }
            }
            return text.toString();
        }

        private void showSelectionMenu(ContextMenuEvent event) {
            String selected = selectedText();
            MenuItem copy = new MenuItem("复制(Ctrl+c)");
            copy.setDisable(selected.isBlank());
            copy.setOnAction(action -> copyToClipboard(selected));

            MenuItem execute = new MenuItem("执行(Ctrl+Enter)");
            execute.setDisable(executeCommand == null || selected.isBlank());
            execute.setOnAction(action -> {
                if (executeCommand != null) {
                    executeCommand.accept(selected);
                }
            });

            ContextMenu menu = new ContextMenu(copy, execute);
            menu.show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        }

        private void handleSelectionShortcut(KeyEvent event) {
            String selected = selectedText();
            if (selected.isBlank() || (!event.isControlDown() && !event.isMetaDown())) {
                return;
            }
            if (event.getCode() == KeyCode.C) {
                event.consume();
                copyToClipboard(selected);
            } else if (event.getCode() == KeyCode.ENTER && executeCommand != null) {
                event.consume();
                executeCommand.accept(selected);
            }
        }
    }
}

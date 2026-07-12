package com.yshell.ui;

import com.yshell.model.Command;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.function.Consumer;

public class CommandTreeCell extends javafx.scene.control.TreeCell<Command> {

    private final HBox content;
    private final FontIcon typeIcon;
    private final javafx.scene.control.Label nameLabel;
    private final HBox actionsBox;
    private final Button editBtn;
    private final Button deleteBtn;
    private final Button runCurrentBtn;
    private final Button runAllBtn;

    private Consumer<Command> editHandler;
    private Consumer<Command> deleteHandler;
    private Consumer<Command> runCurrentHandler;
    private Consumer<Command> runAllHandler;

    public CommandTreeCell() {
        typeIcon = new FontIcon();
        typeIcon.setIconSize(16);

        nameLabel = new javafx.scene.control.Label();
        nameLabel.getStyleClass().add("cmd-cell-name");

        HBox nameBox = new HBox(6, typeIcon, nameLabel);
        nameBox.getStyleClass().add("cmd-name-col");
        HBox.setHgrow(nameBox, Priority.ALWAYS);

        actionsBox = new HBox(4);
        actionsBox.getStyleClass().add("cmd-cell-actions");

        editBtn = createMiniButton(FontAwesomeSolid.PEN, "编辑");
        runCurrentBtn = createMiniButton(FontAwesomeSolid.PLAY, "当前会话执行");
        runAllBtn = createMiniButton(FontAwesomeSolid.SERVER, "全部会话执行");
        deleteBtn = createMiniButton(FontAwesomeSolid.TRASH, "删除");

        StackPane actionArea = new StackPane(actionsBox);
        actionArea.getStyleClass().add("cmd-action-area");

        content = new HBox(6, nameBox, actionArea);
        content.getStyleClass().add("cmd-cell-content");
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        hoverProperty().addListener((obs, ov, nv) -> actionsBox.setVisible(nv));
        setupContextMenu();
    }

    public HBox getContent() {
        return content;
    }

    private Button createMiniButton(FontAwesomeSolid iconLiteral, String tooltipText) {
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(11);
        Button btn = new Button();
        btn.setGraphic(icon);
        btn.getStyleClass().addAll("button-icon-mini", "cmd-mini-btn");
        Tooltip tooltip = new Tooltip(tooltipText);
        tooltip.setShowDelay(Duration.millis(200));
        btn.setTooltip(tooltip);
        btn.setFocusTraversable(false);
        return btn;
    }

    public void setEditHandler(Consumer<Command> handler) {
        this.editHandler = handler;
    }

    public void setDeleteHandler(Consumer<Command> handler) {
        this.deleteHandler = handler;
    }

    public void setRunCurrentHandler(Consumer<Command> handler) {
        this.runCurrentHandler = handler;
    }

    public void setRunAllHandler(Consumer<Command> handler) {
        this.runAllHandler = handler;
    }

    private void setupContextMenu() {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem editItem = createContextItem("编辑", FontAwesomeSolid.PEN);
        MenuItem runCurrentItem = createContextItem("当前会话执行", FontAwesomeSolid.PLAY);
        MenuItem runAllItem = createContextItem("全部会话执行", FontAwesomeSolid.SERVER);
        MenuItem deleteItem = createContextItem("删除", FontAwesomeSolid.TRASH);

        editItem.setOnAction(e -> accept(editHandler));
        runCurrentItem.setOnAction(e -> accept(runCurrentHandler));
        runAllItem.setOnAction(e -> accept(runAllHandler));
        deleteItem.setOnAction(e -> accept(deleteHandler));

        contextMenu.getItems().addAll(editItem, runCurrentItem, runAllItem, deleteItem);
        contextMenu.setOnShowing(e -> {
            Command item = getItem();
            if (isEmpty() || item == null) {
                contextMenu.getItems().clear();
                return;
            }

            contextMenu.getItems().clear();
            contextMenu.getItems().add(editItem);
            if (!item.isCategory()) {
                contextMenu.getItems().addAll(runCurrentItem, runAllItem);
            }
            contextMenu.getItems().add(deleteItem);
        });
        setContextMenu(contextMenu);
    }

    private MenuItem createContextItem(String text, FontAwesomeSolid iconLiteral) {
        MenuItem item = new MenuItem(text);
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(14);
        icon.getStyleClass().add("context-menu-icon");
        item.setGraphic(icon);
        return item;
    }

    private void accept(Consumer<Command> handler) {
        Command item = getItem();
        if (item != null && handler != null) {
            handler.accept(item);
        }
    }

    @Override
    protected void updateItem(Command item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            actionsBox.setVisible(false);
            return;
        }

        if (item.isCategory()) {
            typeIcon.setIconCode(FontAwesomeSolid.FOLDER);
            typeIcon.getStyleClass().removeAll("icon-folder", "icon-command");
            typeIcon.getStyleClass().add("icon-folder");
            actionsBox.getChildren().setAll(editBtn, deleteBtn);
        } else {
            typeIcon.setIconCode(FontAwesomeSolid.SUBSCRIPT);
            typeIcon.getStyleClass().removeAll("icon-folder", "icon-command");
            typeIcon.getStyleClass().add("icon-command");
            actionsBox.getChildren().setAll(editBtn, runCurrentBtn, runAllBtn, deleteBtn);
        }

        nameLabel.setText(item.getName() != null ? item.getName() : "");
        actionsBox.setVisible(isHover());

        editBtn.setOnAction(e -> {
            e.consume();
            if (editHandler != null) editHandler.accept(item);
        });
        deleteBtn.setOnAction(e -> {
            e.consume();
            if (deleteHandler != null) deleteHandler.accept(item);
        });
        runCurrentBtn.setOnAction(e -> {
            e.consume();
            if (runCurrentHandler != null) runCurrentHandler.accept(item);
        });
        runAllBtn.setOnAction(e -> {
            e.consume();
            if (runAllHandler != null) runAllHandler.accept(item);
        });

        setText(null);
        setGraphic(content);
    }
}

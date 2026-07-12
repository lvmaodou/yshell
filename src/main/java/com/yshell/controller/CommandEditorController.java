package com.yshell.controller;

import com.yshell.model.Command;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.util.UUID;
import java.util.function.Consumer;

public class CommandEditorController {

    @FXML
    private Parent root;

    @FXML
    private Label dialogTitle;

    @FXML
    private Button btnClose;

    @FXML
    private TextField cmdName;

    @FXML
    private TextArea cmdCommand;

    @FXML
    private TextField cmdDesc;

    @FXML
    private Button btnDelete;

    @FXML
    private Button btnCancel;

    @FXML
    private Button btnSave;

    private Stage dialogStage;
    private boolean isEditMode = false;
    private Command editingCommand;
    private String categoryId = "";
    private Consumer<Command> saveHandler;
    private Consumer<Command> deleteHandler;

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
    }

    public void setEditMode(boolean isEdit) {
        this.isEditMode = isEdit;
        btnDelete.setVisible(isEdit);
        btnDelete.setManaged(isEdit);
        dialogTitle.setText(isEdit ? "编辑命令" : "新建命令");
    }

    public void setSaveHandler(Consumer<Command> handler) {
        this.saveHandler = handler;
    }

    public void setDeleteHandler(Consumer<Command> handler) {
        this.deleteHandler = handler;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId != null ? categoryId : "";
    }

    public void loadCommand(Command cmd) {
        this.editingCommand = cmd;
        cmdName.setText(cmd.getName() != null ? cmd.getName() : "");
        cmdCommand.setText(cmd.getCommand() != null ? cmd.getCommand() : "");
        cmdDesc.setText(cmd.getDescription() != null ? cmd.getDescription() : "");
        setCategoryId(cmd.getCategoryId());
    }

    @FXML
    public void initialize() {
        WindowDragResize.apply(root, 40, btnClose);
        setupEventHandlers();
    }

    private void setupEventHandlers() {
        btnClose.setOnAction(e -> closeDialog());
        btnCancel.setOnAction(e -> closeDialog());
        btnSave.setOnAction(e -> saveCommand());
        btnDelete.setOnAction(e -> deleteCommand());
    }

    private void saveCommand() {
        if (cmdName.getText() == null || cmdName.getText().trim().isEmpty()) {
            DialogHelper.showError("错误", "命令名称不能为空");
            return;
        }

        if (cmdCommand.getText() == null || cmdCommand.getText().trim().isEmpty()) {
            DialogHelper.showError("错误", "命令内容不能为空");
            return;
        }

        Command cmd;
        if (isEditMode && editingCommand != null) {
            cmd = editingCommand;
        } else {
            cmd = new Command();
            cmd.setId(UUID.randomUUID().toString());
            cmd.setType("command");
        }

        cmd.setName(cmdName.getText().trim());
        cmd.setCommand(cmdCommand.getText().trim());
        cmd.setDescription(cmdDesc.getText() != null ? cmdDesc.getText().trim() : "");
        cmd.setCategoryId(categoryId);

        if (saveHandler != null) {
            saveHandler.accept(cmd);
        }
        closeDialog();
    }

    private void deleteCommand() {
        if (deleteHandler != null && editingCommand != null) {
            deleteHandler.accept(editingCommand);
            closeDialog();
        }
    }

    @FXML
    private void closeDialog() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }
}

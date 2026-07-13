package com.yshell.controller;

import com.yshell.model.Command;
import com.yshell.service.CommandExecutionService;
import com.yshell.service.CommandRepository;
import com.yshell.theme.ThemeManager;
import com.yshell.ui.ApplicationIcons;
import com.yshell.ui.CommandTreeCell;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public class CommandManagerController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandManagerController.class);
    private static final String COMMAND_DRAG_PREFIX = "YSHELL_COMMAND_TREE_MOVE:";

    private long lastPrimaryPressAt;
    private String lastPrimaryPressNodeId;

    @FXML
    private Button btnClose;

    @FXML
    private Button btnMinimize;

    @FXML
    private Button btnMaximize;

    @FXML
    private Button btnNewCmd;

    @FXML
    private Button btnNewCategory;

    @FXML
    private Button btnExpandAll;

    @FXML
    private Button btnCollapseAll;

    @FXML
    private ComboBox<String> searchType;

    @FXML
    private TextField searchInput;

    @FXML
    private Button btnClearSearch;

    @FXML
    private TreeView<Command> cmdTreeView;

    @FXML
    private Parent root;

    private Stage dialogStage;
    private FontIcon maximizeIcon;
    private List<Command> commandData = new ArrayList<>();

    public void setDialogStage(Stage stage) {
        this.dialogStage = stage;
        if (dialogStage != null) {
            dialogStage.maximizedProperty().addListener((obs, oldValue, newValue) -> updateMaximizeIcon());
            updateMaximizeIcon();
        }
    }

    @FXML
    public void initialize() {
        setupComboBoxes();
        setupEventHandlers();
        setupTreeView();
        loadData();
        maximizeIcon = (FontIcon) btnMaximize.getGraphic();
        WindowDragResize.apply(root, 40, btnMinimize, btnMaximize, btnClose);
    }

    private void setupComboBoxes() {
        searchType.getItems().addAll("全部", "名称", "命令", "描述");
        searchType.setValue("全部");
    }

    private void setupEventHandlers() {
        btnClose.setOnAction(e -> closeDialog());
        btnMinimize.setOnAction(e -> minimizeDialog());
        btnMaximize.setOnAction(e -> toggleMaximize());
        btnNewCmd.setOnAction(e -> openNewCommand());
        btnNewCategory.setOnAction(e -> createNewCategory());
        btnExpandAll.setOnAction(e -> expandAll());
        btnCollapseAll.setOnAction(e -> collapseAll());
        btnClearSearch.setOnAction(e -> searchInput.clear());

        btnClearSearch.managedProperty().bind(btnClearSearch.visibleProperty());
        btnClearSearch.visibleProperty().bind(searchInput.textProperty().isNotEmpty());
        searchInput.textProperty().addListener((obs, ov, nv) -> refreshTree());
        searchType.setOnAction(e -> refreshTree());
    }

    private void setupTreeView() {
        cmdTreeView.setCellFactory(param -> {
            CommandTreeCell cell = new CommandTreeCell();
            cell.setEditHandler(this::editCommandOrCategory);
            cell.setDeleteHandler(this::deleteCommand);
            cell.setRunCurrentHandler(this::runCommandInCurrentSession);
            cell.setRunAllHandler(this::runCommandInAllSessions);
            cell.setOnDragDetected(event -> {
                Command command = cell.getItem();
                if (cell.isEmpty() || command == null) {
                    return;
                }
                Dragboard dragboard = cell.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(COMMAND_DRAG_PREFIX + command.getId());
                dragboard.setContent(content);
                event.consume();
            });
            cell.setOnDragOver(event -> {
                String draggedId = parseCommandDrag(event.getDragboard());
                Command target = cell.getItem();
                if (draggedId != null) {
                    if (cell.isEmpty() ? canMoveCommandToCategory(draggedId, "") : canMoveCommand(draggedId, target)) {
                        event.acceptTransferModes(TransferMode.MOVE);
                    }
                    event.consume();
                }
            });
            cell.setOnDragDropped(event -> {
                String draggedId = parseCommandDrag(event.getDragboard());
                Command target = cell.getItem();
                if (draggedId != null) {
                    boolean moved = cell.isEmpty() ? moveCommandToCategory(draggedId, "") : moveCommand(draggedId, target);
                    event.setDropCompleted(moved);
                    event.consume();
                }
            });

            cell.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() != MouseButton.PRIMARY || cell.isEmpty()) {
                    return;
                }
                Command item = cell.getItem();
                if (item == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                String itemId = item.getId();
                boolean doublePress = itemId != null
                        && itemId.equals(lastPrimaryPressNodeId)
                        && now - lastPrimaryPressAt <= 450L;
                lastPrimaryPressAt = now;
                lastPrimaryPressNodeId = itemId;
                if (!doublePress) {
                    return;
                }
                TreeItem<Command> treeItem = cell.getTreeItem();
                if (item.isCategory() && treeItem != null) {
                    treeItem.setExpanded(!treeItem.isExpanded());
                    item.setExpanded(treeItem.isExpanded());
                    e.consume();
                } else if (!item.isCategory()) {
                    runCommandInCurrentSession(item);
                    e.consume();
                }
                lastPrimaryPressAt = 0L;
                lastPrimaryPressNodeId = null;
            });

            return cell;
        });

        TreeItem<Command> rootItem = new TreeItem<>();
        rootItem.setExpanded(true);
        cmdTreeView.setRoot(rootItem);
        cmdTreeView.setShowRoot(false);
        cmdTreeView.setOnDragOver(event -> {
            String draggedId = parseCommandDrag(event.getDragboard());
            if (canMoveCommandToCategory(draggedId, "")) {
                event.acceptTransferModes(TransferMode.MOVE);
                event.consume();
            }
        });
        cmdTreeView.setOnDragDropped(event -> {
            String draggedId = parseCommandDrag(event.getDragboard());
            boolean moved = moveCommandToCategory(draggedId, "");
            event.setDropCompleted(moved);
            event.consume();
        });
    }

    private void loadData() {
        commandData = CommandRepository.getInstance().load();
        refreshTree();
    }

    private void saveData() {
        CommandRepository.getInstance().save(commandData);
    }

    @FXML
    private void refreshTree() {
        Set<String> expandedIds = collectExpandedIds(cmdTreeView.getRoot());
        TreeItem<Command> rootItem = cmdTreeView.getRoot();
        rootItem.getChildren().clear();

        String keyword = searchInput != null && searchInput.getText() != null
                ? searchInput.getText().trim()
                : "";
        for (Command cmd : commandData) {
            if (keyword.isEmpty()) {
                addToTree(rootItem, cmd);
            } else {
                addFilteredToTree(rootItem, cmd, keyword);
            }
        }

        restoreExpandedIds(rootItem, expandedIds);
        if (!keyword.isEmpty()) {
            expandTreeItem(rootItem);
        }
    }

    private void addToTree(TreeItem<Command> parent, Command cmd) {
        TreeItem<Command> item = new TreeItem<>(cmd);
        item.setExpanded(cmd.isExpanded());
        parent.getChildren().add(item);

        if (cmd.getChildren() != null) {
            for (Command child : cmd.getChildren()) {
                addToTree(item, child);
            }
        }
    }

    private boolean addFilteredToTree(TreeItem<Command> parent, Command cmd, String keyword) {
        boolean selfMatches = matches(cmd, keyword);
        TreeItem<Command> item = new TreeItem<>(cmd);

        boolean childMatches = false;
        if (cmd.getChildren() != null) {
            for (Command child : cmd.getChildren()) {
                childMatches |= addFilteredToTree(item, child, keyword);
            }
        }

        if (selfMatches || childMatches) {
            item.setExpanded(true);
            parent.getChildren().add(item);
            return true;
        }
        return false;
    }

    private boolean matches(Command cmd, String keyword) {
        String type = searchType.getValue() != null ? searchType.getValue() : "全部";
        if ("名称".equals(type)) {
            return contains(cmd.getName(), keyword);
        }
        if ("命令".equals(type)) {
            return !cmd.isCategory() && contains(cmd.getCommand(), keyword);
        }
        if ("描述".equals(type)) {
            return !cmd.isCategory() && contains(cmd.getDescription(), keyword);
        }
        return contains(cmd.getName(), keyword)
                || (!cmd.isCategory() && contains(cmd.getCommand(), keyword))
                || (!cmd.isCategory() && contains(cmd.getDescription(), keyword));
    }

    private boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private Set<String> collectExpandedIds(TreeItem<Command> item) {
        Set<String> ids = new HashSet<>();
        if (item == null) {
            return ids;
        }
        if (item.isExpanded() && item.getValue() != null) {
            ids.add(item.getValue().getId());
        }
        for (TreeItem<Command> child : item.getChildren()) {
            ids.addAll(collectExpandedIds(child));
        }
        return ids;
    }

    private void restoreExpandedIds(TreeItem<Command> item, Set<String> expandedIds) {
        if (item == null) {
            return;
        }
        if (item.getValue() != null && expandedIds.contains(item.getValue().getId())) {
            item.setExpanded(true);
        }
        for (TreeItem<Command> child : item.getChildren()) {
            restoreExpandedIds(child, expandedIds);
        }
    }

    @FXML
    private void closeDialog() {
        saveData();
        if (dialogStage != null) {
            dialogStage.close();
        }
    }

    @FXML
    private void minimizeDialog() {
        if (dialogStage != null) {
            dialogStage.setIconified(true);
        }
    }

    @FXML
    private void toggleMaximize() {
        if (dialogStage != null) {
            dialogStage.setMaximized(!dialogStage.isMaximized());
            updateMaximizeIcon();
        }
    }

    private void updateMaximizeIcon() {
        if (maximizeIcon == null || dialogStage == null) {
            return;
        }
        maximizeIcon.setIconLiteral(dialogStage.isMaximized() ? "far-window-restore" : "far-square");
    }

    private void openNewCommand() {
        openCommandEditor(null, false, this::saveNewCommand, null, resolveSelectedCategoryId());
    }

    private void editCommandOrCategory(Command cmd) {
        if (cmd == null) {
            return;
        }
        if (cmd.isCategory()) {
            editCategory(cmd);
        } else {
            openCommandEditor(cmd, true, this::updateCommand, this::deleteCommand, cmd.getCategoryId());
        }
    }

    private void openCommandEditor(Command command,
                                   boolean editMode,
                                   Consumer<Command> saveHandler,
                                   Consumer<Command> deleteHandler,
                                   String selectedCategoryId) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/CommandEditor.fxml"));
            Parent dialogRoot = loader.load();

            CommandEditorController controller = loader.getController();
            Stage stage = new Stage();
            ApplicationIcons.applyTo(stage);
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setTitle(editMode ? "编辑命令" : "新建命令");
            stage.initOwner(dialogStage);

            Scene scene = new Scene(dialogRoot, 560, 420);
            ThemeManager.getInstance().registerScene(scene);
            stage.setScene(scene);
            controller.setDialogStage(stage);
            controller.setEditMode(editMode);
            if (command != null) {
                controller.loadCommand(command);
            } else {
                controller.setCategoryId(selectedCategoryId);
            }
            controller.setSaveHandler(saveHandler);
            if (deleteHandler != null) {
                controller.setDeleteHandler(deleteHandler);
            }

            stage.showAndWait();
            ThemeManager.getInstance().unregisterScene(scene);
        } catch (IOException e) {
            DialogHelper.showError("错误", "无法加载命令编辑器: " + e.getMessage());
            LOGGER.error("加载命令编辑器失败", e);
        }
    }

    private void saveNewCommand(Command cmd) {
        ensureCommand(cmd);
        addCommandToParent(cmd);
        refreshTree();
        saveData();
    }

    private void updateCommand(Command cmd) {
        if (cmd == null) {
            return;
        }
        ensureCommand(cmd);
        removeCommand(commandData, cmd.getId());
        addCommandToParent(cmd);
        refreshTree();
        saveData();
    }

    private void addCommandToParent(Command cmd) {
        String categoryId = cmd.getCategoryId();
        if (categoryId != null && !categoryId.isBlank() && findAndAddToCategory(commandData, categoryId, cmd)) {
            return;
        }
        cmd.setCategoryId("");
        commandData.add(cmd);
    }

    private boolean findAndAddToCategory(List<Command> list, String categoryId, Command cmd) {
        for (Command c : list) {
            if (c.isCategory() && c.getId().equals(categoryId)) {
                c.getChildren().add(cmd);
                return true;
            }
            if (c.getChildren() != null && findAndAddToCategory(c.getChildren(), categoryId, cmd)) {
                return true;
            }
        }
        return false;
    }

    private void deleteCommand(Command cmd) {
        if (cmd == null) {
            return;
        }
        String itemType = cmd.isCategory() ? "分类" : "命令";
        if (DialogHelper.showConfirm("确认删除", "确定要删除" + itemType + " \"" + cmd.getName() + "\" 吗？")) {
            removeCommand(commandData, cmd.getId());
            refreshTree();
            saveData();
        }
    }

    private boolean removeCommand(List<Command> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            Command c = list.get(i);
            if (c.getId().equals(id)) {
                list.remove(i);
                return true;
            }
            if (c.getChildren() != null && removeCommand(c.getChildren(), id)) {
                return true;
            }
        }
        return false;
    }

    private void createNewCategory() {
        String name = DialogHelper.showTextInput("新建分类", null, "分类名称", "新分类");
        if (name == null) {
            return;
        }

        Command newCategory = Command.createCategory(name);
        newCategory.setExpanded(true);

        String parentId = resolveSelectedCategoryId();
        if (parentId != null && !parentId.isBlank() && findAndAddToCategory(commandData, parentId, newCategory)) {
            newCategory.setCategoryId(parentId);
        } else {
            commandData.add(newCategory);
        }

        refreshTree();
        saveData();
    }

    private void editCategory(Command category) {
        String name = DialogHelper.showTextInput("编辑分类", null, "分类名称", category.getName());
        if (name == null) {
            return;
        }
        category.setName(name);
        refreshTree();
        saveData();
    }

    private String resolveSelectedCategoryId() {
        TreeItem<Command> selected = cmdTreeView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null) {
            return "";
        }
        Command value = selected.getValue();
        if (value.isCategory()) {
            return value.getId();
        }
        return value.getCategoryId() != null ? value.getCategoryId() : "";
    }

    private String parseCommandDrag(Dragboard dragboard) {
        if (dragboard == null || !dragboard.hasString()) return null;
        String value = dragboard.getString();
        if (value == null || !value.startsWith(COMMAND_DRAG_PREFIX)) return null;
        String id = value.substring(COMMAND_DRAG_PREFIX.length()).trim();
        return id.isEmpty() ? null : id;
    }

    private boolean canMoveCommand(String draggedId, Command target) {
        if (target == null || !target.isCategory()) return false;
        return canMoveCommandToCategory(draggedId, target.getId());
    }

    private boolean canMoveCommandToCategory(String draggedId, String targetCategoryId) {
        Command source = findCommandById(commandData, draggedId);
        if (source == null) return false;
        String categoryId = targetCategoryId == null ? "" : targetCategoryId;
        if (Objects.equals(source.getCategoryId() == null ? "" : source.getCategoryId(), categoryId)) return false;
        if (Objects.equals(source.getId(), categoryId)) return false;
        return !source.isCategory() || !containsCommandId(source.getChildren(), categoryId);
    }

    private boolean moveCommand(String draggedId, Command target) {
        if (target == null || !target.isCategory()) return false;
        return moveCommandToCategory(draggedId, target.getId());
    }

    private boolean moveCommandToCategory(String draggedId, String targetCategoryId) {
        if (!canMoveCommandToCategory(draggedId, targetCategoryId)) return false;
        Command source = findCommandById(commandData, draggedId);
        String categoryId = targetCategoryId == null ? "" : targetCategoryId;
        String targetName = categoryId.isBlank() ? "顶层" : Optional.ofNullable(findCommandById(commandData, categoryId))
                .map(Command::getName)
                .orElse("目标分类");
        if (!DialogHelper.showConfirm("确认移动", "确定将 \"" + source.getName() + "\" 移动到 \"" + targetName + "\" 下吗？")) {
            return false;
        }

        Command moved = removeCommandNode(commandData, draggedId);
        if (moved == null) return false;
        moved.setCategoryId(categoryId);
        if (!categoryId.isBlank()) {
            Command target = findCommandById(commandData, categoryId);
            if (target == null || !target.isCategory()) {
                commandData.add(moved);
                moved.setCategoryId("");
            } else {
                ensureCommand(target);
                target.getChildren().add(moved);
                target.setExpanded(true);
            }
        } else {
            commandData.add(moved);
        }
        refreshTree();
        saveData();
        selectCommandNode(moved.getId());
        return true;
    }

    private Command findCommandById(List<Command> list, String id) {
        if (id == null || id.isBlank() || list == null) return null;
        for (Command command : list) {
            if (id.equals(command.getId())) {
                return command;
            }
            Command found = findCommandById(command.getChildren(), id);
            if (found != null) return found;
        }
        return null;
    }

    private boolean containsCommandId(List<Command> list, String id) {
        return findCommandById(list, id) != null;
    }

    private Command removeCommandNode(List<Command> list, String id) {
        if (list == null || id == null) return null;
        for (int i = 0; i < list.size(); i++) {
            Command command = list.get(i);
            if (id.equals(command.getId())) {
                return list.remove(i);
            }
            Command removed = removeCommandNode(command.getChildren(), id);
            if (removed != null) {
                return removed;
            }
        }
        return null;
    }

    private void selectCommandNode(String id) {
        TreeItem<Command> item = findCommandTreeItem(cmdTreeView.getRoot(), id);
        if (item == null) return;
        cmdTreeView.getSelectionModel().select(item);
        TreeItem<Command> parent = item.getParent();
        while (parent != null) {
            parent.setExpanded(true);
            if (parent.getValue() != null) {
                parent.getValue().setExpanded(true);
            }
            parent = parent.getParent();
        }
    }

    private TreeItem<Command> findCommandTreeItem(TreeItem<Command> item, String id) {
        if (item == null || id == null) return null;
        if (item.getValue() != null && id.equals(item.getValue().getId())) {
            return item;
        }
        for (TreeItem<Command> child : item.getChildren()) {
            TreeItem<Command> found = findCommandTreeItem(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private void expandAll() {
        TreeItem<Command> rootItem = cmdTreeView.getRoot();
        expandTreeItem(rootItem);
    }

    private void expandTreeItem(TreeItem<Command> item) {
        if (item != null) {
            item.setExpanded(true);
            if (item.getValue() != null) {
                item.getValue().setExpanded(true);
            }
            for (TreeItem<Command> child : item.getChildren()) {
                expandTreeItem(child);
            }
        }
    }

    private void collapseAll() {
        TreeItem<Command> rootItem = cmdTreeView.getRoot();
        rootItem.setExpanded(true);
        for (TreeItem<Command> child : rootItem.getChildren()) {
            collapseTreeItem(child);
        }
    }

    private void collapseTreeItem(TreeItem<Command> item) {
        if (item != null) {
            item.setExpanded(false);
            if (item.getValue() != null) {
                item.getValue().setExpanded(false);
            }
            for (TreeItem<Command> child : item.getChildren()) {
                collapseTreeItem(child);
            }
        }
    }

    private void runCommandInCurrentSession(Command cmd) {
        if (cmd == null || cmd.isCategory()) {
            return;
        }
        int count = CommandExecutionService.getInstance().executeCurrent(cmd.getCommand());
        if (count == 0) {
            DialogHelper.showWarning("执行失败", "当前没有可执行命令的会话。");
        }
    }

    private void runCommandInAllSessions(Command cmd) {
        if (cmd == null || cmd.isCategory()) {
            return;
        }
        int count = CommandExecutionService.getInstance().executeAll(cmd.getCommand());
        if (count == 0) {
            DialogHelper.showWarning("执行失败", "没有可执行命令的已连接会话。");
        }
    }

    private void ensureCommand(Command cmd) {
        if (cmd.getId() == null || cmd.getId().isBlank()) {
            cmd.setId(UUID.randomUUID().toString());
        }
        if (cmd.getType() == null || cmd.getType().isBlank()) {
            cmd.setType("command");
        }
        if (cmd.getChildren() == null) {
            cmd.setChildren(new ArrayList<>());
        }
    }

}

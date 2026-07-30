package com.yshell.ui;

import com.yshell.controller.AiViewController;
import com.yshell.model.ConnInfo;
import com.yshell.service.ConnectionManager;
import com.yshell.service.SshService;
import com.yshell.theme.ThemeManager;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class AiAssistantWindowManager {
    private final Map<String, AiAssistantInstance> instances = new HashMap<>();
    private final Consumer<String> onDocked;

    public AiAssistantWindowManager(Consumer<String> onDocked) {
        this.onDocked = onDocked;
    }

    public void mountForConnection(String connId, StackPane dockHost) {
        if (dockHost == null) {
            return;
        }
        dockHost.getChildren().clear();
        if (connId == null || connId.isBlank()) {
            return;
        }
        AiAssistantInstance instance = instanceFor(connId);
        if (instance.isFloating()) {
            Label placeholder = new Label("AI 助手已在独立窗口中运行");
            placeholder.getStyleClass().add("ai-floating-placeholder");
            dockHost.getChildren().add(placeholder);
            return;
        }
        detach(instance.root);
        dockHost.getChildren().add(instance.root);
    }

    public void showFloating(String connId) {
        if (connId == null || connId.isBlank()) {
            return;
        }
        AiAssistantInstance instance = instanceFor(connId);
        if (instance.isFloating()) {
            instance.stage.show();
            instance.stage.toFront();
            instance.stage.requestFocus();
            return;
        }
        detach(instance.root);
        instance.floatingHost.getChildren().setAll(instance.root);
        String title = "AI 助手 · " + connectionLabel(connId);
        instance.stage.setTitle(title);
        instance.titleLabel.setText(title);
        instance.stage.show();
        instance.stage.toFront();
        instance.stage.requestFocus();
    }

    public void dispose(String connId) {
        AiAssistantInstance instance = instances.remove(connId);
        if (instance == null) {
            return;
        }
        instance.controller.dispose();
        detach(instance.root);
        if (instance.stage != null) {
            Scene scene = instance.stage.getScene();
            instance.stage.setOnCloseRequest(null);
            instance.stage.hide();
            if (scene != null) {
                ThemeManager.getInstance().unregisterScene(scene);
            }
        }
    }

    private AiAssistantInstance instanceFor(String connId) {
        return instances.computeIfAbsent(connId, this::createInstance);
    }

    private AiAssistantInstance createInstance(String connId) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/AiView.fxml"));
            VBox root = loader.load();
            AiViewController controller = loader.getController();
            controller.bindConnection(connId);

            StackPane floatingHost = new StackPane();
            BorderPane windowRoot = new BorderPane();
            windowRoot.getStyleClass().add("ai-floating-window");
            Label titleLabel = new Label();
            titleLabel.getStyleClass().add("ai-floating-title");
            Button dockButton = new Button();
            dockButton.getStyleClass().add("ai-floating-close");
            dockButton.setGraphic(new FontIcon("fas-times"));
            dockButton.setTooltip(new Tooltip("停靠 AI 助手"));
            Region titleSpacer = new Region();
            HBox.setHgrow(titleSpacer, Priority.ALWAYS);
            HBox titleBar = new HBox(titleLabel, titleSpacer, dockButton);
            titleBar.getStyleClass().add("ai-floating-title-bar");
            windowRoot.setTop(titleBar);
            windowRoot.setCenter(floatingHost);

            Scene scene = new Scene(windowRoot, 860, 680);
            ThemeManager.getInstance().registerScene(scene);

            Stage stage = new Stage();
            stage.initStyle(StageStyle.UNDECORATED);
            ApplicationIcons.applyTo(stage);
            stage.setMinWidth(560);
            stage.setMinHeight(420);
            stage.setScene(scene);

            AiAssistantInstance instance = new AiAssistantInstance(
                    connId, root, controller, floatingHost, stage, titleLabel);
            dockButton.setOnAction(event -> dock(instance));
            stage.setOnCloseRequest(event -> {
                event.consume();
                dock(instance);
            });
            WindowDragResize.apply(windowRoot, 32, dockButton);
            return instance;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load AI assistant view", e);
        }
    }

    private void dock(AiAssistantInstance instance) {
        if (!instance.isFloating()) {
            return;
        }
        instance.floatingHost.getChildren().remove(instance.root);
        instance.stage.hide();
        if (onDocked != null) {
            onDocked.accept(instance.connectionId);
        }
    }

    private void detach(Node node) {
        Parent parent = node.getParent();
        if (parent instanceof Pane pane) {
            pane.getChildren().remove(node);
        }
    }

    private String connectionLabel(String connId) {
        SshService service = ConnectionManager.getInstance().getConnectionById(connId);
        ConnInfo connInfo = service == null ? null : service.getConnInfo();
        if (connInfo == null) {
            return connId;
        }
        if (connInfo.getName() != null && !connInfo.getName().isBlank()) {
            return connInfo.getName();
        }
        return Objects.toString(connInfo.getHost(), connId);
    }

    private record AiAssistantInstance(String connectionId, VBox root, AiViewController controller,
                                       StackPane floatingHost, Stage stage, Label titleLabel) {

        private boolean isFloating() {
            return stage.isShowing();
        }
    }
}

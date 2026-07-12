package com.yshell.ui;

import javafx.scene.control.Dialog;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Objects;

public final class ApplicationIcons {
    private static final Image APP_ICON = loadImage();

    private ApplicationIcons() {
    }

    private static Image loadImage() {
        return new Image(Objects.requireNonNull(
                ApplicationIcons.class.getResourceAsStream("/logo/yshell.png"), "/logo/yshell.png"));
    }

    public static void applyTo(Stage stage) {
        if (stage != null && !stage.getIcons().contains(APP_ICON)) {
            stage.getIcons().add(APP_ICON);
        }
    }

    public static void applyTo(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        dialog.setOnShown(event -> applyTo(dialog.getDialogPane().getScene().getWindow()));
        applyTo(dialog.getDialogPane().getScene().getWindow());
    }

    private static void applyTo(Window window) {
        if (window instanceof Stage stage) {
            applyTo(stage);
        }
    }
}

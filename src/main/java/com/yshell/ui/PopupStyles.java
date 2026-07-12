package com.yshell.ui;

import com.yshell.theme.ThemeManager;
import javafx.scene.Parent;

final class PopupStyles {

    private PopupStyles() {
    }

    static void applyDropdownStylesheets(Parent root) {
        if (root == null) {
            return;
        }
        root.getStylesheets().clear();
        addStylesheet(root, ThemeManager.getInstance().isDarkTheme()
                ? "/css/theme-dark.css"
                : "/css/theme-light.css");
        addStylesheet(root, "/css/theme-variables.css");
        addStylesheet(root, "/css/dropdown-menu.css");
    }

    private static void addStylesheet(Parent root, String resource) {
        var url = PopupStyles.class.getResource(resource);
        if (url != null) {
            root.getStylesheets().add(url.toExternalForm());
        }
    }
}

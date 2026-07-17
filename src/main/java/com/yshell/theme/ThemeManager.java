package com.yshell.theme;

import com.yshell.config.AppConfigStore;
import javafx.scene.Scene;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class ThemeManager {

    private static ThemeManager instance;
    private static final String DARK_THEME = "vs-dark";
    private static final String LIGHT_THEME = "vs-light";

    private String currentTheme;
    private final List<Scene> scenes = new ArrayList<>();

    private ThemeManager() {
        currentTheme = AppConfigStore.getInstance().getConfig().appearance.theme;
        if (!DARK_THEME.equals(currentTheme) && !LIGHT_THEME.equals(currentTheme)) {
            currentTheme = DARK_THEME;
        }
    }

    public static synchronized ThemeManager getInstance() {
        if (instance == null) {
            instance = new ThemeManager();
        }
        return instance;
    }

    public String getCurrentTheme() {
        return currentTheme;
    }

    public boolean isDarkTheme() {
        return DARK_THEME.equals(currentTheme);
    }

    public void setTheme(String theme) {
        if (!DARK_THEME.equals(theme) && !LIGHT_THEME.equals(theme)) {
            theme = DARK_THEME;
        }

        if (!currentTheme.equals(theme)) {
            currentTheme = theme;
            AppConfigStore.getInstance().getConfig().appearance.theme = theme;
            AppConfigStore.getInstance().save();
            applyThemeToAllScenes();
        }
    }

    public void registerScene(Scene scene) {
        if (!scenes.contains(scene)) {
            scenes.add(scene);
            applyThemeToScene(scene);
        }
    }

    public void unregisterScene(Scene scene) {
        scenes.remove(scene);
    }

    private void applyThemeToAllScenes() {
        for (Scene scene : scenes) {
            applyThemeToScene(scene);
        }
    }

    private void applyThemeToScene(Scene scene) {
        scene.getStylesheets().clear();

        // 1. 先加载主题文件（根据明暗二选一）
        String themeCss = isDarkTheme()
                ? Objects.requireNonNull(getClass().getResource("/css/theme-dark.css")).toExternalForm()
                : Objects.requireNonNull(getClass().getResource("/css/theme-light.css")).toExternalForm();
        scene.getStylesheets().add(themeCss);

        // 2. 加载主题变量文件（固定）
        String variablesCss = Objects.requireNonNull(getClass().getResource("/css/theme-variables.css")).toExternalForm();
        scene.getStylesheets().add(variablesCss);

        // 3. 自动加载 CSS 目录下的其他文件
        loadAllCssFiles(scene);
    }

    private void loadAllCssFiles(Scene scene) {
        try {
            List<String> cssFiles = listCssFiles();

            for (String fileName : cssFiles) {
                // 跳过已经加载过的主题文件
                if (fileName.equals("theme-dark.css") ||
                        fileName.equals("theme-light.css") ||
                        fileName.equals("theme-variables.css") ||
                        fileName.equals("dropdown-menu.css")) { // dropdown-menu.css 是给 Popup 用的，不需要这里加载
                    continue;
                }

                URL resource = getClass().getResource("/css/" + fileName);
                if (resource != null) {
                    scene.getStylesheets().add(resource.toExternalForm());
                }
            }
        } catch (Exception e) {
            // 如果自动加载失败，回退到手动加载的方式（安全网）
            fallbackToManualLoading(scene);
        }
    }

    private List<String> listCssFiles() throws IOException, URISyntaxException {
        List<String> cssFiles = new ArrayList<>();
        URL resourceUrl = getClass().getResource("/css");

        if (resourceUrl == null) {
            return cssFiles;
        }

        URI uri = resourceUrl.toURI();
        Path path;

        // 处理 JAR 文件内的资源
        if (uri.getScheme().equals("jar")) {
            try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
                path = fs.getPath("/css");
                try (Stream<Path> paths = Files.walk(path, 1)) {
                    paths.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".css"))
                            .map(p -> p.getFileName().toString())
                            .forEach(cssFiles::add);
                }
            }
        } else {
            // 处理开发环境下的文件系统资源
            path = Paths.get(uri);
            try (Stream<Path> paths = Files.walk(path, 1)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".css"))
                        .map(p -> p.getFileName().toString())
                        .forEach(cssFiles::add);
            }
        }

        return cssFiles;
    }

    private void fallbackToManualLoading(Scene scene) {
        // 安全网：如果自动加载失败，使用手动加载的方式
        String[] manualCssFiles = {
                "main.css",
                "left-panel.css",
                "terminal.css",
                "toolbar.css",
                "settings-manager.css",
                "key-manager.css",
                "command-dialog.css",
                "command-editor.css",
                "connection-dialog.css",
                "connection-editor.css",
                "connection-toolbar.css",
                "files-view.css",
                "transfer-queue.css",
                "visual-panel.css",
                "ai-view.css",
                "k8s-view.css",
                "k8s-detail-view.css",
                "docker-view.css",
                "quick-connect.css"
        };

        for (String fileName : manualCssFiles) {
            URL resource = getClass().getResource("/css/" + fileName);
            if (resource != null) {
                scene.getStylesheets().add(resource.toExternalForm());
            }
        }
    }

}

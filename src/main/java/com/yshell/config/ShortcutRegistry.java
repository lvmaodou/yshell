package com.yshell.config;

import java.util.List;
import java.util.stream.Stream;

public final class ShortcutRegistry {
    public record Shortcut(String group, String action, String keyText) {
    }

    private static final List<Shortcut> EDITOR_SHORTCUTS = List.of(
            new Shortcut("文件", "新建空白 Tab", "Ctrl+N"),
            new Shortcut("文件", "保存", "Ctrl+S"),
            new Shortcut("文件", "另存为", "Ctrl+Shift+S"),
            new Shortcut("文件", "关闭当前 Tab", "Ctrl+W"),
            new Shortcut("文件", "重新加载", "F5"),
            new Shortcut("编辑", "查找", "Ctrl+F"),
            new Shortcut("编辑", "替换", "Ctrl+Shift+F"),
            new Shortcut("编辑", "跳转到行", "Ctrl+G"),
            new Shortcut("编辑", "注释/取消注释", "Ctrl+/"),
            new Shortcut("编辑", "撤销", "Ctrl+Z"),
            new Shortcut("编辑", "重做", "Ctrl+Y"),
            new Shortcut("编辑", "格式化", "Alt+Shift+F"),
            new Shortcut("视图", "切换自动换行", "Alt+Z"),
            new Shortcut("视图", "增大字号", "Ctrl+="),
            new Shortcut("视图", "减小字号", "Ctrl+-"),
            new Shortcut("帮助", "快捷键帮助", "F1")
    );

    private static final List<Shortcut> TERMINAL_SHORTCUTS = List.of(
            new Shortcut("终端", "查找", "Ctrl+F"),
            new Shortcut("终端", "清屏", "Ctrl+L"),
            new Shortcut("终端", "中断", "Ctrl+C"),
            new Shortcut("终端", "粘贴", "Ctrl+V"),
            new Shortcut("终端", "字号缩放", "Ctrl+鼠标滚轮")
    );

    private ShortcutRegistry() {
    }

    public static List<Shortcut> editorShortcuts() {
        return EDITOR_SHORTCUTS;
    }

    public static List<Shortcut> terminalShortcuts() {
        return TERMINAL_SHORTCUTS;
    }

    public static List<Shortcut> all() {
        return Stream.concat(EDITOR_SHORTCUTS.stream(), TERMINAL_SHORTCUTS.stream()).toList();
    }

    public static String editorHelpText() {
        StringBuilder builder = new StringBuilder();
        String currentGroup = "";
        for (Shortcut shortcut : EDITOR_SHORTCUTS) {
            if (!shortcut.group().equals(currentGroup)) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                currentGroup = shortcut.group();
                builder.append(currentGroup).append('\n');
            }
            builder.append(shortcut.keyText()).append("：").append(shortcut.action()).append('\n');
        }
        return builder.toString().stripTrailing();
    }
}

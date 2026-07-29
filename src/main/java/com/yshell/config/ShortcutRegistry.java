package com.yshell.config;

import java.util.List;
import java.util.stream.Stream;

public final class ShortcutRegistry {
    public record Shortcut(String group, String action, String keyText) {
    }

    private static final List<Shortcut> EDITOR_SHORTCUTS = List.of(
            new Shortcut("编辑器", "新建空白 Tab", "Ctrl+N"),
            new Shortcut("编辑器", "保存", "Ctrl+S"),
            new Shortcut("编辑器", "另存为", "Ctrl+Shift+S"),
            new Shortcut("编辑器", "关闭当前 Tab", "Ctrl+W"),
            new Shortcut("编辑器", "重新加载", "F5"),
            new Shortcut("编辑器", "查找", "Ctrl+F"),
            new Shortcut("编辑器", "替换", "Ctrl+Shift+F"),
            new Shortcut("编辑器", "跳转到行", "Ctrl+G"),
            new Shortcut("编辑器", "注释/取消注释", "Ctrl+/"),
            new Shortcut("编辑器", "撤销", "Ctrl+Z"),
            new Shortcut("编辑器", "重做", "Ctrl+Y"),
            new Shortcut("编辑器", "复制", "Ctrl+C"),
            new Shortcut("编辑器", "粘贴", "Ctrl+V"),
            new Shortcut("编辑器", "剪切", "Ctrl+X"),
            new Shortcut("编辑器", "格式化", "Alt+Shift+F"),
            new Shortcut("编辑器", "切换自动换行", "Alt+Z"),
            new Shortcut("编辑器", "增大字号", "Ctrl++"),
            new Shortcut("编辑器", "减小字号", "Ctrl+-"),
            new Shortcut("编辑器", "显示快捷键帮助", "F1")
    );

    private static final List<Shortcut> TERMINAL_SHORTCUTS = List.of(
            new Shortcut("终端", "查找", "Ctrl+F"),
            new Shortcut("终端", "清屏", "Ctrl+L"),
            new Shortcut("终端", "复制选中内容/中断命令", "Ctrl+C"),
            new Shortcut("终端", "粘贴", "Ctrl+V"),
            new Shortcut("终端", "关闭查找", "Esc"),
            new Shortcut("终端", "查找下一处", "Enter"),
            new Shortcut("终端", "查找上一处", "Shift+Enter"),
            new Shortcut("终端", "字号缩放", "Ctrl+鼠标滚轮")
    );

    private static final List<Shortcut> AI_ASSISTANT_SHORTCUTS = List.of(
            new Shortcut("AI助手", "发送消息", "Enter"),
            new Shortcut("AI助手", "输入换行", "Shift+Enter"),
            new Shortcut("AI助手", "粘贴图片或文件", "Ctrl+V"),
            new Shortcut("AI助手", "复制选中内容", "Ctrl+C"),
            new Shortcut("AI助手", "执行选中代码或代码块", "Ctrl+Enter")
    );

    private static final List<Shortcut> DOCKER_SHORTCUTS = List.of(
            new Shortcut("Docker", "复制选中的表格或日志内容", "Ctrl+C")
    );

    private static final List<Shortcut> K8S_SHORTCUTS = List.of(
            new Shortcut("K8s", "复制选中的资源、详情或日志内容", "Ctrl+C"),
            new Shortcut("K8s", "跳转到指定页", "Enter")
    );

    private ShortcutRegistry() {
    }

    public static List<Shortcut> all() {
        return Stream.of(
                        EDITOR_SHORTCUTS,
                        TERMINAL_SHORTCUTS,
                        AI_ASSISTANT_SHORTCUTS,
                        DOCKER_SHORTCUTS,
                        K8S_SHORTCUTS
                )
                .flatMap(List::stream)
                .toList();
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

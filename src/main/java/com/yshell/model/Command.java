package com.yshell.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Command {
    private String id;
    private String type; // "category" or "command"
    private String name;
    private String command;
    private String description;
    private String categoryId;
    private boolean expanded;
    private List<Command> children;

    public Command() {
        this.children = new ArrayList<>();
    }

    public Command(String id, String type, String name) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.children = new ArrayList<>();
    }

    public static Command createCategory(String name) {
        return new Command(UUID.randomUUID().toString(), "category", name);
    }

    public static Command createCommand(String name, String command, String description, String categoryId) {
        Command cmd = new Command(UUID.randomUUID().toString(), "command", name);
        cmd.setCommand(command);
        cmd.setDescription(description);
        cmd.setCategoryId(categoryId);
        return cmd;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public List<Command> getChildren() {
        return children;
    }

    public void setChildren(List<Command> children) {
        this.children = children;
    }

    public boolean isCategory() {
        return "category".equals(type);
    }

    @Override
    public String toString() {
        return name;
    }
}

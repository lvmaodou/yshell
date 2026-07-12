package com.yshell.model;

import javafx.beans.property.SimpleStringProperty;

public class UserInfo {
    private final SimpleStringProperty uid;
    private final SimpleStringProperty username;
    private final SimpleStringProperty gid;
    private final SimpleStringProperty groupName;

    public UserInfo(String uid, String username, String gid, String groupName) {
        this.uid = new SimpleStringProperty(uid);
        this.username = new SimpleStringProperty(username);
        this.gid = new SimpleStringProperty(gid);
        this.groupName = new SimpleStringProperty(groupName);
    }

    public SimpleStringProperty uidProperty() {
        return uid;
    }

    public String getUid() {
        return uid.get();
    }

    public SimpleStringProperty usernameProperty() {
        return username;
    }

    public String getUsername() {
        return username.get();
    }

    public SimpleStringProperty gidProperty() {
        return gid;
    }

    public String getGid() {
        return gid.get();
    }

    public SimpleStringProperty groupProperty() {
        return groupName;
    }

    public String getGroup() {
        return groupName.get();
    }
}

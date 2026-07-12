package com.yshell.model;

import java.util.UUID;

/**
 * 代理服务器配置模型
 */
public class ProxyInfo {

    private String id;
    private String name;
    /**
     * 代理类型：socks5 / http
     */
    private String type = "socks5";
    /**
     * 代理主机地址
     */
    private String host;
    /**
     * 代理端口
     */
    private int port;
    /**
     * 用户名（可选）
     */
    private String username;
    /**
     * 密码（可选）
     */
    private String password;

    public ProxyInfo() {
        this.id = UUID.randomUUID().toString().replace("-", "");
    }

    // Getter & Setter
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * 获取类型的中文显示名
     */
    public String getTypeDisplayName() {
        return switch (type) {
            case "socks5" -> "SOCKS5";
            case "http" -> "HTTP";
            default -> type;
        };
    }
}

package com.yshell.model;

import java.util.UUID;

/**
 * 隧道配置模型
 */
public class TunnelInfo {

    private String id;
    private String name;
    /**
     * 隧道类型：local（本地转发）/ remote（远程转发）/ dynamic（SOCKS5动态转发）
     */
    private String type = "local";
    /**
     * 监听端口（本地端口）
     */
    private int listenPort;
    /**
     * 绑定IP
     */
    private String bindIp = "127.0.0.1";
    /**
     * 目标地址（远程主机）
     */
    private String targetHost;
    /**
     * 目标端口
     */
    private int targetPort;

    public TunnelInfo() {
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

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    public String getBindIp() {
        return bindIp;
    }

    public void setBindIp(String bindIp) {
        this.bindIp = bindIp;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public void setTargetHost(String targetHost) {
        this.targetHost = targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }

    /**
     * 获取类型的中文显示名
     */
    public String getTypeDisplayName() {
        return switch (type) {
            case "local" -> "本地";
            case "remote" -> "远程";
            case "dynamic" -> "SOCKS5";
            default -> type;
        };
    }
}

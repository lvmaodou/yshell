package com.yshell.model;

import java.util.*;

/**
 * 连接信息模型（纯连接数据，对应磁盘上的一个 .json 文件）
 * 与 Folder 完全独立，各存各的
 */
public class ConnInfo implements TreeNode {

    // ===== 基础字段（TreeNode 接口） =====
    private String id;
    private String name;
    /**
     * 父节点id（文件夹id），顶层为 "root"
     */
    private String parentId = "root";
    /**
     * 同级排序序号
     */
    private int order;
    /**
     * 连接类型：ssh / rdp / sftp / telnet
     */
    private String type;

    // ===== 时间戳 =====
    private long createTime;
    private long modifiedTime;
    private long accessTime;
    private long deleteTime;
    private long sortTime;
    private long renameTime;
    private long parentUpdateTime;

    // ===== SSH/RDP 连接参数 =====
    private String host;
    private int port;
    private String userName;
    private String password;
    private String description = "";
    /**
     * 认证方式：1=密码 2=私钥
     */
    private int authenticationType = 1;
    private String secretKeyId = "";
    private String terminalEncoding = "UTF-8";
    /**
     * 连接类型码（100=SSH, 200=RDP）
     */
    private int connectionType;

    // ===== SSH 高级选项 =====
    private boolean accelerate;
    private boolean execChannelEnable = true;
    private int backspaceKeySequence = 2;
    private int deleteKeySequence = 0;
    private boolean forwardingAutoReconnect;
    private String proxyId = "0";
    private List<Object> portForwardingList = new ArrayList<>();
    private Map<String, Object> remotePortForwarding = new HashMap<>();

    // ===== RDP 选项 =====
    private boolean fullscreen;
    private boolean customSize;
    private int width;
    private int height;
    private boolean driveStoreDirect = true;

    public ConnInfo() {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.createTime = System.currentTimeMillis();
        this.modifiedTime = System.currentTimeMillis();
    }

    public ConnInfo(String type) {
        this();
        this.type = type;
    }

    // ===== TreeNode 接口实现 =====

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getParentId() {
        return parentId;
    }

    @Override
    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public void setOrder(int order) {
        this.order = order;
    }

    @Override
    public boolean isFolder() {
        return false;
    }

    @Override
    public long getCreateTime() {
        return createTime;
    }

    @Override
    public void setCreateTime(long v) {
        this.createTime = v;
    }

    @Override
    public long getModifiedTime() {
        return modifiedTime;
    }

    @Override
    public void setModifiedTime(long v) {
        this.modifiedTime = v;
    }

    @Override
    public long getAccessTime() {
        return accessTime;
    }

    @Override
    public void setAccessTime(long v) {
        this.accessTime = v;
    }

    @Override
    public long getDeleteTime() {
        return deleteTime;
    }

    @Override
    public void setDeleteTime(long v) {
        this.deleteTime = v;
    }

    @Override
    public long getSortTime() {
        return sortTime;
    }

    @Override
    public void setSortTime(long v) {
        this.sortTime = v;
    }

    @Override
    public long getRenameTime() {
        return renameTime;
    }

    @Override
    public void setRenameTime(long v) {
        this.renameTime = v;
    }

    @Override
    public long getParentUpdateTime() {
        return parentUpdateTime;
    }

    @Override
    public void setParentUpdateTime(long v) {
        this.parentUpdateTime = v;
    }

    // ===== 连接类型 =====

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    // ===== 连接参数 =====

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

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getAuthenticationType() {
        return authenticationType;
    }

    public void setAuthenticationType(int authenticationType) {
        this.authenticationType = authenticationType;
    }

    public String getSecretKeyId() {
        return secretKeyId;
    }

    public void setSecretKeyId(String secretKeyId) {
        this.secretKeyId = secretKeyId;
    }

    public String getTerminalEncoding() {
        return terminalEncoding;
    }

    public void setTerminalEncoding(String terminalEncoding) {
        this.terminalEncoding = terminalEncoding;
    }

    public int getConnectionType() {
        return connectionType;
    }

    public void setConnectionType(int connectionType) {
        this.connectionType = connectionType;
    }

    // ===== SSH 高级选项 =====

    public boolean isAccelerate() {
        return accelerate;
    }

    public void setAccelerate(boolean accelerate) {
        this.accelerate = accelerate;
    }

    public boolean isExecChannelEnable() {
        return execChannelEnable;
    }

    public void setExecChannelEnable(boolean execChannelEnable) {
        this.execChannelEnable = execChannelEnable;
    }

    public int getBackspaceKeySequence() {
        return backspaceKeySequence;
    }

    public void setBackspaceKeySequence(int backspaceKeySequence) {
        this.backspaceKeySequence = backspaceKeySequence;
    }

    public int getDeleteKeySequence() {
        return deleteKeySequence;
    }

    public void setDeleteKeySequence(int deleteKeySequence) {
        this.deleteKeySequence = deleteKeySequence;
    }

    public boolean isForwardingAutoReconnect() {
        return forwardingAutoReconnect;
    }

    public void setForwardingAutoReconnect(boolean forwardingAutoReconnect) {
        this.forwardingAutoReconnect = forwardingAutoReconnect;
    }

    public String getProxyId() {
        return proxyId;
    }

    public void setProxyId(String proxyId) {
        this.proxyId = proxyId;
    }

    public List<Object> getPortForwardingList() {
        return portForwardingList;
    }

    public void setPortForwardingList(List<Object> list) {
        this.portForwardingList = list;
    }

    public Map<String, Object> getRemotePortForwarding() {
        return remotePortForwarding;
    }

    public void setRemotePortForwarding(Map<String, Object> map) {
        this.remotePortForwarding = map;
    }

    // ===== RDP 选项 =====

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public boolean isCustomSize() {
        return customSize;
    }

    public void setCustomSize(boolean customSize) {
        this.customSize = customSize;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public boolean isDriveStoreDirect() {
        return driveStoreDirect;
    }

    public void setDriveStoreDirect(boolean driveStoreDirect) {
        this.driveStoreDirect = driveStoreDirect;
    }

    @Override
    public String toString() {
        return name != null ? name : "";
    }
}

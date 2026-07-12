package com.yshell.model;

import java.util.UUID;

/**
 * 文件夹数据模型
 */
public class Folder implements TreeNode {

    private String id;
    private String name;
    /** 父节点id，顶层为 "root" */
    private String parentId = "root";
    /** 同级排序序号 */
    private int order;

    // ===== 时间戳 =====
    private long createTime;
    private long modifiedTime;
    private long accessTime;
    private long deleteTime;
    private long sortTime;
    private long renameTime;
    private long parentUpdateTime;

    public Folder() {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.createTime = System.currentTimeMillis();
        this.modifiedTime = System.currentTimeMillis();
    }

    public Folder(String id, String name) {
        this();
        this.id = id;
        this.name = name;
    }

    @Override
    public String getId() { return id; }
    @Override
    public void setId(String id) { this.id = id; }

    @Override
    public String getName() { return name; }
    @Override
    public void setName(String name) { this.name = name; }

    @Override
    public String getParentId() { return parentId; }
    @Override
    public void setParentId(String parentId) { this.parentId = parentId; }

    @Override
    public int getOrder() { return order; }
    @Override
    public void setOrder(int order) { this.order = order; }

    @Override
    public boolean isFolder() { return true; }

    @Override
    public long getCreateTime() { return createTime; }
    @Override
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    @Override
    public long getModifiedTime() { return modifiedTime; }
    @Override
    public void setModifiedTime(long modifiedTime) { this.modifiedTime = modifiedTime; }

    @Override
    public long getAccessTime() { return accessTime; }
    @Override
    public void setAccessTime(long accessTime) { this.accessTime = accessTime; }

    @Override
    public long getDeleteTime() { return deleteTime; }
    @Override
    public void setDeleteTime(long deleteTime) { this.deleteTime = deleteTime; }

    @Override
    public long getSortTime() { return sortTime; }
    @Override
    public void setSortTime(long sortTime) { this.sortTime = sortTime; }

    @Override
    public long getRenameTime() { return renameTime; }
    @Override
    public void setRenameTime(long renameTime) { this.renameTime = renameTime; }

    @Override
    public long getParentUpdateTime() { return parentUpdateTime; }
    @Override
    public void setParentUpdateTime(long parentUpdateTime) { this.parentUpdateTime = parentUpdateTime; }

    @Override
    public String toString() { return name != null ? name : ""; }
}

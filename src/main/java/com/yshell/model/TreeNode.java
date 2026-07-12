package com.yshell.model;

/**
 * 树节点公共接口
 * 文件夹(Folder)和连接(Connection)都实现此接口，
 * TreeView<TreeNode> 统一管理两种节点
 */
public interface TreeNode {

    String getId();
    void setId(String id);

    String getName();
    void setName(String name);

    /** 父节点id，顶层为 "root" */
    String getParentId();
    void setParentId(String parentId);

    /** 同级排序序号 */
    int getOrder();
    void setOrder(int order);

    /** 是否为文件夹 */
    boolean isFolder();

    // ===== 时间戳 =====

    long getCreateTime();
    void setCreateTime(long createTime);

    long getModifiedTime();
    void setModifiedTime(long modifiedTime);

    long getAccessTime();
    void setAccessTime(long accessTime);

    long getDeleteTime();
    void setDeleteTime(long deleteTime);

    long getSortTime();
    void setSortTime(long sortTime);

    long getRenameTime();
    void setRenameTime(long renameTime);

    long getParentUpdateTime();
    void setParentUpdateTime(long parentUpdateTime);
}

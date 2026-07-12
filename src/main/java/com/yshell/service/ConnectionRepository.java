package com.yshell.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.ConnInfo;
import com.yshell.model.Folder;
import com.yshell.model.TreeNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连接数据持久化服务 — 树结构即磁盘结构
 * 存储规则：
 * - 文件夹 → 磁盘上是一个目录，目录内含 info.json 描述文件夹信息
 * - 连接信息 → 磁盘上是父文件夹目录（或根目录）下的 {id}.json 文件
 * ~/.yshell/connections/
 * ├── folder-id/              ← 文件夹（目录）
 * │   ├── info.json            ← 文件夹元信息
 * │   └── conn-id.json         ← 该文件夹下的连接
 * └── top-level-conn.json     ← 顶层连接（直接在 connections/ 下）
 */
public class ConnectionRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionRepository.class);
    private static final String APP_DIR = ".yshell";
    private static final String DATA_DIR_NAME = "connections";
    private static final String FOLDER_INFO_FILE = "info.json";

    private static ConnectionRepository instance;

    private final ObjectMapper objectMapper;
    private final Path dataDirPath;

    private ConnectionRepository() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String userHome = System.getProperty("user.home");
        dataDirPath = Paths.get(userHome, APP_DIR, DATA_DIR_NAME);
    }

    public static synchronized ConnectionRepository getInstance() {
        if (instance == null) {
            instance = new ConnectionRepository();
        }
        return instance;
    }

    // ===== 保存 =====

    /**
     * 保存所有树节点到文件系统（递归构建目录树，确保层级正确）
     */
    public void save(List<TreeNode> nodes) {
        try {
            ensureDir(dataDirPath);
            cleanDataDir();

            if (nodes.isEmpty()) return;

            // 建 id→节点映射
            Map<String, TreeNode> nodeMap = new HashMap<>();
            for (TreeNode n : nodes) {
                nodeMap.put(n.getId(), n);
            }

            // 先保存所有顶层节点（parentId="root"），再递归子节点
            for (TreeNode node : nodes) {
                if ("root".equals(node.getParentId())) {
                    saveNode(node, dataDirPath, nodeMap);
                }
            }

            LOGGER.info("数据已保存到: {}，共 {} 条", dataDirPath, nodes.size());
        } catch (IOException e) {
            LOGGER.error("保存失败", e);
        }
    }

    /**
     * 递归保存单个节点及其所有子节点
     *
     * @param node    当前要保存的节点
     * @param dir     当前节点应存放的磁盘目录
     * @param nodeMap 全局 id→节点 映射（用于查找子节点）
     */
    private void saveNode(TreeNode node, Path dir, Map<String, TreeNode> nodeMap) throws IOException {
        if (node.isFolder()) {
            // 文件夹：创建子目录 + 写 info.json
            Path folderDir = dir.resolve(node.getId());
            Files.createDirectories(folderDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    folderDir.resolve(FOLDER_INFO_FILE).toFile(), node);

            LOGGER.debug("保存文件夹: {} ({}) 到 {}, parentId={}",
                    node.getName(), node.getId(), folderDir, node.getParentId());

            // 递归保存该文件夹的所有子节点
            for (TreeNode child : nodeMap.values()) {
                if (node.getId().equals(child.getParentId())) {
                    saveNode(child, folderDir, nodeMap);
                }
            }
        } else {
            // 连接：写入 {id}.json
            File jsonFile = dir.resolve(node.getId() + ".json").toFile();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile, node);

            LOGGER.debug("保存连接: {} ({}) 到 {}, parentId={}",
                    node.getName(), node.getId(), jsonFile, node.getParentId());
        }
    }

    // ===== 加载 =====

    /**
     * 从文件系统递归加载所有节点
     */
    public List<TreeNode> load() {
        List<TreeNode> result = new ArrayList<>();

        if (!Files.exists(dataDirPath)) {
            return result;
        }

        scanDirectory(dataDirPath, "root", result);
        LOGGER.info("数据已加载: 共 {} 条", result.size());
        return result;
    }

    /**
     * 递归扫描目录，还原树节点列表
     */
    private void scanDirectory(Path dir, String parentId, List<TreeNode> result) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();

            if (name.startsWith(".")) continue;

            if (file.isDirectory()) {
                Path infoFile = file.toPath().resolve(FOLDER_INFO_FILE);
                if (Files.exists(infoFile)) {
                    try {
                        Folder folder = objectMapper.readValue(infoFile.toFile(), Folder.class);
                        folder.setParentId(parentId);
                        result.add(folder);
                        scanDirectory(file.toPath(), folder.getId(), result);
                    } catch (IOException e) {
                        LOGGER.warn("读取文件夹失败: {}", name, e);
                    }
                } else {
                    scanDirectory(file.toPath(), parentId, result);
                }
            } else if (name.endsWith(".json") && !FOLDER_INFO_FILE.equals(name)) {
                try {
                    ConnInfo conn = objectMapper.readValue(file, ConnInfo.class);
                    conn.setParentId(parentId);
                    result.add(conn);
                } catch (IOException e) {
                    LOGGER.warn("读取连接文件失败: {}", name, e);
                }
            }
        }
    }

    // ===== 工具方法 =====

    private void ensureDir(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    /**
     * 清理数据目录中的所有内容后重建
     */
    private void cleanDataDir() throws IOException {
        File[] files = dataDirPath.toFile().listFiles();
        if (files != null) {
            for (File f : files) {
                deleteRecursive(f);
            }
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        boolean delete = file.delete();
        if (!delete) {
            LOGGER.error("删除文件失败: {}", file.getAbsolutePath());
        }
    }
}

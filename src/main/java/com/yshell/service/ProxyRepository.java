package com.yshell.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.ProxyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局代理服务器配置持久化服务
 * 存储位置：~/.yshell/proxy.json
 * 代理信息是全局共用的，每个连接通过 proxyId 引用
 */
public class ProxyRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyRepository.class);
    private static final String APP_DIR = ".yshell";
    private static final String PROXY_FILE_NAME = "proxy.json";

    private static ProxyRepository instance;

    private final ObjectMapper objectMapper;
    private final Path proxyFilePath;

    private ProxyRepository() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String userHome = System.getProperty("user.home");
        proxyFilePath = Paths.get(userHome, APP_DIR, PROXY_FILE_NAME);
    }

    public static synchronized ProxyRepository getInstance() {
        if (instance == null) {
            instance = new ProxyRepository();
        }
        return instance;
    }

    // ===== 保存 =====

    /**
     * 保存全部代理列表到 proxy.json
     */
    public void save(List<ProxyInfo> proxies) {
        try {
            ensureParentDir();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(proxyFilePath.toFile(), proxies);
            LOGGER.info("代理数据已保存: {} 条 → {}", proxies.size(), proxyFilePath);
        } catch (IOException e) {
            LOGGER.error("保存代理数据失败", e);
        }
    }

    // ===== 加载 =====

    /**
     * 从 proxy.json 加载全部代理列表
     */
    public List<ProxyInfo> load() {
        if (!Files.exists(proxyFilePath)) {
            return new ArrayList<>();
        }
        try {
            List<ProxyInfo> list = objectMapper.readValue(proxyFilePath.toFile(),
                    new TypeReference<>() {
                    });
            if (list == null) {
                return new ArrayList<>();
            }
            LOGGER.info("代理数据已加载: 共 {} 条", list.size());
            return list;
        } catch (IOException e) {
            LOGGER.error("加载代理数据失败", e);
            return new ArrayList<>();
        }
    }

    // ===== 单条操作（便捷方法，操作后自动持久化）=====

    /**
     * 新增或更新代理（根据 id 判断），然后自动保存
     */
    public void upsert(ProxyInfo proxy) {
        List<ProxyInfo> all = load();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(proxy.getId())) {
                all.set(i, proxy);
                found = true;
                break;
            }
        }
        if (!found) {
            all.add(proxy);
        }
        save(all);
    }

    // ===== 工具方法 =====

    private void ensureParentDir() throws IOException {
        Path parent = proxyFilePath.getParent();
        if (!Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}

package com.yshell.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.ConnInfo;
import com.yshell.model.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class RecentConnectionRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecentConnectionRepository.class);
    private static final String APP_DIR = ".yshell";
    private static final String HISTORY_FILE = "recent-connections.json";
    private static final int MAX_RECENT_CONNECTIONS = 30;

    private static RecentConnectionRepository instance;

    private final ObjectMapper objectMapper;
    private final Path historyFilePath;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    private RecentConnectionRepository() {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        historyFilePath = Paths.get(System.getProperty("user.home"), APP_DIR, HISTORY_FILE);
    }

    public static synchronized RecentConnectionRepository getInstance() {
        if (instance == null) {
            instance = new RecentConnectionRepository();
        }
        return instance;
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null && !changeListeners.contains(listener)) {
            changeListeners.add(listener);
        }
    }

    public synchronized List<ConnInfo> load() {
        LoadResult loaded = loadRaw();
        Map<String, ConnInfo> latestById = loadLatestConnectionsById();
        List<RecentEntry> entries = pruneDeletedEntries(loaded.entries(), latestById.keySet());
        if (loaded.needsRewrite() || entries.size() != loaded.entries().size()) {
            save(entries);
        }
        return resolveLatestConnections(entries, latestById);
    }

    public synchronized void record(ConnInfo connInfo) {
        if (connInfo == null || !isNotBlank(connInfo.getId())) {
            return;
        }

        List<RecentEntry> entries = pruneDeletedEntries(loadRaw().entries());
        entries.removeIf(existing -> connInfo.getId().equals(existing.id));

        RecentEntry entry = new RecentEntry();
        entry.id = connInfo.getId();
        entry.accessTime = System.currentTimeMillis();
        entries.add(0, entry);

        if (entries.size() > MAX_RECENT_CONNECTIONS) {
            entries = new ArrayList<>(entries.subList(0, MAX_RECENT_CONNECTIONS));
        }
        save(entries);
        fireChanged();
    }

    public synchronized void removeByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<String> idSet = new HashSet<>(ids);
        List<RecentEntry> entries = loadRaw().entries();
        boolean removed = entries.removeIf(entry -> entry != null && idSet.contains(entry.id));
        if (removed) {
            save(entries);
            fireChanged();
        }
    }

    public synchronized void notifyConnectionDataChanged() {
        List<RecentEntry> entries = loadRaw().entries();
        List<RecentEntry> pruned = pruneDeletedEntries(entries);
        if (entries.size() != pruned.size()) {
            save(pruned);
        }
        fireChanged();
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(historyFilePath);
            fireChanged();
        } catch (IOException e) {
            LOGGER.warn("Failed to clear recent connections", e);
        }
    }

    private LoadResult loadRaw() {
        if (!Files.exists(historyFilePath)) {
            return new LoadResult(new ArrayList<>(), false);
        }
        try {
            JsonNode root = objectMapper.readTree(historyFilePath.toFile());
            List<RecentEntry> entries = new ArrayList<>();
            boolean needsRewrite = false;
            if (root != null && root.isArray()) {
                for (JsonNode item : root) {
                    if (item == null || !item.isObject()) {
                        needsRewrite = true;
                        continue;
                    }
                    RecentEntry entry = new RecentEntry();
                    entry.id = textValue(item.get("id"));
                    if (!isNotBlank(entry.id)) {
                        entry.id = textValue(item.get("connectionId"));
                        if (isNotBlank(entry.id)) {
                            needsRewrite = true;
                        }
                    }
                    entry.accessTime = longValue(item.get("accessTime"));
                    if (!isNotBlank(entry.id)) {
                        needsRewrite = true;
                        continue;
                    }
                    if (item.size() != 2 || item.get("id") == null || item.get("accessTime") == null) {
                        needsRewrite = true;
                    }
                    entries.add(entry);
                }
            } else {
                needsRewrite = true;
            }
            entries.sort((a, b) -> Long.compare(b.accessTime, a.accessTime));
            if (entries.size() > MAX_RECENT_CONNECTIONS) {
                entries = new ArrayList<>(entries.subList(0, MAX_RECENT_CONNECTIONS));
                needsRewrite = true;
            }
            return new LoadResult(entries, needsRewrite);
        } catch (IOException e) {
            LOGGER.warn("Failed to load recent connections", e);
            return new LoadResult(new ArrayList<>(), false);
        }
    }

    private List<RecentEntry> pruneDeletedEntries(List<RecentEntry> entries) {
        return pruneDeletedEntries(entries, loadLatestConnectionsById().keySet());
    }

    private List<RecentEntry> pruneDeletedEntries(List<RecentEntry> entries, Set<String> existingIds) {
        if (entries == null || entries.isEmpty()) {
            return new ArrayList<>();
        }
        List<RecentEntry> pruned = new ArrayList<>();
        for (RecentEntry entry : entries) {
            if (entry != null && existingIds.contains(entry.id)) {
                pruned.add(entry);
            }
        }
        return pruned;
    }

    private List<ConnInfo> resolveLatestConnections(List<RecentEntry> entries, Map<String, ConnInfo> latestById) {
        if (entries == null || entries.isEmpty()) {
            return new ArrayList<>();
        }

        List<ConnInfo> resolved = new ArrayList<>();
        for (RecentEntry entry : entries) {
            if (entry == null) continue;
            ConnInfo latest = latestById.get(entry.id);
            if (latest == null) {
                continue;
            }
            ConnInfo snapshot = objectMapper.convertValue(latest, ConnInfo.class);
            snapshot.setAccessTime(entry.accessTime);
            resolved.add(snapshot);
        }
        resolved.sort((a, b) -> Long.compare(b.getAccessTime(), a.getAccessTime()));
        return resolved;
    }

    private Map<String, ConnInfo> loadLatestConnectionsById() {
        Map<String, ConnInfo> latestById = new HashMap<>();
        List<TreeNode> nodes = ConnectionRepository.getInstance().load();
        for (TreeNode node : nodes) {
            if (node instanceof ConnInfo conn && isNotBlank(conn.getId())) {
                latestById.put(conn.getId(), conn);
            }
        }
        return latestById;
    }

    private void save(List<RecentEntry> entries) {
        try {
            Files.createDirectories(historyFilePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(historyFilePath.toFile(), entries);
        } catch (IOException e) {
            LOGGER.warn("Failed to save recent connections", e);
        }
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOGGER.warn("Recent connection change listener failed", e);
            }
        }
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText(null);
    }

    private long longValue(JsonNode node) {
        if (node == null || !node.canConvertToLong()) {
            return 0L;
        }
        return node.asLong();
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record LoadResult(List<RecentEntry> entries, boolean needsRewrite) {
    }

    private static class RecentEntry {
        public String id;
        public long accessTime;
    }
}

package com.yshell.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class KnownHostsRepository {

    public record HostKeyEntry(int lineIndex, String rawLine, String host, String keyType, String fingerprint) {
    }

    private static final Path KNOWN_HOSTS_FILE = Paths.get(System.getProperty("user.home"), ".yshell", "known_hosts");
    private static KnownHostsRepository instance;

    public static synchronized KnownHostsRepository getInstance() {
        if (instance == null) {
            instance = new KnownHostsRepository();
        }
        return instance;
    }

    public synchronized List<HostKeyEntry> load() throws IOException {
        if (!Files.exists(KNOWN_HOSTS_FILE)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(KNOWN_HOSTS_FILE, StandardCharsets.UTF_8);
        List<HostKeyEntry> entries = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            HostKeyEntry entry = parse(index, lines.get(index));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public synchronized void delete(HostKeyEntry entry) throws IOException {
        List<String> lines = readExistingLines();
        int lineIndex = entry.lineIndex();
        if (lineIndex < 0 || lineIndex >= lines.size() || !entry.rawLine().equals(lines.get(lineIndex))) {
            lineIndex = lines.indexOf(entry.rawLine());
        }
        if (lineIndex < 0) {
            throw new IOException("主机密钥记录已变更，请刷新后重试");
        }

        lines.remove(lineIndex);
        writeLines(lines);
    }

    public synchronized void clear() throws IOException {
        List<String> lines = readExistingLines();
        lines.removeIf(line -> parse(-1, line) != null);
        writeLines(lines);
    }

    public Path getPath() {
        return KNOWN_HOSTS_FILE;
    }

    private List<String> readExistingLines() throws IOException {
        if (!Files.exists(KNOWN_HOSTS_FILE)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Files.readAllLines(KNOWN_HOSTS_FILE, StandardCharsets.UTF_8));
    }

    private void writeLines(List<String> lines) throws IOException {
        Path parent = KNOWN_HOSTS_FILE.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempFile = Files.createTempFile(parent, "known_hosts-", ".tmp");
        try {
            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, KNOWN_HOSTS_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, KNOWN_HOSTS_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private HostKeyEntry parse(int lineIndex, String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }

        String[] parts = trimmed.split("\\s+");
        int offset = parts.length > 0 && parts[0].startsWith("@") ? 1 : 0;
        if (parts.length < offset + 3) {
            return null;
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(parts[offset + 2]);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest.digest(keyBytes));
            String host = parts[offset];
            if (offset == 1) {
                host = parts[0] + " " + host;
            }
            return new HostKeyEntry(lineIndex, line, host, parts[offset + 1], fingerprint);
        } catch (IllegalArgumentException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}

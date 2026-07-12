package com.yshell.service;

import com.yshell.model.SshKeyInfo;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.keyprovider.KeyPairProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Locale;
import java.util.UUID;

public class SshKeyService {

    private static final String APP_DIR = ".yshell";
    private static final String KEY_DIR = "keys";

    private static SshKeyService instance;

    public static synchronized SshKeyService getInstance() {
        if (instance == null) {
            instance = new SshKeyService();
        }
        return instance;
    }

    private SshKeyService() {
    }

    public void importKey(Path privateKeyPath, String name, String passphrase, boolean savePassphrase)
            throws IOException {
        KeyPair keyPair = loadFirstKeyPair(privateKeyPath, passphrase);
        SshKeyInfo keyInfo = buildInfo(privateKeyPath, keyPair, name, passphrase, savePassphrase);
        SshKeyRepository.getInstance().upsert(keyInfo);
    }

    public void generateKey(String name, String type, int bits, String passphrase, boolean savePassphrase)
            throws IOException, GeneralSecurityException {
        String normalizedType = type == null ? "ED25519" : type.trim().toUpperCase(Locale.ROOT);
        KeyPair keyPair = generateKeyPair(normalizedType, bits);

        Path keyDir = Paths.get(System.getProperty("user.home"), APP_DIR, KEY_DIR);
        Files.createDirectories(keyDir);

        String fileBase = sanitizeFileName(name);
        if (fileBase.isBlank()) {
            fileBase = "id_" + normalizedType.toLowerCase(Locale.ROOT) + "_" + UUID.randomUUID().toString().substring(0, 8);
        }
        Path privatePath = uniquePath(keyDir.resolve(fileBase));
        Path publicPath = Paths.get(privatePath.toString() + ".pub");

        OpenSSHKeyEncryptionContext context = null;
        if (passphrase != null && !passphrase.isBlank()) {
            context = new OpenSSHKeyEncryptionContext();
            context.setPassword(passphrase);
            context.setCipherName(OpenSSHKeyEncryptionContext.AES);
            context.setCipherType("256");
        }

        try (var out = Files.newOutputStream(privatePath)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, name, context, out);
        }
        try (var out = Files.newOutputStream(publicPath)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(keyPair.getPublic(), name, out);
        }
        setOwnerOnlyPermissions(privatePath);

        SshKeyInfo keyInfo = buildInfo(privatePath, keyPair, name, passphrase, savePassphrase);
        keyInfo.setPublicKeyPath(publicPath.toString());
        SshKeyRepository.getInstance().upsert(keyInfo);
    }

    public String readPublicKey(SshKeyInfo keyInfo) throws IOException {
        if (keyInfo == null) {
            return "";
        }
        if (keyInfo.getPublicKeyPath() != null && !keyInfo.getPublicKeyPath().isBlank()) {
            Path publicPath = Paths.get(keyInfo.getPublicKeyPath());
            if (Files.exists(publicPath)) {
                return Files.readString(publicPath, StandardCharsets.UTF_8).trim();
            }
        }
        KeyPair keyPair = loadFirstKeyPair(Paths.get(keyInfo.getPrivateKeyPath()), keyInfo.getPassphrase());
        return PublicKeyEntry.toString(keyPair.getPublic()) + " " + safeName(keyInfo.getName());
    }

    public ResolvedKey resolve(String idOrPath) {
        if (idOrPath == null || idOrPath.isBlank()) {
            return new ResolvedKey("", "");
        }
        return SshKeyRepository.getInstance()
                .findById(idOrPath)
                .map(key -> new ResolvedKey(key.getPrivateKeyPath(), key.getPassphrase()))
                .orElseGet(() -> new ResolvedKey(idOrPath, ""));
    }

    private SshKeyInfo buildInfo(Path privateKeyPath,
                                 KeyPair keyPair,
                                 String name,
                                 String passphrase,
                                 boolean savePassphrase) {
        SshKeyInfo keyInfo = new SshKeyInfo();
        keyInfo.setName(name != null && !name.isBlank() ? name : privateKeyPath.getFileName().toString());
        keyInfo.setPrivateKeyPath(privateKeyPath.toString());
        keyInfo.setPublicKeyPath(Files.exists(Paths.get(privateKeyPath + ".pub"))
                ? privateKeyPath + ".pub"
                : "");
        keyInfo.setType(KeyUtils.getKeyType(keyPair));
        keyInfo.setBits(KeyUtils.getKeySize(keyPair.getPublic()));
        keyInfo.setFingerprint(KeyUtils.getFingerPrint(keyPair.getPublic()));
        keyInfo.setSavePassphrase(savePassphrase);
        keyInfo.setPassphrase(savePassphrase ? (passphrase != null ? passphrase : "") : "");
        return keyInfo;
    }

    private KeyPair loadFirstKeyPair(Path privateKeyPath, String passphrase) throws IOException {
        FileKeyPairProvider provider = new FileKeyPairProvider(privateKeyPath);
        if (passphrase != null && !passphrase.isBlank()) {
            provider.setPasswordFinder(FilePasswordProvider.of(passphrase));
        }
        Iterable<KeyPair> keys = provider.loadKeys(null);
        for (KeyPair keyPair : keys) {
            return keyPair;
        }
        throw new IOException("No key pair found in " + privateKeyPath);
    }

    private KeyPair generateKeyPair(String type, int bits) throws GeneralSecurityException {
        if ("RSA".equals(type)) {
            return KeyUtils.generateKeyPair(KeyPairProvider.SSH_RSA, normalizeRsaBits(bits));
        }
        if ("ECDSA".equals(type) || "EC".equals(type)) {
            return KeyUtils.generateKeyPair(KeyUtils.EC_ALGORITHM, bits > 0 ? bits : 256);
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            return KeyUtils.generateKeyPair(KeyPairProvider.SSH_RSA, 4096);
        }
    }

    private int normalizeRsaBits(int bits) {
        return switch (bits) {
            case 2048, 3072, 4096 -> bits;
            default -> 4096;
        };
    }

    private void setOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows does not support POSIX file permissions.
        }
    }

    private Path uniquePath(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String base = path.toString();
        for (int i = 1; i < 1000; i++) {
            Path candidate = Paths.get(base + "_" + i);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return Paths.get(base + "_" + System.currentTimeMillis());
    }

    private String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private String safeName(String name) {
        return name != null ? name.replaceAll("\\s+", "_") : "yshell-key";
    }

    public record ResolvedKey(String privateKeyPath, String passphrase) {
    }
}

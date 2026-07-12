package com.yshell.model;

import java.util.UUID;

public class SshKeyInfo {
    private String id;
    private String name;
    private String type;
    private int bits;
    private String fingerprint;
    private String privateKeyPath;
    private String publicKeyPath;
    private String passphrase;
    private boolean savePassphrase;
    private String description;
    private long createTime;
    private long modifiedTime;

    public SshKeyInfo() {
        this.id = UUID.randomUUID().toString().replace("-", "");
        this.createTime = System.currentTimeMillis();
        this.modifiedTime = this.createTime;
    }

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

    public int getBits() {
        return bits;
    }

    public void setBits(int bits) {
        this.bits = bits;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPublicKeyPath() {
        return publicKeyPath;
    }

    public void setPublicKeyPath(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }

    public String getPassphrase() {
        return passphrase;
    }

    public void setPassphrase(String passphrase) {
        this.passphrase = passphrase;
    }

    public boolean isSavePassphrase() {
        return savePassphrase;
    }

    public void setSavePassphrase(boolean savePassphrase) {
        this.savePassphrase = savePassphrase;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getModifiedTime() {
        return modifiedTime;
    }

    public void setModifiedTime(long modifiedTime) {
        this.modifiedTime = modifiedTime;
    }

    @Override
    public String toString() {
        return name != null && !name.isBlank() ? name : privateKeyPath;
    }
}

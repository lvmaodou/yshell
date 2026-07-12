package com.yshell.model;

import java.util.ArrayList;
import java.util.List;

public class UpdateDiff {

    private boolean needUpdateRuntime;
    private boolean needUpdateLauncher;
    private Manifest.FileInfo launcherFile;
    private List<Manifest.FileInfo> filesToUpdate = new ArrayList<>();
    private List<String> filesToDelete = new ArrayList<>();

    private String newVersion;
    private String changelog;
    private long totalDownloadSize;

    public boolean isNeedUpdateRuntime() {
        return needUpdateRuntime;
    }

    public void setNeedUpdateRuntime(boolean needUpdateRuntime) {
        this.needUpdateRuntime = needUpdateRuntime;
    }

    public boolean isNeedUpdateLauncher() {
        return needUpdateLauncher;
    }

    public void setNeedUpdateLauncher(boolean needUpdateLauncher) {
        this.needUpdateLauncher = needUpdateLauncher;
    }

    public Manifest.FileInfo getLauncherFile() {
        return launcherFile;
    }

    public void setLauncherFile(Manifest.FileInfo launcherFile) {
        this.launcherFile = launcherFile;
    }

    public List<Manifest.FileInfo> getFilesToUpdate() {
        return filesToUpdate;
    }

    public void setFilesToUpdate(List<Manifest.FileInfo> filesToUpdate) {
        this.filesToUpdate = filesToUpdate;
    }

    public List<String> getFilesToDelete() {
        return filesToDelete;
    }

    public void setFilesToDelete(List<String> filesToDelete) {
        this.filesToDelete = filesToDelete;
    }

    public String getNewVersion() {
        return newVersion;
    }

    public void setNewVersion(String newVersion) {
        this.newVersion = newVersion;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public long getTotalDownloadSize() {
        return totalDownloadSize;
    }

    public void setTotalDownloadSize(long totalDownloadSize) {
        this.totalDownloadSize = totalDownloadSize;
    }

    public boolean hasUpdates() {
        return needUpdateRuntime || needUpdateLauncher || !filesToUpdate.isEmpty() || !filesToDelete.isEmpty();
    }
}

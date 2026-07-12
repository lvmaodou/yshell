package com.yshell.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Manifest {

    private String version;

    @JsonProperty("release_date")
    private String releaseDate;

    private String changelog;

    @JsonProperty("min_updatable_version")
    private String minUpdatableVersion;

    private RuntimeInfo runtime;

    private AppInfo app;

    private LauncherInfo launcher;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(String releaseDate) {
        this.releaseDate = releaseDate;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public String getMinUpdatableVersion() {
        return minUpdatableVersion;
    }

    public void setMinUpdatableVersion(String minUpdatableVersion) {
        this.minUpdatableVersion = minUpdatableVersion;
    }

    public RuntimeInfo getRuntime() {
        return runtime;
    }

    public void setRuntime(RuntimeInfo runtime) {
        this.runtime = runtime;
    }

    public AppInfo getApp() {
        return app;
    }

    public void setApp(AppInfo app) {
        this.app = app;
    }

    public LauncherInfo getLauncher() {
        return launcher;
    }

    public void setLauncher(LauncherInfo launcher) {
        this.launcher = launcher;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuntimeInfo {
        @JsonProperty("java_version")
        private String javaVersion;

        private String hash;

        private long size;

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private Map<String, RuntimePackage> platforms;

        public String getJavaVersion() {
            return javaVersion;
        }

        public void setJavaVersion(String javaVersion) {
            this.javaVersion = javaVersion;
        }

        public String getHash() {
            return hash;
        }

        public void setHash(String hash) {
            this.hash = hash;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public Map<String, RuntimePackage> getPlatforms() {
            return platforms;
        }

        public void setPlatforms(Map<String, RuntimePackage> platforms) {
            this.platforms = platforms;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuntimePackage {
        private String url;
        private String hash;
        private long size;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getHash() {
            return hash;
        }

        public void setHash(String hash) {
            this.hash = hash;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LauncherInfo {
        @JsonProperty("update_required")
        private boolean updateRequired;

        private Map<String, FileInfo> platforms;

        public boolean isUpdateRequired() {
            return updateRequired;
        }

        public void setUpdateRequired(boolean updateRequired) {
            this.updateRequired = updateRequired;
        }

        public Map<String, FileInfo> getPlatforms() {
            return platforms;
        }

        public void setPlatforms(Map<String, FileInfo> platforms) {
            this.platforms = platforms;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AppInfo {
        private List<FileInfo> files;
        private List<String> delete;

        public List<FileInfo> getFiles() {
            return files;
        }

        public void setFiles(List<FileInfo> files) {
            this.files = files;
        }

        public List<String> getDelete() {
            return delete;
        }

        public void setDelete(List<String> delete) {
            this.delete = delete;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileInfo {
        private String path;
        private String url;
        private String hash;
        private long size;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getHash() {
            return hash;
        }

        public void setHash(String hash) {
            this.hash = hash;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }
}

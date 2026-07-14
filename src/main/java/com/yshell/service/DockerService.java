package com.yshell.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.docker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class DockerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerService.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DockerSnapshot loadSnapshot(SshService sshService) {
        if (sshService == null || !sshService.isConnected()) {
            return DockerSnapshot.empty("SSH session is not connected");
        }

        SshService.CommandResult versionResult = run(sshService, "command -v docker >/dev/null 2>&1 && docker version --format '{{json .}}' || echo '__YSHELL_NO_DOCKER__'");
        if (!versionResult.isSuccess() || versionResult.stdout().contains("__YSHELL_NO_DOCKER__")) {
            String error = versionResult.stderr().isBlank() ? "Docker is not installed or not available" : versionResult.stderr();
            return DockerSnapshot.empty(error);
        }

        String version = versionResult.stdout();
        String serverVersion = readTextField(version, "Server", "Version");
        String apiVersion = readTextField(version, "Server", "ApiVersion");
        String os = readTextField(version, "Server", "Os");
        String arch = readTextField(version, "Server", "Arch");
        String build = readTextField(version, "Server", "BuildTime");

        SshService.CommandResult infoResult = run(sshService, "docker info --format '{{json .}}'");
        JsonNode infoNode = parseJson(infoResult.stdout());

        List<DockerContainer> containers = parseContainers(run(sshService, "docker ps -a --no-trunc --format '{{json .}}'").stdout());
        Set<String> usedImageIds = loadUsedImageIds(sshService);
        List<DockerImage> images = parseImages(run(sshService, "docker images --no-trunc --format '{{json .}}'").stdout(), usedImageIds);
        List<DockerNetwork> networks = parseNetworks(run(sshService, "docker network ls --no-trunc --format '{{json .}}'").stdout());
        Set<String> usedVolumes = loadUsedVolumeNames(sshService);
        List<DockerVolume> volumes = parseVolumes(run(sshService, "docker volume ls --format '{{json .}}'").stdout(), sshService, usedVolumes);

        int running = (int) containers.stream().filter(c -> "running".equalsIgnoreCase(c.state())).count();
        return new DockerSnapshot(
                Instant.now(),
                true,
                "",
                firstNonBlank(serverVersion, textValue(infoNode, "ServerVersion")),
                firstNonBlank(apiVersion, textValue(infoNode, "ServerAPIVersion")),
                firstNonBlank(os, textValue(infoNode, "OSType")),
                firstNonBlank(arch, textValue(infoNode, "Architecture")),
                build,
                running,
                containers.size(),
                images.size(),
                containers,
                images,
                networks,
                volumes
        );
    }

    public SshService.CommandResult containerAction(SshService sshService, String action, String id) {
        return run(sshService, "docker " + action + " " + shellQuote(id));
    }

    public SshService.CommandResult containerLogs(SshService sshService, String id) {
        return run(sshService, "docker logs --tail 300 " + shellQuote(id));
    }

    public SshService.CommandResult imageAction(SshService sshService, String action, String id) {
        return run(sshService, "docker " + action + " " + shellQuote(id));
    }

    public SshService.CommandResult networkAction(SshService sshService, String action, String id) {
        return run(sshService, "docker " + action + " " + shellQuote(id));
    }

    public SshService.CommandResult volumeAction(SshService sshService, String action, String name) {
        return run(sshService, "docker " + action + " " + shellQuote(name));
    }

    private SshService.CommandResult run(SshService sshService, String command) {
        String fullCommand = "sh -lc " + shellQuote(command);
        SshService.CommandResult result = sshService.executeRemoteCommand(fullCommand, DEFAULT_TIMEOUT);
        if (!result.stderr().isBlank()) {
            LOGGER.debug("docker command stderr: {}", result.stderr());
        }
        return result;
    }

    private List<DockerContainer> parseContainers(String output) {
        List<DockerContainer> rows = new ArrayList<>();
        forEachJsonLine(output, node -> rows.add(new DockerContainer(
                textValue(node, "ID"),
                textValue(node, "Names"),
                textValue(node, "Image"),
                textValue(node, "Status"),
                textValue(node, "State"),
                textValue(node, "Ports"),
                textValue(node, "RunningFor"),
                textValue(node, "Size")
        )));
        return rows;
    }

    private Set<String> loadUsedImageIds(SshService sshService) {
        Set<String> ids = new HashSet<>();
        String command = "ids=$(docker ps -aq); [ -n \"$ids\" ] && docker inspect --format '{{.Image}}' $ids || true";
        String output = run(sshService, command).stdout();
        if (output == null || output.isBlank()) {
            return ids;
        }
        for (String line : output.split("\\R")) {
            String id = line.trim();
            if (!id.isBlank()) {
                ids.add(id);
                ids.add(normalizeDigest(id));
            }
        }
        return ids;
    }

    private List<DockerImage> parseImages(String output, Set<String> usedImageIds) {
        List<DockerImage> rows = new ArrayList<>();
        forEachJsonLine(output, node -> {
            String id = textValue(node, "ID");
            boolean used = usedImageIds.contains(id) || usedImageIds.contains(normalizeDigest(id));
            rows.add(new DockerImage(
                    id,
                    textValue(node, "Repository"),
                    textValue(node, "Tag"),
                    textValue(node, "CreatedSince"),
                    textValue(node, "CreatedAt"),
                    textValue(node, "Size"),
                    textValue(node, "Containers"),
                    used ? "\u5df2\u4f7f\u7528" : "\u672a\u4f7f\u7528"
            ));
        });
        return rows;
    }

    private List<DockerNetwork> parseNetworks(String output) {
        List<DockerNetwork> rows = new ArrayList<>();
        forEachJsonLine(output, node -> rows.add(new DockerNetwork(
                textValue(node, "ID"),
                textValue(node, "Name"),
                textValue(node, "Driver"),
                textValue(node, "Scope"),
                textValue(node, "IPv6"),
                textValue(node, "Internal"),
                textValue(node, "Labels")
        )));
        return rows;
    }

    private Set<String> loadUsedVolumeNames(SshService sshService) {
        Set<String> names = new HashSet<>();
        String command = "ids=$(docker ps -aq); [ -n \"$ids\" ] && docker inspect --format '{{json .Mounts}}' $ids || true";
        String output = run(sshService, command).stdout();
        if (output == null || output.isBlank()) {
            return names;
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                JsonNode mounts = objectMapper.readTree(trimmed);
                if (!mounts.isArray()) {
                    continue;
                }
                for (JsonNode mount : mounts) {
                    if ("volume".equalsIgnoreCase(textValue(mount, "Type"))) {
                        String name = textValue(mount, "Name");
                        if (!name.isBlank()) {
                            names.add(name);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to parse docker mount json line: {}", trimmed, e);
            }
        }
        return names;
    }

    private List<DockerVolume> parseVolumes(String output, SshService sshService, Set<String> usedVolumes) {
        List<DockerVolume> rows = new ArrayList<>();
        Map<String, JsonNode> inspectByName = loadVolumeInspectByName(sshService);
        forEachJsonLine(output, node -> {
            String name = textValue(node, "Name");
            JsonNode inspectNode = inspectByName.get(name);
            String mountpoint = textValue(inspectNode, "Mountpoint");
            rows.add(new DockerVolume(
                    name,
                    firstNonBlank(textValue(node, "Driver"), textValue(inspectNode, "Driver")),
                    firstNonBlank(textValue(node, "Scope"), textValue(inspectNode, "Scope")),
                    mountpoint,
                    textOrJsonValue(inspectNode, "Labels"),
                    usageLabel(usedVolumes.contains(name)),
                    textValue(inspectNode, "CreatedAt")
            ));
        });
        return rows;
    }

    private Map<String, JsonNode> loadVolumeInspectByName(SshService sshService) {
        Map<String, JsonNode> inspectByName = new HashMap<>();
        String command = "names=$(docker volume ls -q); [ -n \"$names\" ] && docker volume inspect $names || true";
        JsonNode volumes = parseJson(run(sshService, command).stdout());
        if (volumes == null || !volumes.isArray()) {
            return inspectByName;
        }
        for (JsonNode volume : volumes) {
            String name = textValue(volume, "Name");
            if (!name.isBlank()) {
                inspectByName.put(name, volume);
            }
        }
        return inspectByName;
    }

    private void forEachJsonLine(String output, JsonLineConsumer consumer) {
        if (output == null || output.isBlank()) {
            return;
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                consumer.accept(objectMapper.readTree(trimmed));
            } catch (Exception e) {
                LOGGER.debug("Failed to parse docker json line: {}", trimmed, e);
            }
        }
    }

    private JsonNode parseJson(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(output.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String readTextField(String json, String section, String field) {
        JsonNode node = parseJson(json);
        return textValue(node == null ? null : node.path(section), field);
    }

    private String textValue(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String textOrJsonValue(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isObject() || value.isArray()) {
            return value.toString();
        }
        return value.asText("");
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private String normalizeDigest(String id) {
        if (id == null) {
            return "";
        }
        return id.startsWith("sha256:") ? id.substring("sha256:".length()) : id;
    }

    private String usageLabel(boolean used) {
        return used ? "\u5df2\u4f7f\u7528" : "\u672a\u4f7f\u7528";
    }

    private String firstLine(String output) {
        return output == null || output.isBlank() ? "" : output.trim().split("\\R")[0].trim();
    }

    private String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @FunctionalInterface
    private interface JsonLineConsumer {
        void accept(JsonNode node) throws IOException;
    }
}

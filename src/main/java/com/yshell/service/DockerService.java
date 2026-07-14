package com.yshell.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.docker.DockerContainer;
import com.yshell.model.docker.DockerImage;
import com.yshell.model.docker.DockerNetwork;
import com.yshell.model.docker.DockerSnapshot;
import com.yshell.model.docker.DockerVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        List<DockerImage> images = parseImages(run(sshService, "docker images --no-trunc --format '{{json .}}'").stdout());
        List<DockerNetwork> networks = parseNetworks(run(sshService, "docker network ls --no-trunc --format '{{json .}}'").stdout());
        List<DockerVolume> volumes = parseVolumes(run(sshService, "docker volume ls --format '{{json .}}'").stdout(), sshService);

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

    private List<DockerImage> parseImages(String output) {
        List<DockerImage> rows = new ArrayList<>();
        forEachJsonLine(output, node -> rows.add(new DockerImage(
                textValue(node, "ID"),
                textValue(node, "Repository"),
                textValue(node, "Tag"),
                textValue(node, "CreatedSince"),
                textValue(node, "CreatedAt"),
                textValue(node, "Size"),
                textValue(node, "Containers")
        )));
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

    private List<DockerVolume> parseVolumes(String output, SshService sshService) {
        List<DockerVolume> rows = new ArrayList<>();
        forEachJsonLine(output, node -> {
            String name = textValue(node, "Name");
            String inspectOutput = run(sshService, "docker volume inspect --format '{{json .}}' " + shellQuote(name)).stdout();
            JsonNode inspectNode = parseJson(inspectOutput);
            rows.add(new DockerVolume(
                    name,
                    firstNonBlank(textValue(node, "Driver"), textValue(inspectNode, "Driver")),
                    firstNonBlank(textValue(node, "Scope"), textValue(inspectNode, "Scope")),
                    textValue(inspectNode, "Mountpoint"),
                    textOrJsonValue(inspectNode, "Labels")
            ));
        });
        return rows;
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

package com.yshell.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yshell.model.docker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public class DockerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerService.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(60);
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
        String serverVersion = readTextField(version, "Version");
        String apiVersion = readTextField(version, "ApiVersion");
        String os = readTextField(version, "Os");
        String arch = readTextField(version, "Arch");
        String build = readTextField(version, "BuildTime");

        SshService.CommandResult infoResult = run(sshService, "docker info --format '{{json .}}'");
        JsonNode infoNode = parseJson(infoResult.stdout());

        List<DockerContainer> containers = parseContainers(run(sshService, "docker container ls --all --no-trunc --format '{{json .}}'").stdout());
        Set<String> usedImageIds = loadUsedImageIds(sshService);
        List<DockerImage> images = parseImages(run(sshService, "docker image ls --all --no-trunc --format '{{json .}}'").stdout(), usedImageIds);
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

    public SshService.CommandResult containerRun(SshService sshService, String image) {
        return run(sshService, "docker run -d " + shellQuote(image));
    }

    public SshService.CommandResult containerRun(SshService sshService, String image, String options) {
        String cleanOptions = options == null || options.isBlank() ? "" : options.trim() + " ";
        return run(sshService, "docker run -d " + cleanOptions + shellQuote(image));
    }

    public SshService.RemoteCommandHandle followContainerLogs(SshService sshService,
                                                              String id,
                                                              Consumer<String> stdoutConsumer,
                                                              Consumer<String> stderrConsumer) {
        return stream(sshService, "docker logs -f --tail 300 " + shellQuote(id),
                stdoutConsumer, stderrConsumer);
    }

    public SshService.CommandResult containerInspect(SshService sshService, String id) {
        return run(sshService, "docker inspect " + shellQuote(id));
    }

    public SshService.CommandResult containerStats(SshService sshService, String id) {
        String format = "Container ID: {{.Container}}\\n"
                + "Name: {{.Name}}\\n"
                + "CPU: {{.CPUPerc}}\\n"
                + "Memory: {{.MemUsage}}\\n"
                + "Memory %: {{.MemPerc}}\\n"
                + "Network I/O: {{.NetIO}}\\n"
                + "Block I/O: {{.BlockIO}}\\n"
                + "PIDs: {{.PIDs}}";
        return run(sshService, "docker stats --no-stream --no-trunc --format "
                + shellQuote(format) + " " + shellQuote(id));
    }

    public SshService.CommandResult containerTop(SshService sshService, String id) {
        String awk = "NR==1 {next} "
                + "{ "
                + "printf \"Process %d\\n\", ++n; "
                + "printf \"PID: %s\\n\", $1; "
                + "printf \"PPID: %s\\n\", $2; "
                + "printf \"User: %s\\n\", $3; "
                + "printf \"State: %s\\n\", $4; "
                + "printf \"CPU %%: %s\\n\", $5; "
                + "printf \"Memory %%: %s\\n\", $6; "
                + "printf \"Command: %s\\n\", $7; "
                + "if (NF > 7) { printf \"Args:\"; for (i = 8; i <= NF; i++) printf \" %s\", $i; printf \"\\n\"; } "
                + "printf \"\\n\" "
                + "} "
                + "END {if (n == 0) print \"No processes\"}";
        return run(sshService, "docker top " + shellQuote(id)
                + " -eo pid,ppid,user,stat,pcpu,pmem,comm,args | awk " + shellQuote(awk));
    }

    public SshService.CommandResult containerDiff(SshService sshService, String id) {
        return run(sshService, "docker diff " + shellQuote(id));
    }

    public SshService.CommandResult containerRename(SshService sshService, String id, String name) {
        return run(sshService, "docker rename " + shellQuote(id) + " " + shellQuote(name));
    }

    public SshService.CommandResult containerCopy(SshService sshService, String source, String target) {
        return run(sshService, "docker cp " + shellQuote(source) + " " + shellQuote(target));
    }

    public SshService.CommandResult imagePull(SshService sshService, String image) {
        return run(sshService, "docker pull " + shellQuote(image));
    }

    public SshService.CommandResult imageLogin(SshService sshService, String registry, String username, String password) {
        return run(sshService, "printf %s " + shellQuote(password)
                + " | docker login " + shellQuote(registry)
                + " -u " + shellQuote(username)
                + " --password-stdin");
    }

    public SshService.CommandResult imageRemove(SshService sshService, String image) {
        return run(sshService, "docker rmi " + shellQuote(image));
    }

    public SshService.CommandResult imageTag(SshService sshService, String source, String target) {
        return run(sshService, "docker tag " + shellQuote(source) + " " + shellQuote(target));
    }

    public SshService.CommandResult imageTagAndPush(SshService sshService, String source, String target) {
        return run(sshService, "docker tag " + shellQuote(source) + " " + shellQuote(target)
                + " && docker push " + shellQuote(target));
    }

    public SshService.CommandResult imageInspect(SshService sshService, String image) {
        return run(sshService, "docker image inspect " + shellQuote(image));
    }

    public SshService.CommandResult imageHistory(SshService sshService, String image) {
        return run(sshService, "docker history --no-trunc " + shellQuote(image));
    }

    public SshService.CommandResult imageSave(SshService sshService, String image, String path) {
        return run(sshService, "docker save -o " + shellQuote(path) + " " + shellQuote(image));
    }

    public SshService.CommandResult imageLoad(SshService sshService, String path) {
        return run(sshService, "docker load -i " + shellQuote(path));
    }

    public SshService.CommandResult imageImport(SshService sshService, String path, String image) {
        return run(sshService, "docker import " + shellQuote(path) + " " + shellQuote(image));
    }

    public SshService.CommandResult imagePrune(SshService sshService, boolean all) {
        return run(sshService, all ? "docker image prune -a -f" : "docker image prune -f");
    }

    public SshService.CommandResult networkInspect(SshService sshService, String network) {
        return run(sshService, "docker network inspect " + shellQuote(network));
    }

    public SshService.CommandResult networkCreate(SshService sshService,
                                                  String name,
                                                  String driver,
                                                  String subnet,
                                                  String gateway,
                                                  boolean internal) {
        StringBuilder command = new StringBuilder("docker network create");
        String cleanDriver = driver == null || driver.isBlank() ? "bridge" : driver.trim();
        command.append(" --driver ").append(shellQuote(cleanDriver));
        if (subnet != null && !subnet.isBlank()) {
            command.append(" --subnet ").append(shellQuote(subnet.trim()));
        }
        if (gateway != null && !gateway.isBlank()) {
            command.append(" --gateway ").append(shellQuote(gateway.trim()));
        }
        if (internal) {
            command.append(" --internal");
        }
        command.append(' ').append(shellQuote(name));
        return run(sshService, command.toString());
    }

    public SshService.CommandResult networkRemove(SshService sshService, String network) {
        return run(sshService, "docker network rm " + shellQuote(network));
    }

    public SshService.CommandResult networkConnect(SshService sshService, String network, String container) {
        return run(sshService, "docker network connect " + shellQuote(network) + " " + shellQuote(container));
    }

    public SshService.CommandResult networkDisconnect(SshService sshService, String network, String container) {
        return run(sshService, "docker network disconnect " + shellQuote(network) + " " + shellQuote(container));
    }

    public SshService.CommandResult volumeInspect(SshService sshService, String volume) {
        return run(sshService, "docker volume inspect " + shellQuote(volume));
    }

    public SshService.CommandResult volumeContainers(SshService sshService, String volume) {
        String format = "Name: {{.Names}}\\n"
                + "ID: {{.ID}}\\n"
                + "Image: {{.Image}}\\n"
                + "Status: {{.Status}}\\n";
        String command = "containers=$(docker ps -a --filter " + shellQuote("volume=" + volume)
                + " --format " + shellQuote(format) + "); "
                + "if [ -n \"$containers\" ]; then printf '%s\\n' \"$containers\"; else printf '无容器使用该卷\\n'; fi";
        return run(sshService, command);
    }

    public SshService.CommandResult volumeSize(SshService sshService, String volume) {
        String command = "mountpoint=$(docker volume inspect -f '{{.Mountpoint}}' " + shellQuote(volume) + ") && "
                + "printf 'Mountpoint: %s\\n' \"$mountpoint\" && "
                + "(du -sh \"$mountpoint\" 2>/dev/null || sudo -n du -sh \"$mountpoint\" 2>/dev/null)";
        return run(sshService, command);
    }

    public SshService.CommandResult volumeCreate(SshService sshService, String name) {
        return run(sshService, "docker volume create " + shellQuote(name));
    }

    public SshService.CommandResult volumeRemove(SshService sshService, String volume) {
        return run(sshService, "docker volume rm " + shellQuote(volume));
    }

    public SshService.CommandResult volumePrune(SshService sshService) {
        return run(sshService, "docker volume prune -f");
    }

    public DockerConfigFile loadConfigFile(SshService sshService) {
        String path = detectDockerConfigPath(sshService);
        String output = run(sshService,
                "path=" + shellQuote(path) + "; "
                        + "if [ -r \"$path\" ]; then cat \"$path\"; "
                        + "elif sudo -n test -r \"$path\" 2>/dev/null; then sudo -n cat \"$path\"; "
                        + "elif [ -e \"$path\" ]; then echo __YSHELL_CONFIG_PERMISSION_DENIED__; "
                        + "else echo __YSHELL_CONFIG_MISSING__; fi").stdout();
        String content = output == null ? "" : output;
        if (content.contains("__YSHELL_CONFIG_PERMISSION_DENIED__")) {
            return new DockerConfigFile(path, "", "没有权限读取配置文件");
        }
        if (content.contains("__YSHELL_CONFIG_MISSING__")) {
            return new DockerConfigFile(path, "{\n  \n}\n", "");
        }
        return new DockerConfigFile(path, content, "");
    }

    public SshService.CommandResult saveConfigFile(SshService sshService, String path, String content) {
        String encoded = Base64.getEncoder().encodeToString((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        String command = "path=" + shellQuote(path) + "; tmp=$(mktemp); "
                + "printf %s " + shellQuote(encoded) + " | base64 -d > \"$tmp\" && "
                + "(install -m 0644 \"$tmp\" \"$path\" 2>/dev/null || sudo -n install -m 0644 \"$tmp\" \"$path\"); "
                + "rc=$?; rm -f \"$tmp\"; exit $rc";
        return run(sshService, command);
    }

    public SshService.CommandResult restartDocker(SshService sshService) {
        return run(sshService,
                "systemctl restart docker 2>/dev/null "
                        + "|| service docker restart 2>/dev/null "
                        + "|| sudo -n systemctl restart docker 2>/dev/null "
                        + "|| sudo -n service docker restart");
    }

    private SshService.CommandResult run(SshService sshService, String command) {
        String fullCommand = "sh -lc " + shellQuote(command);
        SshService.CommandResult result = sshService.executeRemoteCommand(fullCommand, DEFAULT_TIMEOUT);
        if (!result.stderr().isBlank()) {
            LOGGER.debug("docker command stderr: {}", result.stderr());
        }
        return result;
    }

    private SshService.RemoteCommandHandle stream(SshService sshService,
                                                  String command,
                                                  Consumer<String> stdoutConsumer,
                                                  Consumer<String> stderrConsumer) {
        String fullCommand = "sh -lc " + shellQuote(command);
        return sshService.streamRemoteCommand(fullCommand, stdoutConsumer, stderrConsumer);
    }

    private String detectDockerConfigPath(SshService sshService) {
        String command = "args=$(ps -eo args | grep '[d]ockerd' | head -n 1); "
                + "path=$(printf '%s\\n' \"$args\" | awk '{for (i=1;i<=NF;i++){if ($i==\"--config-file\" && i<NF){print $(i+1); exit} if ($i ~ /^--config-file=/){sub(/^--config-file=/,\"\",$i); print $i; exit}}}'); "
                + "[ -n \"$path\" ] || [ ! -f \"$HOME/.config/docker/daemon.json\" ] || path=\"$HOME/.config/docker/daemon.json\"; "
                + "[ -n \"$path\" ] || path=/etc/docker/daemon.json; "
                + "printf '%s\\n' \"$path\"";
        String path = firstLine(run(sshService, command).stdout());
        return path.isBlank() ? "/etc/docker/daemon.json" : path;
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
                    used ? "在使用" : "未使用"
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
                    textOrJsonValue(inspectNode),
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

    private String readTextField(String json, String field) {
        JsonNode node = parseJson(json);
        return textValue(node == null ? null : node.path("Server"), field);
    }

    private String textValue(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String textOrJsonValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path("Labels");
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
        return used ? "在使用" : "未使用";
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

    public record DockerConfigFile(String path, String content, String error) {
    }

    @FunctionalInterface
    private interface JsonLineConsumer {
        void accept(JsonNode node) throws IOException;
    }
}

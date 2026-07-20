package com.yshell.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public class K8sService {
    private static final Logger LOGGER = LoggerFactory.getLogger(K8sService.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public K8sSessionManager.K8sSnapshot loadSnapshot(SshService session) {
        SshService.CommandResult clientVersion = run(session,
                "command -v kubectl >/dev/null 2>&1 && kubectl version --client=true --output=json || echo '__YSHELL_NO_KUBECTL__'");
        if (!clientVersion.isSuccess() || clientVersion.stdout().contains("__YSHELL_NO_KUBECTL__")) {
            String error = clientVersion.stderr().isBlank()
                    ? "kubectl is not installed or not available"
                    : clientVersion.stderr();
            return K8sSessionManager.K8sSnapshot.empty(error);
        }

        String client = readVersion(parseJson(clientVersion.stdout()), "clientVersion");
        SshService.CommandResult clusterVersion = run(session, "kubectl version --output=json");
        JsonNode clusterVersionJson = parseJson(clusterVersion.stdout());
        String server = readVersion(clusterVersionJson, "serverVersion");

        List<String> namespaces = new ArrayList<>();
        SshService.CommandResult namespacesResult = run(session, "kubectl get namespaces -o json --ignore-not-found");
        JsonNode nsRoot = parseJson(namespacesResult.stdout());
        if (nsRoot != null && nsRoot.path("items").isArray()) {
            for (JsonNode item : nsRoot.path("items")) {
                String name = item.path("metadata").path("name").asText("");
                if (!name.isBlank()) {
                    namespaces.add(name);
                }
            }
        }
        if (namespaces.isEmpty()) {
            namespaces.add(currentNamespace(session));
        }

        return new K8sSessionManager.K8sSnapshot(Instant.now(), true, "", client, server, namespaces);
    }

    public K8sSessionManager.ResourceListResult listResources(SshService session,
                                                              String kubectlType,
                                                              boolean namespaced,
                                                              String namespace) {
        if (kubectlType == null || kubectlType.isBlank()) {
            return K8sSessionManager.ResourceListResult.failed("Missing Kubernetes resource type");
        }
        try {
            String scope = listScopeFlag(namespaced, namespace);
            String command = "kubectl get " + shellQuote(kubectlType) + scope + " -o json --ignore-not-found";
            SshService.CommandResult result = run(session, command);
            if (!result.isSuccess() && namespaced && (namespace == null || namespace.isBlank())) {
                String fallbackNamespace = currentNamespace(session);
                String fallbackCommand = "kubectl get " + shellQuote(kubectlType)
                        + namespaceFlag(fallbackNamespace, true)
                        + " -o json --ignore-not-found";
                SshService.CommandResult fallback = run(session, fallbackCommand);
                if (fallback.isSuccess()) {
                    result = fallback;
                }
            }
            if (!result.isSuccess()) {
                return K8sSessionManager.ResourceListResult.failed(commandMessage(result));
            }
            JsonNode root = parseJson(result.stdout());
            List<JsonNode> items = new ArrayList<>();
            if (root != null && root.path("items").isArray()) {
                for (JsonNode item : root.path("items")) {
                    items.add(item);
                }
            }
            return new K8sSessionManager.ResourceListResult(true, "", items, result.stdout());
        } catch (Exception e) {
            return K8sSessionManager.ResourceListResult.failed(
                    e.getMessage() == null ? "Load resources failed" : e.getMessage());
        }
    }

    public Map<String, K8sSessionManager.PodUsage> podMetrics(SshService session, String namespace) {
        String path = namespace == null || namespace.isBlank()
                ? "/apis/metrics.k8s.io/v1beta1/pods"
                : "/apis/metrics.k8s.io/v1beta1/namespaces/" + namespace + "/pods";
        SshService.CommandResult result = run(session, "kubectl get --raw " + shellQuote(path));
        if (!result.isSuccess()) {
            return Map.of();
        }

        JsonNode root = parseJson(result.stdout());
        if (root == null || !root.path("items").isArray()) {
            return Map.of();
        }

        Map<String, K8sSessionManager.PodUsage> metrics = new LinkedHashMap<>();
        for (JsonNode item : root.path("items")) {
            String podNamespace = item.path("metadata").path("namespace").asText("");
            String podName = item.path("metadata").path("name").asText("");
            if (podNamespace.isBlank() || podName.isBlank()) {
                continue;
            }

            double cpuMillicores = 0;
            double memoryBytes = 0;
            JsonNode containers = item.path("containers");
            if (containers.isArray()) {
                for (JsonNode container : containers) {
                    JsonNode usage = container.path("usage");
                    cpuMillicores += cpuToMillicores(usage.path("cpu").asText(""));
                    memoryBytes += memoryToBytes(usage.path("memory").asText(""));
                }
            }
            metrics.put(podNamespace + "/" + podName,
                    new K8sSessionManager.PodUsage(formatCpuMillicores(cpuMillicores), formatMemoryBytes(memoryBytes)));
        }
        return metrics;
    }

    public SshService.CommandResult getYaml(SshService session,
                                            String kubectlType,
                                            String namespace,
                                            String name,
                                            boolean namespaced) {
        return run(session, "kubectl get " + shellQuote(kubectlType) + namespaceFlag(namespace, namespaced)
                + " " + shellQuote(name) + " -o yaml");
    }

    public SshService.CommandResult getJson(SshService session,
                                            String kubectlType,
                                            String namespace,
                                            String name,
                                            boolean namespaced) {
        return run(session, "kubectl get " + shellQuote(kubectlType) + namespaceFlag(namespace, namespaced)
                + " " + shellQuote(name) + " -o json");
    }

    public SshService.CommandResult describe(SshService session,
                                             String kubectlType,
                                             String namespace,
                                             String name,
                                             boolean namespaced) {
        return run(session, "kubectl describe " + shellQuote(kubectlType) + namespaceFlag(namespace, namespaced)
                + " " + shellQuote(name));
    }

    public SshService.CommandResult events(SshService session,
                                           String namespace,
                                           String name,
                                           boolean namespaced) {
        String command = "kubectl get events"
                + (namespaced ? namespaceFlag(namespace, true) : " -A")
                + " --field-selector " + shellQuote("involvedObject.name=" + name)
                + " --sort-by=.lastTimestamp -o json --ignore-not-found";
        return run(session, command);
    }

    public SshService.RemoteCommandHandle followLogs(SshService session,
                                                     String kubectlType,
                                                     String namespace,
                                                     String name,
                                                     boolean namespaced,
                                                     Consumer<String> stdoutConsumer,
                                                     Consumer<String> stderrConsumer) {
        String target = "pod".equals(kubectlType) ? shellQuote(name) : shellQuote(kubectlType + "/" + name);
        return stream(session, "kubectl logs -f" + namespaceFlag(namespace, namespaced)
                + " " + target + " --all-containers=true --tail=300", stdoutConsumer, stderrConsumer);
    }

    public SshService.CommandResult delete(SshService session,
                                           String kubectlType,
                                           String namespace,
                                           String name,
                                           boolean namespaced) {
        return run(session, "kubectl delete " + shellQuote(kubectlType) + namespaceFlag(namespace, namespaced)
                + " " + shellQuote(name));
    }

    public SshService.CommandResult scale(SshService session,
                                          String kubectlType,
                                          String namespace,
                                          String name,
                                          boolean namespaced,
                                          int replicas) {
        return run(session, "kubectl scale " + shellQuote(kubectlType) + namespaceFlag(namespace, namespaced)
                + " " + shellQuote(name)
                + " --replicas=" + Math.max(0, replicas));
    }

    public SshService.CommandResult rolloutRestart(SshService session,
                                                   String kubectlType,
                                                   String namespace,
                                                   String name,
                                                   boolean namespaced) {
        return run(session, "kubectl rollout restart " + shellQuote(kubectlType) + namespaceFlag(namespace, namespaced)
                + " " + shellQuote(name));
    }

    public SshService.CommandResult triggerCronJob(SshService session, String namespace, String name) {
        String jobName = name + "-manual-" + Instant.now().getEpochSecond();
        return run(session, "kubectl create job -n " + shellQuote(namespace)
                + " " + shellQuote(jobName)
                + " --from=cronjob/" + shellQuote(name));
    }

    public SshService.CommandResult applyYaml(SshService session, String yaml) {
        String encoded = Base64.getEncoder().encodeToString(
                (yaml == null ? "" : yaml).getBytes(StandardCharsets.UTF_8));
        return run(session, "printf %s " + shellQuote(encoded) + " | base64 -d | kubectl apply -f -");
    }

    private double cpuToMillicores(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String normalized = value.trim();
        try {
            if (normalized.endsWith("n")) {
                return Double.parseDouble(normalized.substring(0, normalized.length() - 1)) / 1_000_000;
            }
            if (normalized.endsWith("u")) {
                return Double.parseDouble(normalized.substring(0, normalized.length() - 1)) / 1_000;
            }
            if (normalized.endsWith("m")) {
                return Double.parseDouble(normalized.substring(0, normalized.length() - 1));
            }
            return Double.parseDouble(normalized) * 1000;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private double memoryToBytes(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String normalized = value.trim();
        String suffix = normalized.replaceFirst("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)", "");
        String number = normalized.substring(0, normalized.length() - suffix.length());
        try {
            double amount = Double.parseDouble(number);
            return amount * switch (suffix) {
                case "Ki" -> 1024D;
                case "Mi" -> 1024D * 1024D;
                case "Gi" -> 1024D * 1024D * 1024D;
                case "Ti" -> 1024D * 1024D * 1024D * 1024D;
                case "Pi" -> 1024D * 1024D * 1024D * 1024D * 1024D;
                case "Ei" -> 1024D * 1024D * 1024D * 1024D * 1024D * 1024D;
                case "k" -> 1000D;
                case "M" -> 1000D * 1000D;
                case "G" -> 1000D * 1000D * 1000D;
                case "T" -> 1000D * 1000D * 1000D * 1000D;
                case "P" -> 1000D * 1000D * 1000D * 1000D * 1000D;
                case "E" -> 1000D * 1000D * 1000D * 1000D * 1000D * 1000D;
                default -> 1D;
            };
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String formatCpuMillicores(double millicores) {
        return String.format(Locale.ROOT, "%.2fm", Math.max(0, millicores));
    }

    private String formatMemoryBytes(double bytes) {
        double normalized = Math.max(0, bytes);
        if (normalized >= 1024D * 1024D * 1024D * 1024D) {
            return String.format(Locale.ROOT, "%.2fTi", normalized / 1024D / 1024D / 1024D / 1024D);
        }
        if (normalized >= 1024D * 1024D * 1024D) {
            return String.format(Locale.ROOT, "%.2fGi", normalized / 1024D / 1024D / 1024D);
        }
        if (normalized >= 1024D * 1024D) {
            return String.format(Locale.ROOT, "%.2fMi", normalized / 1024D / 1024D);
        }
        if (normalized >= 1024D) {
            return String.format(Locale.ROOT, "%.2fKi", normalized / 1024D);
        }
        return String.format(Locale.ROOT, "%.0fB", normalized);
    }

    private SshService.CommandResult run(SshService sshService, String command) {
        String fullCommand = "sh -lc " + shellQuote(command);
        SshService.CommandResult result = sshService.executeRemoteCommand(fullCommand, DEFAULT_TIMEOUT);
        if (!result.stderr().isBlank()) {
            LOGGER.debug("k8s command stderr: {}", result.stderr());
        }
        return result;
    }

    private SshService.RemoteCommandHandle stream(SshService sshService,
                                                  String command,
                                                  Consumer<String> stdoutConsumer,
                                                  Consumer<String> stderrConsumer) {
        return sshService.streamRemoteCommand("sh -lc " + shellQuote(command), stdoutConsumer, stderrConsumer);
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            LOGGER.debug("parse kubernetes json failed", e);
            return null;
        }
    }

    private String readVersion(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.path(fieldName);
        if (node == null || node.isMissingNode()) {
            return "";
        }
        String gitVersion = node.path("gitVersion").asText("");
        String major = node.path("major").asText("");
        String minor = node.path("minor").asText("");
        if (!gitVersion.isBlank()) {
            return gitVersion;
        }
        if (!major.isBlank() || !minor.isBlank()) {
            return "v" + major + "." + minor;
        }
        return "";
    }

    private String namespaceFlag(String namespace, boolean namespaced) {
        if (!namespaced || namespace == null || namespace.isBlank() || "-".equals(namespace)) {
            return "";
        }
        return " -n " + shellQuote(namespace);
    }

    private String listScopeFlag(boolean namespaced, String namespace) {
        if (!namespaced) {
            return "";
        }
        if (namespace != null && !namespace.isBlank() && !"-".equals(namespace)) {
            return " -n " + shellQuote(namespace);
        }
        return " -A";
    }

    private String currentNamespace(SshService session) {
        SshService.CommandResult result = run(session,
                "ns=$(kubectl config view --minify --output 'jsonpath={..namespace}' 2>/dev/null); "
                        + "if [ -n \"$ns\" ]; then printf '%s\\n' \"$ns\"; else printf 'default\\n'; fi");
        String namespace = result.stdout() == null ? "" : result.stdout().trim();
        return namespace.isBlank() ? "default" : namespace.lines().findFirst().orElse("default").trim();
    }

    private String commandMessage(SshService.CommandResult result) {
        if (result == null) {
            return "";
        }
        String stdout = result.stdout() == null ? "" : result.stdout().trim();
        String stderr = result.stderr() == null ? "" : result.stderr().trim();
        if (!stdout.isBlank() && !stderr.isBlank()) {
            return stdout + "\n" + stderr;
        }
        if (!stdout.isBlank()) {
            return stdout;
        }
        if (!stderr.isBlank()) {
            return stderr;
        }
        return result.isSuccess() ? "执行成功" : "执行失败";
    }

    private String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

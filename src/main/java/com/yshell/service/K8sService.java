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
import java.util.List;
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

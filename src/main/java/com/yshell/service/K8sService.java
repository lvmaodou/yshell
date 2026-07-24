package com.yshell.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yshell.model.k8s.K8sDetailDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public class K8sService {
    private static final Logger LOGGER = LoggerFactory.getLogger(K8sService.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> SCALABLE = Set.of("deployment", "replicaset", "replicationcontroller", "statefulset");
    private static final Set<String> RESTARTABLE = Set.of("daemonset", "deployment", "statefulset");
    private static final Set<String> CLUSTER_SCOPED = Set.of(
            "namespace",
            "node",
            "persistentvolume",
            "storageclass",
            "ingressclass",
            "clusterrole",
            "clusterrolebinding",
            "customresourcedefinition"
    );

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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

    public SshService.CommandResult getJson(SshService session,
                                            String kubectlType,
                                            String namespace,
                                            String name,
                                            boolean namespaced) {
        return run(session, "kubectl get " + shellQuote(kubectlType) + namespaceFlag(namespace, namespaced)
                + " " + shellQuote(name) + " -o json");
    }

    public K8sSessionManager.K8sDetailResult dashboardDetail(SshService session,
                                                             String kubectlType,
                                                             String namespace,
                                                             String name,
                                                             boolean namespaced) {
        if (kubectlType == null || kubectlType.isBlank() || name == null || name.isBlank()) {
            return K8sSessionManager.K8sDetailResult.failed("Missing Kubernetes resource type or name");
        }
        String kind = canonicalKind(kubectlType);
        try {
            SshService.CommandResult rawResult = getJson(session, kubectlResource(kind), namespace, name, namespaced);
            if (!rawResult.isSuccess()) {
                return K8sSessionManager.K8sDetailResult.failed(commandMessage(rawResult));
            }
            JsonNode raw = parseJson(rawResult.stdout());
            if (raw == null || raw.isMissingNode() || raw.isNull()) {
                return K8sSessionManager.K8sDetailResult.failed("Kubernetes resource JSON is empty");
            }
            return new K8sSessionManager.K8sDetailResult(true, "", buildDashboardDetailDto(session, kind, raw));
        } catch (Exception e) {
            LOGGER.debug("build kubernetes dashboard detail failed", e);
            return K8sSessionManager.K8sDetailResult.failed(
                    e.getMessage() == null ? "Build Kubernetes detail failed" : e.getMessage());
        }
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

    public SshService.CommandResult patch(SshService session,
                                          String kubectlType,
                                          String namespace,
                                          String name,
                                          boolean namespaced,
                                          String patchJson) {
        return run(session, "kubectl patch " + shellQuote(kubectlType) + namespaceFlag(namespace, namespaced)
                + " " + shellQuote(name)
                + " --type=merge -p " + shellQuote(patchJson == null ? "{}" : patchJson));
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
            double v = Double.parseDouble(normalized.substring(0, normalized.length() - 1));
            if (normalized.endsWith("n")) {
                return v / 1_000_000;
            }
            if (normalized.endsWith("u")) {
                return v / 1_000;
            }
            if (normalized.endsWith("m")) {
                return v;
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

    private K8sDetailDtos.ResourceDetailDto buildDashboardDetailDto(SshService session, String kind, JsonNode raw) {
        return switch (canonicalKind(kind)) {
            case "pod" -> podDetail(session, raw);
            case "job" -> jobDetail(session, raw);
            case "cronjob" -> cronJobDetail(session, raw);
            case "daemonset" -> daemonSetDetail(session, raw);
            case "deployment" -> deploymentDetail(session, raw);
            case "replicaset" -> replicaSetDetail(session, raw);
            case "replicationcontroller" -> replicationControllerDetail(session, raw);
            case "statefulset" -> statefulSetDetail(session, raw);
            case "service" -> serviceDetail(session, raw);
            case "namespace" -> namespaceDetail(session, raw);
            case "node" -> nodeDetail(session, raw);
            case "secret" -> secretDetail(raw);
            case "configmap" -> configMapDetail(raw);
            case "persistentvolumeclaim" -> persistentVolumeClaimDetail(raw);
            case "persistentvolume" -> persistentVolumeDetail(raw);
            case "storageclass" -> storageClassDetail(session, raw);
            case "ingress" -> ingressDetail(session, raw);
            case "ingressclass" -> ingressClassDetail(raw);
            case "networkpolicy" -> networkPolicyDetail(raw);
            case "horizontalpodautoscaler" -> horizontalPodAutoscalerDetail(raw);
            case "clusterrole", "role" -> roleDetail(raw, canonicalKind(kind));
            case "clusterrolebinding", "rolebinding" -> roleBindingDetail(raw, canonicalKind(kind));
            case "serviceaccount" -> serviceAccountDetail(session, raw);
            default -> genericDetail(raw, canonicalKind(kind));
        };
    }

    private K8sDetailDtos.PodDetailDto podDetail(SshService session, JsonNode pod) {
        String namespace = text(pod, "/metadata/namespace");
        String name = text(pod, "/metadata/name");
        return new K8sDetailDtos.PodDetailDto(
                objectMeta(pod.path("metadata")),
                typeMeta("pod"),
                List.of(),
                optionalText(podStatus(pod)),
                optionalText(pod, "/status/podIP"),
                optionalText(pod, "/spec/nodeName"),
                optionalText(pod, "/spec/serviceAccountName"),
                restartCount(pod),
                optionalText(pod, "/status/qosClass"),
                podMetricList(session, namespace, name),
                conditions(pod.path("status").path("conditions")),
                controllerOwner(session, namespace, pod),
                containers(
                        pod.path("spec").path("containers"),
                        pod.path("status").path("containerStatuses"),
                        pod.path("spec").path("volumes")),
                containers(
                        pod.path("spec").path("initContainers"),
                        pod.path("status").path("initContainerStatuses"),
                        pod.path("spec").path("volumes")),
                objectList(pod.path("spec").path("imagePullSecrets")),
                eventList(safeEventsForObject(session, namespace, name)),
                podPvcList(pod),
                objectMap(pod.path("spec").path("securityContext")));
    }

    private K8sDetailDtos.JobDetailDto jobDetail(SshService session, JsonNode job) {
        String namespace = text(job, "/metadata/namespace");
        List<JsonNode> pods = podsBySelector(session, namespace, matchLabels(job.path("spec").path("selector")));
        return new K8sDetailDtos.JobDetailDto(
                objectMeta(job.path("metadata")),
                typeMeta("job"),
                List.of(),
                podInfo(intValue(job, "/status/active", 0), integerPointer(job), pods),
                podList(pods, namespace),
                containerImages(job.path("spec").path("template").path("spec").path("containers")),
                containerImages(job.path("spec").path("template").path("spec").path("initContainers")),
                eventList(safeEventsForObject(session, namespace, text(job, "/metadata/name"))),
                intOrNull(job.at("/spec/parallelism")),
                intOrNull(job.at("/spec/completions")),
                jobStatus(job));
    }

    private K8sDetailDtos.CronJobDetailDto cronJobDetail(SshService session, JsonNode cronJob) {
        String namespace = text(cronJob, "/metadata/namespace");
        List<JsonNode> activeJobs = activeCronJobJobs(session, namespace, cronJob);
        List<JsonNode> inactiveJobs = inactiveCronJobJobs(session, namespace, cronJob, activeJobs);
        return new K8sDetailDtos.CronJobDetailDto(
                objectMeta(cronJob.path("metadata")),
                typeMeta("cronjob"),
                List.of(),
                optionalText(cronJob, "/spec/schedule"),
                boolValue(cronJob, "/spec/suspend"),
                cronJob.path("status").path("active").size(),
                optionalText(cronJob, "/status/lastScheduleTime"),
                optionalText(cronJob, "/spec/concurrencyPolicy"),
                intOrNull(cronJob.at("/spec/startingDeadlineSeconds")),
                resourceRefs(activeJobs),
                resourceRefs(inactiveJobs),
                eventList(safeEventsForObject(session, namespace, text(cronJob, "/metadata/name"))));
    }

    private K8sDetailDtos.DaemonSetDetailDto daemonSetDetail(SshService session, JsonNode daemonSet) {
        String namespace = text(daemonSet, "/metadata/namespace");
        List<JsonNode> pods = podsByController(session, namespace, daemonSet);
        return new K8sDetailDtos.DaemonSetDetailDto(
                objectMeta(daemonSet.path("metadata")),
                typeMeta("daemonset"),
                List.of(),
                matchLabels(daemonSet.path("spec").path("selector")),
                podInfo(intValue(daemonSet, "/status/currentNumberScheduled", 0),
                        intOrNull(daemonSet.at("/status/desiredNumberScheduled")), pods),
                podList(pods, namespace),
                serviceList(servicesBySelector(session, namespace, matchLabels(daemonSet.path("spec").path("selector")))),
                templateContainerImages(daemonSet, false),
                templateContainerImages(daemonSet, true),
                eventList(safeEventsForObject(session, namespace, text(daemonSet, "/metadata/name"))));
    }

    private K8sDetailDtos.DeploymentDetailDto deploymentDetail(SshService session, JsonNode deployment) {
        String namespace = text(deployment, "/metadata/namespace");
        String name = text(deployment, "/metadata/name");
        List<JsonNode> replicaSets = replicaSetsForDeployment(session, namespace, deployment);
        JsonNode newReplicaSet = newReplicaSet(deployment, replicaSets);
        List<JsonNode> oldReplicaSets = oldReplicaSets(replicaSets, newReplicaSet);
        return new K8sDetailDtos.DeploymentDetailDto(
                objectMeta(deployment.path("metadata")),
                typeMeta("deployment"),
                List.of(),
                labelArray(matchLabels(deployment.path("spec").path("selector"))),
                new K8sDetailDtos.StatusInfoDto(
                        intValue(deployment, "/status/replicas", 0),
                        intValue(deployment, "/status/updatedReplicas", 0),
                        intValue(deployment, "/status/availableReplicas", 0),
                        intValue(deployment, "/status/unavailableReplicas", 0)),
                conditions(deployment.path("status").path("conditions")),
                optionalText(deployment, "/spec/strategy/type"),
                intValue(deployment, "/spec/minReadySeconds", 0),
                intOrNull(deployment.at("/spec/revisionHistoryLimit")),
                objectMap(deployment.at("/spec/strategy/rollingUpdate")),
                replicaSetSummary(session, namespace, newReplicaSet),
                replicaSetList(session, namespace, oldReplicaSets),
                hpaListFor(session, namespace, "Deployment", name),
                eventList(safeEventsForObject(session, namespace, name)));
    }

    private K8sDetailDtos.ReplicaSetDetailDto replicaSetDetail(SshService session, JsonNode replicaSet) {
        String namespace = text(replicaSet, "/metadata/namespace");
        List<JsonNode> pods = podsByController(session, namespace, replicaSet);
        return new K8sDetailDtos.ReplicaSetDetailDto(
                objectMeta(replicaSet.path("metadata")),
                typeMeta("replicaset"),
                List.of(),
                objectMap(replicaSet.path("spec").path("selector")),
                podInfo(intValue(replicaSet, "/status/replicas", 0), intOrNull(replicaSet.at("/spec/replicas")), pods),
                podList(pods, namespace),
                serviceList(servicesBySelector(session, namespace, matchLabels(replicaSet.path("spec").path("selector")))),
                templateContainerImages(replicaSet, false),
                templateContainerImages(replicaSet, true),
                eventList(safeEventsForObject(session, namespace, text(replicaSet, "/metadata/name"))),
                hpaListFor(session, namespace, "ReplicaSet", text(replicaSet, "/metadata/name")));
    }

    private K8sDetailDtos.ReplicationControllerDetailDto replicationControllerDetail(SshService session, JsonNode rc) {
        String namespace = text(rc, "/metadata/namespace");
        List<JsonNode> pods = podsByController(session, namespace, rc);
        return new K8sDetailDtos.ReplicationControllerDetailDto(
                objectMeta(rc.path("metadata")),
                typeMeta("replicationcontroller"),
                List.of(),
                objectMap(rc.path("spec").path("selector")),
                podInfo(intValue(rc, "/status/replicas", 0), intOrNull(rc.at("/spec/replicas")), pods),
                podList(pods, namespace),
                serviceList(servicesBySelector(session, namespace, labelsFromObject(rc.path("spec").path("selector")))),
                templateContainerImages(rc, false),
                templateContainerImages(rc, true),
                eventList(safeEventsForObject(session, namespace, text(rc, "/metadata/name"))),
                true);
    }

    private K8sDetailDtos.StatefulSetDetailDto statefulSetDetail(SshService session, JsonNode statefulSet) {
        String namespace = text(statefulSet, "/metadata/namespace");
        List<JsonNode> pods = podsByController(session, namespace, statefulSet);
        return new K8sDetailDtos.StatefulSetDetailDto(
                objectMeta(statefulSet.path("metadata")),
                typeMeta("statefulset"),
                List.of(),
                matchLabels(statefulSet.path("spec").path("selector")),
                podInfo(intValue(statefulSet, "/status/replicas", 0), intOrNull(statefulSet.at("/spec/replicas")), pods),
                podList(pods, namespace),
                templateContainerImages(statefulSet, false),
                templateContainerImages(statefulSet, true),
                eventList(safeEventsForObject(session, namespace, text(statefulSet, "/metadata/name"))));
    }

    private K8sDetailDtos.ServiceDetailDto serviceDetail(SshService session, JsonNode service) {
        String namespace = text(service, "/metadata/namespace");
        String name = text(service, "/metadata/name");
        Map<String, String> selector = labelsFromObject(service.path("spec").path("selector"));
        return new K8sDetailDtos.ServiceDetailDto(
                objectMeta(service.path("metadata")),
                typeMeta("service"),
                List.of(),
                serviceInternalEndpoint(service),
                serviceExternalEndpoints(service),
                endpointList(session, namespace, name),
                selector,
                optionalText(service, "/spec/type"),
                optionalText(service, "/spec/clusterIP"),
                podList(podsBySelector(session, namespace, selector), namespace),
                optionalText(service, "/spec/sessionAffinity"),
                ingressListForService(session, namespace, name),
                eventList(safeEventsForObject(session, namespace, name)));
    }

    private K8sDetailDtos.NamespaceDetailDto namespaceDetail(SshService session, JsonNode namespace) {
        String name = text(namespace, "/metadata/name");
        return new K8sDetailDtos.NamespaceDetailDto(
                objectMeta(namespace.path("metadata")),
                typeMeta("namespace"),
                List.of(),
                optionalText(namespace, "/status/phase"),
                eventList(safeEventsForNamespace(session, name)),
                resourceLimits(session, name),
                resourceQuotaList(session, name));
    }

    private K8sDetailDtos.NodeDetailDto nodeDetail(SshService session, JsonNode node) {
        String name = text(node, "/metadata/name");
        List<JsonNode> pods = podsOnNode(session, name);
        return new K8sDetailDtos.NodeDetailDto(
                objectMeta(node.path("metadata")),
                typeMeta("node"),
                List.of(),
                nodePhase(node),
                optionalText(node, "/spec/podCIDR"),
                optionalText(node, "/spec/providerID"),
                boolValue(node, "/spec/unschedulable"),
                nodeAllocatedResources(node, pods),
                objectMap(node.path("status").path("nodeInfo")),
                podContainerImages(pods, false),
                podContainerImages(pods, true),
                objectList(node.path("status").path("addresses")),
                objectList(node.path("spec").path("taints")),
                nodeMetricList(session, name),
                conditions(node.path("status").path("conditions")),
                podList(pods, null),
                eventList(safeEventsForObject(session, null, name)));
    }

    private K8sDetailDtos.SecretDetailDto secretDetail(JsonNode secret) {
        return new K8sDetailDtos.SecretDetailDto(
                objectMeta(secret.path("metadata")),
                typeMeta("secret"),
                List.of(),
                optionalText(secret, "/type"),
                objectMap(secret.path("data")));
    }

    private K8sDetailDtos.ConfigMapDetailDto configMapDetail(JsonNode configMap) {
        return new K8sDetailDtos.ConfigMapDetailDto(
                objectMeta(configMap.path("metadata")),
                typeMeta("configmap"),
                List.of(),
                objectMap(configMap.path("data")));
    }

    private K8sDetailDtos.PersistentVolumeClaimDetailDto persistentVolumeClaimDetail(JsonNode pvc) {
        return new K8sDetailDtos.PersistentVolumeClaimDetailDto(
                objectMeta(pvc.path("metadata")),
                typeMeta("persistentvolumeclaim"),
                List.of(),
                optionalText(pvc, "/status/phase"),
                optionalText(pvc, "/spec/volumeName"),
                optionalText(pvc, "/status/capacity/storage"),
                optionalText(pvc, "/spec/storageClassName"),
                strings(pvc.path("spec").path("accessModes")));
    }

    private K8sDetailDtos.PersistentVolumeDetailDto persistentVolumeDetail(JsonNode pv) {
        String claim = text(pv, "/spec/claimRef/namespace");
        if (!claim.isEmpty()) {
            claim += "/" + text(pv, "/spec/claimRef/name");
        }
        return new K8sDetailDtos.PersistentVolumeDetailDto(
                objectMeta(pv.path("metadata")),
                typeMeta("persistentvolume"),
                List.of(),
                optionalText(pv, "/status/phase"),
                optionalText(claim),
                optionalText(pv, "/spec/persistentVolumeReclaimPolicy"),
                strings(pv.path("spec").path("accessModes")),
                objectMap(pv.path("spec").path("capacity")),
                optionalText(pv, "/status/message"),
                optionalText(pv, "/spec/storageClassName"),
                optionalText(pv, "/status/reason"),
                persistentVolumeSource(pv.path("spec")),
                strings(pv.path("spec").path("mountOptions")));
    }

    private K8sDetailDtos.StorageClassDetailDto storageClassDetail(SshService session, JsonNode storageClass) {
        String name = text(storageClass, "/metadata/name");
        return new K8sDetailDtos.StorageClassDetailDto(
                objectMeta(storageClass.path("metadata")),
                typeMeta("storageclass"),
                List.of(),
                objectMap(storageClass.path("parameters")),
                optionalText(storageClass, "/provisioner"),
                persistentVolumeListForStorageClass(session, name));
    }

    private K8sDetailDtos.IngressDetailDto ingressDetail(JsonNode ingress) {
        return new K8sDetailDtos.IngressDetailDto(
                objectMeta(ingress.path("metadata")),
                typeMeta("ingress"),
                List.of(),
                ingressEndpoints(ingress),
                ingressHosts(ingress),
                objectMap(ingress.path("spec")),
                null);
    }

    private K8sDetailDtos.IngressDetailDto ingressDetail(SshService session, JsonNode ingress) {
        String namespace = text(ingress, "/metadata/namespace");
        String name = text(ingress, "/metadata/name");
        return new K8sDetailDtos.IngressDetailDto(
                objectMeta(ingress.path("metadata")),
                typeMeta("ingress"),
                List.of(),
                ingressEndpoints(ingress),
                ingressHosts(ingress),
                objectMap(ingress.path("spec")),
                eventList(safeEventsForObject(session, namespace, name)));
    }

    private K8sDetailDtos.IngressClassDetailDto ingressClassDetail(JsonNode ingressClass) {
        return new K8sDetailDtos.IngressClassDetailDto(
                objectMeta(ingressClass.path("metadata")),
                typeMeta("ingressclass"),
                List.of(),
                ingressClassParameters(ingressClass.path("spec").path("parameters")),
                optionalText(ingressClass, "/spec/controller"));
    }

    private K8sDetailDtos.NetworkPolicyDetailDto networkPolicyDetail(JsonNode policy) {
        return new K8sDetailDtos.NetworkPolicyDetailDto(
                objectMeta(policy.path("metadata")),
                typeMeta("networkpolicy"),
                List.of(),
                objectMap(policy.path("spec").path("podSelector")),
                objectList(policy.path("spec").path("ingress")),
                objectList(policy.path("spec").path("egress")),
                strings(policy.path("spec").path("policyTypes")));
    }

    private K8sDetailDtos.HorizontalPodAutoscalerDetailDto horizontalPodAutoscalerDetail(JsonNode hpa) {
        return hpaDetail(hpa);
    }

    private K8sDetailDtos.RoleDetailDto roleDetail(JsonNode role, String kind) {
        return new K8sDetailDtos.RoleDetailDto(
                objectMeta(role.path("metadata")),
                typeMeta(kind),
                List.of(),
                objectList(role.path("rules")));
    }

    private K8sDetailDtos.RoleBindingDetailDto roleBindingDetail(JsonNode binding, String kind) {
        return new K8sDetailDtos.RoleBindingDetailDto(
                objectMeta(binding.path("metadata")),
                typeMeta(kind),
                List.of(),
                objectList(binding.path("subjects")),
                objectMap(binding.path("roleRef")));
    }

    private K8sDetailDtos.ServiceAccountDetailDto serviceAccountDetail(SshService session, JsonNode serviceAccount) {
        String namespace = text(serviceAccount, "/metadata/namespace");
        return new K8sDetailDtos.ServiceAccountDetailDto(
                objectMeta(serviceAccount.path("metadata")),
                typeMeta("serviceaccount"),
                List.of(),
                secretListFromRefs(session, namespace, serviceAccount.path("secrets")),
                secretListFromRefs(session, namespace, serviceAccount.path("imagePullSecrets")));
    }


    private K8sDetailDtos.GenericObjectDetailDto genericDetail(JsonNode raw, String kind) {
        return new K8sDetailDtos.GenericObjectDetailDto(
                objectMeta(raw.path("metadata")),
                typeMeta(kind),
                List.of(),
                objectMap(raw));
    }

    private K8sDetailDtos.ObjectMetaDto objectMeta(JsonNode metadata) {
        return new K8sDetailDtos.ObjectMetaDto(
                optionalText(metadata, "/name"),
                optionalText(metadata, "/namespace"),
                stringMap(metadata.path("labels")),
                stringMap(metadata.path("annotations")),
                optionalText(metadata, "/creationTimestamp"),
                optionalText(metadata, "/uid"),
                objectList(metadata.path("ownerReferences")));
    }

    private K8sDetailDtos.TypeMetaDto typeMeta(String kind) {
        String canonical = canonicalKind(kind);
        return new K8sDetailDtos.TypeMetaDto(
                canonical,
                SCALABLE.contains(canonical),
                RESTARTABLE.contains(canonical));
    }

    private List<String> templateContainerImages(JsonNode workload, boolean init) {
        return containerImages(workload.path("spec").path("template").path("spec").path(init ? "initContainers" : "containers"));
    }

    private List<String> containerImages(JsonNode containers) {
        List<String> images = new ArrayList<>();
        for (JsonNode container : iterable(containers)) {
            String image = text(container, "/image");
            if (!image.isEmpty()) {
                images.add(image);
            }
        }
        return images;
    }

    private List<String> podContainerImages(List<JsonNode> pods, boolean init) {
        List<String> images = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String containerField = init ? "initContainers" : "containers";
        for (JsonNode pod : pods) {
            for (JsonNode container : iterable(pod.path("spec").path(containerField))) {
                String image = text(container, "/image");
                if (!image.isEmpty() && seen.add(image)) {
                    images.add(image);
                }
            }
        }
        return images;
    }

    private List<Map<String, Object>> podMetricList(SshService session, String namespace, String name) {
        K8sSessionManager.PodUsage usage = podMetrics(session, namespace).get(namespace + "/" + name);
        if (usage == null) {
            return List.of();
        }
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric("CPU", usage.cpu()));
        metrics.add(metric("Memory", usage.memory()));
        return metrics;
    }

    private Map<String, Object> metric(String name, String value) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("metricName", name);
        metric.put("value", value);
        return metric;
    }

    private List<Map<String, Object>> nodeMetricList(SshService session, String name) {
        SshService.CommandResult result = run(session,
                "kubectl get --raw " + shellQuote("/apis/metrics.k8s.io/v1beta1/nodes/" + name));
        if (!result.isSuccess()) {
            return List.of();
        }
        JsonNode root = parseJson(result.stdout());
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        JsonNode usage = root.path("usage");
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric("CPU", formatCpuMillicores(cpuToMillicores(usage.path("cpu").asText("")))));
        metrics.add(metric("Memory", formatMemoryBytes(memoryToBytes(usage.path("memory").asText("")))));
        return metrics;
    }

    private List<K8sDetailDtos.ContainerDto> containers(JsonNode specs, JsonNode statuses, JsonNode volumes) {
        List<K8sDetailDtos.ContainerDto> result = new ArrayList<>();
        Map<String, JsonNode> statusByName = new HashMap<>();
        Map<String, JsonNode> volumeByName = volumeByName(volumes);
        for (JsonNode status : iterable(statuses)) {
            statusByName.put(text(status, "/name"), status);
        }
        for (JsonNode spec : iterable(specs)) {
            result.add(new K8sDetailDtos.ContainerDto(
                    optionalText(spec, "/name"),
                    optionalText(spec, "/image"),
                    objectList(spec.path("env")),
                    strings(spec.path("command")),
                    strings(spec.path("args")),
                    volumeMounts(spec.path("volumeMounts"), volumeByName),
                    objectMap(spec.path("securityContext")),
                    objectMap(statusByName.get(text(spec, "/name"))),
                    objectMap(spec.path("livenessProbe")),
                    objectMap(spec.path("readinessProbe")),
                    objectMap(spec.path("startupProbe")),
                    objectMap(spec.path("resources"))));
        }
        return result;
    }

    private Map<String, JsonNode> volumeByName(JsonNode volumes) {
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode volume : iterable(volumes)) {
            String name = text(volume, "/name");
            if (!name.isEmpty()) {
                result.put(name, volume);
            }
        }
        return result;
    }

    private List<Map<String, Object>> volumeMounts(JsonNode mounts, Map<String, JsonNode> volumeByName) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode mount : iterable(mounts)) {
            Map<String, Object> dto = objectMap(mount);
            JsonNode volume = volumeByName.get(text(mount, "/name"));
            if (volume != null) {
                dto.put("volume", objectMap(volume));
            }
            result.add(dto);
        }
        return result;
    }

    private K8sDetailDtos.PodInfoDto podInfo(int current, Integer desired, List<JsonNode> pods) {
        int running = 0;
        int pending = 0;
        int failed = 0;
        int succeeded = 0;
        for (JsonNode pod : pods) {
            String status = podStatus(pod);
            switch (status) {
                case "Running" -> running++;
                case "Pending", "ContainerCreating" -> pending++;
                case "Succeeded", "Completed" -> succeeded++;
                case "Failed", "Error" -> failed++;
                default -> {
                    if ("Terminating".equals(status)) {
                        pending++;
                    }
                }
            }
        }
        return new K8sDetailDtos.PodInfoDto(current, desired, running, pending, failed, succeeded, List.of());
    }

    private String podStatus(JsonNode pod) {
        if (pod.at("/metadata/deletionTimestamp").isTextual()) {
            return "Terminating";
        }
        String reason = text(pod, "/status/reason");
        if (!reason.isEmpty()) {
            return reason;
        }
        JsonNode initStatuses = pod.path("status").path("initContainerStatuses");
        if (initStatuses.isArray()) {
            for (int i = 0; i < initStatuses.size(); i++) {
                JsonNode status = initStatuses.get(i);
                JsonNode state = status.path("state");
                if (state.has("terminated") && intValue(state.path("terminated"), "/exitCode", 0) == 0) {
                    continue;
                }
                if (state.has("waiting") && state.path("waiting").hasNonNull("reason")) {
                    return "Init:" + state.path("waiting").path("reason").asText();
                }
                if (state.has("terminated")) {
                    String terminatedReason = text(state.path("terminated"), "/reason");
                    return terminatedReason.isEmpty() ? "Init:Error" : "Init:" + terminatedReason;
                }
                return "Init:" + i + "/" + initStatuses.size();
            }
        }
        for (JsonNode status : iterable(pod.path("status").path("containerStatuses"))) {
            JsonNode state = status.path("state");
            if (state.has("waiting") && state.path("waiting").hasNonNull("reason")) {
                return state.path("waiting").path("reason").asText();
            }
            if (state.has("terminated") && state.path("terminated").hasNonNull("reason")) {
                return state.path("terminated").path("reason").asText();
            }
        }
        return text(pod, "/status/phase");
    }

    private int restartCount(JsonNode pod) {
        int restarts = 0;
        for (JsonNode status : iterable(pod.path("status").path("containerStatuses"))) {
            restarts += status.path("restartCount").asInt(0);
        }
        return restarts;
    }

    private K8sDetailDtos.JobStatusDto jobStatus(JsonNode job) {
        String code = "Running";
        String message = "";
        for (JsonNode condition : iterable(job.path("status").path("conditions"))) {
            if (!"True".equals(text(condition, "/status"))) {
                continue;
            }
            String type = text(condition, "/type");
            if ("Complete".equals(type)) {
                code = "Complete";
                break;
            }
            if ("Failed".equals(type)) {
                code = "Failed";
                message = text(condition, "/message");
                break;
            }
        }
        return new K8sDetailDtos.JobStatusDto(code, optionalText(message), conditions(job.path("status").path("conditions")));
    }

    private List<K8sDetailDtos.ConditionDto> conditions(JsonNode rawConditions) {
        List<K8sDetailDtos.ConditionDto> conditions = new ArrayList<>();
        for (JsonNode raw : iterable(rawConditions)) {
            conditions.add(new K8sDetailDtos.ConditionDto(
                    optionalText(raw, "/type"),
                    optionalText(raw, "/status"),
                    optionalText(raw, "/lastProbeTime"),
                    optionalText(raw, "/lastTransitionTime"),
                    optionalText(raw, "/reason"),
                    optionalText(raw, "/message")));
        }
        return conditions;
    }

    private List<JsonNode> podsByController(SshService session, String namespace, JsonNode owner) {
        String uid = text(owner, "/metadata/uid");
        String name = text(owner, "/metadata/name");
        String kind = text(owner, "/kind");
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode pod : listItems(session, "pod", namespace)) {
            for (JsonNode ref : iterable(pod.path("metadata").path("ownerReferences"))) {
                if (ref.path("controller").asBoolean(false)
                        && ownerReferenceMatches(uid, name, kind, ref)) {
                    result.add(pod);
                    break;
                }
            }
        }
        return result;
    }

    private boolean ownerReferenceMatches(String uid, String name, String kind, JsonNode ref) {
        String refUid = text(ref, "/uid");
        if (!uid.isBlank() && uid.equals(refUid)) {
            return true;
        }
        String refName = text(ref, "/name");
        String refKind = text(ref, "/kind");
        return !name.isBlank()
                && !kind.isBlank()
                && name.equals(refName)
                && kind.equals(refKind);
    }

    private List<JsonNode> activeCronJobJobs(SshService session, String namespace, JsonNode cronJob) {
        Set<String> activeRefs = new HashSet<>();
        for (JsonNode ref : iterable(cronJob.path("status").path("active"))) {
            String uid = text(ref, "/uid");
            String name = text(ref, "/name");
            if (!uid.isBlank()) {
                activeRefs.add("uid:" + uid);
            }
            if (!name.isBlank()) {
                activeRefs.add("name:" + name);
            }
        }
        List<JsonNode> jobs = new ArrayList<>();
        for (JsonNode job : cronJobJobs(session, namespace, cronJob)) {
            String uid = text(job, "/metadata/uid");
            String name = text(job, "/metadata/name");
            if (activeRefs.contains("uid:" + uid)
                    || activeRefs.contains("name:" + name)
                    || intValue(job, "/status/active", 0) > 0) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    private List<JsonNode> inactiveCronJobJobs(SshService session,
                                               String namespace,
                                               JsonNode cronJob,
                                               List<JsonNode> activeJobs) {
        Set<String> activeUids = new HashSet<>();
        for (JsonNode activeJob : activeJobs) {
            String uid = text(activeJob, "/metadata/uid");
            if (!uid.isBlank()) {
                activeUids.add(uid);
            }
        }
        List<JsonNode> jobs = new ArrayList<>();
        for (JsonNode job : cronJobJobs(session, namespace, cronJob)) {
            String uid = text(job, "/metadata/uid");
            if (uid.isBlank() || !activeUids.contains(uid)) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    private List<JsonNode> cronJobJobs(SshService session, String namespace, JsonNode cronJob) {
        List<JsonNode> jobs = new ArrayList<>();
        for (JsonNode job : listItems(session, "job", namespace)) {
            for (JsonNode ref : iterable(job.path("metadata").path("ownerReferences"))) {
                if (ownerRefMatches(ref, cronJob)) {
                    jobs.add(job);
                    break;
                }
            }
        }
        return jobs;
    }

    private boolean ownerRefMatches(JsonNode ref, JsonNode owner) {
        String ownerUid = text(owner, "/metadata/uid");
        String ownerName = text(owner, "/metadata/name");
        String refKind = text(ref, "/kind");
        String refUid = text(ref, "/uid");
        String refName = text(ref, "/name");
        if (!"CronJob".equals(refKind)) {
            return false;
        }
        if (!ownerUid.isBlank() && ownerUid.equals(refUid)) {
            return true;
        }
        return !ownerName.isBlank() && ownerName.equals(refName);
    }

    private List<JsonNode> replicaSetsForDeployment(SshService session, String namespace, JsonNode deployment) {
        List<JsonNode> replicaSets = new ArrayList<>();
        JsonNode selector = deployment.path("spec").path("selector");
        for (JsonNode replicaSet : listItems(session, "replicaset", namespace)) {
            if (labelSelectorMatches(labelsFromObject(replicaSet.path("metadata").path("labels")), selector)) {
                replicaSets.add(replicaSet);
            }
        }
        return replicaSets;
    }

    private JsonNode newReplicaSet(JsonNode deployment, List<JsonNode> replicaSets) {
        JsonNode deploymentTemplate = deployment.path("spec").path("template");
        for (JsonNode replicaSet : replicaSets) {
            if (podTemplatesEqualIgnoreHash(replicaSet.path("spec").path("template"), deploymentTemplate)) {
                return replicaSet;
            }
        }
        return null;
    }

    private List<JsonNode> oldReplicaSets(List<JsonNode> replicaSets, JsonNode newReplicaSet) {
        String newUid = text(newReplicaSet, "/metadata/uid");
        List<JsonNode> old = new ArrayList<>();
        for (JsonNode replicaSet : replicaSets) {
            String uid = text(replicaSet, "/metadata/uid");
            if ((newUid.isBlank() || !newUid.equals(uid)) && intValue(replicaSet, "/spec/replicas", 0) != 0) {
                old.add(replicaSet);
            }
        }
        return old;
    }

    private boolean podTemplatesEqualIgnoreHash(JsonNode first, JsonNode second) {
        if (first == null || second == null || first.isMissingNode() || second.isMissingNode()) {
            return false;
        }
        if (!podTemplateLabelsEqualIgnoreHash(first.at("/metadata/labels"), second.at("/metadata/labels"))) {
            return false;
        }
        JsonNode normalizedFirst = first.deepCopy();
        JsonNode normalizedSecond = second.deepCopy();
        removePodTemplateLabels(normalizedFirst);
        removePodTemplateLabels(normalizedSecond);
        return normalizedFirst.equals(normalizedSecond);
    }

    private boolean podTemplateLabelsEqualIgnoreHash(JsonNode first, JsonNode second) {
        Map<String, String> firstLabels = new LinkedHashMap<>(labelsFromObject(first));
        Map<String, String> secondLabels = new LinkedHashMap<>(labelsFromObject(second));
        firstLabels.remove("pod-template-hash");
        secondLabels.remove("pod-template-hash");
        return firstLabels.equals(secondLabels);
    }

    private void removePodTemplateLabels(JsonNode template) {
        JsonNode metadata = template.path("metadata");
        if (metadata instanceof ObjectNode objectNode) {
            objectNode.remove("labels");
        }
    }

    private List<JsonNode> podsBySelector(SshService session, String namespace, Map<String, String> selector) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode pod : listItems(session, "pod", namespace)) {
            if (labelsMatch(labelsFromObject(pod.path("metadata").path("labels")), selector)) {
                result.add(pod);
            }
        }
        return result;
    }

    private List<JsonNode> podsOnNode(SshService session, String nodeName) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode pod : listItems(session, "pod", null)) {
            if (nodeName.equals(text(pod, "/spec/nodeName"))) {
                result.add(pod);
            }
        }
        return result;
    }

    private List<JsonNode> servicesBySelector(SshService session, String namespace, Map<String, String> selector) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode service : listItems(session, "service", namespace)) {
            if (labelsMatch(selector, labelsFromObject(service.path("spec").path("selector")))) {
                result.add(service);
            }
        }
        return result;
    }

    private K8sDetailDtos.IngressListDto ingressListForService(SshService session, String namespace, String serviceName) {
        List<K8sDetailDtos.IngressDetailDto> items = new ArrayList<>();
        for (JsonNode ingress : listItems(session, "ingress", namespace)) {
            if (ingressMatchesServiceName(ingress, serviceName)) {
                items.add(ingressDetail(ingress));
            }
        }
        return new K8sDetailDtos.IngressListDto(listMeta(items.size()), items, List.of());
    }

    private boolean ingressMatchesServiceName(JsonNode ingress, String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return false;
        }
        if (ingressBackendMatchesServiceName(ingress.at("/spec/defaultBackend"), serviceName)) {
            return true;
        }
        for (JsonNode rule : iterable(ingress.path("spec").path("rules"))) {
            for (JsonNode path : iterable(rule.path("http").path("paths"))) {
                if (ingressBackendMatchesServiceName(path.path("backend"), serviceName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ingressBackendMatchesServiceName(JsonNode backend, String serviceName) {
        if (backend == null || backend.isMissingNode() || backend.isNull()) {
            return false;
        }
        if (serviceName.equals(text(backend, "/service/name"))) {
            return true;
        }
        String resourceKind = text(backend, "/resource/kind");
        return "Service".equals(resourceKind) && serviceName.equals(text(backend, "/resource/name"));
    }

    private K8sDetailDtos.PersistentVolumeListDto persistentVolumeListForStorageClass(SshService session, String storageClassName) {
        List<K8sDetailDtos.PersistentVolumeDetailDto> items = new ArrayList<>();
        if (storageClassName == null || storageClassName.isBlank()) {
            return new K8sDetailDtos.PersistentVolumeListDto(listMeta(0), items, List.of());
        }
        for (JsonNode pv : listItems(session, "persistentvolume", null)) {
            if (storageClassName.equals(text(pv, "/spec/storageClassName"))) {
                items.add(persistentVolumeDetail(pv));
            }
        }
        return new K8sDetailDtos.PersistentVolumeListDto(listMeta(items.size()), items, List.of());
    }

    private K8sDetailDtos.SecretListDto secretListFromRefs(SshService session, String namespace, JsonNode refs) {
        List<K8sDetailDtos.SecretDetailDto> items = new ArrayList<>();
        for (JsonNode ref : iterable(refs)) {
            String name = text(ref, "/name");
            if (name.isBlank()) {
                continue;
            }
            try {
                SshService.CommandResult result = getJson(session, kubectlResource("secret"), namespace, name, true);
                JsonNode secret = result.isSuccess() ? parseJson(result.stdout()) : null;
                if (secret != null && !secret.isMissingNode() && !secret.isNull()) {
                    items.add(secretDetail(secret));
                }
            } catch (Exception ignored) {
                // Missing or inaccessible referenced secrets are treated like Dashboard non-critical child list misses.
            }
        }
        return new K8sDetailDtos.SecretListDto(listMeta(items.size()), items, List.of());
    }

    private List<JsonNode> safeEventsForObject(SshService session, String namespace, String name) {
        try {
            List<JsonNode> result = new ArrayList<>();
            for (JsonNode event : listItems(session, "event", namespace)) {
                String involvedName = firstText(event, "/involvedObject/name", "/regarding/name");
                if (name.equals(involvedName)) {
                    result.add(event);
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<JsonNode> safeEventsForNamespace(SshService session, String namespace) {
        try {
            return new ArrayList<>(listItems(session, "event", namespace));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private K8sDetailDtos.EventListDto eventList(List<JsonNode> events) {
        List<K8sDetailDtos.EventDto> items = new ArrayList<>();
        for (JsonNode event : events) {
            items.add(eventDto(event));
        }
        return new K8sDetailDtos.EventListDto(listMeta(items.size()), items, List.of());
    }

    private K8sDetailDtos.EventDto eventDto(JsonNode event) {
        String objectKind = firstText(event, "/involvedObject/kind", "/regarding/kind");
        String objectName = firstText(event, "/involvedObject/name", "/regarding/name");
        String objectNamespace = firstText(event, "/involvedObject/namespace", "/regarding/namespace");
        return new K8sDetailDtos.EventDto(
                objectMeta(event.path("metadata")),
                typeMeta("event"),
                optionalText(event, "/message"),
                optionalText(event, "/source/component"),
                optionalText(event, "/source/host"),
                optionalText(objectKind + "/" + objectName),
                optionalText(objectKind),
                optionalText(objectName),
                optionalText(objectNamespace),
                optionalText(event, "/reason"),
                optionalText(event, "/type"),
                optionalText(firstText(event, "/firstTimestamp", "/eventTime", "/metadata/creationTimestamp")),
                optionalText(firstText(event, "/lastTimestamp", "/eventTime", "/metadata/creationTimestamp")),
                intValue(event, "/count", 1));
    }

    private K8sDetailDtos.PodListDto podList(List<JsonNode> pods, String fallbackNamespace) {
        List<K8sDetailDtos.PodSummaryDto> items = new ArrayList<>();
        for (JsonNode pod : pods) {
            K8sDetailDtos.ObjectMetaDto meta = objectMeta(pod.path("metadata"));
            if ((meta.namespace() == null || meta.namespace().isBlank()) && fallbackNamespace != null) {
                meta = new K8sDetailDtos.ObjectMetaDto(
                        meta.name(),
                        fallbackNamespace,
                        meta.labels(),
                        meta.annotations(),
                        meta.creationTimestamp(),
                        meta.uid(),
                        meta.ownerReferences());
            }
            items.add(new K8sDetailDtos.PodSummaryDto(
                    meta,
                    typeMeta("pod"),
                    optionalText(podStatus(pod)),
                    optionalText(pod, "/status/podIP"),
                    restartCount(pod),
                    optionalText(pod, "/status/qosClass"),
                    Map.of(),
                    warningEventsForPods(List.of(pod)),
                    optionalText(pod, "/spec/nodeName"),
                    optionalText(pod, "/spec/serviceAccountName"),
                    containerImages(pod.path("spec").path("containers"))));
        }
        return new K8sDetailDtos.PodListDto(listMeta(items.size()), items, Map.of(), List.of(), List.of());
    }

    private List<K8sDetailDtos.EventDto> warningEventsForPods(List<JsonNode> pods) {
        List<K8sDetailDtos.EventDto> warnings = new ArrayList<>();
        for (JsonNode pod : pods) {
            for (JsonNode event : safeEventsForObject(null, text(pod, "/metadata/namespace"), text(pod, "/metadata/name"))) {
                if ("Warning".equals(text(event, "/type"))) {
                    warnings.add(eventDto(event));
                }
            }
        }
        return warnings;
    }

    private K8sDetailDtos.ServiceListDto serviceList(List<JsonNode> services) {
        List<K8sDetailDtos.ServiceSummaryDto> items = new ArrayList<>();
        for (JsonNode service : services) {
            items.add(new K8sDetailDtos.ServiceSummaryDto(
                    objectMeta(service.path("metadata")),
                    typeMeta("service"),
                    List.of(),
                    serviceInternalEndpoint(service),
                    serviceExternalEndpoints(service),
                    optionalText(service, "/spec/type"),
                    optionalText(service, "/spec/clusterIP")));
        }
        return new K8sDetailDtos.ServiceListDto(listMeta(items.size()), items, List.of());
    }

    private K8sDetailDtos.ReplicaSetListDto replicaSetList(SshService session,
                                                           String namespace,
                                                           List<JsonNode> replicaSets) {
        List<K8sDetailDtos.ReplicaSetSummaryDto> items = new ArrayList<>();
        for (JsonNode replicaSet : replicaSets) {
            K8sDetailDtos.ReplicaSetSummaryDto item = replicaSetSummary(session, namespace, replicaSet);
            if (item != null) {
                items.add(item);
            }
        }
        return new K8sDetailDtos.ReplicaSetListDto(listMeta(items.size()), items, List.of());
    }

    private K8sDetailDtos.ReplicaSetSummaryDto replicaSetSummary(SshService session,
                                                                 String namespace,
                                                                 JsonNode replicaSet) {
        if (replicaSet == null || replicaSet.isMissingNode() || replicaSet.isNull()) {
            return null;
        }
        List<JsonNode> pods = podsByController(session, namespace, replicaSet);
        return new K8sDetailDtos.ReplicaSetSummaryDto(
                objectMeta(replicaSet.path("metadata")),
                typeMeta("replicaset"),
                podInfo(intValue(replicaSet, "/status/replicas", 0), intOrNull(replicaSet.at("/spec/replicas")), pods),
                templateContainerImages(replicaSet, false),
                templateContainerImages(replicaSet, true));
    }

    private K8sDetailDtos.HorizontalPodAutoscalerListDto hpaListFor(SshService session,
                                                                    String namespace,
                                                                    String targetKind,
                                                                    String targetName) {
        List<K8sDetailDtos.HorizontalPodAutoscalerDetailDto> items = new ArrayList<>();
        for (JsonNode hpa : listItems(session, "horizontalpodautoscaler", namespace)) {
            if (targetKind.equals(text(hpa, "/spec/scaleTargetRef/kind"))
                    && targetName.equals(text(hpa, "/spec/scaleTargetRef/name"))) {
                items.add(hpaDetail(hpa));
            }
        }
        return new K8sDetailDtos.HorizontalPodAutoscalerListDto(listMeta(items.size()), items, List.of());
    }

    private K8sDetailDtos.HorizontalPodAutoscalerDetailDto hpaDetail(JsonNode hpa) {
        return new K8sDetailDtos.HorizontalPodAutoscalerDetailDto(
                objectMeta(hpa.path("metadata")),
                typeMeta("horizontalpodautoscaler"),
                List.of(),
                objectMap(hpa.path("spec").path("scaleTargetRef")),
                intValue(hpa, "/spec/minReplicas", 1),
                intValue(hpa, "/spec/maxReplicas", 1),
                intValue(hpa, "/status/currentCPUUtilizationPercentage", 0),
                intOrNull(hpa.at("/spec/targetCPUUtilizationPercentage")),
                intValue(hpa, "/status/currentReplicas", 0),
                intValue(hpa, "/status/desiredReplicas", 0),
                optionalText(hpa, "/status/lastScaleTime"));
    }

    private K8sDetailDtos.EndpointListDto endpointList(SshService session, String namespace, String serviceName) {
        List<K8sDetailDtos.EndpointDto> items = new ArrayList<>();
        for (JsonNode endpoint : listItems(session, "endpoints", namespace)) {
            if (!serviceName.equals(text(endpoint, "/metadata/name"))) {
                continue;
            }
            for (JsonNode subset : iterable(endpoint.path("subsets"))) {
                for (JsonNode address : iterable(subset.path("addresses"))) {
                    items.add(new K8sDetailDtos.EndpointDto(
                            optionalText(address, "/ip"),
                            optionalText(address, "/nodeName"),
                            true,
                            objectList(subset.path("ports")),
                            objectMeta(endpoint.path("metadata")),
                            typeMeta("endpoint")));
                }
                for (JsonNode address : iterable(subset.path("notReadyAddresses"))) {
                    items.add(new K8sDetailDtos.EndpointDto(
                            optionalText(address, "/ip"),
                            optionalText(address, "/nodeName"),
                            false,
                            objectList(subset.path("ports")),
                            objectMeta(endpoint.path("metadata")),
                            typeMeta("endpoint")));
                }
            }
        }
        return new K8sDetailDtos.EndpointListDto(listMeta(items.size()), items, List.of());
    }

    private K8sDetailDtos.EndpointDto serviceInternalEndpoint(JsonNode service) {
        return new K8sDetailDtos.EndpointDto(
                optionalText(service, "/spec/clusterIP"),
                null,
                null,
                objectList(service.path("spec").path("ports")),
                null,
                null);
    }

    private List<K8sDetailDtos.EndpointDto> serviceExternalEndpoints(JsonNode service) {
        List<K8sDetailDtos.EndpointDto> endpoints = new ArrayList<>();
        for (JsonNode ingress : iterable(service.path("status").path("loadBalancer").path("ingress"))) {
            endpoints.add(new K8sDetailDtos.EndpointDto(
                    optionalText(firstText(ingress, "/hostname", "/ip")),
                    null,
                    null,
                    objectList(service.path("spec").path("ports")),
                    null,
                    null));
        }
        for (JsonNode ip : iterable(service.path("spec").path("externalIPs"))) {
            endpoints.add(new K8sDetailDtos.EndpointDto(
                    optionalText(ip.asText("")),
                    null,
                    null,
                    objectList(service.path("spec").path("ports")),
                    null,
                    null));
        }
        return endpoints;
    }

    private List<K8sDetailDtos.EndpointDto> ingressEndpoints(JsonNode ingress) {
        List<K8sDetailDtos.EndpointDto> endpoints = new ArrayList<>();
        for (JsonNode item : iterable(ingress.path("status").path("loadBalancer").path("ingress"))) {
            String host = firstText(item, "/hostname", "/ip");
            if (host.isEmpty()) {
                continue;
            }
            endpoints.add(new K8sDetailDtos.EndpointDto(host, null, null, List.of(), null, null));
        }
        return endpoints;
    }

    private List<String> ingressHosts(JsonNode ingress) {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode rule : iterable(ingress.path("spec").path("rules"))) {
            String host = text(rule, "/host");
            if (!host.isBlank()) {
                seen.add(host);
            }
        }
        return new ArrayList<>(seen);
    }

    private Map<String, Object> ingressClassParameters(JsonNode parameters) {
        Map<String, Object> result = new LinkedHashMap<>();
        putObjectValue(result, "Kind", optionalText(parameters, "/kind"));
        putObjectValue(result, "Name", optionalText(parameters, "/name"));
        putObjectValue(result, "ApiGroup", optionalText(parameters, "/apiGroup"));
        putObjectValue(result, "Namespace", optionalText(parameters, "/namespace"));
        putObjectValue(result, "Scope", optionalText(parameters, "/scope"));
        return result;
    }

    private void putObjectValue(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private K8sDetailDtos.ResourceRefDto controllerOwner(SshService session, String namespace, JsonNode pod) {
        JsonNode refs = pod.path("metadata").path("ownerReferences");
        if (!refs.isArray() || refs.isEmpty()) {
            return null;
        }
        JsonNode ownerRef = refs.get(0);
        String ownerKind = canonicalKind(text(ownerRef, "/kind"));
        String ownerName = text(ownerRef, "/name");
        try {
            SshService.CommandResult result = getJson(session, kubectlResource(ownerKind), namespace, ownerName, !CLUSTER_SCOPED.contains(ownerKind));
            JsonNode owner = result.isSuccess() ? parseJson(result.stdout()) : null;
            if (owner != null) {
                return new K8sDetailDtos.ResourceRefDto(objectMeta(owner.path("metadata")), typeMeta(ownerKind));
            }
        } catch (Exception ignored) {
        }
        return new K8sDetailDtos.ResourceRefDto(
                new K8sDetailDtos.ObjectMetaDto(
                        optionalText(ownerName),
                        optionalText(namespace == null ? "" : namespace),
                        Map.of(),
                        Map.of(),
                        null,
                        null,
                        List.of()),
                typeMeta(ownerKind));
    }

    private List<K8sDetailDtos.ResourceRefDto> resourceRefs(List<JsonNode> resources) {
        List<K8sDetailDtos.ResourceRefDto> refs = new ArrayList<>();
        for (JsonNode resource : resources) {
            refs.add(new K8sDetailDtos.ResourceRefDto(objectMeta(resource.path("metadata")), typeMeta("job")));
        }
        return refs;
    }

    private K8sDetailDtos.AllocatedResourcesDto nodeAllocatedResources(JsonNode node, List<JsonNode> pods) {
        long cpuRequests = 0;
        long cpuLimits = 0;
        long memoryRequests = 0;
        long memoryLimits = 0;
        for (JsonNode pod : pods) {
            for (JsonNode c : iterable(pod.path("spec").path("containers"))) {
                cpuRequests += milliCpu(text(c, "/resources/requests/cpu"));
                cpuLimits += milliCpu(text(c, "/resources/limits/cpu"));
                memoryRequests += bytes(text(c, "/resources/requests/memory"));
                memoryLimits += bytes(text(c, "/resources/limits/memory"));
            }
        }
        long cpuCapacity = milliCpu(text(node, "/status/capacity/cpu"));
        long memoryCapacity = bytes(text(node, "/status/capacity/memory"));
        int podCapacity = intValue(node, "/status/capacity/pods", 0);
        return new K8sDetailDtos.AllocatedResourcesDto(
                cpuRequests,
                cpuLimits,
                cpuCapacity,
                fraction(cpuRequests, cpuCapacity),
                fraction(cpuLimits, cpuCapacity),
                memoryRequests,
                memoryLimits,
                memoryCapacity,
                fraction(memoryRequests, memoryCapacity),
                fraction(memoryLimits, memoryCapacity),
                pods.size(),
                podCapacity,
                fraction(pods.size(), podCapacity));
    }

    private String nodePhase(JsonNode node) {
        for (JsonNode condition : iterable(node.path("status").path("conditions"))) {
            if ("Ready".equals(text(condition, "/type"))) {
                return "True".equals(text(condition, "/status")) ? "Ready" : "NotReady";
            }
        }
        return "Unknown";
    }

    private K8sDetailDtos.PersistentVolumeClaimListDto podPvcList(JsonNode pod) {
        List<K8sDetailDtos.PersistentVolumeClaimRefDto> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode volume : iterable(pod.path("spec").path("volumes"))) {
            String claimName = text(volume, "/persistentVolumeClaim/claimName");
            if (!claimName.isEmpty() && seen.add(claimName)) {
                items.add(new K8sDetailDtos.PersistentVolumeClaimRefDto(
                        new K8sDetailDtos.ObjectMetaDto(
                                claimName,
                                optionalText(pod, "/metadata/namespace"),
                                Map.of(),
                                Map.of(),
                                null,
                                null,
                                List.of()),
                        typeMeta("persistentvolumeclaim")));
            }
        }
        return new K8sDetailDtos.PersistentVolumeClaimListDto(listMeta(items.size()), items, List.of());
    }

    private Map<String, Object> persistentVolumeSource(JsonNode spec) {
        Map<String, Object> source = new LinkedHashMap<>();
        List<String> keys = List.of(
                "hostPath", "awsElasticBlockStore", "cinder", "fc", "flocker", "gcePersistentDisk", "glusterfs",
                "csi", "iscsi", "nfs", "rbd", "cephfs", "azureDisk", "azureFile", "vsphereVolume", "local");
        for (String key : keys) {
            if (spec.has(key)) {
                source.put(key, objectMap(spec.path(key)));
            }
        }
        return source;
    }

    private K8sDetailDtos.ResourceQuotaListDto resourceQuotaList(SshService session, String namespace) {
        List<K8sDetailDtos.ResourceQuotaDto> items = new ArrayList<>();
        for (JsonNode quota : listItems(session, "resourcequota", namespace)) {
            items.add(new K8sDetailDtos.ResourceQuotaDto(
                    objectMeta(quota.path("metadata")),
                    typeMeta("resourcequota"),
                    List.of(),
                    strings(quota.path("spec").path("scopes")),
                    resourceQuotaStatusList(quota)));
        }
        return new K8sDetailDtos.ResourceQuotaListDto(listMeta(items.size()), items, List.of());
    }

    private Map<String, Map<String, String>> resourceQuotaStatusList(JsonNode quota) {
        Map<String, Map<String, String>> statusList = new LinkedHashMap<>();
        JsonNode used = quota.path("status").path("used");
        JsonNode hard = quota.path("status").path("hard");
        Set<String> names = new HashSet<>();
        used.fieldNames().forEachRemaining(names::add);
        hard.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            Map<String, String> status = new LinkedHashMap<>();
            String usedValue = text(used, "/" + escapePointer(name));
            String hardValue = text(hard, "/" + escapePointer(name));
            if (!usedValue.isBlank()) {
                status.put("used", usedValue);
            }
            if (!hardValue.isBlank()) {
                status.put("hard", hardValue);
            }
            statusList.put(name, status);
        }
        return statusList;
    }

    private List<K8sDetailDtos.ResourceLimitDto> resourceLimits(SshService session, String namespace) {
        List<K8sDetailDtos.ResourceLimitDto> items = new ArrayList<>();
        for (JsonNode limitRange : listItems(session, "limitrange", namespace)) {
            for (JsonNode limit : iterable(limitRange.path("spec").path("limits"))) {
                Set<String> resourceNames = new HashSet<>();
                collectLimitResourceNames(resourceNames, limit.path("min"));
                collectLimitResourceNames(resourceNames, limit.path("max"));
                collectLimitResourceNames(resourceNames, limit.path("default"));
                collectLimitResourceNames(resourceNames, limit.path("defaultRequest"));
                collectLimitResourceNames(resourceNames, limit.path("maxLimitRequestRatio"));
                for (String resourceName : resourceNames) {
                    items.add(new K8sDetailDtos.ResourceLimitDto(
                            optionalText(limit, "/type"),
                            resourceName,
                            optionalText(quantity(limit.path("min"), resourceName)),
                            optionalText(quantity(limit.path("max"), resourceName)),
                            optionalText(quantity(limit.path("default"), resourceName)),
                            optionalText(quantity(limit.path("defaultRequest"), resourceName)),
                            optionalText(quantity(limit.path("maxLimitRequestRatio"), resourceName))));
                }
            }
        }
        return items;
    }

    private void collectLimitResourceNames(Set<String> names, JsonNode quantities) {
        if (quantities != null && quantities.isObject()) {
            quantities.fieldNames().forEachRemaining(names::add);
        }
    }

    private String quantity(JsonNode quantities, String resourceName) {
        return text(quantities, "/" + escapePointer(resourceName));
    }

    private List<JsonNode> listItems(SshService session, String kind, String namespace) {
        SshService.CommandResult result = listResourceJson(session, kind, namespace);
        if (!result.isSuccess()) {
            return List.of();
        }
        JsonNode root = parseJson(result.stdout());
        List<JsonNode> items = new ArrayList<>();
        if (root != null && root.path("items").isArray()) {
            for (JsonNode item : root.path("items")) {
                items.add(item);
            }
        }
        return items;
    }

    private SshService.CommandResult listResourceJson(SshService session, String kind, String namespace) {
        String normalized = canonicalKind(kind);
        boolean namespaced = !CLUSTER_SCOPED.contains(normalized);
        String command = "kubectl get " + shellQuote(kubectlResource(normalized))
                + listScopeFlag(namespaced, namespace)
                + " -o json --ignore-not-found";
        return run(session, command);
    }

    private Map<String, String> matchLabels(JsonNode selector) {
        return labelsFromObject(selector.path("matchLabels"));
    }

    private Map<String, String> labelsFromObject(JsonNode labels) {
        Map<String, String> map = new LinkedHashMap<>();
        if (labels != null && labels.isObject()) {
            for (Map.Entry<String, JsonNode> entry : labels.properties()) {
                map.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return map;
    }

    private boolean labelsMatch(Map<String, String> labels, Map<String, String> selector) {
        if (selector.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : selector.entrySet()) {
            if (!Objects.equals(labels.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean labelSelectorMatches(Map<String, String> labels, JsonNode selector) {
        if (selector == null || selector.isMissingNode() || selector.isNull()) {
            return false;
        }
        Map<String, String> matchLabels = matchLabels(selector);
        JsonNode matchExpressions = selector.path("matchExpressions");
        if (matchLabels.isEmpty() && (!matchExpressions.isArray() || matchExpressions.isEmpty())) {
            return false;
        }
        if (!matchLabels.isEmpty() && !labelsMatch(labels, matchLabels)) {
            return false;
        }
        for (JsonNode expression : iterable(matchExpressions)) {
            String key = text(expression, "/key");
            String operator = text(expression, "/operator");
            List<String> values = strings(expression.path("values"));
            boolean hasKey = labels.containsKey(key);
            String labelValue = labels.get(key);
            if ("In".equals(operator)) {
                if (!hasKey || !values.contains(labelValue)) {
                    return false;
                }
            } else if ("NotIn".equals(operator)) {
                if (hasKey && values.contains(labelValue)) {
                    return false;
                }
            } else if ("Exists".equals(operator)) {
                if (!hasKey) {
                    return false;
                }
            } else if ("DoesNotExist".equals(operator)) {
                if (hasKey) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<K8sDetailDtos.KeyValueDto> labelArray(Map<String, String> labels) {
        List<K8sDetailDtos.KeyValueDto> values = new ArrayList<>();
        labels.forEach((key, value) -> values.add(new K8sDetailDtos.KeyValueDto(key, value)));
        return values;
    }

    private K8sDetailDtos.ListMetaDto listMeta(int totalItems) {
        return new K8sDetailDtos.ListMetaDto(totalItems);
    }

    private static String canonicalKind(String kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "")) {
            case "deploy", "deployment", "deployments" -> "deployment";
            case "ds", "daemonset", "daemonsets" -> "daemonset";
            case "rs", "replicaset", "replicasets" -> "replicaset";
            case "rc", "replicationcontroller", "replicationcontrollers" -> "replicationcontroller";
            case "sts", "statefulset", "statefulsets" -> "statefulset";
            case "po", "pod", "pods" -> "pod";
            case "svc", "service", "services" -> "service";
            case "ep", "endpoint", "endpoints" -> "endpoints";
            case "job", "jobs" -> "job";
            case "cj", "cronjob", "cronjobs" -> "cronjob";
            case "cm", "configmap", "configmaps" -> "configmap";
            case "ns", "namespace", "namespaces" -> "namespace";
            case "no", "node", "nodes" -> "node";
            case "pvc", "persistentvolumeclaim", "persistentvolumeclaims" -> "persistentvolumeclaim";
            case "pv", "persistentvolume", "persistentvolumes" -> "persistentvolume";
            case "sc", "storageclass", "storageclasses" -> "storageclass";
            case "ing", "ingress", "ingresses" -> "ingress";
            case "ingressclass", "ingressclasses" -> "ingressclass";
            case "netpol", "networkpolicy", "networkpolicies" -> "networkpolicy";
            case "hpa", "horizontalpodautoscaler", "horizontalpodautoscalers" -> "horizontalpodautoscaler";
            case "clusterrole", "clusterroles" -> "clusterrole";
            case "role", "roles" -> "role";
            case "clusterrolebinding", "clusterrolebindings" -> "clusterrolebinding";
            case "rolebinding", "rolebindings" -> "rolebinding";
            case "sa", "serviceaccount", "serviceaccounts" -> "serviceaccount";
            case "crd", "customresourcedefinition", "customresourcedefinitions" -> "customresourcedefinition";
            default -> kind.toLowerCase(Locale.ROOT);
        };
    }

    private static String kubectlResource(String kind) {
        return switch (canonicalKind(kind)) {
            case "deployment" -> "deployment.apps";
            case "daemonset" -> "daemonset.apps";
            case "replicaset" -> "replicaset.apps";
            case "statefulset" -> "statefulset.apps";
            case "job" -> "job.batch";
            case "cronjob" -> "cronjob.batch";
            case "ingress" -> "ingress.networking.k8s.io";
            case "ingressclass" -> "ingressclass.networking.k8s.io";
            case "networkpolicy" -> "networkpolicy.networking.k8s.io";
            case "horizontalpodautoscaler" -> "horizontalpodautoscaler.autoscaling";
            case "clusterrole" -> "clusterrole.rbac.authorization.k8s.io";
            case "role" -> "role.rbac.authorization.k8s.io";
            case "clusterrolebinding" -> "clusterrolebinding.rbac.authorization.k8s.io";
            case "rolebinding" -> "rolebinding.rbac.authorization.k8s.io";
            case "customresourcedefinition" -> "customresourcedefinition.apiextensions.k8s.io";
            default -> canonicalKind(kind);
        };
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        if (node != null && node.isArray()) {
            return node::elements;
        }
        return List.of();
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private List<Map<String, Object>> objectList(JsonNode node) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isObject()) {
                    values.add(objectMap(item));
                }
            }
            return values;
        }
        if (node.isObject()) {
            values.add(objectMap(node));
        }
        return values;
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        }
        String value = node.asText("");
        if (!value.isBlank()) {
            values.add(value);
        }
        return values;
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                values.put(field.getKey(), field.getValue().asText(""));
            }
        }
        return values;
    }

    private String text(JsonNode source, String pointer) {
        if (source == null) {
            return "";
        }
        JsonNode node = pointer.startsWith("/") ? source.at(pointer) : source.path(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    private String optionalText(JsonNode source, String pointer) {
        return optionalText(text(source, pointer));
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String firstText(JsonNode source, String... pointers) {
        for (String pointer : pointers) {
            String value = text(source, pointer);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String escapePointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private int intValue(JsonNode source, String pointer, int defaultValue) {
        JsonNode node = source == null ? null : source.at(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        return node.asInt(defaultValue);
    }

    private Integer integerPointer(JsonNode source) {
        JsonNode node = source == null ? null : source.at("/spec/completions");
        return intOrNull(node);
    }

    private Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }

    private boolean boolValue(JsonNode source, String pointer) {
        JsonNode node = source == null ? null : source.at(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        return node.asBoolean(false);
    }

    private int fraction(long used, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.round((double) used * 100 / total);
    }

    private long milliCpu(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String normalized = value.trim();
        try {
            if (normalized.endsWith("m")) {
                return Long.parseLong(normalized.substring(0, normalized.length() - 1));
            }
            return Math.round(Double.parseDouble(normalized) * 1000);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private long bytes(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String normalized = value.trim();
        Map<String, Long> multipliers = Map.of(
                "Ki", 1024L,
                "Mi", 1024L * 1024L,
                "Gi", 1024L * 1024L * 1024L,
                "K", 1000L,
                "M", 1000L * 1000L,
                "G", 1000L * 1000L * 1000L);
        try {
            for (Map.Entry<String, Long> entry : multipliers.entrySet()) {
                if (normalized.endsWith(entry.getKey())) {
                    return Long.parseLong(normalized.substring(0, normalized.length() - entry.getKey().length())) * entry.getValue();
                }
            }
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

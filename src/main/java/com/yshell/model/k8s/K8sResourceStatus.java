package com.yshell.model.k8s;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

public record K8sResourceStatus(Level level, String text, boolean eventDetailAvailable) {
    private static final List<String> EVENT_DETAIL_TYPES = List.of(
            "pod",
            "daemonset",
            "deployment",
            "job",
            "replicaset",
            "replicationcontroller",
            "statefulset"
    );

    public enum Level {
        SUCCESS("kd-success"),
        WARNING("kd-warning"),
        ERROR("kd-error"),
        MUTED("kd-muted");

        private final String cssClass;

        Level(String cssClass) {
            this.cssClass = cssClass;
        }

        public String cssClass() {
            return cssClass;
        }
    }

    public static K8sResourceStatus resolve(String kubectlType, JsonNode item) {
        String type = normalize(kubectlType);
        K8sResourceStatus status = switch (type) {
            case "cronjob" -> cronJobStatus(item);
            case "daemonset" -> daemonSetStatus(item);
            case "deployment" -> deploymentStatus(item);
            case "job" -> jobStatus(item);
            case "pod" -> podStatus(item);
            case "replicaset", "replicationcontroller" -> replicaStatus(item);
            case "statefulset" -> statefulSetStatus(item);
            case "service" -> serviceStatus(item);
            case "persistentvolumeclaim" -> pvcStatus(item);
            case "namespace" -> namespaceStatus(item);
            case "node" -> nodeStatus(item);
            case "persistentvolume" -> pvStatus(item);
            default -> muted("-");
        };
        return new K8sResourceStatus(status.level(), status.text(),
                EVENT_DETAIL_TYPES.contains(type) && status.isWarningOrError());
    }

    public boolean isWarningOrError() {
        return level == Level.WARNING || level == Level.ERROR;
    }

    private static K8sResourceStatus cronJobStatus(JsonNode item) {
        return boolValue(item, "spec", "suspend") ? muted("Suspended") : success("Running");
    }

    private static K8sResourceStatus daemonSetStatus(JsonNode item) {
        int desired = intValue(item, 0, "status", "desiredNumberScheduled");
        int ready = intValue(item, 0, "status", "numberReady");
        int unavailable = firstPresentInt(item,
                List.of("status", "numberUnavailable"),
                List.of("status", "numberMisscheduled")).orElse(0);
        if (hasErrorCondition(item) || unavailable > 0 && ready == 0) {
            return error("Error");
        }
        if (desired > 0 && ready >= desired && unavailable == 0) {
            return success("Running");
        }
        if (desired > 0 && ready < desired) {
            return warning("Pending");
        }
        if (desired == 0 && ready == 0) {
            return success("Running");
        }
        return warning("Pending");
    }

    private static K8sResourceStatus deploymentStatus(JsonNode item) {
        int desired = firstPresentInt(item,
                List.of("spec", "replicas"),
                List.of("status", "replicas")).orElse(0);
        int ready = firstPresentInt(item,
                List.of("status", "readyReplicas"),
                List.of("status", "availableReplicas")).orElse(0);
        int unavailable = intValue(item, 0, "status", "unavailableReplicas");
        if (hasCondition(item, "ReplicaFailure") || hasErrorCondition(item)) {
            return error("Error");
        }
        if (desired == 0) {
            return ready == 0 ? success("Scaled to 0") : warning("Pending");
        }
        if (ready >= desired && unavailable == 0) {
            return success("Running");
        }
        return warning("Pending");
    }

    private static K8sResourceStatus jobStatus(JsonNode item) {
        if (hasCondition(item, "Failed") || intValue(item, 0, "status", "failed") > 0) {
            return error("Failed");
        }
        if (hasCondition(item, "Complete") || intValue(item, 0, "status", "succeeded") > 0) {
            return success("Complete");
        }
        if (boolValue(item, "spec", "suspend")) {
            return muted("Suspended");
        }
        if (intValue(item, 1, "spec", "completions") == 0
                && intValue(item, 0, "status", "active") == 0
                && intValue(item, 0, "status", "succeeded") == 0
                && intValue(item, 0, "status", "failed") == 0) {
            return success("Complete");
        }
        if (intValue(item, 0, "status", "active") > 0) {
            return success("Running");
        }
        return warning("Pending");
    }

    private static K8sResourceStatus podStatus(JsonNode item) {
        String status = podPhaseForList(item);
        return switch (status) {
            case "Running", "Succeeded", "Completed" -> success(status);
            case "Pending", "ContainerCreating" -> warning(status);
            case "Terminating" -> muted(status);
            case "Failed", "Error", "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull",
                 "CreateContainerConfigError", "CreateContainerError", "InvalidImageName",
                 "RunContainerError", "OOMKilled" -> error(status);
            default -> status.startsWith("Init:") && !"Init:0/0".equals(status)
                    ? warning(status)
                    : muted(status.isBlank() ? "Unknown" : status);
        };
    }

    private static K8sResourceStatus replicaStatus(JsonNode item) {
        int desired = firstPresentInt(item,
                List.of("spec", "replicas"),
                List.of("status", "replicas")).orElse(0);
        int ready = firstPresentInt(item,
                List.of("status", "readyReplicas"),
                List.of("status", "availableReplicas"),
                List.of("status", "replicas")).orElse(0);
        if (hasErrorCondition(item)) {
            return error("Error");
        }
        if (desired == 0) {
            return ready == 0 ? success("Scaled to 0") : warning("Pending");
        }
        if (ready >= desired) {
            return success("Running");
        }
        return warning("Pending");
    }

    private static K8sResourceStatus statefulSetStatus(JsonNode item) {
        int desired = firstPresentInt(item,
                List.of("spec", "replicas"),
                List.of("status", "replicas")).orElse(0);
        int ready = firstPresentInt(item,
                List.of("status", "readyReplicas"),
                List.of("status", "replicas")).orElse(0);
        if (hasErrorCondition(item)) {
            return error("Error");
        }
        if (desired == 0) {
            return ready == 0 ? success("Scaled to 0") : warning("Pending");
        }
        if (ready >= desired) {
            return success("Running");
        }
        return warning("Pending");
    }

    private static K8sResourceStatus serviceStatus(JsonNode item) {
        String type = text(item, "spec", "type");
        if ("ExternalName".equals(type)) {
            return success("Succeeded");
        }
        if ("LoadBalancer".equals(type) && externalEndpointCount(item) == 0) {
            return warning("Pending");
        }
        return text(item, "spec", "clusterIP").isBlank() ? warning("Pending") : success("Succeeded");
    }

    private static K8sResourceStatus pvcStatus(JsonNode item) {
        return switch (text(item, "status", "phase")) {
            case "Bound" -> success("Bound");
            case "Pending" -> warning("Pending");
            case "Lost" -> error("Lost");
            default -> muted("Unrecognized");
        };
    }

    private static K8sResourceStatus namespaceStatus(JsonNode item) {
        return switch (text(item, "status", "phase")) {
            case "Active" -> success("Active");
            case "Terminating" -> error("Terminating");
            default -> muted("Unrecognized");
        };
    }

    private static K8sResourceStatus nodeStatus(JsonNode item) {
        String ready = readyCondition(item);
        if ("True".equals(ready)) {
            return success("Ready");
        }
        if ("False".equals(ready)) {
            return error("NotReady");
        }
        return muted("Unrecognized");
    }

    private static K8sResourceStatus pvStatus(JsonNode item) {
        return switch (text(item, "status", "phase")) {
            case "Available", "Bound" -> success(text(item, "status", "phase"));
            case "Pending" -> warning("Pending");
            case "Released" -> muted("Released");
            case "Failed" -> error("Failed");
            default -> muted("Unrecognized");
        };
    }

    private static String podPhaseForList(JsonNode pod) {
        if (!text(pod, "metadata", "deletionTimestamp").isBlank()) {
            return "Terminating";
        }
        String reason = text(pod, "status", "reason");
        if (!reason.isBlank()) {
            return reason;
        }

        JsonNode initStatuses = pod.path("status").path("initContainerStatuses");
        if (initStatuses.isArray()) {
            for (int i = 0; i < initStatuses.size(); i++) {
                JsonNode status = initStatuses.get(i);
                JsonNode state = status.path("state");
                if (state.has("terminated") && intValue(state.path("terminated"), 0, "exitCode") == 0) {
                    continue;
                }
                if (state.has("waiting") && state.path("waiting").hasNonNull("reason")) {
                    return "Init:" + state.path("waiting").path("reason").asText();
                }
                if (state.has("terminated")) {
                    String terminatedReason = text(state.path("terminated"), "reason");
                    return terminatedReason.isBlank() ? "Init:Error" : "Init:" + terminatedReason;
                }
                return "Init:" + i + "/" + initStatuses.size();
            }
        }

        boolean hasRunning = false;
        JsonNode containerStatuses = pod.path("status").path("containerStatuses");
        if (containerStatuses.isArray()) {
            for (JsonNode status : containerStatuses) {
                JsonNode state = status.path("state");
                if (state.has("waiting") && state.path("waiting").hasNonNull("reason")) {
                    return state.path("waiting").path("reason").asText();
                }
                if (state.has("terminated") && state.path("terminated").hasNonNull("reason")) {
                    String terminated = state.path("terminated").path("reason").asText();
                    if ("Completed".equals(terminated) && status.path("ready").asBoolean(false)) {
                        hasRunning = true;
                    } else {
                        return terminated;
                    }
                }
                if (state.has("running") && status.path("ready").asBoolean(false)) {
                    hasRunning = true;
                }
            }
        }
        String phase = text(pod, "status", "phase");
        if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
            return phase;
        }
        return hasRunning ? "Running" : phase;
    }

    private static boolean hasErrorCondition(JsonNode item) {
        JsonNode conditions = item.path("status").path("conditions");
        if (!conditions.isArray()) {
            return false;
        }
        for (JsonNode condition : conditions) {
            String type = text(condition, "type");
            String status = text(condition, "status");
            String reason = text(condition, "reason");
            if ("True".equals(status) && ("ReplicaFailure".equals(type) || isErrorReason(reason))) {
                return true;
            }
            if ("False".equals(status) && ("Available".equals(type) || "Progressing".equals(type))
                    && isErrorReason(reason)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCondition(JsonNode item, String type) {
        JsonNode conditions = item.path("status").path("conditions");
        if (!conditions.isArray()) {
            return false;
        }
        for (JsonNode condition : conditions) {
            if (type.equals(text(condition, "type")) && "True".equals(text(condition, "status"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isErrorReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String value = reason.toLowerCase(Locale.ROOT);
        return value.contains("fail")
                || value.contains("error")
                || value.contains("backoff")
                || value.contains("exceeded")
                || value.contains("forbidden")
                || value.contains("invalid");
    }

    private static String readyCondition(JsonNode item) {
        JsonNode conditions = item.path("status").path("conditions");
        if (!conditions.isArray()) {
            return "";
        }
        for (JsonNode condition : conditions) {
            if ("Ready".equals(text(condition, "type"))) {
                return text(condition, "status");
            }
        }
        return "";
    }

    private static int externalEndpointCount(JsonNode item) {
        return item.path("status").path("loadBalancer").path("ingress").size()
                + item.path("spec").path("externalIPs").size();
    }

    private static K8sResourceStatus success(String text) {
        return new K8sResourceStatus(Level.SUCCESS, text, false);
    }

    private static K8sResourceStatus warning(String text) {
        return new K8sResourceStatus(Level.WARNING, text, false);
    }

    private static K8sResourceStatus error(String text) {
        return new K8sResourceStatus(Level.ERROR, text, false);
    }

    private static K8sResourceStatus muted(String text) {
        return new K8sResourceStatus(Level.MUTED, text, false);
    }

    private static String normalize(String kubectlType) {
        if (kubectlType == null) {
            return "";
        }
        return kubectlType.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private static String text(JsonNode source, String... path) {
        if (source == null) {
            return "";
        }
        JsonNode current = source;
        for (String name : path) {
            current = current.path(name);
        }
        return current.isMissingNode() || current.isNull() ? "" : current.asText("");
    }

    private static boolean boolValue(JsonNode source, String... path) {
        if (source == null) {
            return false;
        }
        JsonNode current = source;
        for (String name : path) {
            current = current.path(name);
        }
        return current.asBoolean(false);
    }

    @SafeVarargs
    private static OptionalInt firstPresentInt(JsonNode source, List<String>... paths) {
        if (source == null) {
            return OptionalInt.empty();
        }
        for (List<String> path : paths) {
            JsonNode current = source;
            for (String name : path) {
                current = current.path(name);
            }
            if (!current.isMissingNode() && !current.isNull()) {
                return OptionalInt.of(current.asInt(0));
            }
        }
        return OptionalInt.empty();
    }

    private static int intValue(JsonNode source, int defaultValue, String... path) {
        if (source == null) {
            return defaultValue;
        }
        JsonNode current = source;
        for (String name : path) {
            current = current.path(name);
        }
        return current.isMissingNode() || current.isNull() ? defaultValue : current.asInt(defaultValue);
    }
}

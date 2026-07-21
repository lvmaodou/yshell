package com.yshell.model.k8s;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public final class K8sDetailDtos {
    private K8sDetailDtos() {
    }

    public sealed interface ResourceDetailDto permits
            PodDetailDto,
            JobDetailDto,
            CronJobDetailDto,
            DaemonSetDetailDto,
            DeploymentDetailDto,
            ReplicaSetDetailDto,
            ReplicationControllerDetailDto,
            StatefulSetDetailDto,
            ServiceDetailDto,
            NamespaceDetailDto,
            NodeDetailDto,
            SecretDetailDto,
            ConfigMapDetailDto,
            PersistentVolumeClaimDetailDto,
            PersistentVolumeDetailDto,
            StorageClassDetailDto,
            IngressDetailDto,
            IngressClassDetailDto,
            NetworkPolicyDetailDto,
            HorizontalPodAutoscalerDetailDto,
            RoleDetailDto,
            RoleBindingDetailDto,
            ServiceAccountDetailDto,
            GenericObjectDetailDto {
    }

    public record ObjectMetaDto(
            String name,
            String namespace,
            Map<String, String> labels,
            Map<String, String> annotations,
            String creationTimestamp,
            String uid,
            List<Map<String, Object>> ownerReferences
    ) {
    }

    public record TypeMetaDto(String kind, boolean scalable, boolean restartable) {
    }

    public record KeyValueDto(String key, String value) {
    }

    public record ConditionDto(
            String type,
            String status,
            String lastProbeTime,
            String lastTransitionTime,
            String reason,
            String message
    ) {
    }

    public record ResourceRefDto(ObjectMetaDto objectMeta, TypeMetaDto typeMeta) {
    }

    public record ContainerDto(
            String name,
            String image,
            List<Map<String, Object>> env,
            List<String> commands,
            List<String> args,
            List<Map<String, Object>> volumeMounts,
            Map<String, Object> securityContext,
            Map<String, Object> status,
            Map<String, Object> livenessProbe,
            Map<String, Object> readinessProbe,
            Map<String, Object> startupProbe,
            Map<String, Object> resources
    ) {
    }

    public record EventDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            String message,
            String sourceComponent,
            String sourceHost,
            String object,
            String objectKind,
            String objectName,
            String objectNamespace,
            String reason,
            String type,
            String firstSeen,
            String lastSeen,
            int count
    ) {
    }

    public record ListMetaDto(int totalItems) {
    }

    public record EventListDto(ListMetaDto listMeta, List<EventDto> events, List<String> errors) {
    }

    public record PodInfoDto(
            int current,
            Integer desired,
            int running,
            int pending,
            int failed,
            int succeeded,
            List<EventDto> warnings
    ) {
    }

    public record PodSummaryDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            String status,
            String podIP,
            int restartCount,
            String qosClass,
            Map<String, Object> metrics,
            List<EventDto> warnings,
            String nodeName,
            String serviceAccountName,
            List<String> containerImages
    ) {
    }

    public record PodListDto(
            ListMetaDto listMeta,
            List<PodSummaryDto> pods,
            Map<String, Object> status,
            List<Map<String, Object>> cumulativeMetrics,
            List<String> errors
    ) {
    }

    public record ReplicaSetSummaryDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            PodInfoDto podInfo,
            List<String> containerImages,
            List<String> initContainerImages
    ) {
    }

    public record ReplicaSetListDto(ListMetaDto listMeta, List<ReplicaSetSummaryDto> replicaSets, List<String> errors) {
    }

    public record EndpointDto(
            String host,
            String nodeName,
            Boolean ready,
            List<Map<String, Object>> ports,
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta
    ) {
    }

    public record EndpointListDto(ListMetaDto listMeta, List<EndpointDto> endpoints, List<String> errors) {
    }

    public record ServiceSummaryDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            EndpointDto internalEndpoint,
            List<EndpointDto> externalEndpoints,
            String type,
            String clusterIP
    ) {
    }

    public record ServiceListDto(ListMetaDto listMeta, List<ServiceSummaryDto> services, List<String> errors) {
    }

    public record SecretListDto(ListMetaDto listMeta, List<SecretDetailDto> secrets, List<String> errors) {
    }

    public record IngressListDto(ListMetaDto listMeta, List<IngressDetailDto> ingresses, List<String> errors) {
    }

    public record PersistentVolumeListDto(
            ListMetaDto listMeta,
            List<PersistentVolumeDetailDto> items,
            List<String> errors
    ) {
    }

    public record HorizontalPodAutoscalerListDto(
            ListMetaDto listMeta,
            List<HorizontalPodAutoscalerDetailDto> horizontalpodautoscalers,
            List<String> errors
    ) {
    }

    public record PersistentVolumeClaimRefDto(ObjectMetaDto objectMeta, TypeMetaDto typeMeta) {
    }

    public record PersistentVolumeClaimListDto(
            ListMetaDto listMeta,
            List<PersistentVolumeClaimRefDto> items,
            List<String> errors
    ) {
    }

    public record StatusInfoDto(int replicas, int updated, int available, int unavailable) {
    }

    public record JobStatusDto(String status, String message, List<ConditionDto> conditions) {
    }

    public record ResourceQuotaDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            List<String> scopes,
            Map<String, Map<String, String>> statusList
    ) {
    }

    public record ResourceQuotaListDto(ListMetaDto listMeta, List<ResourceQuotaDto> items, List<String> errors) {
    }

    public record ResourceLimitDto(
            String resourceType,
            String resourceName,
            String min,
            String max,
            @JsonProperty("default")
            String defaultValue,
            String defaultRequest,
            String maxLimitRequestRatio
    ) {
    }

    public record AllocatedResourcesDto(
            long cpuRequests,
            long cpuLimits,
            long cpuCapacity,
            int cpuRequestsFraction,
            int cpuLimitsFraction,
            long memoryRequests,
            long memoryLimits,
            long memoryCapacity,
            int memoryRequestsFraction,
            int memoryLimitsFraction,
            int allocatedPods,
            int podCapacity,
            int podFraction
    ) {
    }

    public record PodDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            String podPhase,
            String podIP,
            String nodeName,
            String serviceAccountName,
            int restartCount,
            String qosClass,
            List<Map<String, Object>> metrics,
            List<ConditionDto> conditions,
            ResourceRefDto controller,
            List<ContainerDto> containers,
            List<ContainerDto> initContainers,
            List<Map<String, Object>> imagePullSecrets,
            EventListDto eventList,
            PersistentVolumeClaimListDto persistentVolumeClaimList,
            Map<String, Object> securityContext
    ) implements ResourceDetailDto {
    }

    public record JobDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            PodInfoDto podInfo,
            PodListDto podList,
            List<String> containerImages,
            List<String> initContainerImages,
            EventListDto eventList,
            Integer parallelism,
            Integer completions,
            JobStatusDto jobStatus
    ) implements ResourceDetailDto {
    }

    public record CronJobDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            String schedule,
            boolean suspend,
            int active,
            String lastSchedule,
            String concurrencyPolicy,
            Integer startingDeadlineSeconds,
            List<ResourceRefDto> activeJobs,
            List<ResourceRefDto> inactiveJobs,
            EventListDto eventList
    ) implements ResourceDetailDto {
    }

    public record DaemonSetDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, String> labelSelector,
            PodInfoDto podInfo,
            PodListDto podList,
            ServiceListDto serviceList,
            List<String> containerImages,
            List<String> initContainerImages,
            EventListDto eventList
    ) implements ResourceDetailDto {
    }

    public record DeploymentDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            List<KeyValueDto> selector,
            StatusInfoDto statusInfo,
            List<ConditionDto> conditions,
            String strategy,
            int minReadySeconds,
            Integer revisionHistoryLimit,
            Map<String, Object> rollingUpdateStrategy,
            ReplicaSetSummaryDto newReplicaSet,
            ReplicaSetListDto oldReplicaSetList,
            HorizontalPodAutoscalerListDto horizontalPodAutoscalerList,
            EventListDto events
    ) implements ResourceDetailDto {
    }

    public record ReplicaSetDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, Object> selector,
            PodInfoDto podInfo,
            PodListDto podList,
            ServiceListDto serviceList,
            List<String> containerImages,
            List<String> initContainerImages,
            EventListDto eventList,
            HorizontalPodAutoscalerListDto horizontalPodAutoscalerList
    ) implements ResourceDetailDto {
    }

    public record ReplicationControllerDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, Object> labelSelector,
            PodInfoDto podInfo,
            PodListDto podList,
            ServiceListDto serviceList,
            List<String> containerImages,
            List<String> initContainerImages,
            EventListDto eventList,
            boolean hasMetrics
    ) implements ResourceDetailDto {
    }

    public record StatefulSetDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, String> labelSelector,
            PodInfoDto podInfo,
            PodListDto podList,
            List<String> containerImages,
            List<String> initContainerImages,
            EventListDto eventList
    ) implements ResourceDetailDto {
    }

    public record ServiceDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            EndpointDto internalEndpoint,
            List<EndpointDto> externalEndpoints,
            EndpointListDto endpointList,
            Map<String, String> selector,
            String type,
            String clusterIP,
            PodListDto podList,
            String sessionAffinity,
            IngressListDto ingressList,
            EventListDto eventList
    ) implements ResourceDetailDto {
    }

    public record NamespaceDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            String phase,
            EventListDto eventList,
            List<ResourceLimitDto> resourceLimits,
            ResourceQuotaListDto resourceQuotaList
    ) implements ResourceDetailDto {
    }

    public record NodeDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            String phase,
            String podCIDR,
            String providerID,
            boolean unschedulable,
            AllocatedResourcesDto allocatedResources,
            Map<String, Object> nodeInfo,
            List<String> containerImages,
            List<String> initContainerImages,
            List<Map<String, Object>> addresses,
            List<Map<String, Object>> taints,
            List<Map<String, Object>> metrics,
            List<ConditionDto> conditions,
            PodListDto podList,
            EventListDto eventList
    ) implements ResourceDetailDto {
    }

    public record SecretDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            String type,
            Map<String, Object> data
    ) implements ResourceDetailDto {
    }

    public record ConfigMapDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, Object> data
    ) implements ResourceDetailDto {
    }

    public record PersistentVolumeClaimDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            String status,
            String volume,
            String capacity,
            String storageClass,
            List<String> accessModes
    ) implements ResourceDetailDto {
    }

    public record PersistentVolumeDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            String status,
            String claim,
            String reclaimPolicy,
            List<String> accessModes,
            Map<String, Object> capacity,
            String message,
            String storageClass,
            String reason,
            Map<String, Object> persistentVolumeSource,
            List<String> mountOptions
    ) implements ResourceDetailDto {
    }

    public record StorageClassDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, Object> parameters,
            String provisioner,
            PersistentVolumeListDto persistentVolumeList
    ) implements ResourceDetailDto {
    }

    public record IngressDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            List<EndpointDto> endpoints,
            List<String> hosts,
            Map<String, Object> spec,
            EventListDto eventList
    ) implements ResourceDetailDto {
    }

    public record IngressClassDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, Object> parameters,
            String controller
    ) implements ResourceDetailDto {
    }

    public record NetworkPolicyDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, Object> podSelector,
            List<Map<String, Object>> ingress,
            List<Map<String, Object>> egress,
            List<String> policyTypes
    ) implements ResourceDetailDto {
    }

    public record HorizontalPodAutoscalerDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, Object> scaleTargetRef,
            int minReplicas,
            int maxReplicas,
            int currentCPUUtilization,
            Integer targetCPUUtilization,
            int currentReplicas,
            int desiredReplicas,
            String lastScaleTime
    ) implements ResourceDetailDto {
    }

    public record RoleDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            List<Map<String, Object>> rules
    ) implements ResourceDetailDto {
    }

    public record RoleBindingDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            List<Map<String, Object>> subjects,
            Map<String, Object> roleRef
    ) implements ResourceDetailDto {
    }

    public record ServiceAccountDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            SecretListDto secretList,
            SecretListDto imagePullSecretList
    ) implements ResourceDetailDto {
    }

    public record GenericObjectDetailDto(
            ObjectMetaDto objectMeta,
            TypeMetaDto typeMeta,
            List<String> errors,
            Map<String, Object> raw
    ) implements ResourceDetailDto {
    }
}

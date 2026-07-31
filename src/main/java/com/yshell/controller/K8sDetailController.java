package com.yshell.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.yshell.model.k8s.K8sDetailDtos;
import com.yshell.model.k8s.K8sResourceStatus;
import com.yshell.theme.ThemeManager;
import com.yshell.ui.DialogHelper;
import com.yshell.ui.WindowDragResize;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public class K8sDetailController {
    private static final ObjectMapper DISPLAY_MAPPER = new ObjectMapper();
    private static final Duration AUTO_REFRESH_INTERVAL = Duration.seconds(5);
    private static final ObjectMapper YAML_MAPPER = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build();
    private static final List<String> POD_TABLE_COLUMNS = List.of(
            "状态", "名称", "命名空间", "镜像", "标签", "节点", "重启次数", "CPU 使用量", "内存使用量", "创建时间");
    private static final List<String> SERVICE_TABLE_COLUMNS = List.of(
            "状态", "名称", "命名空间", "标签", "类型", "Cluster IP", "内部端点", "外部端点", "创建时间");
    private static final List<String> EVENT_TABLE_COLUMNS = List.of(
            "名称", "命名空间", "Reason", "Message", "Source", "Object", "Count", "First Seen", "Last Seen");
    private static final List<String> SECRET_TABLE_COLUMNS = List.of(
            "名称", "命名空间", "标签", "类型", "创建时间");
    private static final List<String> PERSISTENT_VOLUME_TABLE_COLUMNS = List.of(
            "状态", "名称", "容量", "访问模式", "Reclaim Policy", "绑定状态", "Claim", "Storage Class", "Reason", "创建时间");
    private static final List<String> PERSISTENT_VOLUME_CLAIM_TABLE_COLUMNS = List.of(
            "状态", "名称", "命名空间", "标签", "绑定状态", "Volume", "容量", "访问模式", "Storage Class", "创建时间");
    private static final List<String> REPLICA_SET_TABLE_COLUMNS = List.of(
            "状态", "名称", "命名空间", "镜像", "标签", "Pods", "创建时间");
    private static final List<String> JOB_TABLE_COLUMNS = List.of(
            "状态", "名称", "命名空间", "镜像", "标签", "Pods", "创建时间");
    private static final List<String> INGRESS_TABLE_COLUMNS = List.of(
            "名称", "命名空间", "标签", "Endpoints", "Hosts", "创建时间");
    private static final List<String> HPA_TABLE_COLUMNS = List.of(
            "名称", "命名空间", "Min Replicas", "Max Replicas", "Reference", "创建时间");
    private static final List<String> POD_TABLE_ACTIONS = List.of("查看详情", "查看日志", "执行", "编辑资源", "删除资源");
    private static final List<String> SERVICE_TABLE_ACTIONS = List.of("查看详情", "编辑资源", "删除资源");
    private static final List<String> EVENT_TABLE_ACTIONS = List.of("查看详情");
    private static final List<String> SECRET_TABLE_ACTIONS = List.of("查看详情", "编辑资源", "删除资源");
    private static final List<String> PERSISTENT_VOLUME_TABLE_ACTIONS = List.of("查看详情", "编辑资源", "删除资源");
    private static final List<String> PERSISTENT_VOLUME_CLAIM_TABLE_ACTIONS = List.of("查看详情", "编辑资源", "删除资源");
    private static final List<String> REPLICA_SET_TABLE_ACTIONS = List.of("查看详情", "查看日志", "编辑资源", "删除资源", "扩缩容");
    private static final List<String> JOB_TABLE_ACTIONS = List.of("查看详情", "查看日志", "编辑资源", "删除资源");
    private static final List<String> INGRESS_TABLE_ACTIONS = List.of("查看详情", "编辑资源", "删除资源");

    @FXML
    private BorderPane root;
    @FXML
    private HBox actionBar;
    @FXML
    private Button btnClose;
    @FXML
    private Label kindLabel;
    @FXML
    private Label titleLabel;
    @FXML
    private Label subtitleLabel;
    @FXML
    private VBox overviewHost;

    private Stage stage;
    private DetailActionHandler actionHandler;
    private K8sDetailDtos.ResourceDetailDto currentData;
    private DetailRefreshHandler detailRefreshHandler;
    private VBox dynamicListsHost;
    private Timeline autoRefreshTimeline;
    private boolean autoRefreshInFlight;

    @FXML
    public void initialize() {
        WindowDragResize.apply(root, 52, actionBar, btnClose);
        btnClose.setOnAction(e -> close());
    }

    public void setStage(Stage stage) {
        this.stage = stage;
        stage.showingProperty().addListener((observable, wasShowing, showing) -> {
            if (showing) {
                startAutoRefresh();
            } else {
                stopAutoRefresh();
            }
        });
    }

    public void setData(K8sDetailDtos.ResourceDetailDto data,
                        List<DetailActionSpec> actions,
                        DetailActionHandler actionHandler) {
        currentData = data;
        List<DetailActionSpec> currentActions = actions == null ? List.of() : List.copyOf(actions);
        this.actionHandler = actionHandler;
        kindLabel.setText(prettyKind(data));
        titleLabel.setText(displayTitle(data));
        subtitleLabel.setText(displaySubtitle(data));
        renderActions(currentActions);
        renderOverview(data);
    }

    public void setAutoRefreshHandler(DetailRefreshHandler detailRefreshHandler) {
        this.detailRefreshHandler = detailRefreshHandler;
        startAutoRefresh();
    }

    private void startAutoRefresh() {
        if (stage == null || !stage.isShowing() || detailRefreshHandler == null || currentData == null) {
            return;
        }
        if (autoRefreshTimeline == null) {
            autoRefreshTimeline = new Timeline(new KeyFrame(AUTO_REFRESH_INTERVAL, event -> refreshDetail()));
            autoRefreshTimeline.setCycleCount(Animation.INDEFINITE);
        }
        if (autoRefreshTimeline.getStatus() != Animation.Status.RUNNING) {
            autoRefreshTimeline.play();
        }
    }

    private void stopAutoRefresh() {
        if (autoRefreshTimeline != null) {
            autoRefreshTimeline.stop();
        }
        autoRefreshInFlight = false;
    }

    private void refreshDetail() {
        if (autoRefreshInFlight || stage == null || !stage.isShowing() || detailRefreshHandler == null) {
            return;
        }
        autoRefreshInFlight = true;
        try {
            detailRefreshHandler.refresh(this::applyAutoRefreshData, () -> autoRefreshInFlight = false);
        } catch (Exception e) {
            autoRefreshInFlight = false;
        }
    }

    private void applyAutoRefreshData(K8sDetailDtos.ResourceDetailDto data) {
        if (stage == null || !stage.isShowing() || data == null) {
            return;
        }
        currentData = data;
        renderDynamicLists(data);
    }

    private void renderActions(List<DetailActionSpec> actions) {
        actionBar.getChildren().clear();
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (DetailActionSpec spec : actions) {
            Button button = new Button(spec.label());
            button.getStyleClass().add("detail-action-btn");
            button.getStyleClass().add(spec.primary() ? "button-primary" : "button-cancel");
            button.setOnAction(e -> {
                if (actionHandler != null) {
                    actionHandler.handle(spec);
                }
            });
            actionBar.getChildren().add(button);
        }
    }

    private void renderOverview(K8sDetailDtos.ResourceDetailDto data) {
        overviewHost.getChildren().clear();
        dynamicListsHost = new VBox(10);
        dynamicListsHost.getStyleClass().add("detail-dynamic-lists");
        if (data == null) {
            overviewHost.getChildren().add(createTextCard("Detail", "No data"));
            return;
        }

        if (data instanceof K8sDetailDtos.PodDetailDto pod) {
            renderPodDetail(pod);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.JobDetailDto job) {
            renderJobDetail(job);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.CronJobDetailDto cronJob) {
            renderCronJobDetail(cronJob);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.DaemonSetDetailDto daemonSet) {
            renderDaemonSetDetail(daemonSet);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.DeploymentDetailDto deployment) {
            renderDeploymentDetail(deployment);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.ReplicaSetDetailDto replicaSet) {
            renderReplicaSetDetail(replicaSet);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.ReplicationControllerDetailDto replicationController) {
            renderReplicationControllerDetail(replicationController);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.StatefulSetDetailDto statefulSet) {
            renderStatefulSetDetail(statefulSet);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.ServiceDetailDto service) {
            renderServiceDetail(service);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.NamespaceDetailDto namespace) {
            renderNamespaceDetail(namespace);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.NodeDetailDto node) {
            renderNodeDetail(node);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.SecretDetailDto secret) {
            renderSecretDetail(secret);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.ConfigMapDetailDto configMap) {
            renderConfigMapDetail(configMap);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.PersistentVolumeClaimDetailDto persistentVolumeClaim) {
            renderPersistentVolumeClaimDetail(persistentVolumeClaim);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.PersistentVolumeDetailDto persistentVolume) {
            renderPersistentVolumeDetail(persistentVolume);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.StorageClassDetailDto storageClass) {
            renderStorageClassDetail(storageClass);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.IngressDetailDto ingress) {
            renderIngressDetail(ingress);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.IngressClassDetailDto ingressClass) {
            renderIngressClassDetail(ingressClass);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.NetworkPolicyDetailDto networkPolicy) {
            renderNetworkPolicyDetail(networkPolicy);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.HorizontalPodAutoscalerDetailDto hpa) {
            renderHorizontalPodAutoscalerDetail(hpa);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.RoleDetailDto role) {
            renderRoleDetail(role);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.RoleBindingDetailDto roleBinding) {
            renderRoleBindingDetail(roleBinding);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }
        if (data instanceof K8sDetailDtos.ServiceAccountDetailDto serviceAccount) {
            renderServiceAccountDetail(serviceAccount);
            addErrorsIfPresent(data);
            addEmptyFallback();
            return;
        }

        addValueSection("Object Meta", objectMeta(data));
        addValueSection("Type Meta", typeMeta(data));

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        for (RecordComponent component : data.getClass().getRecordComponents()) {
            String name = component.getName();
            if (isBaseField(name)) {
                continue;
            }
            Object value = readComponent(component, data);
            if (value == null || isEmptyValue(value)) {
                continue;
            }
            if (isSimpleValue(value)) {
                resourceInfo.put(prettyLabel(name), String.valueOf(value));
            } else if ("errors".equals(name)) {
                addValueSection("Errors", value);
            } else {
                addValueSection(prettyLabel(name), value);
            }
        }

        addKeyValueCardIfPresent(resourceInfo);

        addErrorsIfPresent(data);
        addEmptyFallback();
    }

    private void renderPodDetail(K8sDetailDtos.PodDetailDto pod) {
        addValueSection("Object Meta", pod.objectMeta());
        addMetricList(pod.metrics());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Node", pod.nodeName());
        putDisplayValue(resourceInfo, "Status", pod.podPhase());
        putDisplayValue(resourceInfo, "IP", pod.podIP());
        putDisplayValue(resourceInfo, "QoS Class", pod.qosClass());
        resourceInfo.put("Restarts", String.valueOf(pod.restartCount()));
        putDisplayValue(resourceInfo, "Service Account", pod.serviceAccountName());
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        List<String> imagePullSecrets = namedObjectList(pod.imagePullSecrets());
        if (!imagePullSecrets.isEmpty()) {
            overviewHost.getChildren().add(createListCard("Image Pull Secrets", imagePullSecrets));
        }

        addValueSection("Security Context", pod.securityContext());
        addValueSection("Conditions", pod.conditions());
        addValueSection("Creator", pod.controller());
        addPersistentVolumeClaimList(pod.persistentVolumeClaimList());
        addEventList(pod.eventList());
        addContainerGroup("Containers", pod.containers());
        addContainerGroup("Init containers", pod.initContainers());
    }

    private void renderJobDetail(K8sDetailDtos.JobDetailDto job) {
        addValueSection("Object Meta", job.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Completions", job.completions());
        putDisplayValue(resourceInfo, "Parallelism", job.parallelism());
        putDisplayValue(resourceInfo, "Status", job.jobStatus() == null ? null : job.jobStatus().status());
        putDisplayValue(resourceInfo, "Message", job.jobStatus() == null ? null : job.jobStatus().message());
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        addValueSection("Images", job.containerImages());
        addValueSection("Init Images", job.initContainerImages());
        addValueSection("Conditions", job.jobStatus() == null ? null : job.jobStatus().conditions());
        addValueSection("Pod Status", job.podInfo());
        addPodList(job.podList());
        addEventList(job.eventList());
    }

    private void renderCronJobDetail(K8sDetailDtos.CronJobDetailDto cronJob) {
        addValueSection("Object Meta", cronJob.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Schedule", cronJob.schedule());
        resourceInfo.put("Active Jobs", String.valueOf(cronJob.active()));
        resourceInfo.put("Suspend", String.valueOf(cronJob.suspend()));
        putDisplayValue(resourceInfo, "Last Schedule", cronJob.lastSchedule());
        putDisplayValue(resourceInfo, "Concurrency Policy", cronJob.concurrencyPolicy());
        putDisplayValue(resourceInfo, "Starting Deadline Seconds", cronJob.startingDeadlineSeconds());
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        addJobRefList("Active Jobs", cronJob.activeJobs());
        addJobRefList("Inactive Jobs", cronJob.inactiveJobs());
        addEventList(cronJob.eventList());
    }

    private void renderDaemonSetDetail(K8sDetailDtos.DaemonSetDetailDto daemonSet) {
        addValueSection("Object Meta", daemonSet.objectMeta());

        addWorkloadResourceInfo("Selector", daemonSet.labelSelector(), daemonSet.containerImages(), daemonSet.initContainerImages());
        addValueSection("Pod Status", daemonSet.podInfo());
        addPodList(daemonSet.podList());
        addServiceList(daemonSet.serviceList());
        addEventList(daemonSet.eventList());
    }

    private void renderDeploymentDetail(K8sDetailDtos.DeploymentDetailDto deployment) {
        addValueSection("Object Meta", deployment.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Strategy", deployment.strategy());
        resourceInfo.put("Min Ready Seconds", String.valueOf(deployment.minReadySeconds()));
        putDisplayValue(resourceInfo, "Revision History Limit", deployment.revisionHistoryLimit());
        putDisplayValue(resourceInfo, "Selector", selectorDisplay(deployment.selector()));
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        addValueSection("Rolling Update Strategy", deployment.rollingUpdateStrategy());
        addValueSection("Pods Status", deployment.statusInfo());
        addValueSection("Conditions", deployment.conditions());
        addReplicaSet(deployment.newReplicaSet());
        addReplicaSetList(deployment.oldReplicaSetList());
        addHorizontalPodAutoscalerList(deployment.horizontalPodAutoscalerList());
        addEventList(deployment.events());
    }

    private void renderReplicaSetDetail(K8sDetailDtos.ReplicaSetDetailDto replicaSet) {
        addValueSection("Object Meta", replicaSet.objectMeta());

        addWorkloadResourceInfo("Selector", replicaSet.selector(), replicaSet.containerImages(), replicaSet.initContainerImages());
        addValueSection("Pod Status", replicaSet.podInfo());
        addPodList(replicaSet.podList());
        addServiceList(replicaSet.serviceList());
        addHorizontalPodAutoscalerList(replicaSet.horizontalPodAutoscalerList());
        addEventList(replicaSet.eventList());
    }

    private void renderReplicationControllerDetail(K8sDetailDtos.ReplicationControllerDetailDto replicationController) {
        addValueSection("Object Meta", replicationController.objectMeta());

        addWorkloadResourceInfo("Label Selector",
                replicationController.labelSelector(),
                replicationController.containerImages(),
                replicationController.initContainerImages());
        addValueSection("Pod Status", replicationController.podInfo());
        addPodList(replicationController.podList());
        addServiceList(replicationController.serviceList());
        addEventList(replicationController.eventList());
    }

    private void renderStatefulSetDetail(K8sDetailDtos.StatefulSetDetailDto statefulSet) {
        addValueSection("Object Meta", statefulSet.objectMeta());

        addWorkloadResourceInfo("Label Selector", statefulSet.labelSelector(), statefulSet.containerImages(), statefulSet.initContainerImages());
        addValueSection("Pod Status", statefulSet.podInfo());
        addPodList(statefulSet.podList());
        addEventList(statefulSet.eventList());
    }

    private void renderServiceDetail(K8sDetailDtos.ServiceDetailDto service) {
        addValueSection("Object Meta", service.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Type", service.type());
        putDisplayValue(resourceInfo, "Cluster IP", service.clusterIP());
        putDisplayValue(resourceInfo, "Session Affinity", service.sessionAffinity());
        putDisplayValue(resourceInfo, "Selector", selectorDisplay(service.selector()));
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        addEndpointList(service.endpointList());
        addPodList(service.podList());
        addIngressList(service.ingressList());
        addEventList(service.eventList());
    }

    private void renderNamespaceDetail(K8sDetailDtos.NamespaceDetailDto namespace) {
        addValueSection("Object Meta", namespace.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Status", namespace.phase());
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        addResourceQuotaList(namespace.resourceQuotaList());
        addResourceLimitList(namespace.resourceLimits());
        addEventList(namespace.eventList());
    }

    private void renderNodeDetail(K8sDetailDtos.NodeDetailDto node) {
        addMetricList(node.metrics());
        addValueSection("Object Meta", node.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Phase", node.phase());
        putDisplayValue(resourceInfo, "Pod CIDR", node.podCIDR());
        putDisplayValue(resourceInfo, "Provider ID", node.providerID());
        if (node.unschedulable()) {
            resourceInfo.put("Unschedulable", "true");
        }
        putDisplayValue(resourceInfo, "Addresses", joined(nodeAddresses(node.addresses())));
        putDisplayValue(resourceInfo, "Taints", joined(nodeTaints(node.taints())));
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        Map<String, String> systemInfo = new LinkedHashMap<>();
        putDisplayValue(systemInfo, "Machine ID", node.nodeInfo().get("machineID"));
        putDisplayValue(systemInfo, "System UUID", node.nodeInfo().get("systemUUID"));
        putDisplayValue(systemInfo, "Boot ID", node.nodeInfo().get("bootID"));
        putDisplayValue(systemInfo, "Kernel Version", node.nodeInfo().get("kernelVersion"));
        putDisplayValue(systemInfo, "OS Image", node.nodeInfo().get("osImage"));
        putDisplayValue(systemInfo, "Container Runtime Version", node.nodeInfo().get("containerRuntimeVersion"));
        putDisplayValue(systemInfo, "kubelet Version", node.nodeInfo().get("kubeletVersion"));
        putDisplayValue(systemInfo, "kube-proxy Version", node.nodeInfo().get("kubeProxyVersion"));
        putDisplayValue(systemInfo, "Operating System", node.nodeInfo().get("operatingSystem"));
        putDisplayValue(systemInfo, "Architecture", node.nodeInfo().get("architecture"));
        if (node.allocatedResources() != null) {
            systemInfo.put("CPU Capacity", String.valueOf(node.allocatedResources().cpuCapacity()));
            systemInfo.put("Memory Capacity", String.valueOf(node.allocatedResources().memoryCapacity()));
            systemInfo.put("Pods Capacity", String.valueOf(node.allocatedResources().podCapacity()));
        }
        if (!systemInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("System Information", systemInfo));
        }

        addValueSection("Allocation", node.allocatedResources());
        addValueSection("Conditions", node.conditions());
        addPodList(node.podList());
        addEventList(node.eventList());
    }

    private void renderSecretDetail(K8sDetailDtos.SecretDetailDto secret) {
        addValueSection("Object Meta", secret.objectMeta());
        addValueSection("Data", secretDataSummary(secret.data()));
    }

    private void renderConfigMapDetail(K8sDetailDtos.ConfigMapDetailDto configMap) {
        addValueSection("Object Meta", configMap.objectMeta());
        if (configMap.data() != null && !configMap.data().isEmpty()) {
            overviewHost.getChildren().add(createConfigMapDataCard(configMap.data()));
        }
    }

    private void renderPersistentVolumeClaimDetail(K8sDetailDtos.PersistentVolumeClaimDetailDto persistentVolumeClaim) {
        addValueSection("Object Meta", persistentVolumeClaim.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Status", persistentVolumeClaim.status());
        putDisplayValue(resourceInfo, "Storage Class", persistentVolumeClaim.storageClass());
        putDisplayValue(resourceInfo, "Volume Name", persistentVolumeClaim.volume());
        putDisplayValue(resourceInfo, "Capacity", persistentVolumeClaim.capacity());
        putDisplayValue(resourceInfo, "Access Modes", joined(persistentVolumeClaim.accessModes()));
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }
    }

    private void renderPersistentVolumeDetail(K8sDetailDtos.PersistentVolumeDetailDto persistentVolume) {
        addValueSection("Object Meta", persistentVolume.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Status", persistentVolume.status());
        putDisplayValue(resourceInfo, "Claim", persistentVolume.claim());
        putDisplayValue(resourceInfo, "Reclaim Policy", persistentVolume.reclaimPolicy());
        putDisplayValue(resourceInfo, "Storage Class", persistentVolume.storageClass());
        putDisplayValue(resourceInfo, "Reason", persistentVolume.reason());
        putDisplayValue(resourceInfo, "Message", persistentVolume.message());
        putDisplayValue(resourceInfo, "Mount Option(s)", joined(persistentVolume.mountOptions()));
        putDisplayValue(resourceInfo, "Access Modes", joined(persistentVolume.accessModes()));
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        addValueSection("Source", persistentVolume.persistentVolumeSource());
        addCapacityTable(persistentVolume.capacity());
    }

    private void renderStorageClassDetail(K8sDetailDtos.StorageClassDetailDto storageClass) {
        addValueSection("Object Meta", storageClass.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Provisioner", storageClass.provisioner());
        if (storageClass.parameters() != null) {
            for (Map.Entry<String, Object> entry : storageClass.parameters().entrySet()) {
                putDisplayValue(resourceInfo, entry.getKey(), entry.getValue());
            }
        }
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        addPersistentVolumeList(storageClass.persistentVolumeList());
    }

    private void renderIngressDetail(K8sDetailDtos.IngressDetailDto ingress) {
        addValueSection("Object Meta", ingress.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Ingress Class Name", mapPath(ingress.spec()));
        putDisplayValue(resourceInfo, "Endpoints", joined(endpointHosts(ingress.endpoints())));
        addBackendInfo(resourceInfo, mapValue(ingress.spec(), "defaultBackend"));
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        addIngressRules(ingress.spec());
        addEventList(ingress.eventList());
    }

    private void renderIngressClassDetail(K8sDetailDtos.IngressClassDetailDto ingressClass) {
        addValueSection("Object Meta", ingressClass.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Controller", ingressClass.controller());
        if (ingressClass.parameters() != null) {
            for (Map.Entry<String, Object> entry : ingressClass.parameters().entrySet()) {
                putDisplayValue(resourceInfo, entry.getKey(), entry.getValue());
            }
        }
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }
    }

    private void renderNetworkPolicyDetail(K8sDetailDtos.NetworkPolicyDetailDto networkPolicy) {
        addValueSection("Object Meta", networkPolicy.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Pod Selector", selectorDisplay(networkPolicy.podSelector()));
        putDisplayValue(resourceInfo, "Policy Types", joined(networkPolicy.policyTypes()));
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }

        if (networkPolicy.ingress() != null && !networkPolicy.ingress().isEmpty()) {
            overviewHost.getChildren().add(createYamlCard("Ingress Rules", networkPolicy.ingress()));
        }
        if (networkPolicy.egress() != null && !networkPolicy.egress().isEmpty()) {
            overviewHost.getChildren().add(createYamlCard("Egress Rules", networkPolicy.egress()));
        }
    }

    private void renderHorizontalPodAutoscalerDetail(K8sDetailDtos.HorizontalPodAutoscalerDetailDto hpa) {
        addValueSection("Object Meta", hpa.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Reference", scaleTargetRefDisplay(hpa.scaleTargetRef()));
        resourceInfo.put("Min Replicas", String.valueOf(hpa.minReplicas()));
        resourceInfo.put("Max Replicas", String.valueOf(hpa.maxReplicas()));
        resourceInfo.put("Current Replicas", String.valueOf(hpa.currentReplicas()));
        resourceInfo.put("Desired Replicas", String.valueOf(hpa.desiredReplicas()));
        resourceInfo.put("Current CPU Utilization", hpa.currentCPUUtilization() + "%");
        putDisplayValue(resourceInfo, "Target CPU Utilization",
                hpa.targetCPUUtilization() == null ? null : hpa.targetCPUUtilization() + "%");
        putDisplayValue(resourceInfo, "Last Scale Time", hpa.lastScaleTime());
        overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
    }

    private void renderRoleDetail(K8sDetailDtos.RoleDetailDto role) {
        addValueSection("Object Meta", role.objectMeta());
        addPolicyRules(role.rules());
    }

    private void renderRoleBindingDetail(K8sDetailDtos.RoleBindingDetailDto roleBinding) {
        addValueSection("Object Meta", roleBinding.objectMeta());

        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, "Role Reference", roleRefDisplay(roleBinding.roleRef()));
        overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));

        addSubjects(roleBinding.subjects());
    }

    private void renderServiceAccountDetail(K8sDetailDtos.ServiceAccountDetailDto serviceAccount) {
        addValueSection("Object Meta", serviceAccount.objectMeta());
        addSecretList("Secrets", serviceAccount.secretList());
        addSecretList("Image Pull Secrets", serviceAccount.imagePullSecretList());
    }

    private void addWorkloadResourceInfo(String selectorLabel,
                                         Object selector,
                                         List<String> containerImages,
                                         List<String> initContainerImages) {
        Map<String, String> resourceInfo = new LinkedHashMap<>();
        putDisplayValue(resourceInfo, selectorLabel, selectorDisplay(selector));
        putDisplayValue(resourceInfo, "Images", joined(containerImages));
        putDisplayValue(resourceInfo, "Init Images", joined(initContainerImages));
        if (!resourceInfo.isEmpty()) {
            overviewHost.getChildren().add(createKeyValueCard("Resource Information", resourceInfo));
        }
    }

    private void addPodList(K8sDetailDtos.PodListDto podList) {
        if (podList == null || podList.pods() == null || podList.pods().isEmpty()) {
            return;
        }
        List<ResourceTableRow> rows = new ArrayList<>();
        for (K8sDetailDtos.PodSummaryDto pod : podList.pods()) {
            K8sDetailDtos.ObjectMetaDto meta = pod.objectMeta();
            Map<String, String> values = new LinkedHashMap<>();
            String statusText = emptyAsDash(pod.status());
            values.put("状态", statusText);
            values.put("名称", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            values.put("镜像", emptyAsDash(joined(pod.containerImages())));
            values.put("标签", labelsDisplay(meta == null ? null : meta.labels()));
            values.put("节点", emptyAsDash(pod.nodeName()));
            values.put("重启次数", String.valueOf(pod.restartCount()));
            values.put("CPU 使用量", metricDisplay(pod.metrics(), "CPU", "cpu"));
            values.put("内存使用量", metricDisplay(pod.metrics(), "Memory", "memory"));
            values.put("创建时间", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            rows.add(resourceRow("pod", values, POD_TABLE_ACTIONS, podStatus(statusText)));
        }
        addDynamicListCard(createResourceTableCard("Pods", POD_TABLE_COLUMNS, rows));
    }

    private void addServiceList(K8sDetailDtos.ServiceListDto serviceList) {
        if (serviceList == null || serviceList.services() == null || serviceList.services().isEmpty()) {
            return;
        }
        List<ResourceTableRow> rows = new ArrayList<>();
        for (K8sDetailDtos.ServiceSummaryDto service : serviceList.services()) {
            K8sDetailDtos.ObjectMetaDto meta = service.objectMeta();
            Map<String, String> values = new LinkedHashMap<>();
            K8sResourceStatus serviceStatus = serviceStatus(service);
            values.put("状态", serviceStatus.text());
            values.put("名称", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            values.put("标签", labelsDisplay(meta == null ? null : meta.labels()));
            values.put("类型", emptyAsDash(service.type()));
            values.put("Cluster IP", emptyAsDash(service.clusterIP()));
            values.put("内部端点", endpointDisplay(service.internalEndpoint()));
            values.put("外部端点", endpointDisplay(service.externalEndpoints()));
            values.put("创建时间", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            rows.add(resourceRow("service", values, SERVICE_TABLE_ACTIONS, serviceStatus));
        }
        addDynamicListCard(createResourceTableCard("Services", SERVICE_TABLE_COLUMNS, rows));
    }

    private void addSecretList(String title, K8sDetailDtos.SecretListDto secretList) {
        if (secretList == null || secretList.secrets() == null || secretList.secrets().isEmpty()) {
            return;
        }
        List<ResourceTableRow> rows = new ArrayList<>();
        for (K8sDetailDtos.SecretDetailDto secret : secretList.secrets()) {
            K8sDetailDtos.ObjectMetaDto meta = secret.objectMeta();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("名称", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            values.put("标签", labelsDisplay(meta == null ? null : meta.labels()));
            values.put("类型", emptyAsDash(secret.type()));
            values.put("创建时间", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            rows.add(resourceRow("secret", values, SECRET_TABLE_ACTIONS, null));
        }
        overviewHost.getChildren().add(createResourceTableCard(title, SECRET_TABLE_COLUMNS, rows));
    }

    private void addPersistentVolumeList(K8sDetailDtos.PersistentVolumeListDto persistentVolumeList) {
        if (persistentVolumeList == null
                || persistentVolumeList.items() == null
                || persistentVolumeList.items().isEmpty()) {
            return;
        }
        List<ResourceTableRow> rows = new ArrayList<>();
        for (K8sDetailDtos.PersistentVolumeDetailDto persistentVolume : persistentVolumeList.items()) {
            K8sDetailDtos.ObjectMetaDto meta = persistentVolume.objectMeta();
            String statusText = emptyAsDash(persistentVolume.status());
            Map<String, String> values = new LinkedHashMap<>();
            values.put("状态", statusText);
            values.put("名称", metaName(meta));
            values.put("容量", capacityDisplay(persistentVolume.capacity()));
            values.put("访问模式", emptyAsDash(joined(persistentVolume.accessModes())));
            values.put("Reclaim Policy", emptyAsDash(persistentVolume.reclaimPolicy()));
            values.put("绑定状态", statusText);
            values.put("Claim", emptyAsDash(persistentVolume.claim()));
            values.put("Storage Class", emptyAsDash(persistentVolume.storageClass()));
            values.put("Reason", emptyAsDash(persistentVolume.reason()));
            values.put("创建时间", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            rows.add(resourceRow("persistentvolume",
                    false,
                    values,
                    PERSISTENT_VOLUME_TABLE_ACTIONS,
                    persistentVolumeStatus(statusText)));
        }
        addDynamicListCard(createResourceTableCard(
                "Persistent Volumes",
                PERSISTENT_VOLUME_TABLE_COLUMNS,
                rows));
    }

    private void addPersistentVolumeClaimList(K8sDetailDtos.PersistentVolumeClaimListDto persistentVolumeClaimList) {
        if (persistentVolumeClaimList == null
                || persistentVolumeClaimList.items() == null
                || persistentVolumeClaimList.items().isEmpty()) {
            return;
        }
        List<ResourceTableRow> rows = new ArrayList<>();
        for (K8sDetailDtos.PersistentVolumeClaimRefDto pvc : persistentVolumeClaimList.items()) {
            K8sDetailDtos.ObjectMetaDto meta = pvc.objectMeta();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("状态", "-");
            values.put("名称", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            values.put("标签", labelsDisplay(meta == null ? null : meta.labels()));
            values.put("绑定状态", "-");
            values.put("Volume", "-");
            values.put("容量", "-");
            values.put("访问模式", "-");
            values.put("Storage Class", "-");
            values.put("创建时间", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            rows.add(resourceRow("persistentvolumeclaim",
                    values,
                    PERSISTENT_VOLUME_CLAIM_TABLE_ACTIONS,
                    mutedStatus()));
        }
        addDynamicListCard(createResourceTableCard(
                "Persistent Volume Claims",
                PERSISTENT_VOLUME_CLAIM_TABLE_COLUMNS,
                rows));
    }

    private void addJobRefList(String title, List<K8sDetailDtos.ResourceRefDto> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        List<ResourceTableRow> rows = new ArrayList<>();
        for (K8sDetailDtos.ResourceRefDto job : jobs) {
            K8sDetailDtos.ObjectMetaDto meta = job.objectMeta();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("状态", "-");
            values.put("名称", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            values.put("镜像", "-");
            values.put("标签", labelsDisplay(meta == null ? null : meta.labels()));
            values.put("Pods", "-");
            values.put("创建时间", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            rows.add(resourceRow("job", values, JOB_TABLE_ACTIONS, mutedStatus()));
        }
        addDynamicListCard(createResourceTableCard(title, JOB_TABLE_COLUMNS, rows));
    }

    private void addReplicaSet(K8sDetailDtos.ReplicaSetSummaryDto replicaSet) {
        if (replicaSet == null || isEmptyValue(replicaSet)) {
            return;
        }
        addReplicaSetRows("New Replica Set", List.of(replicaSet));
    }

    private void addReplicaSetList(K8sDetailDtos.ReplicaSetListDto replicaSetList) {
        if (replicaSetList == null
                || replicaSetList.replicaSets() == null
                || replicaSetList.replicaSets().isEmpty()) {
            return;
        }
        addReplicaSetRows("Old Replica Sets", replicaSetList.replicaSets());
    }

    private void addReplicaSetRows(String title, List<K8sDetailDtos.ReplicaSetSummaryDto> replicaSets) {
        List<ResourceTableRow> rows = new ArrayList<>();
        for (K8sDetailDtos.ReplicaSetSummaryDto replicaSet : replicaSets) {
            K8sDetailDtos.ObjectMetaDto meta = replicaSet.objectMeta();
            K8sResourceStatus status = replicaSetStatus(replicaSet.podInfo());
            Map<String, String> values = new LinkedHashMap<>();
            values.put("状态", status.text());
            values.put("名称", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            values.put("镜像", emptyAsDash(joined(replicaSet.containerImages())));
            values.put("标签", labelsDisplay(meta == null ? null : meta.labels()));
            values.put("Pods", podsRatio(replicaSet.podInfo()));
            values.put("创建时间", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            rows.add(resourceRow("replicaset", values, REPLICA_SET_TABLE_ACTIONS, status));
        }
        addDynamicListCard(createResourceTableCard(title, REPLICA_SET_TABLE_COLUMNS, rows));
    }

    private void addIngressList(K8sDetailDtos.IngressListDto ingressList) {
        if (ingressList == null || ingressList.ingresses() == null || ingressList.ingresses().isEmpty()) {
            return;
        }
        List<ResourceTableRow> rows = new ArrayList<>();
        for (K8sDetailDtos.IngressDetailDto ingress : ingressList.ingresses()) {
            K8sDetailDtos.ObjectMetaDto meta = ingress.objectMeta();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("名称", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            values.put("标签", labelsDisplay(meta == null ? null : meta.labels()));
            values.put("Endpoints", emptyAsDash(joined(endpointHosts(ingress.endpoints()))));
            values.put("Hosts", emptyAsDash(joined(ingress.hosts())));
            values.put("创建时间", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            rows.add(resourceRow("ingress", values, INGRESS_TABLE_ACTIONS, null));
        }
        overviewHost.getChildren().add(createResourceTableCard("Ingresses", INGRESS_TABLE_COLUMNS, rows));
    }

    private void addEndpointList(K8sDetailDtos.EndpointListDto endpointList) {
        if (endpointList == null || endpointList.endpoints() == null || endpointList.endpoints().isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (K8sDetailDtos.EndpointDto endpoint : endpointList.endpoints()) {
            K8sDetailDtos.ObjectMetaDto meta = endpoint.objectMeta();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("地址", emptyAsDash(endpoint.host()));
            values.put("节点", emptyAsDash(endpoint.nodeName()));
            values.put("Ready", endpoint.ready() == null ? "-" : endpoint.ready() ? "是" : "否");
            values.put("端口", endpointPortsDisplay(endpoint.ports()));
            values.put("Endpoint", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            rows.add(values);
        }
        overviewHost.getChildren().add(createTableCard("Endpoints",
                List.of("地址", "节点", "Ready", "端口", "Endpoint", "命名空间"),
                rows));
    }

    private void addContainerGroup(String title, List<K8sDetailDtos.ContainerDto> containers) {
        if (containers == null || containers.isEmpty()) {
            return;
        }
        overviewHost.getChildren().add(createGroupHeader(title));
        for (K8sDetailDtos.ContainerDto container : containers) {
            overviewHost.getChildren().add(createContainerCard(container));
        }
    }

    private void addHorizontalPodAutoscalerList(K8sDetailDtos.HorizontalPodAutoscalerListDto hpaList) {
        if (hpaList == null
                || hpaList.horizontalpodautoscalers() == null
                || hpaList.horizontalpodautoscalers().isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (K8sDetailDtos.HorizontalPodAutoscalerDetailDto hpa : hpaList.horizontalpodautoscalers()) {
            K8sDetailDtos.ObjectMetaDto meta = hpa.objectMeta();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("名称", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            values.put("Min Replicas", String.valueOf(hpa.minReplicas()));
            values.put("Max Replicas", String.valueOf(hpa.maxReplicas()));
            values.put("Reference", emptyAsDash(scaleTargetRefDisplay(hpa.scaleTargetRef())));
            values.put("创建时间", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            rows.add(values);
        }
        overviewHost.getChildren().add(createTableCard("Horizontal Pod Autoscalers", HPA_TABLE_COLUMNS, rows));
    }

    private void addEventList(K8sDetailDtos.EventListDto eventList) {
        if (eventList == null || eventList.events() == null || eventList.events().isEmpty()) {
            return;
        }
        List<ResourceTableRow> rows = new ArrayList<>();
        for (K8sDetailDtos.EventDto event : eventList.events()) {
            K8sDetailDtos.ObjectMetaDto meta = event.objectMeta();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("名称", metaName(meta));
            values.put("命名空间", metaNamespace(meta));
            values.put("Reason", emptyAsDash(event.reason()));
            values.put("Message", emptyAsDash(event.message()));
            values.put("Source", eventSource(event));
            values.put("Object", emptyAsDash(event.object()));
            values.put("Count", String.valueOf(event.count()));
            values.put("First Seen", ageDisplay(event.firstSeen()));
            values.put("Last Seen", ageDisplay(event.lastSeen()));
            rows.add(resourceRow("event", values, EVENT_TABLE_ACTIONS, null));
        }
        overviewHost.getChildren().add(createResourceTableCard("Events", EVENT_TABLE_COLUMNS, rows));
    }

    private ResourceTableRow resourceRow(String resourceKind,
                                         Map<String, String> values,
                                         List<String> actionLabels,
                                         K8sResourceStatus status) {
        return resourceRow(resourceKind, true, values, actionLabels, status);
    }

    private ResourceTableRow resourceRow(String resourceKind,
                                         boolean namespaced,
                                         Map<String, String> values,
                                         List<String> actionLabels,
                                         K8sResourceStatus status) {
        List<DetailActionSpec> actions = new ArrayList<>();
        String name = values.getOrDefault("名称", "");
        String namespace = values.getOrDefault("命名空间", "");
        for (String label : actionLabels) {
            actions.add(new DetailActionSpec(label,
                    kubectlHint(label, resourceKind, namespaced, namespace, name),
                    "编辑资源".equals(label) || "扩缩容".equals(label) || "触发执行".equals(label),
                    resourceKind,
                    namespace,
                    name,
                    namespaced));
        }
        return new ResourceTableRow(resourceKind, namespaced, values, actions, status);
    }

    private K8sResourceStatus podStatus(String text) {
        return switch (text) {
            case "Running", "Succeeded", "Completed" ->
                    new K8sResourceStatus(K8sResourceStatus.Level.SUCCESS, text, false);
            case "Pending", "ContainerCreating" -> new K8sResourceStatus(K8sResourceStatus.Level.WARNING, text, false);
            case "Failed", "Error", "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull",
                 "CreateContainerConfigError", "CreateContainerError", "InvalidImageName",
                 "RunContainerError", "OOMKilled" -> new K8sResourceStatus(K8sResourceStatus.Level.ERROR, text, false);
            default -> text.startsWith("Init:") && !"Init:0/0".equals(text)
                    ? new K8sResourceStatus(K8sResourceStatus.Level.WARNING, text, false)
                    : new K8sResourceStatus(K8sResourceStatus.Level.MUTED, emptyAsDash(text), false);
        };
    }

    private K8sResourceStatus serviceStatus(K8sDetailDtos.ServiceSummaryDto service) {
        if (service == null) {
            return new K8sResourceStatus(K8sResourceStatus.Level.MUTED, "-", false);
        }
        if ("ExternalName".equals(service.type())) {
            return new K8sResourceStatus(K8sResourceStatus.Level.SUCCESS, "Succeeded", false);
        }
        if ("LoadBalancer".equals(service.type())
                && (service.externalEndpoints() == null || service.externalEndpoints().isEmpty())) {
            return new K8sResourceStatus(K8sResourceStatus.Level.WARNING, "Pending", false);
        }
        return hasDisplayValue(service.clusterIP())
                ? new K8sResourceStatus(K8sResourceStatus.Level.SUCCESS, "Succeeded", false)
                : new K8sResourceStatus(K8sResourceStatus.Level.WARNING, "Pending", false);
    }

    private K8sResourceStatus persistentVolumeStatus(String text) {
        return switch (text) {
            case "Available", "Bound" -> new K8sResourceStatus(K8sResourceStatus.Level.SUCCESS, text, false);
            case "Pending" -> new K8sResourceStatus(K8sResourceStatus.Level.WARNING, text, false);
            case "Released" -> new K8sResourceStatus(K8sResourceStatus.Level.MUTED, text, false);
            case "Failed" -> new K8sResourceStatus(K8sResourceStatus.Level.ERROR, text, false);
            default -> new K8sResourceStatus(K8sResourceStatus.Level.MUTED, emptyAsDash(text), false);
        };
    }

    private K8sResourceStatus replicaSetStatus(K8sDetailDtos.PodInfoDto podInfo) {
        if (podInfo == null) {
            return mutedStatus();
        }
        int desired = podInfo.desired() == null ? podInfo.current() : podInfo.desired();
        int actual = podInfo.current();
        if (desired == 0) {
            return actual == 0
                    ? new K8sResourceStatus(K8sResourceStatus.Level.SUCCESS, "Scaled to 0", false)
                    : new K8sResourceStatus(K8sResourceStatus.Level.WARNING, "Pending", false);
        }
        return actual >= desired
                ? new K8sResourceStatus(K8sResourceStatus.Level.SUCCESS, "Running", false)
                : new K8sResourceStatus(K8sResourceStatus.Level.WARNING, "Pending", false);
    }

    private K8sResourceStatus mutedStatus() {
        return new K8sResourceStatus(K8sResourceStatus.Level.MUTED, emptyAsDash("-"), false);
    }

    private String podsRatio(K8sDetailDtos.PodInfoDto podInfo) {
        if (podInfo == null) {
            return "-";
        }
        int desired = podInfo.desired() == null ? podInfo.current() : podInfo.desired();
        return Math.max(podInfo.current(), 0) + "/" + Math.max(desired, 0);
    }

    private String capacityDisplay(Map<String, Object> capacity) {
        if (capacity == null || capacity.isEmpty()) {
            return "-";
        }
        Object storage = capacity.get("storage");
        if (storage != null && !String.valueOf(storage).isBlank()) {
            return String.valueOf(storage);
        }
        return mapDisplay(capacity);
    }

    private String kubectlHint(String label,
                               String resourceKind,
                               boolean namespaced,
                               String namespace,
                               String name) {
        String ns = namespaced && hasDisplayValue(namespace) ? " -n " + namespace : "";
        return switch (label) {
            case "查看详情" -> "kubectl get " + resourceKind + ns + " " + name + " -o yaml";
            case "查看日志" -> "kubectl logs" + ns + " " + name;
            case "执行" -> "kubectl exec -it" + ns + " " + name + " -- /bin/sh";
            case "编辑资源" -> "kubectl edit " + resourceKind + ns + " " + name;
            case "删除资源" -> "kubectl delete " + resourceKind + ns + " " + name;
            default -> resourceKind + "/" + name;
        };
    }

    private String metaName(K8sDetailDtos.ObjectMetaDto meta) {
        return meta == null ? "-" : emptyAsDash(meta.name());
    }

    private String metaNamespace(K8sDetailDtos.ObjectMetaDto meta) {
        return meta == null ? "-" : emptyAsDash(meta.namespace());
    }

    private String labelsDisplay(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            values.add(entry.getKey() + "=" + entry.getValue());
            if (values.size() >= 6) {
                break;
            }
        }
        if (labels.size() > values.size()) {
            values.add("+" + (labels.size() - values.size()));
        }
        return String.join(", ", values);
    }

    private String metricDisplay(Map<String, Object> metrics, String... names) {
        if (metrics == null || metrics.isEmpty()) {
            return "-";
        }
        for (String name : names) {
            Object value = metrics.get(name);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        for (Object value : metrics.values()) {
            if (!(value instanceof Map<?, ?> metric)) {
                continue;
            }
            String metricName = stringValue(metric.get("metricName"));
            for (String name : names) {
                if (name.equalsIgnoreCase(metricName)) {
                    return emptyAsDash(stringValue(metric.get("value")));
                }
            }
        }
        return "-";
    }

    private String endpointDisplay(K8sDetailDtos.EndpointDto endpoint) {
        if (endpoint == null) {
            return "-";
        }
        String host = emptyAsDash(endpoint.host());
        String ports = portsDisplay(endpoint.ports());
        return "-".equals(ports) ? host : host + ":" + ports;
    }

    private String endpointDisplay(List<K8sDetailDtos.EndpointDto> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (K8sDetailDtos.EndpointDto endpoint : endpoints) {
            String value = endpointDisplay(endpoint);
            if (!"-".equals(value)) {
                values.add(value);
            }
        }
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private String portsDisplay(List<Map<String, Object>> ports) {
        if (ports == null || ports.isEmpty()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (Map<String, Object> port : ports) {
            String protocol = emptyAsDash(stringValue(port.get("protocol")));
            if ("-".equals(protocol)) {
                protocol = "TCP";
            }
            String portValue = stringValue(port.get("port"));
            String targetPort = stringValue(port.get("targetPort"));
            if (targetPort.isBlank()) {
                targetPort = portValue;
            }
            values.add(emptyAsDash(portValue) + ":" + emptyAsDash(targetPort) + "/" + protocol);
        }
        return String.join(", ", values);
    }

    private String endpointPortsDisplay(List<Map<String, Object>> ports) {
        if (ports == null || ports.isEmpty()) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        for (Map<String, Object> port : ports) {
            String name = stringValue(port.get("name"));
            String portValue = stringValue(port.get("port"));
            String targetPort = stringValue(port.get("targetPort"));
            String protocol = emptyAsDash(stringValue(port.get("protocol")));
            String appProtocol = stringValue(port.get("appProtocol"));
            if ("-".equals(protocol)) {
                protocol = "TCP";
            }

            StringBuilder value = new StringBuilder();
            if (!name.isBlank()) {
                value.append(name).append(": ");
            }
            value.append(emptyAsDash(portValue)).append("/").append(protocol);
            if (!targetPort.isBlank() && !targetPort.equals(portValue)) {
                value.append(" -> ").append(targetPort);
            }
            if (!appProtocol.isBlank()) {
                value.append(" (").append(appProtocol).append(")");
            }
            values.add(value.toString());
        }
        return String.join(", ", values);
    }

    private VBox createContainerCard(K8sDetailDtos.ContainerDto container) {
        String name = emptyAsDash(container.name());
        VBox card = createCardShell(name);

        Map<String, String> summary = new LinkedHashMap<>();
        putDisplayValue(summary, "Image", container.image());
        putDisplayValue(summary, "Status", containerStatus(container.status()));
        putDisplayValue(summary, "Ready", statusValue(container.status(), "ready"));
        putDisplayValue(summary, "Started", statusValue(container.status(), "started"));
        putDisplayValue(summary, "Restart Count", statusValue(container.status(), "restartCount"));
        putDisplayValue(summary, "State Detail", containerStateDetail(container.status()));
        if (!summary.isEmpty()) {
            card.getChildren().add(createContainerKeyValueSection("Overview", summary));
        }

        addContainerEnv(card, container.env());
        addContainerCodeBlock(card, "Commands", container.commands());
        addContainerCodeBlock(card, "Arguments", container.args());
        addContainerMounts(card, container.volumeMounts());
        addContainerMap(card, container.securityContext());
        addContainerProbe(card, "Liveness Probe", container.livenessProbe());
        addContainerProbe(card, "Readiness Probe", container.readinessProbe());
        addContainerProbe(card, "Startup Probe", container.startupProbe());
        addContainerResources(card, container.resources());
        return card;
    }

    private void addContainerEnv(VBox card, List<Map<String, Object>> env) {
        if (env == null || env.isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> item : env) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Name", emptyAsDash(stringValue(item.get("name"))));
            row.put("Value", envValueDisplay(item));
            row.put("Source", envSourceDisplay(item));
            rows.add(row);
        }
        card.getChildren().add(createContainerTableSection("Environment Variables",
                List.of("Name", "Value", "Source"),
                rows));
    }

    private void addContainerMounts(VBox card, List<Map<String, Object>> mounts) {
        if (mounts == null || mounts.isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> mount : mounts) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Name", emptyAsDash(stringValue(mount.get("name"))));
            row.put("Read Only", emptyAsDash(stringValue(mount.get("readOnly"))));
            row.put("Mount Path", emptyAsDash(stringValue(mount.get("mountPath"))));
            row.put("Sub Path", emptyAsDash(stringValue(mount.get("subPath"))));
            Object volume = mount.get("volume");
            row.put("Source Type", volumeSourceType(volume));
            row.put("Source Name", volumeSourceName(volume));
            rows.add(row);
        }
        card.getChildren().add(createContainerTableSection("Mounts",
                List.of("Name", "Read Only", "Mount Path", "Sub Path", "Source Type", "Source Name"),
                rows));
    }

    private void addContainerMap(VBox card, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        card.getChildren().add(createContainerKeyValueSection("Security Context", flattenMap(values)));
    }

    private void addContainerProbe(VBox card, String title, Map<String, Object> probe) {
        if (probe == null || probe.isEmpty()) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        putDisplayValue(values, "Initial Delay (Seconds)", probe.get("initialDelaySeconds"));
        putDisplayValue(values, "Timeout (Seconds)", probe.get("timeoutSeconds"));
        putDisplayValue(values, "Probe Period (Seconds)", probe.get("periodSeconds"));
        putDisplayValue(values, "Success Threshold", probe.get("successThreshold"));
        putDisplayValue(values, "Failure Threshold", probe.get("failureThreshold"));
        putDisplayValue(values, "Termination Grace Period (Seconds)", probe.get("terminationGracePeriodSeconds"));
        putDisplayValue(values, "HTTP Healthcheck URI", probeHttpUri(mapValue(probe, "httpGet")));
        putDisplayValue(values, "TCP Socket", probeTcpSocket(mapValue(probe, "tcpSocket")));
        putDisplayValue(values, "Exec Commands", probeExecCommands(mapValue(probe, "exec")));
        card.getChildren().add(createContainerKeyValueSection(title, values.isEmpty() ? flattenMap(probe) : values));
    }

    private void addContainerResources(VBox card, Map<String, Object> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        Object limits = resources.get("limits");
        Object requests = resources.get("requests");
        putDisplayValue(values, "Limits", limits instanceof Map<?, ?> map ? mapDisplay(map) : joinedValue(limits));
        putDisplayValue(values, "Requests", requests instanceof Map<?, ?> map ? mapDisplay(map) : joinedValue(requests));
        if (!values.isEmpty()) {
            card.getChildren().add(createContainerKeyValueSection("Resources", values));
        }
    }

    private void addContainerCodeBlock(VBox card, String title, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        TextArea area = new TextArea(String.join("\n", lines));
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().addAll("detail-yaml", "container-code-block");
        area.setPrefRowCount(Math.max(3, Math.min(12, lines.size() + 1)));
        VBox section = createContainerSection(title);
        section.getChildren().add(area);
        card.getChildren().add(section);
    }

    private VBox createContainerSection(String title) {
        VBox section = new VBox(6);
        section.getStyleClass().add("container-detail-section");
        Label header = new Label(title);
        header.getStyleClass().add("container-section-title");
        section.getChildren().add(header);
        return section;
    }

    private VBox createContainerKeyValueSection(String title, Map<String, String> values) {
        VBox section = createContainerSection(title);
        GridPane grid = new GridPane();
        grid.getStyleClass().add("detail-kv-grid");
        grid.setHgap(12);
        grid.setVgap(8);

        int row = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!hasDisplayValue(entry.getValue())) {
                continue;
            }
            Label key = new Label(entry.getKey());
            key.getStyleClass().add("detail-kv-key");
            Label value = new Label(entry.getValue());
            value.setWrapText(true);
            value.getStyleClass().add("detail-kv-value");
            grid.addRow(row++, key, value);
        }
        if (row == 0) {
            Label empty = new Label("No data");
            empty.getStyleClass().add("detail-empty");
            grid.add(empty, 0, 0, 2, 1);
        }

        ColumnConstraints keyCol = new ColumnConstraints();
        keyCol.setMinWidth(140);
        keyCol.setPrefWidth(160);
        keyCol.setHgrow(Priority.NEVER);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(keyCol, valueCol);
        section.getChildren().add(grid);
        return section;
    }

    private VBox createContainerTableSection(String title, List<String> columns, List<Map<String, String>> rows) {
        VBox section = createContainerSection(title);
        TableView<Map<String, String>> table = new TableView<>();
        table.getStyleClass().add("detail-table");
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(Math.min(240, Math.max(96, (rows.size() + 1) * 32)));

        for (String columnName : columns) {
            TableColumn<Map<String, String>, String> column = new TableColumn<>(columnName);
            column.setPrefWidth(Math.max(120, Math.min(260, columnName.length() * 16.0)));
            column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                    data.getValue().getOrDefault(columnName, "-")));
            table.getColumns().add(column);
        }
        table.setItems(FXCollections.observableArrayList(rows));
        section.getChildren().add(table);
        return section;
    }

    private String eventSource(K8sDetailDtos.EventDto event) {
        String component = stringValue(event.sourceComponent());
        String host = stringValue(event.sourceHost());
        if (!component.isBlank() && !host.isBlank()) {
            return component + " / " + host;
        }
        return emptyAsDash(!component.isBlank() ? component : host);
    }

    private String containerStatus(Map<String, Object> status) {
        Object state = mapValue(status, "state");
        if (mapValue(state, "waiting") instanceof Map<?, ?>) {
            return "Waiting";
        }
        if (mapValue(state, "terminated") instanceof Map<?, ?> terminated) {
            String reason = stringValue(terminated.get("reason"));
            return reason.isBlank() ? "Terminated" : reason;
        }
        if (mapValue(state, "running") instanceof Map<?, ?>) {
            return "Running";
        }
        return status == null || status.isEmpty() ? null : "Unknown";
    }

    private String containerStateDetail(Map<String, Object> status) {
        Object state = mapValue(status, "state");
        Object waiting = mapValue(state, "waiting");
        if (waiting instanceof Map<?, ?> waitingMap) {
            return stateDetail(waitingMap, List.of("reason", "message"));
        }
        Object terminated = mapValue(state, "terminated");
        if (terminated instanceof Map<?, ?> terminatedMap) {
            return stateDetail(terminatedMap, List.of("reason", "message", "exitCode", "signal", "finishedAt"));
        }
        Object running = mapValue(state, "running");
        if (running instanceof Map<?, ?> runningMap) {
            return stateDetail(runningMap, List.of("startedAt"));
        }
        return null;
    }

    private String stateDetail(Map<?, ?> state, List<String> keys) {
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            String value = stringValue(state.get(key));
            if (!value.isBlank()) {
                values.add(prettyLabel(key) + ": " + value);
            }
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private String statusValue(Map<String, Object> status, String key) {
        Object value = mapValue(status, key);
        return value == null || isEmptyValue(value) ? null : String.valueOf(value);
    }

    private String envValueDisplay(Map<String, Object> env) {
        String value = stringValue(env.get("value"));
        if (!value.isBlank()) {
            return value;
        }
        return env.containsKey("valueFrom") ? "-" : "";
    }

    private String envSourceDisplay(Map<String, Object> env) {
        Object valueFrom = env.get("valueFrom");
        if (!(valueFrom instanceof Map<?, ?> source)) {
            return "-";
        }
        Object secretRef = source.get("secretKeyRef");
        if (secretRef instanceof Map<?, ?> secret) {
            return "Secret " + refNameKey(secret);
        }
        Object configMapRef = source.get("configMapKeyRef");
        if (configMapRef instanceof Map<?, ?> configMap) {
            return "ConfigMap " + refNameKey(configMap);
        }
        Object fieldRef = source.get("fieldRef");
        if (fieldRef instanceof Map<?, ?> field) {
            return "Field " + emptyAsDash(stringValue(field.get("fieldPath")));
        }
        Object resourceRef = source.get("resourceFieldRef");
        if (resourceRef instanceof Map<?, ?> resource) {
            return "Resource " + emptyAsDash(stringValue(resource.get("resource")));
        }
        return mapDisplay(source);
    }

    private String refNameKey(Map<?, ?> ref) {
        String name = stringValue(ref.get("name"));
        String key = stringValue(ref.get("key"));
        if (name.isBlank()) {
            return emptyAsDash(key);
        }
        return key.isBlank() ? name : name + "/" + key;
    }

    private String volumeSourceType(Object volume) {
        if (!(volume instanceof Map<?, ?> map)) {
            return "-";
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                return prettyLabel(String.valueOf(entry.getKey()));
            }
        }
        return "-";
    }

    private String volumeSourceName(Object volume) {
        if (!(volume instanceof Map<?, ?> map)) {
            return "-";
        }
        for (Object value : map.values()) {
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                String name = stringValue(nested.get("name"));
                String claimName = stringValue(nested.get("claimName"));
                String secretName = stringValue(nested.get("secretName"));
                String configMapName = stringValue(nested.get("configMapName"));
                return firstDisplayValue(name, claimName, secretName, configMapName);
            }
        }
        return "-";
    }

    private String firstDisplayValue(String... values) {
        for (String value : values) {
            if (hasDisplayValue(value)) {
                return value;
            }
        }
        return "-";
    }

    private Map<String, String> flattenMap(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value != null && !isEmptyValue(value)) {
                result.put(prettyLabel(entry.getKey()), value instanceof Map<?, ?> map ? mapDisplay(map) : joinedValue(value));
            }
        }
        return result;
    }

    private String probeHttpUri(Object httpGet) {
        if (!(httpGet instanceof Map<?, ?> http)) {
            return null;
        }
        String scheme = stringValue(http.get("scheme"));
        if (scheme.isBlank()) {
            return null;
        }
        String host = stringValue(http.get("host"));
        if (host.isBlank()) {
            host = "[host]";
        }
        String port = stringValue(http.get("port"));
        String path = stringValue(http.get("path"));
        return scheme.toLowerCase(Locale.ROOT) + "://" + host + (port.isBlank() ? "" : ":" + port) + path;
    }

    private String probeTcpSocket(Object tcpSocket) {
        if (!(tcpSocket instanceof Map<?, ?> tcp)) {
            return null;
        }
        String host = stringValue(tcp.get("host"));
        if (host.isBlank()) {
            host = "[host]";
        }
        String port = stringValue(tcp.get("port"));
        return port.isBlank() ? host : host + ":" + port;
    }

    private String probeExecCommands(Object exec) {
        Object command = mapValue(exec, "command");
        if (command instanceof Collection<?> commands) {
            return emptyAsDash(joined(commands));
        }
        return null;
    }

    private String ageDisplay(String timestamp) {
        String age = ageSince(timestamp);
        return age.isBlank() ? emptyAsDash(timestamp) : age;
    }

    private String ageSince(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return "";
        }
        try {
            java.time.Duration duration = java.time.Duration.between(Instant.parse(timestamp), Instant.now());
            if (duration.isNegative()) {
                return "刚刚";
            }
            long seconds = duration.getSeconds();
            if (seconds < 60) {
                return Math.max(0, seconds) + "秒前";
            }
            long minutes = duration.toMinutes();
            if (minutes < 60) {
                return minutes + "分钟前";
            }
            long hours = duration.toHours();
            if (hours < 24) {
                return hours + "小时前";
            }
            long days = duration.toDays();
            if (days < 30) {
                return days + "天前";
            }
            if (days < 365) {
                return Math.max(1L, days / 30) + "个月前";
            }
            return Math.max(1L, days / 365) + "年前";
        } catch (Exception ignored) {
            return "";
        }
    }

    private void addCapacityTable(Map<String, Object> capacity) {
        if (capacity == null || capacity.isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map.Entry<String, Object> entry : capacity.entrySet()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Resource Name", entry.getKey());
            row.put("Quantity", formatValue(entry.getValue()));
            rows.add(row);
        }
        overviewHost.getChildren().add(createTableCard("Capacity", List.of("Resource Name", "Quantity"), rows));
    }

    private void addMetricList(List<Map<String, Object>> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> metric : metrics) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Metric", emptyAsDash(stringValue(metric.get("metricName"))));
            row.put("Value", emptyAsDash(stringValue(metric.get("value"))));
            rows.add(row);
        }
        overviewHost.getChildren().add(createTableCard("Metrics", List.of("Metric", "Value"), rows));
    }

    private void addResourceQuotaList(K8sDetailDtos.ResourceQuotaListDto resourceQuotaList) {
        if (resourceQuotaList == null
                || resourceQuotaList.items() == null
                || resourceQuotaList.items().isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (K8sDetailDtos.ResourceQuotaDto quota : resourceQuotaList.items()) {
            K8sDetailDtos.ObjectMetaDto meta = quota.objectMeta();
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Name", metaName(meta));
            row.put("Created", ageDisplay(meta == null ? null : meta.creationTimestamp()));
            row.put("Status", quotaStatusDisplay(quota.statusList()));
            rows.add(row);
        }
        overviewHost.getChildren().add(createTableCard("Resource Quotas", List.of("Name", "Created", "Status"), rows));
    }

    private void addResourceLimitList(List<K8sDetailDtos.ResourceLimitDto> limits) {
        if (limits == null || limits.isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (K8sDetailDtos.ResourceLimitDto limit : limits) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Resource name", emptyAsDash(limit.resourceName()));
            row.put("Resource type", emptyAsDash(limit.resourceType()));
            row.put("Default", emptyAsDash(limit.defaultValue()));
            row.put("Default request", emptyAsDash(limit.defaultRequest()));
            rows.add(row);
        }
        overviewHost.getChildren().add(createTableCard("Resource Limits",
                List.of("Resource name", "Resource type", "Default", "Default request"),
                rows));
    }

    private void addIngressRules(Map<String, Object> spec) {
        List<Map<String, String>> rows = ingressRuleRows(spec);
        if (rows.isEmpty()) {
            return;
        }
        overviewHost.getChildren().add(createTableCard("Rules",
                List.of("Host", "Path", "Path Type", "Service Name", "Service Port", "TLS Secret"),
                rows));
    }

    private void addPolicyRules(List<Map<String, Object>> rules) {
        if (rules == null || rules.isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Resources", joinedValue(rule.get("resources")));
            row.put("Non-resource URL", joinedValue(rule.get("nonResourceURLs")));
            row.put("Resource Names", joinedValue(rule.get("resourceNames")));
            row.put("Verbs", joinedValue(rule.get("verbs")));
            row.put("API Groups", joinedValue(nonEmptyApiGroups(rule.get("apiGroups"))));
            rows.add(row);
        }
        overviewHost.getChildren().add(createTableCard("Rules",
                List.of("Resources", "Non-resource URL", "Resource Names", "Verbs", "API Groups"),
                rows));
    }

    private void addSubjects(List<Map<String, Object>> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return;
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> subject : subjects) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Name", emptyAsDash(stringValue(subject.get("name"))));
            row.put("Namespace", emptyAsDash(stringValue(subject.get("namespace"))));
            row.put("Kind", emptyAsDash(stringValue(subject.get("kind"))));
            row.put("API Group", emptyAsDash(stringValue(subject.get("apiGroup"))));
            rows.add(row);
        }
        overviewHost.getChildren().add(createTableCard("Subjects",
                List.of("Name", "Namespace", "Kind", "API Group"),
                rows));
    }

    private String selectorDisplay(Object selector) {
        if (selector == null || isEmptyValue(selector)) {
            return null;
        }
        if (selector instanceof Map<?, ?> map) {
            Object matchLabels = map.get("matchLabels");
            if (matchLabels instanceof Map<?, ?> labels && !labels.isEmpty()) {
                return mapDisplay(labels);
            }
            return mapDisplay(map);
        }
        if (selector instanceof Collection<?> values) {
            List<String> labels = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof K8sDetailDtos.KeyValueDto label) {
                    labels.add(label.key() + "=" + label.value());
                } else if (value != null && !isEmptyValue(value)) {
                    labels.add(String.valueOf(value));
                }
            }
            return labels.isEmpty() ? null : String.join(", ", labels);
        }
        return String.valueOf(selector);
    }

    private String mapDisplay(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (isSimpleValue(value)) {
                labels.add(entry.getKey() + "=" + value);
            }
        }
        return labels.isEmpty() ? formatValue(map) : String.join(", ", labels);
    }

    private String scaleTargetRefDisplay(Map<String, Object> scaleTargetRef) {
        if (scaleTargetRef == null || scaleTargetRef.isEmpty()) {
            return null;
        }
        String kind = stringValue(scaleTargetRef.get("kind"));
        String name = stringValue(scaleTargetRef.get("name"));
        if (kind.isBlank()) {
            return name;
        }
        if (name.isBlank()) {
            return kind;
        }
        return kind + " / " + name;
    }

    private String roleRefDisplay(Map<String, Object> roleRef) {
        if (roleRef == null || roleRef.isEmpty()) {
            return "-";
        }
        String name = stringValue(roleRef.get("name"));
        String kind = stringValue(roleRef.get("kind"));
        if (kind.isBlank()) {
            return emptyAsDash(name);
        }
        if (name.isBlank()) {
            return kind;
        }
        return kind + " / " + name;
    }

    private String configMapDataText(Object value) {
        String text;
        if (value == null) {
            text = "";
        } else if (value instanceof String string) {
            text = string;
        } else {
            text = formatValue(value);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!text.contains("\n") && (text.contains("\\n") || text.contains("\\r"))) {
            text = unescapeTextNewlines(text);
        }
        return text;
    }

    private int configMapDataRowCount(String text) {
        if (text == null || text.isBlank()) {
            return 3;
        }
        int lines = text.split("\n", -1).length;
        return Math.max(3, Math.min(22, lines + 1));
    }

    private String unescapeTextNewlines(String text) {
        return text
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\\t", "\t");
    }

    private String joined(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> text = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                text.add(String.valueOf(value));
            }
        }
        return text.isEmpty() ? null : String.join(", ", text);
    }

    private String joinedValue(Object value) {
        if (value instanceof Collection<?> collection) {
            return joined(collection);
        }
        if (value == null || isEmptyValue(value)) {
            return "-";
        }
        return String.valueOf(value);
    }

    private List<String> nonEmptyApiGroups(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> groups = new ArrayList<>();
        for (Object item : collection) {
            if (item != null && !String.valueOf(item).isBlank()) {
                groups.add(String.valueOf(item));
            }
        }
        return groups;
    }

    private void putDisplayValue(Map<String, String> target, String key, Object value) {
        if (value != null && !isEmptyValue(value)) {
            target.put(key, String.valueOf(value));
        }
    }

    private List<String> namedObjectList(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> value : values) {
            Object name = value.get("name");
            if (name != null && !String.valueOf(name).isBlank()) {
                names.add(String.valueOf(name));
            }
        }
        return names;
    }

    private List<String> nodeAddresses(List<Map<String, Object>> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Map<String, Object> address : addresses) {
            Object type = address.get("type");
            Object value = address.get("address");
            if (type != null && value != null) {
                values.add(type + ": " + value);
            }
        }
        return values;
    }

    private List<String> nodeTaints(List<Map<String, Object>> taints) {
        if (taints == null || taints.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Map<String, Object> taint : taints) {
            Object key = taint.get("key");
            Object value = taint.get("value");
            Object effect = taint.get("effect");
            if (key == null || effect == null) {
                continue;
            }
            if (value == null || String.valueOf(value).isBlank()) {
                values.add(key + "=" + effect);
            } else {
                values.add(key + "=" + value + ":" + effect);
            }
        }
        return values;
    }

    private List<String> endpointHosts(List<K8sDetailDtos.EndpointDto> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (K8sDetailDtos.EndpointDto endpoint : endpoints) {
            if (endpoint.host() != null && !endpoint.host().isBlank()) {
                values.add(endpoint.host());
            }
        }
        return values;
    }

    private List<Map<String, String>> ingressRuleRows(Map<String, Object> spec) {
        List<Map<String, String>> rows = new ArrayList<>();
        Map<String, String> tlsByHost = tlsByHost(spec);
        Object rules = mapValue(spec, "rules");
        if (!(rules instanceof Collection<?> ruleList)) {
            return rows;
        }
        for (Object ruleValue : ruleList) {
            if (!(ruleValue instanceof Map<?, ?> rule)) {
                continue;
            }
            String host = stringValue(rule.get("host"));
            Object paths = mapValue(mapValue(rule, "http"), "paths");
            if (!(paths instanceof Collection<?> pathList)) {
                continue;
            }
            for (Object pathValue : pathList) {
                if (!(pathValue instanceof Map<?, ?> path)) {
                    continue;
                }
                Object backend = path.get("backend");
                Map<String, String> row = new LinkedHashMap<>();
                row.put("Host", emptyAsDash(host));
                row.put("Path", emptyAsDash(stringValue(path.get("path"))));
                row.put("Path Type", emptyAsDash(stringValue(path.get("pathType"))));
                row.put("Service Name", emptyAsDash(backendName(backend)));
                row.put("Service Port", emptyAsDash(backendPort(backend)));
                row.put("TLS Secret", emptyAsDash(tlsByHost.get(host)));
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, String> tlsByHost(Map<String, Object> spec) {
        Map<String, String> result = new HashMap<>();
        Object tls = mapValue(spec, "tls");
        if (!(tls instanceof Collection<?> tlsList)) {
            return result;
        }
        for (Object tlsValue : tlsList) {
            if (!(tlsValue instanceof Map<?, ?> tlsItem)) {
                continue;
            }
            String secretName = stringValue(tlsItem.get("secretName"));
            Object hosts = tlsItem.get("hosts");
            if (!(hosts instanceof Collection<?> hostList)) {
                continue;
            }
            for (Object host : hostList) {
                result.put(stringValue(host), secretName);
            }
        }
        return result;
    }

    private void addBackendInfo(Map<String, String> target, Object backend) {
        if (!(backend instanceof Map<?, ?> map)) {
            return;
        }
        String name = backendName(map);
        String port = backendPort(map);
        putDisplayValue(target, "Default Backend" + " Service Name", name);
        putDisplayValue(target, "Default Backend" + " Service Port", port);
        Object resource = map.get("resource");
        if (resource instanceof Map<?, ?> resourceMap) {
            String kind = stringValue(resourceMap.get("kind"));
            String resourceName = stringValue(resourceMap.get("name"));
            if (!kind.isBlank() && !resourceName.isBlank()) {
                putDisplayValue(target, "Default Backend" + " " + kind, resourceName);
            }
        }
    }

    private String backendName(Object backend) {
        Object service = mapValue(backend, "service");
        String serviceName = stringValue(mapValue(service, "name"));
        if (!serviceName.isBlank()) {
            return serviceName;
        }
        Object resource = mapValue(backend, "resource");
        String apiGroup = stringValue(mapValue(resource, "apiGroup"));
        String name = stringValue(mapValue(resource, "name"));
        if (name.isBlank()) {
            return "";
        }
        return apiGroup.isBlank() ? name : apiGroup + "/" + name;
    }

    private String backendPort(Object backend) {
        Object service = mapValue(backend, "service");
        Object port = mapValue(service, "port");
        String name = stringValue(mapValue(port, "name"));
        if (!name.isBlank()) {
            return name;
        }
        return stringValue(mapValue(port, "number"));
    }

    private Object mapValue(Object map, String key) {
        if (map instanceof Map<?, ?> values) {
            return values.get(key);
        }
        return null;
    }

    private String mapPath(Map<String, Object> map) {
        return stringValue(mapValue(map, "ingressClassName"));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String emptyAsDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private Map<String, String> secretDataSummary(Map<String, Object> data) {
        Map<String, String> values = new LinkedHashMap<>();
        if (data == null || data.isEmpty()) {
            return values;
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String encoded = String.valueOf(entry.getValue());
            values.put(entry.getKey(), decodedSecretLength(encoded) + " bytes");
        }
        return values;
    }

    private int decodedSecretLength(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return 0;
        }
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).length();
        } catch (IllegalArgumentException ignored) {
            return "Unable to decode file. It might contain binary data.".length();
        }
    }

    private void addErrorsIfPresent(K8sDetailDtos.ResourceDetailDto data) {
        List<String> errors = errors(data);
        if (errors != null && !errors.isEmpty()) {
            overviewHost.getChildren().add(createListCard("Errors", errors));
        }
    }

    private void addEmptyFallback() {
        syncDynamicListsHost();
        if (overviewHost.getChildren().isEmpty()) {
            overviewHost.getChildren().add(createTextCard("Detail", "No data"));
        }
    }

    private void renderDynamicLists(K8sDetailDtos.ResourceDetailDto data) {
        if (dynamicListsHost == null) {
            return;
        }
        dynamicListsHost.getChildren().clear();
        if (data instanceof K8sDetailDtos.PodDetailDto pod) {
            addPersistentVolumeClaimList(pod.persistentVolumeClaimList());
        } else if (data instanceof K8sDetailDtos.JobDetailDto job) {
            addPodList(job.podList());
        } else if (data instanceof K8sDetailDtos.CronJobDetailDto cronJob) {
            addJobRefList("Active Jobs", cronJob.activeJobs());
            addJobRefList("Inactive Jobs", cronJob.inactiveJobs());
        } else if (data instanceof K8sDetailDtos.DaemonSetDetailDto daemonSet) {
            addPodList(daemonSet.podList());
            addServiceList(daemonSet.serviceList());
        } else if (data instanceof K8sDetailDtos.DeploymentDetailDto deployment) {
            addReplicaSet(deployment.newReplicaSet());
            addReplicaSetList(deployment.oldReplicaSetList());
        } else if (data instanceof K8sDetailDtos.ReplicaSetDetailDto replicaSet) {
            addPodList(replicaSet.podList());
            addServiceList(replicaSet.serviceList());
        } else if (data instanceof K8sDetailDtos.ReplicationControllerDetailDto replicationController) {
            addPodList(replicationController.podList());
            addServiceList(replicationController.serviceList());
        } else if (data instanceof K8sDetailDtos.StatefulSetDetailDto statefulSet) {
            addPodList(statefulSet.podList());
        } else if (data instanceof K8sDetailDtos.ServiceDetailDto service) {
            addPodList(service.podList());
        } else if (data instanceof K8sDetailDtos.NodeDetailDto node) {
            addPodList(node.podList());
        }
        syncDynamicListsHost();
    }

    private void addDynamicListCard(Node card) {
        if (card != null) {
            dynamicListsHost.getChildren().add(card);
        }
    }

    private void syncDynamicListsHost() {
        if (dynamicListsHost == null) {
            return;
        }
        if (dynamicListsHost.getChildren().isEmpty()) {
            overviewHost.getChildren().remove(dynamicListsHost);
        } else if (!overviewHost.getChildren().contains(dynamicListsHost)) {
            overviewHost.getChildren().add(dynamicListsHost);
        }
    }

    private void addValueSection(String title, Object value) {
        Node section = renderValueSection(title, value);
        if (section != null) {
            overviewHost.getChildren().add(section);
        }
    }

    private Node renderValueSection(String title, Object value) {
        if (value == null || isEmptyValue(value)) {
            return null;
        }
        if (value instanceof K8sDetailDtos.ObjectMetaDto meta) {
            return createKeyValueCard(title, recordToMap(meta));
        }
        if (value instanceof K8sDetailDtos.TypeMetaDto meta) {
            return createKeyValueCard(title, recordToMap(meta));
        }
        if (value instanceof Map<?, ?> map) {
            return renderMapSection(title, map);
        }
        if (value instanceof Collection<?> collection) {
            return renderCollectionSection(title, collection);
        }
        if (value.getClass().isRecord()) {
            return renderRecordSection(title, value);
        }
        if (isSimpleValue(value)) {
            return createTextCard(title, String.valueOf(value));
        }
        return createTextCard(title, safeToString(value));
    }

    private Node renderRecordSection(String title, Object record) {
        VBox card = createCardShell(title);
        GridPane grid = new GridPane();
        grid.getStyleClass().add("detail-kv-grid");
        grid.setHgap(12);
        grid.setVgap(10);

        VBox nested = new VBox();
        nested.getStyleClass().add("detail-nested-section");

        int row = 0;
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            String name = component.getName();
            Object value = readComponent(component, record);
            if (value == null || isEmptyValue(value)) {
                continue;
            }
            if (isSimpleValue(value)) {
                Label key = new Label(prettyLabel(name));
                key.getStyleClass().add("detail-kv-key");
                Label val = new Label(String.valueOf(value));
                val.setWrapText(true);
                val.getStyleClass().add("detail-kv-value");
                grid.addRow(row++, key, val);
            } else {
                Node child = renderValueSection(prettyLabel(name), value);
                if (child != null) {
                    nested.getChildren().add(child);
                }
            }
        }

        if (row > 0) {
            ColumnConstraints keyCol = new ColumnConstraints();
            keyCol.setMinWidth(140);
            keyCol.setPrefWidth(160);
            keyCol.setHgrow(Priority.NEVER);
            ColumnConstraints valueCol = new ColumnConstraints();
            valueCol.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(keyCol, valueCol);
            card.getChildren().add(grid);
        }
        if (!nested.getChildren().isEmpty()) {
            card.getChildren().add(nested);
        }
        if (row == 0 && nested.getChildren().isEmpty()) {
            Label empty = new Label("No data");
            empty.getStyleClass().add("detail-empty");
            card.getChildren().add(empty);
        }
        return card;
    }

    private Node renderMapSection(String title, Map<?, ?> map) {
        Map<String, String> flat = new LinkedHashMap<>();
        VBox nested = new VBox();
        nested.getStyleClass().add("detail-nested-section");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null || isEmptyValue(value)) {
                continue;
            }
            if (isSimpleValue(value)) {
                flat.put(key, String.valueOf(value));
            } else {
                Node child = renderValueSection(prettyLabel(key), value);
                if (child != null) {
                    nested.getChildren().add(child);
                }
            }
        }
        if (!flat.isEmpty() && nested.getChildren().isEmpty()) {
            return createKeyValueCard(title, flat);
        }
        VBox card = createCardShell(title);
        if (!flat.isEmpty()) {
            card.getChildren().add(createKeyValueCard("Fields", flat));
        }
        if (!nested.getChildren().isEmpty()) {
            card.getChildren().addAll(nested.getChildren());
        }
        if (flat.isEmpty() && nested.getChildren().isEmpty()) {
            Label empty = new Label("No data");
            empty.getStyleClass().add("detail-empty");
            card.getChildren().add(empty);
        }
        return card;
    }

    private Node renderCollectionSection(String title, Collection<?> items) {
        if (items.isEmpty()) {
            return null;
        }
        boolean allSimple = true;
        boolean allStructured = true;
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            allSimple &= isSimpleValue(item);
            allStructured &= item instanceof Map<?, ?> || item.getClass().isRecord();
        }
        if (allSimple) {
            List<String> values = new ArrayList<>();
            for (Object item : items) {
                if (item != null && !isEmptyValue(item)) {
                    values.add(String.valueOf(item));
                }
            }
            return values.isEmpty() ? null : createListCard(title, values);
        }
        if (allStructured) {
            List<String> columns = new ArrayList<>();
            List<Map<String, String>> rows = new ArrayList<>();
            boolean tableFriendly = true;
            for (Object item : items) {
                tableFriendly &= structuredItemHasOnlySimpleValues(item);
                Map<String, String> row = structuredItemToMap(item);
                if (!row.isEmpty()) {
                    rows.add(row);
                    for (String key : row.keySet()) {
                        if (!columns.contains(key)) {
                            columns.add(key);
                        }
                    }
                }
            }
            if (tableFriendly && !rows.isEmpty()) {
                return createTableCard(title, columns, rows);
            }
        }

        VBox group = createCardShell(title);
        int index = 1;
        for (Object item : items) {
            if (item == null || isEmptyValue(item)) {
                continue;
            }
            Node child = renderValueSection("#" + index++, item);
            if (child != null) {
                group.getChildren().add(child);
            }
        }
        if (group.getChildren().size() == 1) {
            Label empty = new Label("No data");
            empty.getStyleClass().add("detail-empty");
            group.getChildren().add(empty);
        }
        return group;
    }

    private Map<String, String> recordToMap(Object record) {
        Map<String, String> values = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object value = readComponent(component, record);
            if (value != null && !isEmptyValue(value)) {
                values.put(prettyLabel(component.getName()), formatValue(value));
            }
        }
        return values;
    }

    private Map<String, String> structuredItemToMap(Object item) {
        if (item instanceof Map<?, ?> map) {
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value != null && !isEmptyValue(value)) {
                    values.put(String.valueOf(entry.getKey()), formatValue(value));
                }
            }
            return values;
        }
        if (item != null && item.getClass().isRecord()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (RecordComponent component : item.getClass().getRecordComponents()) {
                Object value = readComponent(component, item);
                if (value != null && !isEmptyValue(value)) {
                    values.put(prettyLabel(component.getName()), formatValue(value));
                }
            }
            return values;
        }
        return Map.of();
    }

    private boolean structuredItemHasOnlySimpleValues(Object item) {
        if (item instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value != null && !isEmptyValue(value) && !isSimpleValue(value)) {
                    return false;
                }
            }
            return true;
        }
        if (item != null && item.getClass().isRecord()) {
            for (RecordComponent component : item.getClass().getRecordComponents()) {
                Object value = readComponent(component, item);
                if (value != null && !isEmptyValue(value) && !isSimpleValue(value)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private Object readComponent(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private Object readComponent(String name, Object record) {
        if (record == null || !record.getClass().isRecord()) {
            return null;
        }
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            if (name.equals(component.getName())) {
                return readComponent(component, record);
            }
        }
        return null;
    }

    private K8sDetailDtos.ObjectMetaDto objectMeta(K8sDetailDtos.ResourceDetailDto data) {
        Object value = readComponent("objectMeta", data);
        return value instanceof K8sDetailDtos.ObjectMetaDto meta ? meta : null;
    }

    private K8sDetailDtos.TypeMetaDto typeMeta(K8sDetailDtos.ResourceDetailDto data) {
        Object value = readComponent("typeMeta", data);
        return value instanceof K8sDetailDtos.TypeMetaDto meta ? meta : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> errors(K8sDetailDtos.ResourceDetailDto data) {
        Object value = readComponent("errors", data);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    private boolean isBaseField(String name) {
        return "objectMeta".equals(name) || "typeMeta".equals(name) || "errors".equals(name);
    }

    private boolean isSimpleValue(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>;
    }

    private boolean isEmptyValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence seq) {
            return seq.toString().isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return value.getClass().isArray() && java.lang.reflect.Array.getLength(value) == 0;
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (isSimpleValue(value)) {
            return String.valueOf(value);
        }
        try {
            return DISPLAY_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return safeToString(value);
        }
    }

    private String formatYaml(Object value) {
        if (value == null || isEmptyValue(value)) {
            return "";
        }
        try {
            return YAML_MAPPER.writeValueAsString(value).stripTrailing();
        } catch (Exception e) {
            return formatValue(value);
        }
    }

    private String quotaStatusDisplay(Map<String, Map<String, String>> statusList) {
        if (statusList == null || statusList.isEmpty()) {
            return "-";
        }
        return formatValue(statusList);
    }

    private String safeToString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void addKeyValueCardIfPresent(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        overviewHost.getChildren().add(createKeyValueCard("Resource Info", values));
    }

    private VBox createKeyValueCard(String title, Map<String, String> values) {
        VBox card = createCardShell(title);
        GridPane grid = new GridPane();
        grid.getStyleClass().add("detail-kv-grid");
        grid.setHgap(12);
        grid.setVgap(10);

        int row = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!hasDisplayValue(entry.getValue())) {
                continue;
            }
            Label key = new Label(entry.getKey());
            key.getStyleClass().add("detail-kv-key");
            Label value = new Label(entry.getValue());
            value.setWrapText(true);
            value.getStyleClass().add("detail-kv-value");
            grid.addRow(row++, key, value);
        }
        if (row == 0) {
            Label empty = new Label("No data");
            empty.getStyleClass().add("detail-empty");
            grid.add(empty, 0, 0, 2, 1);
        }

        ColumnConstraints keyCol = new ColumnConstraints();
        keyCol.setMinWidth(140);
        keyCol.setPrefWidth(160);
        keyCol.setHgrow(Priority.NEVER);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(keyCol, valueCol);

        card.getChildren().add(grid);
        return card;
    }

    private VBox createTextCard(String title, String text) {
        VBox card = createCardShell(title);
        TextArea area = new TextArea(text == null || text.isBlank() ? "No data" : text);
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("detail-text-area");
        area.setPrefRowCount(8);
        card.getChildren().add(area);
        return card;
    }

    private VBox createYamlCard(String title, Object value) {
        String text = formatYaml(value);
        VBox card = createCardShell(title);
        TextArea area = new TextArea(text.isBlank() ? "No data" : text);
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().addAll("detail-yaml", "detail-yaml-editor");
        area.setPrefColumnCount(110);
        area.setPrefRowCount(Math.max(4, Math.min(18, text.split("\\R", -1).length + 1)));
        card.getChildren().add(area);
        return card;
    }

    private VBox createConfigMapDataCard(Map<String, Object> data) {
        VBox card = createCardShell("Data");
        VBox entries = new VBox(10);
        entries.getStyleClass().add("configmap-data-list");

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            VBox block = new VBox(6);
            block.getStyleClass().add("configmap-data-block");

            Label key = new Label(String.valueOf(entry.getKey()));
            key.getStyleClass().add("configmap-data-key");

            String text = configMapDataText(entry.getValue());
            TextArea area = new TextArea(text.isBlank() ? "No data" : text);
            area.setEditable(false);
            area.setWrapText(false);
            area.getStyleClass().addAll("detail-yaml", "configmap-data-editor");
            area.setPrefColumnCount(110);
            area.setPrefRowCount(configMapDataRowCount(text));

            block.getChildren().addAll(key, area);
            entries.getChildren().add(block);
        }

        if (entries.getChildren().isEmpty()) {
            Label empty = new Label("No data");
            empty.getStyleClass().add("detail-empty");
            card.getChildren().add(empty);
        } else {
            card.getChildren().add(entries);
        }
        return card;
    }

    private VBox createTableCard(String title, List<String> columns, List<Map<String, String>> rows) {
        VBox card = createCardShell(title);
        TableView<Map<String, String>> table = new TableView<>();
        table.getStyleClass().add("detail-table");
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(Math.min(280, Math.max(120, (rows.size() + 1) * 32)));

        for (String columnName : columns) {
            TableColumn<Map<String, String>, String> column = new TableColumn<>(columnName);
            column.setPrefWidth(Math.max(120, Math.min(240, columnName.length() * 16.0)));
            column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                    data.getValue().getOrDefault(columnName, "-")));
            table.getColumns().add(column);
        }

        table.setItems(FXCollections.observableArrayList(rows));
        card.getChildren().add(table);
        return card;
    }

    private VBox createResourceTableCard(String title, List<String> columns, List<ResourceTableRow> rows) {
        VBox card = createCardShell(title);
        TableView<ResourceTableRow> table = new TableView<>();
        table.getStyleClass().add("k8s-table");
        table.getStyleClass().add("detail-table");
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(Math.min(320, Math.max(120, (rows.size() + 1) * 34)));
        table.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                copyResourceTableSelection(table, columns);
                event.consume();
            }
        });
        table.setRowFactory(view -> {
            TableRow<ResourceTableRow> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    table.getSelectionModel().clearAndSelect(row.getIndex());
                    createResourceContextMenu(row.getItem()).show(row, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getClickCount() == 2) {
                    runRowAction(row.getItem());
                }
            });
            return row;
        });

        for (String columnName : columns) {
            TableColumn<ResourceTableRow, String> column = new TableColumn<>(columnName);
            column.setPrefWidth(resourceColumnWidth(columnName));
            column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                    data.getValue().value(columnName)));
            column.setCellFactory(tableColumn -> "状态".equals(columnName)
                    ? createResourceStatusCell(table)
                    : createResourceCopyableCell(table));
            table.getColumns().add(column);
        }

        table.setItems(FXCollections.observableArrayList(rows));
        card.getChildren().add(table);
        return card;
    }

    private TableCell<ResourceTableRow, String> createResourceStatusCell(TableView<ResourceTableRow> table) {
        return new TableCell<>() {
            private final Region dot = new Region();
            private final HBox content = new HBox(dot);
            private final Tooltip tooltip = new Tooltip();
            private boolean tooltipInstalled;

            {
                setAlignment(Pos.CENTER);
                content.setAlignment(Pos.CENTER);
                content.getStyleClass().add("k8s-status-cell");
                dot.getStyleClass().add("k8s-status-dot");
                tooltip.setShowDelay(new Duration(200));
                content.setOnMouseClicked(event -> {
                    ResourceTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row == null || row.status() == null || !row.status().eventDetailAvailable()
                            || event.getButton() != MouseButton.PRIMARY) {
                        return;
                    }
                    table.getSelectionModel().clearAndSelect(getIndex());
                    runRowAction(row);
                    event.consume();
                });
                content.setOnContextMenuRequested(event -> {
                    ResourceTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        table.getSelectionModel().clearAndSelect(getIndex());
                        createResourceContextMenu(row).show(content, event.getScreenX(), event.getScreenY());
                    }
                    event.consume();
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                dot.getStyleClass().removeAll("kd-success", "kd-warning", "kd-error", "kd-muted");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                    setGraphic(null);
                    uninstallStatusTooltip();
                    content.setCursor(Cursor.DEFAULT);
                    return;
                }

                ResourceTableRow row = getTableRow().getItem();
                K8sResourceStatus status = row.status() == null
                        ? new K8sResourceStatus(K8sResourceStatus.Level.MUTED, emptyAsDash(item), false)
                        : row.status();
                dot.getStyleClass().add(status.level().cssClass());
                tooltip.setText(status.eventDetailAvailable() ? status.text() + "，点击查看事件详情" : status.text());
                installStatusTooltip();
                content.setCursor(status.eventDetailAvailable() ? Cursor.HAND : Cursor.DEFAULT);
                setText(null);
                setGraphic(content);
            }

            private void installStatusTooltip() {
                if (!tooltipInstalled) {
                    Tooltip.install(content, tooltip);
                    tooltipInstalled = true;
                }
            }

            private void uninstallStatusTooltip() {
                if (tooltipInstalled) {
                    Tooltip.uninstall(content, tooltip);
                    tooltipInstalled = false;
                }
            }
        };
    }

    private TableCell<ResourceTableRow, String> createResourceCopyableCell(TableView<ResourceTableRow> table) {
        return new TableCell<>() {
            private final TextField textField = new TextField();
            private final Tooltip tooltip = new Tooltip();

            {
                setAlignment(Pos.CENTER_LEFT);
                tooltip.setShowDelay(new Duration(200));
                textField.setEditable(false);
                textField.setAlignment(Pos.CENTER_LEFT);
                textField.setMinWidth(0);
                textField.setMaxWidth(Double.MAX_VALUE);
                textField.getStyleClass().add("docker-table-cell-text");
                textField.prefWidthProperty().bind(widthProperty().subtract(12));
                textField.setContextMenu(null);
                textField.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    ResourceTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row == null || event.getButton() != MouseButton.PRIMARY) {
                        return;
                    }
                    MultipleSelectionModel<ResourceTableRow> selectionModel = table.getSelectionModel();
                    int rowIndex = getIndex();
                    if (rowIndex < 0) {
                        return;
                    }
                    if (event.isShortcutDown()) {
                        if (selectionModel.isSelected(rowIndex)) {
                            selectionModel.clearSelection(rowIndex);
                        } else {
                            selectionModel.select(rowIndex);
                        }
                    } else if (event.isShiftDown()) {
                        selectionModel.select(rowIndex);
                    } else if (!selectionModel.isSelected(rowIndex) || selectionModel.getSelectedIndices().size() != 1) {
                        selectionModel.clearAndSelect(rowIndex);
                    }
                });
                textField.setOnContextMenuRequested(event -> {
                    ResourceTableRow row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) {
                        table.getSelectionModel().clearAndSelect(getIndex());
                        createResourceContextMenu(row).show(textField, event.getScreenX(), event.getScreenY());
                    }
                    event.consume();
                });
                widthProperty().addListener((obs, oldValue, newValue) -> updateTooltip());
                textField.fontProperty().addListener((obs, oldValue, newValue) -> updateTooltip());
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                    textField.setTooltip(null);
                    return;
                }
                textField.setText(item == null ? "" : item);
                updateTooltip();
                setText(null);
                setGraphic(textField);
            }

            private void updateTooltip() {
                String value = textField.getText();
                if (value == null || value.isBlank()) {
                    textField.setTooltip(null);
                    return;
                }
                Text helper = new Text(value);
                helper.setFont(textField.getFont());
                double available = Math.max(0, getWidth() - 18);
                if (helper.getLayoutBounds().getWidth() > available) {
                    tooltip.setText(value);
                    textField.setTooltip(tooltip);
                } else {
                    textField.setTooltip(null);
                }
            }
        };
    }

    private ContextMenu createResourceContextMenu(ResourceTableRow row) {
        ContextMenu menu = new ContextMenu();
        if (row == null) {
            return menu;
        }
        for (DetailActionSpec spec : row.actions()) {
            MenuItem item = new MenuItem(spec.label());
            item.setOnAction(event -> {
                if (actionHandler != null) {
                    actionHandler.handle(spec);
                }
            });
            menu.getItems().add(item);
        }
        return menu;
    }

    private void copyResourceTableSelection(TableView<ResourceTableRow> table, List<String> columns) {
        List<ResourceTableRow> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder(String.join("\t", columns));
        for (ResourceTableRow row : selected) {
            builder.append(System.lineSeparator());
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    builder.append('\t');
                }
                builder.append(row.value(columns.get(i)));
            }
        }
        copyText(builder.toString());
    }

    private void copyText(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value == null ? "" : value);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void runRowAction(ResourceTableRow row) {
        if (row == null || actionHandler == null) {
            return;
        }
        for (DetailActionSpec spec : row.actions()) {
            if (Objects.equals(spec.label(), "查看详情")) {
                actionHandler.handle(spec);
                return;
            }
        }
    }

    private double resourceColumnWidth(String columnName) {
        return switch (columnName) {
            case "状态" -> 38;
            case "重启次数", "Count", "类型" -> 90;
            case "名称" -> 220;
            case "Reason", "First Seen", "Last Seen", "创建时间" -> 110;
            case "命名空间", "Cluster IP", "节点", "Source", "Object" -> 140;
            case "镜像", "标签", "Message", "内部端点", "外部端点" -> 260;
            case "CPU 使用量", "内存使用量" -> 160;
            default -> 130;
        };
    }

    private VBox createListCard(String title, List<String> items) {
        VBox card = createCardShell(title);
        ListView<String> listView = new ListView<>(FXCollections.observableArrayList(items));
        listView.getStyleClass().add("detail-list");
        listView.setPrefHeight(Math.min(260, Math.max(96, items.size() * 28 + 16)));
        card.getChildren().add(listView);
        return card;
    }

    private Label createGroupHeader(String title) {
        Label header = new Label(title);
        header.getStyleClass().add("detail-group-header");
        return header;
    }

    private VBox createCardShell(String title) {
        VBox card = new VBox();
        card.getStyleClass().add("detail-card");

        Label header = new Label(title);
        header.getStyleClass().add("detail-card-title");

        card.getChildren().add(header);
        return card;
    }

    private boolean hasDisplayValue(String value) {
        return value != null && !value.isBlank() && !"-".equals(value.trim()) && !"null".equalsIgnoreCase(value.trim());
    }

    private String prettyKind(K8sDetailDtos.ResourceDetailDto data) {
        K8sDetailDtos.TypeMetaDto typeMeta = typeMeta(data);
        if (typeMeta == null || typeMeta.kind() == null) {
            return "Detail";
        }
        return prettyLabel(typeMeta.kind());
    }

    private String displayTitle(K8sDetailDtos.ResourceDetailDto data) {
        K8sDetailDtos.ObjectMetaDto objectMeta = objectMeta(data);
        if (objectMeta == null) {
            return "-";
        }
        String name = objectMeta.name();
        return hasDisplayValue(name) ? name : "-";
    }

    private String displaySubtitle(K8sDetailDtos.ResourceDetailDto data) {
        K8sDetailDtos.ObjectMetaDto objectMeta = objectMeta(data);
        if (objectMeta == null) {
            return "-";
        }
        String namespace = objectMeta.namespace();
        return hasDisplayValue(namespace) ? namespace : "-";
    }

    private String prettyLabel(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ');
        return spaced.substring(0, 1).toUpperCase() + spaced.substring(1);
    }

    private void close() {
        if (stage != null) {
            stage.close();
        }
    }

    public static void show(Window owner,
                            K8sDetailDtos.ResourceDetailDto data,
                            List<DetailActionSpec> actions,
                            DetailActionHandler actionHandler,
                            DetailRefreshHandler detailRefreshHandler) {
        try {
            FXMLLoader loader = new FXMLLoader(K8sDetailController.class.getResource("/fxml/K8sDetailView.fxml"));
            Parent root = loader.load();
            K8sDetailController controller = loader.getController();

            Stage stage = new Stage();
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setTitle(data == null ? "Detail" : controller.displayTitle(data));
            stage.setMinWidth(920);
            stage.setMinHeight(640);
            stage.setWidth(1180);
            stage.setHeight(820);
            stage.setResizable(true);

            Scene scene = new Scene(root);
            ThemeManager.getInstance().registerScene(scene);
            stage.setScene(scene);
            controller.setStage(stage);
            controller.setData(data, actions, actionHandler);
            controller.setAutoRefreshHandler(detailRefreshHandler);

            stage.setOnHidden(e -> {
                controller.stopAutoRefresh();
                ThemeManager.getInstance().unregisterScene(scene);
            });
            if (owner != null) {
                stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
                stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
            }
            stage.show();
        } catch (IOException e) {
            DialogHelper.showError("Error", "Unable to open detail window: " + e.getMessage());
        }
    }

    @FunctionalInterface
    public interface DetailActionHandler {
        void handle(DetailActionSpec actionSpec);
    }

    @FunctionalInterface
    public interface DetailRefreshHandler {
        void refresh(Consumer<K8sDetailDtos.ResourceDetailDto> onData, Runnable onComplete);
    }

    private record ResourceTableRow(String resourceKind,
                                    boolean namespaced,
                                    Map<String, String> values,
                                    List<DetailActionSpec> actions,
                                    K8sResourceStatus status) {
        private String value(String column) {
            return values.getOrDefault(column, "-");
        }
    }

    public record DetailActionSpec(String label,
                                   String hint,
                                   boolean primary,
                                   String resourceKind,
                                   String namespace,
                                   String name,
                                   boolean namespaced) {
        public DetailActionSpec(String label, String hint, boolean primary) {
            this(label, hint, primary, null, null, null, true);
        }

        public boolean hasResourceTarget() {
            return resourceKind != null && !resourceKind.isBlank()
                    && name != null && !name.isBlank()
                    && !"-".equals(name);
        }
    }
}

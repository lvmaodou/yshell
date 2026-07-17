## 一、通用框架结构

所有详情页都遵循以下基本框架：

```
┌─────────────────────────────────────────────────────────────┐
│ 顶部操作栏                                                    │
│  - 操作按钮(编辑/删除/扩缩容/重启等,根据资源类型不同)        │
├─────────────────────────────────────────────────────────────┤
│ 元数据组件                                        │
│  - Name (名称)                                               │
│  - Namespace (命名空间,如适用)                                │
│  - Labels (标签)                                             │
│  - Annotations (注解)                                        │
│  - Creation Timestamp (创建时间)                              │
├─────────────────────────────────────────────────────────────┤
│ 资源信息卡片 (kd-card) - 根据资源类型显示不同属性            │
├─────────────────────────────────────────────────────────────┤
│ 其他卡片/列表 (根据资源类型而定)                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、Workloads 工作负载详情页

### 2.1 Pod 详情页

#### 顶部

- **指标图表** (可选)
    - CPU 使用率
    - 内存使用率

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性                 | 说明            |
|--------------------|---------------|
| Node               | 运行节点(可点击链接)   |
| Status             | Pod状态         |
| IP                 | Pod IP地址      |
| QoS Class          | QoS类别         |
| Restarts           | 重启次数          |
| Service Account    | 服务账户(可点击链接)   |
| Image Pull Secrets | 镜像拉取密钥(可点击链接) |
| Security Context   | 安全上下文         |

#### 条件列表

- 类型、状态、原因、消息、最后探测时间

#### 创建者信息卡片

- 显示创建该Pod的控制器信息

#### PVC列表

- 关联的持久卷声明

#### 事件列表

- 相关事件记录

#### 容器卡片组

每个容器(Containers/Init Containers)显示独立卡片：

| 信息组               | 内容          |
|-------------------|-------------|
| **基本信息**          |             |
| Image             | 容器镜像        |
| **状态信息**          |             |
| Ready             | 是否就绪        |
| Started           | 是否已启动       |
| Reason            | 原因(等待/终止状态) |
| Message           | 消息(等待/终止状态) |
| Exit Code         | 退出码(终止状态)   |
| Signal            | 信号(终止状态)    |
| Started At        | 启动时间(运行状态)  |
| **环境变量**          |             |
| 普通变量              | 变量名和值       |
| Secret引用          | 可点击链接,默认隐藏值 |
| ConfigMap引用       | 可点击链接,默认隐藏值 |
| **命令**            |             |
| Commands          | 命令列表        |
| Arguments         | 参数列表        |
| **挂载**            |             |
| Mounts            | 卷挂载列表       |
| **安全上下文**         |             |
| Security Context  | 容器安全配置      |
| **探针**            |             |
| Liveness Probe    | 存活探针配置      |
| Readiness Probe   | 就绪探针配置      |
| Startup Probe     | 启动探针配置      |
| **资源限制**          |             |
| Resource Limits   | 资源限制        |
| Resource Requests | 资源请求        |

---

### 2.2 Deployment 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性                     | 说明     |
|------------------------|--------|
| Strategy               | 更新策略   |
| Min ready seconds      | 最小就绪秒数 |
| Revision history limit | 历史版本限制 |
| Selector               | 选择器    |

#### 滚动更新策略卡片

| 属性              | 说明     |
|-----------------|--------|
| Max surge       | 最大激增数  |
| Max unavailable | 最大不可用数 |

#### Pods状态卡片

| 属性          | 说明     |
|-------------|--------|
| Updated     | 已更新副本数 |
| Total       | 总副本数   |
| Available   | 可用副本数  |
| Unavailable | 不可用副本数 |

#### 条件列表

- Deployment条件状态

#### 新副本集卡片

| 属性          | 说明      |
|-------------|---------|
| Name        | 名称(可点击) |
| Namespace   | 命名空间    |
| Age         | 年龄      |
| Pods        | Pod数量   |
| Labels      | 标签      |
| Images      | 镜像      |
| Init images | 初始化镜像   |

#### 旧副本集列表

- 历史版本Replica Sets列表

#### HPA列表

- 关联的Horizontal Pod Autoscaler列表

#### 事件列表

- 相关事件

---

### 2.3 DaemonSet 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性          | 说明    |
|-------------|-------|
| Selector    | 标签选择器 |
| Images      | 容器镜像  |
| Init images | 初始化镜像 |

#### Pod状态卡片

- Running/Desired数量
- 状态百分比

#### Pod列表

- 关联的Pod列表

#### Service列表

- 关联的Service列表

#### 事件列表

- 相关事件

---

### 2.4 StatefulSet 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性             | 说明    |
|----------------|-------|
| Label Selector | 标签选择器 |
| Images         | 容器镜像  |
| Init images    | 初始化镜像 |

#### Pod状态卡片

- Running/Desired数量

#### Pod列表

- 关联的Pod列表

#### 事件列表

- 相关事件

---

### 2.5 ReplicaSet 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性          | 说明    |
|-------------|-------|
| Selector    | 选择器   |
| Images      | 容器镜像  |
| Init images | 初始化镜像 |

#### Pod状态卡片

- Running/Desired数量

#### Pod列表

- 关联的Pod列表

#### Service列表

- 关联的Service列表

#### 事件列表

- 相关事件

---

### 2.6 Job 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性          | 说明    |
|-------------|-------|
| Completions | 完成数   |
| Parallelism | 并行数   |
| Images      | 容器镜像  |
| Init images | 初始化镜像 |

#### 条件列表

- Job条件状态

#### Pod状态卡片

- Running/Desired数量

#### Pod列表

- 关联的Pod列表

#### 事件列表

- 相关事件

---

### 2.7 CronJob 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性                        | 说明     |
|---------------------------|--------|
| Schedule                  | 定时规则   |
| Active Jobs               | 活跃任务数  |
| Suspend                   | 是否暂停   |
| Last schedule             | 最后调度时间 |
| Concurrency policy        | 并发策略   |
| Starting deadline seconds | 启动截止秒数 |

#### 活跃任务列表

- 当前活跃的Jobs列表

#### 非活跃任务列表

- 历史Jobs列表

#### 事件列表

- 相关事件

---

## 三、Service 服务发现详情页

### 3.1 Service 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性               | 说明    |
|------------------|-------|
| Type             | 服务类型  |
| Cluster IP       | 集群IP  |
| Session Affinity | 会话亲和性 |
| Selector         | 选择器   |

#### 端点卡片列表

- 内部端点
- 外部端点

#### Pod列表

- 关联的Pod列表

#### Ingress列表

- 关联的Ingress列表

#### 事件列表

- 相关事件

---

### 3.2 Ingress 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性                  | 说明        |
|---------------------|-----------|
| Ingress Class Name  | Ingress类名 |
| Endpoints           | 端点列表      |
| **Default Backend** | 默认后端      |
| Service Name        | 服务名称      |
| Service Port Name   | 服务端口名     |
| Service Port Number | 服务端口号     |
| Resource Kind       | 资源类型      |
| Resource Name       | 资源名称      |

#### Ingress规则卡片列表

- 规则详情
- TLS配置

#### 事件列表

- 相关事件

---

### 3.3 Ingress Class 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性         | 说明  |
|------------|-----|
| Controller | 控制器 |

---

## 四、Config and Storage 配置存储详情页

### 4.1 ConfigMap 详情页

#### 元数据部分

- 标准元数据

#### 数据卡片

- JSON格式的只读文本框显示所有数据
- 无数据时显示"There is no data to display"

---

### 4.2 Secret 详情页

#### 元数据部分

- 标准元数据

#### 数据卡片

- 每个密钥一行
- **默认隐藏**: 显示字节数
- **点击显示**: Base64解码后的内容
- **可编辑**: 每个密钥可单独编辑

---

### 4.3 Persistent Volume Claim 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性            | 说明        |
|---------------|-----------|
| Status        | 状态        |
| Storage Class | 存储类       |
| Volume Name   | 卷名(可点击链接) |
| Capacity      | 容量        |
| Access Modes  | 访问模式      |

---

### 4.4 Storage Class 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性          | 说明       |
|-------------|----------|
| Provisioner | 供应者      |
| Parameters  | 参数列表(多个) |

#### 持久卷列表

- 使用该存储类的PV列表

---

## 五、Cluster 集群详情页

### 5.1 Node 详情页

#### 顶部

- **指标图表**
    - CPU使用率
    - 内存使用率

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性            | 说明       |
|---------------|----------|
| Phase         | 阶段       |
| Pod CIDR      | Pod CIDR |
| Provider ID   | 提供者ID    |
| Unschedulable | 是否不可调度   |
| Addresses     | 地址列表     |
| Taints        | 污点列表     |

#### 系统信息卡片

| 属性                        | 说明           |
|---------------------------|--------------|
| Machine ID                | 机器ID         |
| System UUID               | 系统UUID       |
| Boot ID                   | 启动ID         |
| Kernel version            | 内核版本         |
| OS Image                  | 操作系统镜像       |
| Container runtime version | 容器运行时版本      |
| kubelet version           | kubelet版本    |
| kube-proxy version        | kube-proxy版本 |
| Operating system          | 操作系统         |
| Architecture              | 架构           |
| CPU capacity              | CPU容量        |
| Memory capacity           | 内存容量         |
| Pods capacity             | Pod容量        |

#### 分配卡片

- **CPU分配饼图**
    - 已请求
    - 可分配
- **Memory分配饼图**
    - 已请求
    - 可分配
- **Pods分配饼图**
    - 已分配
    - 容量

#### 条件列表

- 节点条件状态

#### Pod列表

- 运行在该节点的Pod列表

#### 事件列表

- 相关事件

---

### 5.2 Namespace 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性     | 说明 |
|--------|----|
| Status | 状态 |

#### 资源配额列表

- 配额项目
- 使用情况

#### 资源限制列表

- 限制项目
- 配置值

#### 事件列表

- 相关事件

---

### 5.3 Persistent Volume 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性              | 说明           |
|-----------------|--------------|
| Status          | 状态           |
| Claim           | 关联PVC(可点击链接) |
| Reclaim policy  | 回收策略         |
| Storage class   | 存储类          |
| Reason          | 原因           |
| Message         | 消息           |
| Mount Option(s) | 挂载选项         |
| Access modes    | 访问模式         |

#### PV源组件

- 根据不同类型显示(NFS/iSCSI/HostPath等)

#### 容量卡片(表格)

| 列             | 内容   |
|---------------|------|
| Resource name | 资源名称 |
| Quantity      | 数量   |

---

### 5.4 Role / Cluster Role 详情页

#### 元数据部分

- 标准元数据

#### 策略规则列表(表格)

| 列                 | 内容     |
|-------------------|--------|
| Resources         | 资源类型   |
| Non-Resource URLs | 非资源URL |
| Resource Names    | 资源名称   |
| Verbs             | 操作动词   |
| API Groups        | API组   |

---

### 5.5 Role Binding / Cluster Role Binding 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性             | 说明           |
|----------------|--------------|
| Role Reference | 引用的角色(可点击链接) |

#### 主体列表(表格)

| 列         | 内容   |
|-----------|------|
| Kind      | 类型   |
| Name      | 名称   |
| Namespace | 命名空间 |

---

### 5.6 Service Account 详情页

#### 元数据部分

- 标准元数据

#### Secret列表

- 关联的Secret列表

#### Image Pull Secret列表

- 关联的镜像拉取Secret列表

---

### 5.7 Network Policy 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性           | 说明     |
|--------------|--------|
| Pod Selector | Pod选择器 |
| Policy Types | 策略类型   |

#### Ingress规则卡片

- YAML格式的只读文本框显示规则

#### Egress规则卡片

- YAML格式的只读文本框显示规则

---

### 5.8 Event 详情页

#### 元数据部分

- 标准元数据

#### 资源信息卡片

| 属性      | 说明   |
|---------|------|
| Source  | 事件来源 |
| Count   | 次数   |
| Type    | 类型   |
| Reason  | 原因   |
| Message | 消息   |

---

## 七、通用子组件详解

### 7.1 条件列表组件

表格显示:
| 列 | 内容 |
|----|------|
| Type | 条件类型 |
| Status | 状态 |
| Reason | 原因 |
| Message | 消息 |
| Last Probe Time | 最后探测时间(可选) |

### 7.2 事件列表组件

表格显示:
| 列 | 内容 |
|----|------|
| 图标 | 事件类型图标 |
| Source | 来源 |
| Type | 类型 |
| Count | 次数 |
| Age | 年龄 |
| Message | 消息 |

### 7.3 探针卡片组件

显示探针配置:

- 探测类型(HTTP/TCP/Exec)
- 初始延迟秒数
- 超时秒数
- 周期秒数
- 成功阈值
- 失败阈值
- 具体探测参数

### 7.4 安全上下文组件

显示安全配置:

- runAsUser
- runAsGroup
- runAsNonRoot
- readOnlyRootFilesystem
- allowPrivilegeEscalation
- capabilities
- seLinuxOptions
- seccompProfile

---

## 八、详情页可用操作

### 通用操作

| 操作     | 适用资源 |
|--------|------|
| 编辑资源   | 所有资源 |
| 删除资源   | 所有资源 |
| 固定到侧边栏 | 所有资源 |

### 工作负载特有操作

| 操作   | 适用资源                                |
|------|-------------------------------------|
| 查看日志 | Pod, Job, Deployment等               |
| 执行命令 | Pod                                 |
| 扩缩容  | Deployment, ReplicaSet, StatefulSet |
| 重启   | Deployment, DaemonSet, StatefulSet  |
| 触发   | CronJob                             |

---

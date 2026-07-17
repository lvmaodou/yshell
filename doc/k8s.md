## 左侧导航菜单结构

1.Workloads (工作负载)

- Cron Jobs - 定时任务
- Daemon Sets - 守护进程集
- Deployments - 部署
- Jobs - 任务
- Pods - Pod
- Replica Sets - 副本集
- Replication Controllers - 副本控制器
- Stateful Sets - 有状态集

2.Service (服务发现)

- Ingresses - 入口
- Ingress Classes - 入口类
- Services - 服务

3.Config and Storage (配置与存储)

- Config Maps - 配置映射
- Persistent Volume Claims - 持久卷声明
- Secrets - 密钥
- Storage Classes - 存储类

4.Cluster (集群)

- Cluster Role Bindings - 集群角色绑定
- Cluster Roles - 集群角色
- Events - 事件
- Namespaces - 命名空间
- Network Policies - 网络策略
- Nodes - 节点
- Persistent Volumes - 持久卷
- Role Bindings - 角色绑定
- Roles - 角色
- Service Accounts - 服务账户

---

## 一、Workloads (工作负载)

### 1.1 Cron Jobs - 定时任务

#### 列表字段

| 列名            | 说明             |
|---------------|----------------|
| 状态图标          | 显示 Cron Job 状态 |
| Name          | 定时任务名称         |
| Namespace     | 命名空间           |
| Images        | 容器镜像列表         |
| Labels        | 标签             |
| Schedule      | 定时规则(Cron表达式)  |
| Suspend       | 是否暂停           |
| Active        | 活跃任务数          |
| Last Schedule | 最后调度时间         |
| Created       | 创建时间           |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Cron Job
- **触发执行**: 手动触发定时任务立即执行一次

#### 触发执行操作界面

- **对话框标题**: "Trigger a Cron Job"
- **显示内容**:
    - Cron Job 名称和命名空间
    - 提示信息："A new Job will be created based on this Cron Job"
- **命令等价**: 显示等效的 kubectl 命令
- **操作按钮**: Trigger / Cancel

---

### 1.2 Daemon Sets - 守护进程集

#### 列表字段

| 列名        | 说明            |
|-----------|---------------|
| 状态图标      | 显示状态(可展开查看事件) |
| Name      | 守护进程集名称(可点击)  |
| Namespace | 命名空间          |
| Images    | 容器镜像列表        |
| Labels    | 标签            |
| Pods      | 运行Pod数/期望Pod数 |
| Created   | 创建时间          |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Daemon Set
- **重启**: 滚动重启所有 Pod

#### 重启操作界面

- **对话框标题**: "Restart a resource"
- **显示内容**:
    - Daemon Set 名称和命名空间
    - 提示信息："will be restarted"
- **命令等价**: `kubectl rollout restart -n <namespace> daemonset <name>`
- **操作按钮**: Restart / Cancel

---

### 1.3 Deployments - 部署

#### 列表字段

| 列名        | 说明            |
|-----------|---------------|
| 状态图标      | 显示状态(可展开查看事件) |
| Name      | 部署名称(可点击)     |
| Namespace | 命名空间          |
| Images    | 容器镜像列表        |
| Labels    | 标签            |
| Pods      | 运行Pod数/期望Pod数 |
| Created   | 创建时间          |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **查看日志**: 查看容器日志
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Deployment
- **扩缩容**: 调整副本数量
- **重启**: 滚动重启

#### 扩缩容操作界面

- **对话框标题**: "Scale a resource"
- **显示内容**:
    - Deployment 名称和命名空间
    - 输入框：
        - Desired replicas(期望副本数): 可编辑的数字输入框
        - Actual replicas(实际副本数): 显示当前值(只读)
- **命令等价**: `kubectl scale -n <namespace> deployment <name> --replicas=<数量>`
- **操作按钮**: Scale / Cancel

---

### 1.4 Jobs - 任务

#### 列表字段

| 列名        | 说明            |
|-----------|---------------|
| 状态图标      | 显示状态(可展开查看事件) |
| Name      | 任务名称(可点击)     |
| Namespace | 命名空间          |
| Images    | 容器镜像列表        |
| Labels    | 标签            |
| Pods      | 运行Pod数/期望Pod数 |
| Created   | 创建时间          |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **查看日志**: 查看任务日志
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Job

---

### 1.5 Pods - Pod

#### 列表字段

| 列名           | 说明            |
|--------------|---------------|
| 状态图标         | 显示状态(可展开查看事件) |
| Name         | Pod名称(可点击)    |
| Namespace    | 命名空间          |
| Images       | 容器镜像列表        |
| Labels       | 标签            |
| Node         | 运行节点          |
| Status       | 状态            |
| Restarts     | 重启次数          |
| CPU Usage    | CPU使用率        |
| Memory Usage | 内存使用率         |
| Created      | 创建时间          |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **查看日志**: 查看容器日志
- **执行命令**: 进入容器终端
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Pod

#### 查看日志操作界面

- **顶部工具栏**:
    - 容器选择下拉框(Containers / Init Containers)
    - Pod选择下拉框(多个Pod时)
    - 下载日志按钮
    - 更多操作菜单
- **日志显示区域**:
    - 滚动显示日志内容
    - 支持ANSI颜色代码过滤
    - 支持紧凑/宽松显示模式
    - 自动滚动到最新日志
- **底部工具栏**:
    - 日志时间范围显示
    - 刷新/暂停按钮
    - 从头显示/从尾部显示按钮
    - 搜索过滤功能

#### 执行命令操作界面

- **显示终端界面**:
    - 黑色背景的终端窗口
    - 显示容器名称和 Pod 信息
    - 支持 xterm 终端模拟
    - 实时输入输出交互

---

### 1.6 Replica Sets - 副本集

#### 列表字段

| 列名        | 说明            |
|-----------|---------------|
| 状态图标      | 显示状态(可展开查看事件) |
| Name      | 副本集名称(可点击)    |
| Namespace | 命名空间          |
| Images    | 容器镜像列表        |
| Labels    | 标签            |
| Pods      | 运行Pod数/期望Pod数 |
| Created   | 创建时间          |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Replica Set
- **扩缩容**: 调整副本数量

---

### 1.7 Replication Controllers - 副本控制器

#### 列表字段

与 Replica Sets 类似

#### 可用操作

- 查看详情、编辑、删除、扩缩容

---

### 1.8 Stateful Sets - 有状态集

#### 列表字段

| 列名        | 说明            |
|-----------|---------------|
| 状态图标      | 显示状态(可展开查看事件) |
| Name      | 有状态集名称(可点击)   |
| Namespace | 命名空间          |
| Images    | 容器镜像列表        |
| Labels    | 标签            |
| Pods      | 运行Pod数/期望Pod数 |
| Created   | 创建时间          |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Stateful Set
- **扩缩容**: 调整副本数量
- **重启**: 滚动重启

---

## 二、Service (服务发现)

### 2.1 Ingresses - 入口

#### 列表字段

| 列名        | 说明             |
|-----------|----------------|
| Name      | Ingress名称(可点击) |
| Namespace | 命名空间           |
| Labels    | 标签             |
| Endpoints | 外部端点链接         |
| Hosts     | 主机名列表          |
| Created   | 创建时间           |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Ingress

---

### 2.2 Ingress Classes - 入口类

#### 列表字段

| 列名         | 说明                   |
|------------|----------------------|
| Name       | Ingress Class名称(可点击) |
| Controller | 控制器                  |
| Created    | 创建时间                 |

#### 可用操作

- 查看详情、编辑、删除

---

### 2.3 Services - 服务

#### 列表字段

| 列名                 | 说明        |
|--------------------|-----------|
| 状态图标               | 显示服务状态    |
| Name               | 服务名称(可点击) |
| Namespace          | 命名空间      |
| Labels             | 标签        |
| Type               | 服务类型      |
| Cluster IP         | 集群IP      |
| Internal Endpoints | 内部端点      |
| External Endpoints | 外部端点      |
| Created            | 创建时间      |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Service

---

## 三、Config and Storage (配置与存储)

### 3.1 Config Maps - 配置映射

#### 列表字段

| 列名        | 说明          |
|-----------|-------------|
| Name      | 配置映射名称(可点击) |
| Namespace | 命名空间        |
| Labels    | 标签          |
| Created   | 创建时间        |

#### 可用操作

- **查看详情**: 点击名称进入详情页(显示配置数据)
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Config Map

---

### 3.2 Persistent Volume Claims - 持久卷声明

#### 列表字段

| 列名            | 说明            |
|---------------|---------------|
| 状态图标          | 显示状态          |
| Name          | PVC名称(可点击)    |
| Namespace     | 命名空间          |
| Labels        | 标签            |
| Status        | 状态            |
| Volume        | 关联的持久卷(可点击链接) |
| Capacity      | 容量            |
| Access Modes  | 访问模式          |
| Storage Class | 存储类           |
| Created       | 创建时间          |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 PVC

---

### 3.3 Secrets - 密钥

#### 列表字段

| 列名        | 说明        |
|-----------|-----------|
| Name      | 密钥名称(可点击) |
| Namespace | 命名空间      |
| Labels    | 标签        |
| Type      | 密钥类型      |
| Created   | 创建时间      |

#### 可用操作

- **查看详情**: 点击名称进入详情页(密钥数据默认隐藏,需点击显示)
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除 Secret

---

### 3.4 Storage Classes - 存储类

#### 列表字段

| 列名          | 说明         |
|-------------|------------|
| Name        | 存储类名称(可点击) |
| Provisioner | 供应者        |
| Parameters  | 参数         |
| Created     | 创建时间       |

#### 可用操作

- 查看详情、编辑、删除

---

## 四、Cluster (集群)

### 4.1 Cluster Role Bindings - 集群角色绑定

#### 列表字段

| 列名       | 说明          |
|----------|-------------|
| Name     | 角色绑定名称(可点击) |
| Labels   | 标签          |
| Role Ref | 引用的角色       |
| Subjects | 主体列表        |
| Created  | 创建时间        |

#### 可用操作

- 查看详情、编辑、删除

---

### 4.2 Cluster Roles - 集群角色

#### 列表字段

| 列名      | 说明          |
|---------|-------------|
| Name    | 集群角色名称(可点击) |
| Labels  | 标签          |
| Created | 创建时间        |

#### 可用操作

- 查看详情、编辑、删除

---

### 4.3 Events - 事件

#### 列表字段

| 列名        | 说明     |
|-----------|--------|
| 状态图标      | 事件类型图标 |
| Name      | 事件名称   |
| Namespace | 命名空间   |
| Source    | 事件来源   |
| Type      | 类型     |
| Age       | 年龄     |
| Message   | 消息内容   |

#### 可用操作

- 查看详情(查看完整事件信息)

---

### 4.4 Namespaces - 命名空间

#### 列表字段

| 列名      | 说明          |
|---------|-------------|
| 状态图标    | 显示状态        |
| Name    | 命名空间名称(可点击) |
| Labels  | 标签          |
| Phase   | 阶段状态        |
| Created | 创建时间        |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除命名空间

---

### 4.5 Network Policies - 网络策略

#### 列表字段

| 列名        | 说明          |
|-----------|-------------|
| Name      | 网络策略名称(可点击) |
| Namespace | 命名空间        |
| Labels    | 标签          |
| Created   | 创建时间        |

#### 可用操作

- 查看详情、编辑、删除

---

### 4.6 Nodes - 节点

#### 列表字段

| 列名              | 说明            |
|-----------------|---------------|
| 状态图标            | 显示节点状态        |
| Name            | 节点名称(可点击)     |
| Labels          | 标签            |
| Ready           | 就绪状态          |
| CPU requests    | CPU请求(核数及百分比) |
| CPU limits      | CPU限制(核数及百分比) |
| CPU capacity    | CPU容量(核数)     |
| Memory requests | 内存请求(字节及百分比)  |
| Memory limits   | 内存限制(字节及百分比)  |
| Memory capacity | 内存容量(字节)      |
| Pods            | 已分配Pod数(及百分比) |
| Created         | 创建时间          |

#### 可用操作

- **查看详情**: 点击名称进入详情页
- **编辑资源**: 编辑 YAML/JSON 配置
- **删除资源**: 删除节点(需谨慎)

---

### 4.7 Persistent Volumes - 持久卷

#### 列表字段

| 列名            | 说明         |
|---------------|------------|
| 状态图标          | 显示状态       |
| Name          | 持久卷名称(可点击) |
| Labels        | 标签         |
| Status        | 状态         |
| Claim         | 关联的PVC     |
| Capacity      | 容量         |
| Access Modes  | 访问模式       |
| Storage Class | 存储类        |
| Created       | 创建时间       |

#### 可用操作

- 查看详情、编辑、删除

---

### 4.8 Role Bindings - 角色绑定

#### 列表字段

与 Cluster Role Bindings 类似

#### 可用操作

- 查看详情、编辑、删除

---

### 4.9 Roles - 角色

#### 列表字段

| 列名        | 说明        |
|-----------|-----------|
| Name      | 角色名称(可点击) |
| Namespace | 命名空间      |
| Labels    | 标签        |
| Created   | 创建时间      |

#### 可用操作

- 查看详情、编辑、删除

---

### 4.10 Service Accounts - 服务账户

#### 列表字段

| 列名        | 说明          |
|-----------|-------------|
| Name      | 服务账户名称(可点击) |
| Namespace | 命名空间        |
| Labels    | 标签          |
| Created   | 创建时间        |

#### 可用操作

- 查看详情、编辑、删除

---

## 五、通用操作界面结构详解

### 5.1 编辑资源对话框

- **对话框标题**: "Edit a resource"
- **内容区域**:
    - 格式切换按钮: YAML / JSON
    - 代码编辑器: 显示完整资源配置
    - 提示信息: "This action is equivalent to: kubectl apply -f <spec.yaml>"
- **操作按钮**: Update / Cancel

### 5.2 删除资源对话框

- **对话框标题**: "Delete a resource"
- **内容区域**:
    - 确认消息: "Are you sure you want to delete <type> <name> in namespace <namespace>?"
    - 传播策略选择: Background / Foreground / Orphan
    - 立即删除选项: "Delete now (sets delete grace period to 1 second)"
    - 提示信息: 显示等效的 kubectl delete 命令
- **操作按钮**: Delete / Cancel

### 5.3 扩缩容对话框

- **对话框标题**: "Scale a resource"
- **内容区域**:
    - 资源信息显示
    - Desired replicas: 期望副本数输入框(min=0)
    - Actual replicas: 当前副本数显示(只读)
    - 提示信息: 显示等效的 kubectl scale 命令
- **操作按钮**: Scale / Cancel

### 5.4 重启资源对话框

- **对话框标题**: "Restart a resource"
- **内容区域**:
    - 确认消息: "<type> <name> in namespace <namespace> will be restarted"
    - 提示信息: 显示等效的 kubectl rollout restart 命令
- **操作按钮**: Restart / Cancel

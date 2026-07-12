# YShell - JavaFX SSH 客户端

一个类似 FinalShell 的开源 SSH 客户端，使用 Java 和 JavaFX 技术实现。

## 功能特性

- ✅ SSH 远程连接（支持密码和私钥认证）
- ✅ 交互式终端模拟器
- ✅ SFTP 文件浏览器
- ✅ 服务器监控（CPU、内存、磁盘使用率）
- ✅ 多标签页界面
- ✅ 服务器配置管理
- ✅ 会话日志记录
- ✅ 服务器搜索功能
- ✅ 服务器分组管理
- ✅ 批量命令执行
- ✅ 多种主题支持（亮色/暗色主题）

## 技术栈

- Java 26
- JavaFX 26
- JSch（SSH 库）
- ControlsFX
- Gson
- Maven

## 项目结构

```
yshell/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/yshell/
│   │   │   ├── core/           # 核心应用类
│   │   │   ├── ui/             # 界面控制器
│   │   │   ├── ssh/            # SSH 连接模块
│   │   │   ├── sftp/           # SFTP 传输模块
│   │   │   ├── monitor/        # 服务器监控模块
│   │   │   ├── model/          # 数据模型
│   │   │   └── utils/          # 工具类
│   │   └── resources/
│   │       ├── fxml/           # FXML 界面文件
│   │       └── css/            # 样式文件
```

## 快速开始

### 环境要求

- JDK 26
- Maven 3.6+

### 编译运行

```bash
cd yshell
mvn clean package
mvn javafx:run
```

## 使用说明

### 添加服务器

1. 点击左侧的「添加」按钮
2. 填写服务器信息：
   - 服务器名称
   - 主机地址
   - 端口（默认 22）
   - 用户名
   - 密码或私钥路径
   - 分组（可选）
   - 备注（可选）
3. 点击「保存」

### 连接服务器

1. 在服务器列表中双击要连接的服务器
2. 系统会自动建立 SSH 连接并开始监控
3. 连接后可以在「终端」标签页中执行命令
4. 在「SFTP 文件管理器」标签页中管理文件

### 服务器分组管理

- 添加服务器时可以指定分组
- 服务器会按分组显示在树形视图中
- 可以使用搜索功能快速找到服务器

### 批量命令执行

1. 点击菜单「工具 -> 批量命令」
2. 选择要执行命令的服务器（支持分组选择和全选）
3. 输入要执行的命令
4. 点击「执行」，查看各服务器的执行结果

### 主题切换

- 点击菜单「主题」
- 选择「亮色主题」或「暗色主题」
- 主题设置会自动保存

### 服务器监控

连接成功后，左侧下方会实时显示：
- CPU 使用率
- 内存使用率
- 磁盘使用率

### 会话日志

所有终端会话都会自动记录日志，保存位置为用户目录下的 `.yshell/logs/` 文件夹中。

## 核心模块说明

### SSH 模块 (com.yshell.ssh)

- `SSHClient`：主要的 SSH 连接类，负责管理 SSH 会话
- 支持密码认证和密钥认证

### SFTP 模块 (com.yshell.sftp)

- `SftpClient`：SFTP 文件传输客户端
- 支持文件上传、下载、删除、重命名等操作
- 目录浏览和管理

### 监控模块 (com.yshell.monitor)

- `ServerMonitor`：服务器监控类
- 定期获取 CPU、内存、磁盘使用率
- 通过执行 shell 命令获取系统信息

### UI 模块 (com.yshell.ui)

- `MainController`：主界面控制器
- `ServerDialogController`：服务器配置对话框控制器
- `TerminalComponent`：终端模拟器组件
- `SftpFileBrowser`：SFTP 文件浏览器组件
- `BatchCommandController`：批量命令执行对话框控制器

### 工具模块 (com.yshell.utils)

- `ConfigManager`：配置管理器，负责服务器配置的保存和加载
- `SessionLogger`：会话日志记录器
- `SettingsManager`：应用设置管理器（主题等）

## 开发计划

- [x] 基础 SSH 连接功能
- [x] 交互式终端
- [x] SFTP 文件管理器
- [x] 服务器监控
- [x] 会话日志
- [x] 服务器分组管理
- [x] 批量命令执行
- [x] 多种主题支持
- [ ] 端口转发
- [ ] 快捷键设置
- [ ] 更多主题

## 许可证

MIT License

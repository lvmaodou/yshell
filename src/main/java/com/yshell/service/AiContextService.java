package com.yshell.service;

import com.yshell.model.ConnInfo;
import com.yshell.model.docker.DockerSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AiContextService {
    private static final AiContextService INSTANCE = new AiContextService();
    private static final Duration CONTEXT_COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final DateTimeFormatter CURRENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final Map<String, String> contextByConnection = new ConcurrentHashMap<>();

    private AiContextService() {
        ConnectionManager.getInstance().addOnConnectionClosedListener(contextByConnection::remove);
    }

    public static AiContextService getInstance() {
        return INSTANCE;
    }

    public String buildSystemPrompt(String connId) {
        String currentTimePrompt = "- 当前时间（本地时区）：" + CURRENT_TIME_FORMAT.format(Instant.now()) + "\n";
        return "你是 YShell 内置的 Linux 运维命令问答助手。\n"
                + "主要回答 Linux、Shell、Docker、Kubernetes 相关命令的查询、解释、排错和示例。\n"
                + "请使用和用户相同的语言回答。回答时优先给出可直接复制的命令，再给必要解释。\n"
                + "涉及删除、覆盖、重启、停机、sudo、chmod、chown、防火墙、磁盘、集群变更等高风险操作时，必须明确风险，并优先给出更保守的检查命令。\n"
                + "不要声称已经执行命令；当前上下文只来自客户端在连接后采集的信息。\n\n"
                + "当前连接上下文：\n"
                + currentTimePrompt
                + cachedContext(connId);
    }

    private String cachedContext(String connId) {
        if (connId == null || connId.isBlank()) {
            return connectionContext(null) + '\n'
                    + "- Linux 系统：未连接\n"
                    + "- Docker：未连接\n"
                    + "- Kubernetes：未连接\n";
        }
        return contextByConnection.computeIfAbsent(connId, this::queryContext);
    }

    private String queryContext(String connId) {
        return connectionContext(connId) + '\n'
                + linuxContext(connId) + '\n'
                + dockerContext(connId) + '\n'
                + k8sContext(connId) + '\n';
    }

    private String connectionContext(String connId) {
        ConnInfo connInfo = ConnectionManager.getInstance().getCurrentConnection();
        if (connId == null || connInfo == null) {
            return "- SSH 连接：未连接";
        }
        String name = blankTo(connInfo.getName(), "未命名");
        String host = blankTo(connInfo.getHost(), "");
        String user = blankTo(connInfo.getUserName(), "");
        int port = connInfo.getPort() > 0 ? connInfo.getPort() : 22;
        return "- SSH 连接：" + name + " (" + user + "@" + host + ":" + port + ")";
    }

    private String linuxContext(String connId) {
        SshService ssh = ConnectionManager.getInstance().getConnectionById(connId);
        if (ssh == null || !ssh.isConnected() || ssh.isExecAvailable()) {
            return "- Linux 系统：当前连接不可执行远程查询";
        }
        String command = "sh -lc " + shellQuote(
                "printf 'Distro: '; "
                        + "(cat /etc/os-release 2>/dev/null | awk -F= '$1==\"PRETTY_NAME\"{gsub(/\\\"/,\"\",$2); print $2; found=1} END{if(!found) print \"unknown\"}'); "
                        + "printf 'Kernel: '; uname -r 2>/dev/null || true; "
                        + "printf 'Arch: '; uname -m 2>/dev/null || true; "
                        + "printf 'Shell: '; printf '%s\\n' \"$SHELL\"");
        SshService.CommandResult result = ssh.executeRemoteCommand(command, CONTEXT_COMMAND_TIMEOUT);
        if (!result.isSuccess() || result.stdout().isBlank()) {
            return "- Linux 系统：查询失败";
        }
        return "- Linux 系统：\n" + indent(result.stdout().trim());
    }

    private String dockerContext(String connId) {
        DockerSnapshot snapshot = DockerSessionManager.getInstance().getCachedSnapshot(connId);
        if (snapshot != null) {
            if (!snapshot.dockerAvailable()) {
                return "- Docker：不可用" + suffix(snapshot.errorMessage());
            }
            return "- Docker：Server " + blankTo(snapshot.serverVersion(), "unknown")
                    + ", API " + blankTo(snapshot.apiVersion(), "unknown")
                    + ", " + blankTo(snapshot.os(), "unknown") + "/" + blankTo(snapshot.arch(), "unknown")
                    + ", containers " + snapshot.runningContainers() + "/" + snapshot.totalContainers()
                    + ", images " + snapshot.imageCount();
        }
        SshService ssh = ConnectionManager.getInstance().getConnectionById(connId);
        if (ssh == null || !ssh.isConnected() || ssh.isExecAvailable()) {
            return "- Docker：未查询";
        }
        String command = "sh -lc " + shellQuote("command -v docker >/dev/null 2>&1 && docker version --format 'Server {{.Server.Version}}, API {{.Server.APIVersion}}, {{.Server.Os}}/{{.Server.Arch}}' 2>/dev/null || printf 'not available'");
        SshService.CommandResult result = ssh.executeRemoteCommand(command, CONTEXT_COMMAND_TIMEOUT);
        String output = result.stdout() == null ? "" : result.stdout().trim();
        return "- Docker：" + (output.isBlank() ? "未查询" : output);
    }

    private String k8sContext(String connId) {
        K8sSessionManager.K8sSnapshot snapshot = K8sSessionManager.getInstance().getCachedSnapshot(connId);
        if (snapshot != null) {
            if (!snapshot.kubectlAvailable()) {
                return "- Kubernetes：kubectl 不可用" + suffix(snapshot.errorMessage());
            }
            return "- Kubernetes：kubectl client " + blankTo(snapshot.clientVersion(), "unknown")
                    + ", server " + blankTo(snapshot.serverVersion(), "unknown")
                    + ", namespaces " + String.join(",", snapshot.namespaces());
        }
        SshService ssh = ConnectionManager.getInstance().getConnectionById(connId);
        if (ssh == null || !ssh.isConnected() || ssh.isExecAvailable()) {
            return "- Kubernetes：未查询";
        }
        String command = "sh -lc " + shellQuote("command -v kubectl >/dev/null 2>&1 && (kubectl version --client=true 2>/dev/null; kubectl version 2>/dev/null | sed -n '1,4p') || printf 'not available'");
        SshService.CommandResult result = ssh.executeRemoteCommand(command, CONTEXT_COMMAND_TIMEOUT);
        String output = result.stdout() == null ? "" : result.stdout().trim();
        return "- Kubernetes：" + (output.isBlank() ? "未查询" : "\n" + indent(output));
    }

    private String suffix(String message) {
        return message == null || message.isBlank() ? "" : "：" + message;
    }

    private String indent(String value) {
        return "  " + value.replace("\n", "\n  ");
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

package com.yshell.service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class CommandExecutionService {

    private static CommandExecutionService instance;

    public static synchronized CommandExecutionService getInstance() {
        if (instance == null) {
            instance = new CommandExecutionService();
        }
        return instance;
    }

    private CommandExecutionService() {
    }

    public int executeCurrent(String command) {
        SshService service = ConnectionManager.getInstance().getCurrentSshService();
        return execute(service, command) ? 1 : 0;
    }

    public int executeAll(String command) {
        int count = 0;
        Set<SshService> visited = new HashSet<>();
        for (SshService service : ConnectionManager.getInstance().getAllConnections().values()) {
            if (service != null && visited.add(service) && execute(service, command)) {
                count++;
            }
        }
        return count;
    }

    private boolean execute(SshService service, String command) {
        if (service == null || !service.isConnected() || !service.isShellOpen()) {
            return false;
        }
        String text = normalizeCommand(command);
        if (text.isBlank()) {
            return false;
        }
        service.writeToShell(text.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String normalized = command.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }
}

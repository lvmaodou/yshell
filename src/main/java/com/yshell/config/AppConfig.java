package com.yshell.config;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    public int version = 1;
    public Appearance appearance = new Appearance();
    public Update update = new Update();
    public Terminal terminal = new Terminal();
    public Editor editor = new Editor();
    public Transfer transfer = new Transfer();
    public Commands commands = new Commands();
    public Layout layout = new Layout();
    public Ai ai = new Ai();
    public Docker docker = new Docker();

    public static class Appearance {
        public String theme = "vs-dark";
    }

    public static class Update {
        public boolean startupCheckEnabled = true;
        public boolean startupPromptSuppressed = false;
    }

    public static class Terminal {
        public int defaultFontSize = 12;
        public String defaultEncoding = "UTF-8";
        public int defaultBackspaceSequence = 1;
        public int defaultDeleteSequence = 0;
        public int scrollbackLines = 1000;
    }

    public static class Editor {
        public int defaultFontSize = 13;
    }

    public static class Transfer {
        public String defaultDownloadDirectory =
                Paths.get(System.getProperty("user.home"), "Downloads", "Yshell").toString();
        public String uploadChooserDirectory = System.getProperty("user.home");
        public String duplicateStrategy = "ASK";
        public boolean closeQueueWhenFinished = false;
        public boolean clearFinishedWhenDone = false;
    }

    public static class Commands {
        public boolean seeded = false;
    }

    public static class Layout {
        public Double windowX = null;
        public Double windowY = null;
        public double windowWidth = 1200;
        public double windowHeight = 800;
        public boolean windowMaximized = false;
        public Double mainDividerPosition = null;
        public Double contentDividerPosition = null;
        public boolean leftPanelVisible = true;
        public boolean bottomPanelVisible = true;
        public boolean interactivePanelVisible = true;
        public boolean systemInfoVisible = true;
    }

    public static class Ai {
        public boolean enabled = true;
        public String selectedConnectionId = "";
        public List<AiModelConnection> connections = new ArrayList<>();

        public String provider = "OpenAI Compatible";
        public String model = "";
        public String models = "";
        public String apiKey = "";
        public String baseUrl = AiModelConnection.OPENAI_BASE_URL;
        public boolean streamOutput = true;
        public boolean thinkingEnabled = false;
        public double temperature = 0.1;
        public int maxOutputTokens = 4096;
    }

    public static class AiModelConnection {
        public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
        public static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1";
        public static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

        public String id = "";
        public String name = "";
        public String apiFormat = "OPENAI_CHAT_COMPLETIONS";
        public String baseUrl = OPENAI_BASE_URL;
        public String apiKey = "";
        public String model = "";
        public boolean imageInputSupported = false;
    }

    public static class Docker {
        public List<DockerRegistry> registries = new ArrayList<>();
    }

    public static class DockerRegistry {
        public String name = "";
        public String address = "";
        public String username = "";
        public String password = "";
    }
}

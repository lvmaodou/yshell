package com.yshell.model.docker;

import java.time.Instant;
import java.util.List;

public record DockerSnapshot(
        Instant capturedAt,
        boolean dockerAvailable,
        String errorMessage,
        String serverVersion,
        String apiVersion,
        String os,
        String arch,
        String serverBuild,
        int runningContainers,
        int totalContainers,
        int imageCount,
        List<DockerContainer> containers,
        List<DockerImage> images,
        List<DockerNetwork> networks,
        List<DockerVolume> volumes
) {
    public static DockerSnapshot empty(String errorMessage) {
        return new DockerSnapshot(
                Instant.now(),
                false,
                errorMessage,
                "",
                "",
                "",
                "",
                "",
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}

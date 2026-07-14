package com.yshell.model.docker;

public record DockerImage(
        String id,
        String repository,
        String tag,
        String createdSince,
        String createdAt,
        String size,
        String containers
) {
}

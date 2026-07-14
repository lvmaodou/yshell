package com.yshell.model.docker;

public record DockerContainer(
        String id,
        String name,
        String image,
        String status,
        String state,
        String ports,
        String createdAt,
        String size
) {
}

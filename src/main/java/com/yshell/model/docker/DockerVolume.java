package com.yshell.model.docker;

public record DockerVolume(
        String name,
        String driver,
        String scope,
        String mountpoint,
        String labels,
        String used,
        String createdAt
) {
}

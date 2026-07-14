package com.yshell.model.docker;

public record DockerNetwork(
        String id,
        String name,
        String driver,
        String scope,
        String ipv6,
        String internal,
        String labels
) {
}

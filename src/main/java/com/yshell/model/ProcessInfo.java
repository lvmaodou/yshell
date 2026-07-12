package com.yshell.model;

import javafx.beans.property.SimpleDoubleProperty;

public class ProcessInfo {
    public final String name;
    private final SimpleDoubleProperty cpuPercent;
    private final SimpleDoubleProperty memPercent;

    public ProcessInfo(String name, double cpuPercent, double memPercent) {
        this.name = name;
        this.cpuPercent = new SimpleDoubleProperty(cpuPercent);
        this.memPercent = new SimpleDoubleProperty(memPercent);
    }

    public SimpleDoubleProperty cpuPercentProperty() {
        return cpuPercent;
    }

    public double getCpuPercent() {
        return cpuPercent.get();
    }

    public SimpleDoubleProperty memPercentProperty() {
        return memPercent;
    }

    public double getMemPercent() {
        return memPercent.get();
    }
}
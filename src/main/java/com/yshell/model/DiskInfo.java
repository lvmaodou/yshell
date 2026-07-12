package com.yshell.model;

import javafx.beans.property.SimpleDoubleProperty;

public class DiskInfo {
    public final String path;
    public final String sizeText;
    private final SimpleDoubleProperty usedPercent;

    public DiskInfo(String path, String sizeText, double usedPercent) {
        this.path = path;
        this.sizeText = sizeText;
        this.usedPercent = new SimpleDoubleProperty(usedPercent);
    }

    public SimpleDoubleProperty usedPercentProperty() {
        return usedPercent;
    }

    public double getUsedPercent() {
        return usedPercent.get();
    }
}
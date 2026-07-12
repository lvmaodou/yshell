package com.yshell.transfer;

public enum TransferStatus {
    WAITING("等待中"),
    RUNNING("传输中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("失败"),
    CANCELED("已取消");

    private final String text;

    TransferStatus(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}

package com.yshell.transfer;

public enum CompressedTransferStage {
    WAITING("等待中"),
    PACKING("打包中"),
    TRANSFERRING("传输中"),
    EXTRACTING("解压中"),
    CLEANING("清理中"),
    COMPLETED("完成"),
    FAILED("失败"),
    CANCELED("已取消");

    private final String text;

    CompressedTransferStage(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}

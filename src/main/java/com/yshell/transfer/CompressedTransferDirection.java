package com.yshell.transfer;

public enum CompressedTransferDirection {
    UPLOAD("压缩上传"),
    DOWNLOAD("压缩下载");

    private final String text;

    CompressedTransferDirection(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}

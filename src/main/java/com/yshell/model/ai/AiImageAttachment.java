package com.yshell.model.ai;

public class AiImageAttachment {
    public String id;
    public String name;
    public String mimeType;
    public String base64Data;

    public AiImageAttachment() {
    }

    public AiImageAttachment(String id, String name, String mimeType, String base64Data) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.mimeType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
        this.base64Data = base64Data == null ? "" : base64Data;
    }
}

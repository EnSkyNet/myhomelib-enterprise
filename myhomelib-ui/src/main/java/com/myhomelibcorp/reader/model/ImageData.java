package com.myhomelibcorp.reader.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageData {
    private String id;
    private String mimeType;
    private byte[] data;
    private String base64Data;
}
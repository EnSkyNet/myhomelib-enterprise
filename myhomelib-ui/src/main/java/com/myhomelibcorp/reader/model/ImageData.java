package com.myhomelibcorp.reader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageData {
    private String id;
    private String mimeType;
    private byte[] data;           // <-- ТІЛЬКИ ЦЕ
    private boolean cached;
    private String cacheKey;

    // ВИДАЛЕНО: private String base64Data;

    public boolean isEmpty() {
        return data == null || data.length == 0;
    }

    public long getSize() {
        return data != null ? data.length : 0;
    }

    public String getSizeFormatted() {
        long size = getSize();
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }
}
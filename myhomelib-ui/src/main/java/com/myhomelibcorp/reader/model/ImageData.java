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
    private byte[] data;
    private boolean cached; // Показує чи зображення в кеші
    private String cacheKey; // Ключ для кешу
}
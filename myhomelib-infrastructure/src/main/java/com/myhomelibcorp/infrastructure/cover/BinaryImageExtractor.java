package com.myhomelibcorp.infrastructure.cover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
@Slf4j
public class BinaryImageExtractor {

    public byte[] extractFromBase64(String base64Content) {
        if (base64Content == null || base64Content.isEmpty()) {
            return null;
        }
        try {
            String clean = base64Content.replaceAll("\\s+", "");
            return Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            log.trace("Invalid Base64: {}", e.getMessage());
            return null;
        }
    }
}

package com.myhomelibcorp.infrastructure.cover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class CharsetDetector {

    public Charset detect(InputStream is) throws IOException {
        if (!is.markSupported()) {
            is = new BufferedInputStream(is);
        }
        is.mark(512);
        byte[] buffer = new byte[512];
        int bytesRead = is.read(buffer);
        is.reset();

        if (bytesRead <= 0) {
            return StandardCharsets.UTF_8;
        }

        String start = new String(buffer, 0, Math.min(bytesRead, 256), StandardCharsets.ISO_8859_1);
        int encIdx = start.toLowerCase().indexOf("encoding");
        if (encIdx > 0) {
            int startQuote = start.indexOf('"', encIdx);
            int endQuote = start.indexOf('"', startQuote + 1);
            if (startQuote > 0 && endQuote > startQuote) {
                String encoding = start.substring(startQuote + 1, endQuote);
                try {
                    return Charset.forName(encoding);
                } catch (Exception e) {
                    // fallback
                }
            }
        }

        return StandardCharsets.UTF_8;
    }
}
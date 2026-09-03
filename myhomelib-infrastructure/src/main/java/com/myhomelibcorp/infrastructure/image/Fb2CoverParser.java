package com.myhomelibcorp.infrastructure.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;

@Component
@Slf4j
public class Fb2CoverParser {

    private static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024; // 10 MB
    private final XMLInputFactory xmlFactory = SecureXmlInputFactory.create(false, false);


    /**
     * Parses FB2 and returns its declared cover. If the document has no coverpage,
     * the first valid image binary is used as a conservative fallback.
     *
     * <p>The parser intentionally never treats body illustrations as a cover reference:
     * FB2 stores binary payloads after the body, so remembering the last encountered
     * {@code <image>} used to make the preview select the final illustration in the book.</p>
     */
    public byte[] parseToBytes(InputStream inputStream) {
        if (inputStream == null) return null;
        try {
            XMLStreamReader xmlReader = xmlFactory.createXMLStreamReader(inputStream);
            try {
                return parseCoverFromXml(xmlReader);
            } finally {
                try { xmlReader.close(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            log.debug("Не вдалося розпарсити FB2 cover: {}", e.getMessage());
            return null;
        }
    }

    /** Allows deterministic re-parsing without relying on mark/reset support of the source stream. */
    public byte[] parseToBytes(byte[] data) {
        if (data == null || data.length == 0) return null;
        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            XMLStreamReader xmlReader = xmlFactory.createXMLStreamReader(input);
            try {
                return parseCoverFromXml(xmlReader);
            } finally {
                try { xmlReader.close(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            log.debug("Не вдалося розпарсити FB2 cover: {}", e.getMessage());
            return null;
        }
    }

    private byte[] parseCoverFromXml(XMLStreamReader xmlReader) throws Exception {
        String coverId = null;
        byte[] firstValidImage = null;
        boolean inCoverpage = false;

        while (xmlReader.hasNext()) {
            int event = xmlReader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = xmlReader.getLocalName().toLowerCase(java.util.Locale.ROOT);

                if ("coverpage".equals(localName) || "cover-page".equals(localName)) {
                    inCoverpage = true;
                    continue;
                }

                // Only an image referenced by <coverpage> is the declared FB2 cover.
                if (inCoverpage && ("image".equals(localName) || "img".equals(localName))) {
                    String href = xmlReader.getAttributeValue("http://www.w3.org/1999/xlink", "href");
                    if (href == null) href = xmlReader.getAttributeValue(null, "href");
                    if (href != null && href.startsWith("#") && href.length() > 1 && coverId == null) {
                        coverId = href.substring(1);
                    }
                    continue;
                }

                if ("binary".equals(localName)) {
                    String id = xmlReader.getAttributeValue(null, "id");
                    String contentType = xmlReader.getAttributeValue(null, "content-type");
                    String content = xmlReader.getElementText();
                    if (content == null || content.isBlank()) continue;

                    boolean declaredCover = sameResourceId(id, coverId);
                    if (!declaredCover && !isImage(contentType, id)) continue;

                    byte[] decoded = decodeImage(content);
                    if (decoded == null || !looksLikeImage(decoded)) continue;
                    if (firstValidImage == null) firstValidImage = decoded;
                    if (declaredCover) return decoded;
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String localName = xmlReader.getLocalName().toLowerCase(java.util.Locale.ROOT);
                if ("coverpage".equals(localName) || "cover-page".equals(localName)) {
                    inCoverpage = false;
                }
            }
        }

        // Broken/minimal FB2 without <coverpage>: first image is a much safer fallback
        // than the old "last binary wins" behaviour.
        return firstValidImage;
    }

    private static boolean sameResourceId(String id, String coverId) {
        if (id == null || coverId == null) return false;
        return stripHash(id).equals(stripHash(coverId));
    }

    private static String stripHash(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
    }

    private static boolean isImage(String contentType, String id) {
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return true;
        String lowerId = id == null ? "" : id.toLowerCase(java.util.Locale.ROOT);
        return lowerId.endsWith(".jpg") || lowerId.endsWith(".jpeg") || lowerId.endsWith(".png")
                || lowerId.endsWith(".gif") || lowerId.endsWith(".webp") || lowerId.endsWith(".bmp");
    }

    private static boolean looksLikeImage(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        // JPEG
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return true;
        // PNG
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return true;
        // GIF
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') return true;
        // BMP
        if (bytes[0] == 'B' && bytes[1] == 'M') return true;
        // WEBP: RIFF....WEBP
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static byte[] decodeImage(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        try {
            byte[] imageBytes = Base64.getMimeDecoder().decode(base64);
            if (imageBytes.length == 0 || imageBytes.length > MAX_IMAGE_SIZE) return null;
            return imageBytes;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}

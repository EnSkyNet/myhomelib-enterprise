package com.myhomelibcorp.reader.inspection;

/** Lightweight image descriptor. Payload is opened lazily by the inspection session. */
public record DocumentImageInfo(String id, String mimeType, int length) {
}

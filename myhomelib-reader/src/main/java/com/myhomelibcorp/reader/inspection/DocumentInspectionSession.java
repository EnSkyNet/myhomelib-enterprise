package com.myhomelibcorp.reader.inspection;

import java.io.InputStream;
import java.util.Optional;

/**
 * Owns resources created during inspection. Image bytes are read lazily and the
 * session must be closed when selection changes.
 */
public interface DocumentInspectionSession extends AutoCloseable {
    DocumentInspection inspection();
    Optional<InputStream> openImage(String id);
    @Override void close();
}

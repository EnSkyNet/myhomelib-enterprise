package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;

/** Persisted Stage-16 export/device profile. */
public record ExportProfile(
        String id,
        String name,
        ExportRequest.ExportFormat format,
        String destinationFolder,
        ExportRequest.CollisionPolicy collisionPolicy,
        boolean extractOnly,
        String filenameTemplate,
        String subfolderTemplate,
        String postActionProfileId
) {
    public ExportProfile {
        id = text(id);
        name = text(name);
        destinationFolder = text(destinationFolder);
        collisionPolicy = collisionPolicy == null ? ExportRequest.CollisionPolicy.RENAME : collisionPolicy;
        filenameTemplate = text(filenameTemplate);
        subfolderTemplate = text(subfolderTemplate);
        postActionProfileId = text(postActionProfileId);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}

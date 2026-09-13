package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Application-owned description of a removable-reader target.
 *
 * <p>The destination is always relative to the user-selected mount/root folder. This keeps a
 * profile portable across drive letters and operating systems and prevents a saved profile from
 * escaping the selected root.</p>
 */
public record DeviceTargetProfile(
        String id,
        String displayName,
        List<ExportRequest.ExportFormat> preferredFormats,
        String destinationSubfolder,
        boolean builtIn
) {
    public DeviceTargetProfile {
        id = requiredId(id);
        displayName = requiredText(displayName, "displayName");
        preferredFormats = normalizeFormats(preferredFormats);
        destinationSubfolder = normalizeRelativeFolder(destinationSubfolder);
    }

    public Path resolveDestination(Path mountRoot) {
        Objects.requireNonNull(mountRoot, "mountRoot");
        Path root = mountRoot.toAbsolutePath().normalize();
        Path target = destinationSubfolder.isBlank()
                ? root
                : root.resolve(destinationSubfolder.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Device destination escapes the selected root");
        }
        return target;
    }

    public boolean supports(ExportRequest.ExportFormat format) {
        return format != null && preferredFormats.contains(format);
    }

    private static List<ExportRequest.ExportFormat> normalizeFormats(List<ExportRequest.ExportFormat> formats) {
        LinkedHashSet<ExportRequest.ExportFormat> unique = new LinkedHashSet<>();
        if (formats != null) formats.stream().filter(Objects::nonNull).forEach(unique::add);
        if (unique.isEmpty()) throw new IllegalArgumentException("preferredFormats must not be empty");
        return List.copyOf(new ArrayList<>(unique));
    }

    private static String normalizeRelativeFolder(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isBlank()) return "";
        if (normalized.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("destinationSubfolder must be relative");
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("destinationSubfolder contains an unsafe segment");
            }
        }
        return normalized;
    }

    private static String requiredId(String value) {
        String id = requiredText(value, "id");
        if (!id.matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("Invalid device profile id");
        return id;
    }

    private static String requiredText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}

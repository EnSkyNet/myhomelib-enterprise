package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Application-owned persistence of the last ad-hoc export state used by the UI. */
@Service
@RequiredArgsConstructor
public class ExportLastStateService {
    private static final String PREFIX = "export.last";
    private static final String PROFILE = PREFIX + "ProfileId";
    private static final String DESTINATION = PREFIX + "Destination";
    private static final String FORMAT = PREFIX + "Format";
    private static final String COLLISION = PREFIX + "CollisionPolicy";
    private static final String FILENAME = PREFIX + "FilenameTemplate";
    private static final String SUBFOLDER = PREFIX + "SubfolderTemplate";
    private static final String POST_ACTION = PREFIX + "PostAction";
    private static final String EXTRACT_ONLY = PREFIX + "ExtractOnly";

    private final ApplicationSettingsPort settings;

    public State load() {
        Map<String, String> saved = settings.findByPrefix(PREFIX);
        return new State(
                saved.getOrDefault(PROFILE, ""),
                parseFormat(saved.get(FORMAT)),
                parseCollision(saved.get(COLLISION)),
                saved.containsKey(DESTINATION), saved.getOrDefault(DESTINATION, ""),
                saved.containsKey(FILENAME), saved.getOrDefault(FILENAME, ""),
                saved.containsKey(SUBFOLDER), saved.getOrDefault(SUBFOLDER, ""),
                saved.containsKey(POST_ACTION), saved.getOrDefault(POST_ACTION, ""),
                saved.containsKey(EXTRACT_ONLY) ? Boolean.parseBoolean(saved.get(EXTRACT_ONLY)) : null);
    }

    public void rememberProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) settings.remove(PROFILE);
        else settings.put(PROFILE, profileId);
    }

    public void save(ExportRequest request) {
        if (request == null) return;
        rememberProfile(request.getProfileId());
        settings.put(DESTINATION, request.getDestinationFolder() == null ? "" : request.getDestinationFolder().toString());
        if (request.getFormat() != null) settings.put(FORMAT, request.getFormat().name());
        if (request.getCollisionPolicy() != null) settings.put(COLLISION, request.getCollisionPolicy().name());
        settings.put(FILENAME, safe(request.getCustomFileNameTemplate()));
        settings.put(SUBFOLDER, safe(request.getSubfolderTemplate()));
        settings.put(POST_ACTION, safe(request.getPostActionProfileId()));
        settings.putBoolean(EXTRACT_ONLY, request.isExtractOnly());
    }

    private static ExportRequest.ExportFormat parseFormat(String value) {
        if (value == null || value.isBlank()) return null;
        try { return ExportRequest.ExportFormat.valueOf(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static ExportRequest.CollisionPolicy parseCollision(String value) {
        if (value == null || value.isBlank()) return null;
        try { return ExportRequest.CollisionPolicy.valueOf(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    public record State(
            String profileId,
            ExportRequest.ExportFormat format,
            ExportRequest.CollisionPolicy collisionPolicy,
            boolean hasDestination,
            String destination,
            boolean hasFilenameTemplate,
            String filenameTemplate,
            boolean hasSubfolderTemplate,
            String subfolderTemplate,
            boolean hasPostAction,
            String postActionId,
            Boolean extractOnly
    ) {
        public State {
            profileId = safe(profileId);
            destination = safe(destination);
            filenameTemplate = safe(filenameTemplate);
            subfolderTemplate = safe(subfolderTemplate);
            postActionId = safe(postActionId);
        }
    }
}

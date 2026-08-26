package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Small persisted export history; capped to avoid unbounded settings growth. */
@Component
@RequiredArgsConstructor
public class ExportHistoryService {
    private static final String ORDER = "exportHistory.order";
    private static final String PREFIX = "exportHistory.entry.";
    private static final int MAX_ENTRIES = 50;
    private final ApplicationSettingsPort settings;

    public synchronized void record(ExportRequest request, int requested, int exported, int skipped,
                                    int failed, boolean cancelled, long durationMs) {
        if (request == null) return;
        String id = UUID.randomUUID().toString();
        String base = PREFIX + id;
        settings.put(base + ".completedAt", Instant.now().toString());
        settings.put(base + ".profileName", text(request.getProfileName()));
        settings.put(base + ".destination", request.getDestinationFolder() == null ? "" : request.getDestinationFolder().toAbsolutePath().normalize().toString());
        settings.put(base + ".format", request.getFormat() == null ? "" : request.getFormat().name());
        settings.putInt(base + ".requested", requested);
        settings.putInt(base + ".exported", exported);
        settings.putInt(base + ".skipped", skipped);
        settings.putInt(base + ".failed", failed);
        settings.putBoolean(base + ".cancelled", cancelled);
        settings.put(base + ".durationMs", Long.toString(Math.max(0, durationMs)));

        List<String> order = ids(settings.get(ORDER, ""));
        order.remove(id); order.add(0, id);
        while (order.size() > MAX_ENTRIES) deleteKeys(order.removeLast());
        settings.put(ORDER, String.join(",", order));
    }

    public synchronized List<ExportHistoryEntry> loadRecent(int limit) {
        int safeLimit = Math.max(0, Math.min(limit, MAX_ENTRIES));
        if (safeLimit == 0) return List.of();
        List<ExportHistoryEntry> out = new ArrayList<>();
        for (String id : ids(settings.get(ORDER, ""))) {
            read(id).ifPresent(out::add);
            if (out.size() >= safeLimit) break;
        }
        return List.copyOf(out);
    }

    public synchronized void clear() {
        for (String id : ids(settings.get(ORDER, ""))) deleteKeys(id);
        settings.remove(ORDER);
    }

    private Optional<ExportHistoryEntry> read(String id) {
        String base = PREFIX + id;
        String completed = settings.get(base + ".completedAt", "");
        if (completed.isBlank()) return Optional.empty();
        try {
            String formatText = settings.get(base + ".format", "");
            ExportRequest.ExportFormat format = formatText.isBlank() ? null : ExportRequest.ExportFormat.valueOf(formatText);
            return Optional.of(new ExportHistoryEntry(id, Instant.parse(completed),
                    settings.get(base + ".profileName", ""), settings.get(base + ".destination", ""), format,
                    settings.getInt(base + ".requested", 0), settings.getInt(base + ".exported", 0),
                    settings.getInt(base + ".skipped", 0), settings.getInt(base + ".failed", 0),
                    settings.getBoolean(base + ".cancelled", false), parseLong(settings.get(base + ".durationMs", "0"))));
        } catch (RuntimeException ignored) { return Optional.empty(); }
    }

    private void deleteKeys(String id) {
        new ArrayList<>(settings.findByPrefix(PREFIX + id + ".").keySet()).forEach(settings::remove);
    }
    private static long parseLong(String v) { try { return Long.parseLong(v); } catch (Exception e) { return 0L; } }
    private static List<String> ids(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) return out;
        Arrays.stream(value.split(",")).map(String::trim).filter(s -> s.matches("[A-Za-z0-9._-]{1,80}")).distinct().forEach(out::add);
        return out;
    }
    private static String text(String v) { return v == null ? "" : v.trim(); }
}

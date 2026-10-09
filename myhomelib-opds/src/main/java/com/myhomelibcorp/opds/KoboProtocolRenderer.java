package com.myhomelibcorp.opds;

import com.myhomelibcorp.shared.text.JsonStrings;
import com.myhomelibcorp.application.kobo.KoboBookProjection;
import com.myhomelibcorp.application.kobo.KoboLibraryChange;
import com.myhomelibcorp.application.kobo.KoboProgressState;
import com.myhomelibcorp.application.kobo.KoboSyncBatch;
import com.myhomelibcorp.application.kobo.KoboSyncCursor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Small deterministic JSON boundary for the subset of the Kobo Store protocol used by MyHomeLib.
 * It deliberately has no persistence/network dependencies so protocol fixtures stay stable.
 */
final class KoboProtocolRenderer {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    String initialization(String baseUrl) {
        String base = trimSlash(baseUrl);
        return "{\"Resources\":{" +
                "\"library_sync\":\"" + JsonStrings.escape(base + "/v1/library/sync") + "\"," +
                "\"device_auth\":\"" + JsonStrings.escape(base + "/v1/auth/device") + "\"," +
                "\"device_refresh\":\"" + JsonStrings.escape(base + "/v1/auth/refresh") + "\"," +
                "\"book\":\"" + JsonStrings.escape(base + "/v1/library/{ProductId}/metadata") + "\"," +
                "\"reading_state\":\"" + JsonStrings.escape(base + "/v1/library/{EntitlementId}/state") + "\"" +
                "}}";
    }

    String deviceAuth(String deviceId) {
        String stable = deviceId == null || deviceId.isBlank() ? "myhomelib-kobo" : deviceId;
        return "{\"AccessToken\":\"mhl-device\",\"RefreshToken\":\"mhl-device\"," +
                "\"TokenType\":\"Bearer\",\"TrackingId\":\"" + JsonStrings.escape(stable) + "\"," +
                "\"UserKey\":\"" + JsonStrings.escape(stable) + "\"}";
    }

    String sync(KoboSyncBatch batch, String baseUrl) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (KoboLibraryChange change : batch.changes()) {
            if (!first) out.append(',');
            first = false;
            switch (change.kind()) {
                case ADD -> appendEntitlement(out, "NewEntitlement", change.book(), baseUrl, false);
                case UPDATE -> appendEntitlement(out, "ChangedEntitlement", change.book(), baseUrl, false);
                case REMOVE -> appendRemoved(out, change.bookId());
            }
        }
        return out.append(']').toString();
    }

    String metadata(KoboBookProjection book, String baseUrl) {
        return bookMetadata(book, baseUrl);
    }

    String state(KoboProgressState state) {
        return "{\"ReadingState\":" + readingState(state) + "}";
    }

    String readingState(KoboProgressState state) {
        if (state == null) return "null";
        String modified = iso(state.modifiedAt());
        double fraction = Math.max(0.0, Math.min(1.0, state.percent() / 100.0));
        return "{" +
                "\"EntitlementId\":\"" + JsonStrings.escape(state.bookId()) + "\"," +
                "\"Created\":\"" + JsonStrings.escape(modified) + "\"," +
                "\"LastModified\":\"" + JsonStrings.escape(modified) + "\"," +
                "\"PriorityTimestamp\":\"" + JsonStrings.escape(modified) + "\"," +
                "\"StatusInfo\":{\"Status\":\"" + JsonStrings.escape(normalizeStatus(state.status())) + "\"}," +
                "\"Statistics\":{\"SpentReadingMinutes\":0}," +
                "\"CurrentBookmark\":{" +
                "\"ProgressPercent\":" + decimal(fraction) + "," +
                "\"Location\":{\"Type\":\"KoboSpan\",\"Value\":\"" + JsonStrings.escape(state.location()) + "\"}" +
                "}," +
                "\"MyHomeLibVersion\":" + state.version() +
                "}";
    }

    String token(KoboSyncCursor cursor) {
        long value = cursor == null ? 0L : cursor.value();
        String json = "{\"cursor\":" + value + "}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    KoboSyncCursor parseToken(String raw) {
        if (raw == null || raw.isBlank()) return KoboSyncCursor.initial();
        String candidate = raw.trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(candidate);
            String json = new String(decoded, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\\"cursor\\\"\\s*:\\s*(\\d+)").matcher(json);
            if (m.find()) return new KoboSyncCursor(Long.parseLong(m.group(1)));
        } catch (RuntimeException ignored) {
            // Some third-party clients/proxies may pass the numeric cursor through unchanged.
        }
        try { return new KoboSyncCursor(Long.parseLong(candidate)); }
        catch (RuntimeException ignored) { return KoboSyncCursor.initial(); }
    }

    private static void appendEntitlement(StringBuilder out, String wrapper, KoboBookProjection book,
                                          String baseUrl, boolean removed) {
        out.append('{').append('"').append(wrapper).append("\":{")
                .append("\"BookEntitlement\":").append(bookEntitlement(book.bookId(), book.revision(), book.modifiedAt(), removed)).append(',')
                .append("\"BookMetadata\":").append(bookMetadata(book, baseUrl))
                .append("}}");
    }

    private static void appendRemoved(StringBuilder out, String bookId) {
        String revision = "removed-" + bookId;
        out.append("{\"ChangedEntitlement\":{\"BookEntitlement\":")
                .append(bookEntitlement(bookId, revision, Instant.EPOCH, true))
                .append("}}");
    }

    private static String bookEntitlement(String id, String revision, Instant modified, boolean removed) {
        String when = iso(modified);
        return "{" +
                "\"Accessibility\":\"Full\"," +
                "\"ActivePeriod\":{}," +
                "\"Created\":\"" + JsonStrings.escape(when) + "\"," +
                "\"CrossRevisionId\":\"" + JsonStrings.escape(revision) + "\"," +
                "\"Id\":\"" + JsonStrings.escape(id) + "\"," +
                "\"IsRemoved\":" + removed + ',' +
                "\"LastModified\":\"" + JsonStrings.escape(when) + "\"," +
                "\"RevisionId\":\"" + JsonStrings.escape(revision) + "\"," +
                "\"Status\":\"Active\"" +
                "}";
    }

    private static String bookMetadata(KoboBookProjection book, String baseUrl) {
        String base = trimSlash(baseUrl);
        String format = koboFormat(book.format());
        String filename = book.downloadName().isBlank() ? book.bookId() + ".epub" : book.downloadName();
        String url = base + "/download/" + path(book.bookId()) + "/" + path(format.toLowerCase(Locale.ROOT));
        StringBuilder out = new StringBuilder("{")
                .append("\"Categories\":[],")
                .append("\"Contributors\":[{\"Name\":\"").append(JsonStrings.escape(book.authors())).append("\"}],")
                .append("\"CoverImageId\":\"").append(JsonStrings.escape(book.bookId())).append("\",")
                .append("\"CrossRevisionId\":\"").append(JsonStrings.escape(book.revision())).append("\",")
                .append("\"CurrentDisplayPrice\":0,")
                .append("\"Description\":\"\",")
                .append("\"DownloadUrls\":[{\"Format\":\"").append(format)
                .append("\",\"Size\":0,\"Url\":\"").append(JsonStrings.escape(url)).append("\",\"Platform\":\"Generic\"}],")
                .append("\"EntitlementId\":\"").append(JsonStrings.escape(book.bookId())).append("\",")
                .append("\"ExternalIds\":[],")
                .append("\"IsInternetArchive\":false,")
                .append("\"IsPreOrder\":false,")
                .append("\"Language\":\"\",")
                .append("\"PublicationDate\":\"").append(JsonStrings.escape(iso(book.modifiedAt()))).append("\",")
                .append("\"RevisionId\":\"").append(JsonStrings.escape(book.revision())).append("\",")
                .append("\"Title\":\"").append(JsonStrings.escape(book.title())).append("\",")
                .append("\"WorkId\":\"").append(JsonStrings.escape(book.bookId())).append("\",")
                .append("\"Slug\":\"").append(JsonStrings.escape(filename)).append('"');
        if (!book.series().isBlank()) {
            out.append(",\"Series\":{\"Name\":\"").append(JsonStrings.escape(book.series())).append('"');
            if (book.sequenceNumber() != null) out.append(",\"Number\":").append(book.sequenceNumber());
            out.append('}');
        }
        return out.append('}').toString();
    }

    static String koboFormat(String format) {
        String f = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (f.contains("kepub")) return "KEPUB";
        return "EPUB";
    }

    private static String normalizeStatus(String status) {
        if ("Finished".equalsIgnoreCase(status)) return "Finished";
        if ("Reading".equalsIgnoreCase(status)) return "Reading";
        return "ReadyToRead";
    }

    private static String iso(Instant instant) {
        return ISO.format(instant == null ? Instant.EPOCH : instant);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.8f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String trimSlash(String value) {
        if (value == null) return "";
        String v = value;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String path(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

}

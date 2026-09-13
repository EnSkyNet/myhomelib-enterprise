package com.myhomelibcorp.infrastructure.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.application.metadata.MetadataProvider;
import com.myhomelibcorp.application.metadata.MetadataProviderErrorKind;
import com.myhomelibcorp.application.metadata.MetadataProviderException;
import com.myhomelibcorp.application.metadata.MetadataQuery;
import com.myhomelibcorp.application.metadata.MetadataRequestContext;
import com.myhomelibcorp.application.metadata.MetadataSource;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.valueobject.Isbn;
import com.myhomelibcorp.infrastructure.download.OnlineHttpPolicy;
import static com.myhomelibcorp.infrastructure.metadata.MetadataProviderSupport.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Google Books Volumes API adapter for the vendor-neutral metadata SPI.
 *
 * <p>The provider is disabled by default because public-data requests require an application
 * credential. The API key is configuration-only, is never copied into metadata candidates and
 * remote error payloads are never exposed through the application failure model.</p>
 */
@Component
public final class GoogleBooksMetadataProvider implements MetadataProvider {
    public static final String PROVIDER_ID = "google-books";
    public static final String PROVIDER_NAME = "Google Books";

    private static final URI DEFAULT_VOLUMES_ENDPOINT = URI.create("https://www.googleapis.com/books/v1/volumes");
    private static final URI DEFAULT_WEB_BASE = URI.create("https://books.google.com/books");
    private static final int GOOGLE_MAX_RESULTS = 40;
    private static final long WAIT_POLL_MILLIS = 50L;

    private final ApplicationSettingsPort settings;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final URI volumesEndpoint;
    private final URI webBase;
    private final Cache<MetadataQuery, List<MetadataCandidate>> cache;
    private final AtomicLong nextRequestNanos = new AtomicLong(System.nanoTime());

    @Autowired
    public GoogleBooksMetadataProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper) {
        this(
                settings,
                objectMapper,
                new OnlineHttpPolicy(Objects.requireNonNull(settings, "settings")).create(null),
                DEFAULT_VOLUMES_ENDPOINT,
                DEFAULT_WEB_BASE);
    }

    GoogleBooksMetadataProvider(
            ApplicationSettingsPort settings,
            ObjectMapper objectMapper,
            HttpClient client,
            URI volumesEndpoint,
            URI webBase) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.client = Objects.requireNonNull(client, "client");
        this.volumesEndpoint = requireHttpUri(volumesEndpoint, "volumesEndpoint");
        this.webBase = requireHttpUri(webBase, "webBase");
        int cacheMinutes = clamp(settings.getInt("metadata.googleBooks.cacheMinutes", 30), 1, 24 * 60);
        this.cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(cacheMinutes, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isEnabled() {
        return settings.getBoolean("metadata.googleBooks.enabled", false);
    }

    @Override
    public List<MetadataCandidate> search(MetadataQuery query, MetadataRequestContext context)
            throws MetadataProviderException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(context, "context");
        context.throwIfStopped();

        String apiKey = apiKey();
        if (apiKey.isBlank()) {
            throw new MetadataProviderException(
                    MetadataProviderErrorKind.AUTHENTICATION,
                    "Google Books API key is not configured");
        }

        List<MetadataCandidate> cached = cache.getIfPresent(query);
        if (cached != null) return cached;

        throttle(context);
        context.throwIfStopped();

        URI uri = buildSearchUri(query);
        Duration remaining = context.remainingTimeout();
        if (remaining.isZero()) throw MetadataProviderException.timeout();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(remaining)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .header("X-Goog-Api-Key", safeHeader(apiKey, ""))
                .build();

        HttpResponse<String> response = MetadataProviderSupport.send(client, request, context, PROVIDER_NAME);
        List<MetadataCandidate> candidates = handleResponse(response, query);
        context.throwIfStopped();
        List<MetadataCandidate> immutable = List.copyOf(candidates);
        cache.put(query, immutable);
        return immutable;
    }

    URI buildSearchUri(MetadataQuery query) {
        Objects.requireNonNull(query, "query");

        List<String> params = new ArrayList<>();
        params.add(param("q", googleQuery(query)));
        params.add(param("maxResults", Integer.toString(Math.min(query.limit(), GOOGLE_MAX_RESULTS))));
        params.add(param("orderBy", "relevance"));
        params.add(param("printType", "books"));
        params.add(param("projection", "full"));
        String separator = volumesEndpoint.toString().contains("?") ? "&" : "?";
        return URI.create(volumesEndpoint + separator + String.join("&", params));
    }

    static String googleQuery(MetadataQuery query) {
        Objects.requireNonNull(query, "query");
        List<String> terms = new ArrayList<>();
        if (query.hasIsbn()) terms.add("isbn:" + query.isbn());
        if (query.hasTitle()) terms.add("intitle:\"" + phrase(query.title()) + "\"");
        if (query.hasAuthor()) terms.add("inauthor:\"" + phrase(query.author()) + "\"");
        return String.join(" ", terms);
    }

    private List<MetadataCandidate> handleResponse(HttpResponse<String> response, MetadataQuery query)
            throws MetadataProviderException {
        int status = response.statusCode();
        if (status == 429) throw MetadataProviderException.rateLimited(parseRetryAfter(response));
        if (status == 408 || status == 504) throw MetadataProviderException.timeout();
        if (status == 400 || status == 401 || status == 403) {
            throw new MetadataProviderException(
                    MetadataProviderErrorKind.AUTHENTICATION,
                    "Google Books request was rejected");
        }
        if (status >= 500) {
            throw new MetadataProviderException(
                    MetadataProviderErrorKind.UNAVAILABLE,
                    "Google Books is unavailable");
        }
        if (status < 200 || status >= 300) {
            throw new MetadataProviderException(
                    MetadataProviderErrorKind.FAILED,
                    "Google Books request failed with HTTP " + status);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body() == null ? "" : response.body());
        } catch (JsonProcessingException invalidJson) {
            throw new MetadataProviderException(
                    MetadataProviderErrorKind.INVALID_RESPONSE,
                    "Google Books returned invalid JSON",
                    invalidJson);
        }
        if (root == null || !root.isObject()) {
            throw new MetadataProviderException(
                    MetadataProviderErrorKind.INVALID_RESPONSE,
                    "Google Books response is not an object");
        }

        JsonNode items = root.get("items");
        if (items == null || items.isNull()) {
            int totalItems = root.path("totalItems").asInt(0);
            if (totalItems == 0) return List.of();
            throw new MetadataProviderException(
                    MetadataProviderErrorKind.INVALID_RESPONSE,
                    "Google Books response is missing items array");
        }
        if (!items.isArray()) {
            throw new MetadataProviderException(
                    MetadataProviderErrorKind.INVALID_RESPONSE,
                    "Google Books items is not an array");
        }

        List<MetadataCandidate> candidates = new ArrayList<>();
        int rank = 0;
        for (JsonNode item : items) {
            if (candidates.size() >= query.limit()) break;
            Optional<MetadataCandidate> candidate = mapCandidate(item, query, rank++);
            candidate.ifPresent(candidates::add);
        }
        return candidates;
    }

    private Optional<MetadataCandidate> mapCandidate(JsonNode item, MetadataQuery query, int rank) {
        if (item == null || !item.isObject()) return Optional.empty();
        String id = clean(item.path("id").asText(""));
        JsonNode info = item.get("volumeInfo");
        if (id.isBlank() || info == null || !info.isObject()) return Optional.empty();

        String title = clean(info.path("title").asText(""));
        if (title.isBlank()) return Optional.empty();
        List<String> authors = textArray(info.get("authors"));
        List<String> isbns = validIsbns(info.get("industryIdentifiers"));
        boolean exactIsbn = query.hasIsbn() && isbns.stream().anyMatch(value -> sameIsbn(value, query.isbn()));
        if (query.hasIsbn() && !exactIsbn) return Optional.empty();
        String isbn = exactIsbn ? query.isbn() : preferredIsbn(isbns);

        Integer year = publishedYear(info.path("publishedDate").asText(""));
        String publisher = clean(info.path("publisher").asText(""));
        String language = clean(info.path("language").asText(""));
        String annotation = clean(info.path("description").asText(""));
        String coverUrl = firstHttpUri(
                info.path("imageLinks").path("thumbnail").asText(""),
                info.path("imageLinks").path("smallThumbnail").asText(""));
        String recordUrl = firstHttpUri(info.path("infoLink").asText(""), fallbackRecordUrl(id));
        double confidence = confidence(query, title, authors, exactIsbn, rank);

        try {
            return Optional.of(new MetadataCandidate(
                    new MetadataSource(PROVIDER_ID, PROVIDER_NAME, id, recordUrl),
                    confidence,
                    title,
                    authors,
                    isbn,
                    year,
                    publisher,
                    language,
                    annotation,
                    coverUrl));
        } catch (IllegalArgumentException invalidRemoteData) {
            return Optional.empty();
        }
    }

    private void throttle(MetadataRequestContext context) throws MetadataProviderException {
        int requestsPerSecond = clamp(settings.getInt("metadata.googleBooks.requestsPerSecond", 5), 1, 20);
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / requestsPerSecond;
        context.throwIfStopped();
        long target = reserveRequestSlot(nextRequestNanos, intervalNanos, System.nanoTime());
        waitUntil(target, context, WAIT_POLL_MILLIS);
    }

    private String apiKey() {
        return clean(settings.get("metadata.googleBooks.apiKey", ""));
    }

    private String userAgent() {
        return safeHeader(new OnlineHttpPolicy(settings).userAgent(), "MyHomeLib Enterprise/7.1");
    }

    private String fallbackRecordUrl(String id) {
        String separator = webBase.toString().contains("?") ? "&" : "?";
        return webBase + separator + param("id", id);
    }

    private static double confidence(
            MetadataQuery query,
            String title,
            List<String> authors,
            boolean exactIsbn,
            int rank) {
        if (exactIsbn) return 0.99;
        double score = 0.56 + Math.max(0.0, 0.20 - (Math.min(rank, 20) * 0.01));
        if (query.hasTitle() && normalizeForMatch(title).equals(normalizeForMatch(query.title()))) score += 0.12;
        if (query.hasAuthor()) {
            String expected = normalizeForMatch(query.author());
            if (authors.stream().map(MetadataProviderSupport::normalizeForMatch).anyMatch(expected::equals)) {
                score += 0.10;
            }
        }
        return Math.min(0.98, roundConfidence(score));
    }

    private static List<String> validIsbns(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode identifier : node) {
            if (identifier == null || !identifier.isObject()) continue;
            String type = clean(identifier.path("type").asText(""));
            if (!"ISBN_10".equals(type) && !"ISBN_13".equals(type)) continue;
            Isbn.tryParse(identifier.path("identifier").asText(""))
                    .map(Isbn::value)
                    .ifPresent(values::add);
        }
        return List.copyOf(values);
    }

    private static Integer publishedYear(String value) {
        String clean = clean(value);
        if (clean.length() < 4) return null;
        String prefix = clean.substring(0, 4);
        for (int i = 0; i < prefix.length(); i++) if (!Character.isDigit(prefix.charAt(i))) return null;
        try {
            int year = Integer.parseInt(prefix);
            return year > 0 && year <= 9999 ? year : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String firstHttpUri(String... values) {
        for (String value : values) {
            String candidate = clean(value);
            if (candidate.isBlank()) continue;
            try {
                URI uri = URI.create(candidate);
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return uri.toString();
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed remote URL and continue to a safe fallback.
            }
        }
        return "";
    }

    private static String phrase(String value) {
        return clean(value).replace('"', ' ').replaceAll("\\s+", " ").trim();
    }

}

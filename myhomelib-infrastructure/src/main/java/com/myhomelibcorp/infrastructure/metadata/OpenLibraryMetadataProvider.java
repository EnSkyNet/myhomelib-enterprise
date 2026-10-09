package com.myhomelibcorp.infrastructure.metadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.application.metadata.MetadataProvider;
import com.myhomelibcorp.application.metadata.MetadataProviderErrorKind;
import com.myhomelibcorp.application.metadata.MetadataProviderException;
import com.myhomelibcorp.application.metadata.MetadataQuery;
import com.myhomelibcorp.application.metadata.MetadataRequestContext;
import com.myhomelibcorp.application.metadata.MetadataSource;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.valueobject.Isbn;
import com.myhomelibcorp.infrastructure.download.OnlineHttpClientProvider;
import com.myhomelibcorp.infrastructure.download.OnlineHttpPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.myhomelibcorp.infrastructure.metadata.MetadataProviderSupport.*;

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
 * Production Open Library Search API adapter for the vendor-neutral metadata SPI.
 *
 * <p>The adapter deliberately requests a small stable field set, caches normalized results,
 * throttles calls to Open Library's documented low-volume limits and never exposes raw remote
 * errors/quota details through the application candidate model.</p>
 */
@Component
public final class OpenLibraryMetadataProvider implements MetadataProvider {
    public static final String PROVIDER_ID = "open-library";
    public static final String PROVIDER_NAME = "Open Library";

    private static final URI DEFAULT_SEARCH_ENDPOINT = URI.create("https://openlibrary.org/search.json");
    private static final URI DEFAULT_WEB_BASE = URI.create("https://openlibrary.org/");
    private static final URI DEFAULT_COVER_BASE = URI.create("https://covers.openlibrary.org/b/id/");
    private static final String SEARCH_FIELDS =
            "key,title,author_name,first_publish_year,isbn,publisher,language,cover_i";
    private static final long WAIT_POLL_MILLIS = 50L;

    private final ApplicationSettingsPort settings;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final URI searchEndpoint;
    private final URI webBase;
    private final URI coverBase;
    private final Cache<MetadataQuery, List<MetadataCandidate>> cache;
    private final AtomicLong nextRequestNanos = new AtomicLong(System.nanoTime());

    public OpenLibraryMetadataProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, new OnlineHttpClientProvider(settings).shared());
    }

    @Autowired
    public OpenLibraryMetadataProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper,
                                       OnlineHttpClientProvider httpClients) {
        this(settings, objectMapper,
                Objects.requireNonNull(httpClients, "httpClients").shared(),
                DEFAULT_SEARCH_ENDPOINT,
                DEFAULT_WEB_BASE,
                DEFAULT_COVER_BASE);
    }

    private OpenLibraryMetadataProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper, HttpClient client) {
        this(settings, objectMapper, client, DEFAULT_SEARCH_ENDPOINT, DEFAULT_WEB_BASE, DEFAULT_COVER_BASE);
    }

    OpenLibraryMetadataProvider(
            ApplicationSettingsPort settings,
            ObjectMapper objectMapper,
            HttpClient client,
            URI searchEndpoint,
            URI webBase,
            URI coverBase) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.client = Objects.requireNonNull(client, "client");
        this.searchEndpoint = requireHttpUri(searchEndpoint, "searchEndpoint");
        this.webBase = requireHttpUri(webBase, "webBase");
        this.coverBase = requireHttpUri(coverBase, "coverBase");
        this.cache = candidateCache(settings.getInt("metadata.openLibrary.cacheMinutes", 30));
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
        return settings.getBoolean("metadata.openLibrary.enabled", true);
    }

    @Override
    public List<MetadataCandidate> search(MetadataQuery query, MetadataRequestContext context)
            throws MetadataProviderException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(context, "context");
        context.throwIfStopped();

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
        params.add(param("fields", SEARCH_FIELDS));
        params.add(param("limit", Integer.toString(query.limit())));
        if (query.hasIsbn()) params.add(param("isbn", query.isbn()));
        if (query.hasTitle()) params.add(param("title", query.title()));
        if (query.hasAuthor()) params.add(param("author", query.author()));
        String separator = searchEndpoint.toString().contains("?") ? "&" : "?";
        return URI.create(searchEndpoint + separator + String.join("&", params));
    }

    private List<MetadataCandidate> handleResponse(HttpResponse<String> response, MetadataQuery query)
            throws MetadataProviderException {
        requireSuccessfulResponse(response, PROVIDER_NAME, status -> status == 401 || status == 403);
        JsonNode root = parseJson(objectMapper, response, PROVIDER_NAME);
        JsonNode docs = root == null ? null : root.get("docs");
        if (docs == null || !docs.isArray()) {
            throw new MetadataProviderException(
                    MetadataProviderErrorKind.INVALID_RESPONSE,
                    "Open Library response is missing docs array");
        }

        List<MetadataCandidate> candidates = new ArrayList<>();
        int rank = 0;
        for (JsonNode doc : docs) {
            if (candidates.size() >= query.limit()) break;
            Optional<MetadataCandidate> candidate = mapCandidate(doc, query, rank++);
            candidate.ifPresent(candidates::add);
        }
        return candidates;
    }

    private Optional<MetadataCandidate> mapCandidate(JsonNode doc, MetadataQuery query, int rank) {
        if (doc == null || !doc.isObject()) return Optional.empty();
        String key = clean(doc.path("key").asString(""));
        String title = clean(doc.path("title").asString(""));
        if (key.isBlank() || title.isBlank()) return Optional.empty();

        List<String> isbns = validIsbns(doc.get("isbn"));
        boolean exactIsbn = query.hasIsbn() && isbns.stream().anyMatch(value -> sameIsbn(value, query.isbn()));
        if (query.hasIsbn() && !exactIsbn) return Optional.empty();
        String isbn = exactIsbn ? query.isbn() : preferredIsbn(isbns);

        List<String> authors = textArray(doc.get("author_name"));
        Integer year = positiveIntOrNull(doc.get("first_publish_year"));
        String publisher = firstText(doc.get("publisher"));
        String language = firstText(doc.get("language"));
        String recordUrl = webUri(key).toString();
        String coverUrl = coverUri(doc.get("cover_i"));
        double confidence = confidence(query, title, authors, exactIsbn, rank);

        try {
            return Optional.of(new MetadataCandidate(
                    new MetadataSource(PROVIDER_ID, PROVIDER_NAME, key, recordUrl),
                    confidence,
                    title,
                    authors,
                    isbn,
                    year,
                    publisher,
                    language,
                    "",
                    coverUrl));
        } catch (IllegalArgumentException invalidRemoteData) {
            return Optional.empty();
        }
    }

    private void throttle(MetadataRequestContext context) throws MetadataProviderException {
        boolean identified = !contact().isBlank();
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / (identified ? 3L : 1L);
        context.throwIfStopped();
        long target = reserveRequestSlot(nextRequestNanos, intervalNanos, System.nanoTime());
        waitUntil(target, context, WAIT_POLL_MILLIS);
    }

    private String userAgent() {
        String base = new OnlineHttpPolicy(settings).userAgent();
        String contact = contact();
        String value = contact.isBlank() ? base : base + " (" + contact + ")";
        return safeHeader(value, "MyHomeLib Enterprise/8.0.0");
    }

    private String contact() {
        return safeHeader(settings.get("metadata.openLibrary.contact", ""), "");
    }

    private URI webUri(String key) {
        String normalized = key.startsWith("/") ? key.substring(1) : key;
        return webBase.resolve(normalized);
    }

    private String coverUri(JsonNode coverIdNode) {
        if (coverIdNode == null || !coverIdNode.canConvertToLong()) return "";
        long coverId = coverIdNode.asLong();
        if (coverId <= 0) return "";
        return coverBase.resolve(coverId + "-M.jpg").toString();
    }

    private static double confidence(
            MetadataQuery query,
            String title,
            List<String> authors,
            boolean exactIsbn,
            int rank) {
        return MetadataProviderSupport.rankedMatchConfidence(query, title, authors, exactIsbn, rank, 0.55);
    }

    private static List<String> validIsbns(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (String raw : textArray(node)) {
            Isbn.tryParse(raw).map(Isbn::value).ifPresent(values::add);
        }
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String firstText(JsonNode node) {
        List<String> values = textArray(node);
        return values.isEmpty() ? "" : values.getFirst();
    }

    private static Integer positiveIntOrNull(JsonNode node) {
        if (node == null || !node.canConvertToInt()) return null;
        int value = node.asInt();
        return value > 0 && value <= 9999 ? value : null;
    }

}

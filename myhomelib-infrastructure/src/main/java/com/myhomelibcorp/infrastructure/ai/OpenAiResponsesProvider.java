package com.myhomelibcorp.infrastructure.ai;

import com.myhomelibcorp.shared.util.NetworkUris;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.ai.AiProvider;
import com.myhomelibcorp.application.ai.AiProviderCapabilities;
import com.myhomelibcorp.application.ai.AiProviderContext;
import com.myhomelibcorp.application.ai.AiProviderErrorKind;
import com.myhomelibcorp.application.ai.AiProviderException;
import com.myhomelibcorp.application.ai.AiRequest;
import com.myhomelibcorp.application.ai.AiResponse;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Optional provider for the OpenAI Responses API. Endpoint/model stay configurable so the desktop
 * UI can also target a compatible corporate gateway. The API key is supplied only through
 * {@link AiProviderContext}; this class never reads or persists host credentials directly.
 */
@Component
public final class OpenAiResponsesProvider implements AiProvider {
    public static final String ID = "openai.responses";
    public static final String ENDPOINT_KEY = "ai.openai.endpoint";
    public static final String MODEL_KEY = "ai.openai.model";
    public static final String MAX_OUTPUT_TOKENS_KEY = "ai.openai.maxOutputTokens";
    public static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/responses";
    public static final String DEFAULT_MODEL = "gpt-5.6-luna";
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 2048;
    private static final int MAX_MAX_OUTPUT_TOKENS = 32768;
    private static final int MAX_ERROR_BODY = 16_384;

    private final ApplicationSettingsPort settings;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public OpenAiResponsesProvider(ApplicationSettingsPort settings, ObjectMapper mapper) {
        this(settings, mapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20))
                .build());
    }

    OpenAiResponsesProvider(ApplicationSettingsPort settings, ObjectMapper mapper, HttpClient http) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public String id() { return ID; }

    @Override
    public String displayName() { return "OpenAI (Responses API)"; }

    @Override
    public AiProviderCapabilities capabilities() {
        return new AiProviderCapabilities(Set.of(AiOperation.SUMMARY, AiOperation.QUESTION_ANSWER), true, Set.of("api-key"));
    }

    @Override
    public AiResponse execute(AiRequest request, AiProviderContext context) throws AiProviderException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        context.throwIfStopped();

        URI endpoint = validateEndpoint(settings.get(ENDPOINT_KEY, DEFAULT_ENDPOINT));
        String model = required(settings.get(MODEL_KEY, DEFAULT_MODEL), "model");
        int maxTokens = Math.max(1, Math.min(MAX_MAX_OUTPUT_TOKENS,
                settings.getInt(MAX_OUTPUT_TOKENS_KEY, DEFAULT_MAX_OUTPUT_TOKENS)));
        String apiKey = context.secret("api-key")
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new AiProviderException(AiProviderErrorKind.AUTHENTICATION,
                        "Ключ API для OpenAI не налаштовано"));

        byte[] body;
        try {
            body = mapper.writeValueAsBytes(requestBody(model, maxTokens, request));
        } catch (Exception e) {
            throw new AiProviderException(AiProviderErrorKind.FAILED, "Не вдалося сформувати запит до ШІ", e);
        }

        Duration remaining = context.remainingTimeout();
        if (remaining.isZero()) throw AiProviderException.timeout();
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(remaining)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> response = await(future, context);
            return parseResponse(response);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private ObjectNode requestBody(String model, int maxTokens, AiRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_output_tokens", maxTokens);

        ArrayNode input = root.putArray("input");
        ObjectNode developer = input.addObject();
        developer.put("role", "developer");
        ArrayNode developerContent = developer.putArray("content");
        developerContent.addObject().put("type", "input_text").put("text",
                "Ти допомагаєш користувачу працювати з особистою електронною бібліотекою. "
                        + "Відповідай українською, не вигадуй відсутні факти і чітко відділяй зміст книги від власних висновків.");

        ObjectNode user = input.addObject();
        user.put("role", "user");
        ArrayNode userContent = user.putArray("content");
        userContent.addObject().put("type", "input_text").put("text", buildUserText(request));
        return root;
    }

    private static String buildUserText(AiRequest request) {
        String task = switch (request.operation()) {
            case SUMMARY -> "Стисло підсумуй наданий текст книги. ";
            case QUESTION_ANSWER -> "Дай відповідь на запитання, спираючись лише на наданий текст книги. ";
        };
        StringBuilder text = new StringBuilder(task).append("Запит користувача: ").append(request.prompt());
        if (request.sharesBookContent()) {
            text.append("\n\nТекст книги:\n").append(request.bookContent());
        }
        return text.toString();
    }

    private AiResponse parseResponse(HttpResponse<byte[]> response) throws AiProviderException {
        int status = response.statusCode();
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        if (status < 200 || status >= 300) {
            throw statusFailure(status, bytes);
        }
        try {
            JsonNode root = mapper.readTree(bytes);
            String text = extractOutputText(root);
            if (text.isBlank()) {
                throw new AiProviderException(AiProviderErrorKind.INVALID_RESPONSE,
                        "Сервіс ШІ повернув відповідь без тексту");
            }
            return new AiResponse(text);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiProviderException(AiProviderErrorKind.INVALID_RESPONSE,
                    "Не вдалося прочитати відповідь сервісу ШІ", e);
        }
    }

    private AiProviderException statusFailure(int status, byte[] bytes) {
        AiProviderErrorKind kind = switch (status) {
            case 401, 403 -> AiProviderErrorKind.AUTHENTICATION;
            case 408 -> AiProviderErrorKind.TIMEOUT;
            case 429 -> AiProviderErrorKind.RATE_LIMITED;
            default -> status >= 500 ? AiProviderErrorKind.UNAVAILABLE : AiProviderErrorKind.FAILED;
        };
        String detail = "HTTP " + status;
        try {
            JsonNode root = mapper.readTree(bytes);
            JsonNode message = root.path("error").path("message");
            if (message.isTextual() && !message.asText().isBlank()) detail += ": " + message.asText();
        } catch (Exception ignored) {
            if (bytes.length > 0) {
                String raw = new String(bytes, 0, Math.min(bytes.length, MAX_ERROR_BODY), StandardCharsets.UTF_8)
                        .replaceAll("[\\r\\n\\t]+", " ").trim();
                if (!raw.isBlank()) detail += ": " + raw;
            }
        }
        return new AiProviderException(kind, detail);
    }

    private static String extractOutputText(JsonNode root) {
        if (root == null) return "";
        JsonNode direct = root.get("output_text");
        if (direct != null && direct.isTextual()) return direct.asText().trim();
        StringBuilder result = new StringBuilder();
        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content == null || !content.isArray()) continue;
                for (JsonNode part : content) {
                    JsonNode text = part.get("text");
                    if (text != null && text.isTextual() && !text.asText().isBlank()) {
                        if (!result.isEmpty()) result.append('\n');
                        result.append(text.asText().trim());
                    }
                }
            }
        }
        return result.toString().trim();
    }

    private static HttpResponse<byte[]> await(CompletableFuture<HttpResponse<byte[]>> future,
                                              AiProviderContext context) throws AiProviderException {
        while (true) {
            context.throwIfStopped();
            Duration remaining = context.remainingTimeout();
            if (remaining.isZero()) throw AiProviderException.timeout();
            long waitMillis = Math.max(1L, Math.min(200L, remaining.toMillis()));
            try {
                return future.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Poll cancellation/deadline instead of blocking for the whole request.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw AiProviderException.cancelled();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new AiProviderException(AiProviderErrorKind.UNAVAILABLE,
                        "Не вдалося з'єднатися із сервісом ШІ: " + safeMessage(cause), cause);
            }
        }
    }

    public static URI validateEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(required(value, "endpoint")).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Некоректна URL-адреса сервісу ШІ", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || NetworkUris.isLoopbackHttp(uri))) {
            throw new IllegalArgumentException("Сервіс ШІ має використовувати HTTPS; HTTP дозволено лише для localhost");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("URL сервісу ШІ не повинен містити облікові дані");
        return uri;
    }


    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.isBlank()) return error == null ? "невідома помилка" : error.getClass().getSimpleName();
        String clean = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300);
    }
}
package com.myhomelibcorp.application.content;

import com.myhomelibcorp.application.port.out.content.ContentExtractor;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Selects a format adapter and maps unsupported/cancelled/failure states to a stable typed result. */
@Service
public class ContentExtractionService {
    private final List<ContentExtractor> extractors;
    private final RuntimeExtensionRegistry runtimeExtensions;

    public ContentExtractionService(List<ContentExtractor> extractors) {
        this(extractors, new RuntimeExtensionRegistry());
    }

    @Autowired
    public ContentExtractionService(List<ContentExtractor> extractors, RuntimeExtensionRegistry runtimeExtensions) {
        this.extractors = extractors == null ? List.of() : extractors.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ContentExtractor::id))
                .toList();
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

    public ContentExtractionResult extract(ContentExtractionRequest request, ContentExtractionContext context) {
        Objects.requireNonNull(request, "request");
        ContentExtractionContext effectiveContext = context == null ? ContentExtractionContext.none() : context;
        ContentExtractor extractor = allExtractors().stream()
                .filter(candidate -> candidate.supports(request.format()))
                .findFirst()
                .orElse(null);
        if (extractor == null) return ContentExtractionResult.unsupported(request.format());

        try {
            effectiveContext.report("starting", 0L, 1L);
            ExtractedContent content = extractor.extract(request, effectiveContext);
            effectiveContext.throwIfCancelled();
            effectiveContext.report("complete", 1L, 1L);
            return ContentExtractionResult.success(extractor.id(), content);
        } catch (ContentExtractionCancelledException cancelled) {
            return ContentExtractionResult.cancelled(extractor.id());
        } catch (IOException | RuntimeException failure) {
            return ContentExtractionResult.failed(extractor.id(), rootMessage(failure));
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
    private List<ContentExtractor> allExtractors() {
        java.util.LinkedHashMap<String, ContentExtractor> result = new java.util.LinkedHashMap<>();
        extractors.forEach(extractor -> result.putIfAbsent(extractor.id(), extractor));
        runtimeExtensions.contentExtractors().forEach(extractor -> result.putIfAbsent(extractor.id(), extractor));
        return result.values().stream().sorted(Comparator.comparing(ContentExtractor::id)).toList();
    }

}

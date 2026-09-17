package com.myhomelibcorp.application.extension;

import com.myhomelibcorp.application.ai.AiProvider;
import com.myhomelibcorp.application.dictionary.DictionaryProvider;
import com.myhomelibcorp.application.metadata.MetadataProvider;
import com.myhomelibcorp.application.port.out.content.ContentExtractor;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.translation.TranslationProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for extensions that may be enabled or disabled while the desktop application is running.
 * Static Spring beans remain the core providers; this registry contains only runtime/plugin-owned providers.
 */
@Component
public final class RuntimeExtensionRegistry {
    private final Map<String, ExtensionBundle> plugins = new ConcurrentHashMap<>();

    public record ExtensionBundle(
            List<MetadataProvider> metadataProviders,
            List<DictionaryProvider> dictionaryProviders,
            List<TranslationProvider> translationProviders,
            List<ContentExtractor> contentExtractors,
            List<CoverExtractor> coverExtractors,
            List<BookImporterPort> bookImporters,
            List<BookConverter> bookConverters,
            List<AiProvider> aiProviders,
            List<DeviceProfileDetector> deviceProfileDetectors,
            List<LocalMetadataExtractor> localMetadataExtractors
    ) {
        public ExtensionBundle {
            metadataProviders = copy(metadataProviders);
            dictionaryProviders = copy(dictionaryProviders);
            translationProviders = copy(translationProviders);
            contentExtractors = copy(contentExtractors);
            coverExtractors = copy(coverExtractors);
            bookImporters = copy(bookImporters);
            bookConverters = copy(bookConverters);
            aiProviders = copy(aiProviders);
            deviceProfileDetectors = copy(deviceProfileDetectors);
            localMetadataExtractors = copy(localMetadataExtractors);
        }

        public static ExtensionBundle empty() {
            return new ExtensionBundle(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        private static <T> List<T> copy(Collection<? extends T> values) {
            if (values == null || values.isEmpty()) return List.of();
            List<T> result = new ArrayList<>(values.size());
            for (T value : values) if (value != null) result.add(value);
            return List.copyOf(result);
        }
    }

    public void replacePlugin(String pluginId, ExtensionBundle bundle) {
        requirePluginId(pluginId);
        plugins.put(pluginId, Objects.requireNonNull(bundle, "bundle"));
    }

    public boolean removePlugin(String pluginId) {
        requirePluginId(pluginId);
        return plugins.remove(pluginId) != null;
    }

    public void clear() { plugins.clear(); }

    public List<MetadataProvider> metadataProviders() { return flatten(ExtensionBundle::metadataProviders); }
    public List<DictionaryProvider> dictionaryProviders() { return flatten(ExtensionBundle::dictionaryProviders); }
    public List<TranslationProvider> translationProviders() { return flatten(ExtensionBundle::translationProviders); }
    public List<ContentExtractor> contentExtractors() { return flatten(ExtensionBundle::contentExtractors); }
    public List<CoverExtractor> coverExtractors() { return flatten(ExtensionBundle::coverExtractors); }
    public List<BookImporterPort> bookImporters() { return flatten(ExtensionBundle::bookImporters); }
    public List<BookConverter> bookConverters() { return flatten(ExtensionBundle::bookConverters); }
    public List<AiProvider> aiProviders() { return flatten(ExtensionBundle::aiProviders); }
    public List<DeviceProfileDetector> deviceProfileDetectors() { return flatten(ExtensionBundle::deviceProfileDetectors); }
    public List<LocalMetadataExtractor> localMetadataExtractors() { return flatten(ExtensionBundle::localMetadataExtractors); }

    /** Core converters first, runtime plugin converters second; duplicate ids never shadow core providers. */
    public List<BookConverter> mergeBookConverters(Collection<? extends BookConverter> coreConverters) {
        Map<String, BookConverter> result = new LinkedHashMap<>();
        if (coreConverters != null) {
            for (BookConverter converter : coreConverters) {
                if (converter != null) result.putIfAbsent(converter.id(), converter);
            }
        }
        for (BookConverter converter : bookConverters()) {
            if (converter != null) result.putIfAbsent(converter.id(), converter);
        }
        return List.copyOf(result.values());
    }

    private <T> List<T> flatten(java.util.function.Function<ExtensionBundle, List<T>> extractor) {
        List<T> result = new ArrayList<>();
        plugins.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.addAll(extractor.apply(entry.getValue())));
        return List.copyOf(result);
    }

    private static void requirePluginId(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) throw new IllegalArgumentException("pluginId is required");
    }
}

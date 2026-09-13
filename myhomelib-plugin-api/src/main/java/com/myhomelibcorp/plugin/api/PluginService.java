package com.myhomelibcorp.plugin.api;

/** Stable service identifiers exposed to plugin manifests. */
public enum PluginService {
    METADATA_PROVIDER(MetadataProvider.class),
    COVER_PROVIDER(CoverProvider.class),
    BOOK_IMPORTER(BookImporter.class),
    METADATA_EXTRACTOR(MetadataExtractor.class),
    CONTENT_EXTRACTOR(ContentExtractor.class),
    EXPORT_PROVIDER(ExportProvider.class),
    TRANSLATION_PROVIDER(TranslationProvider.class),
    DICTIONARY_PROVIDER(DictionaryProvider.class),
    DEVICE_PROVIDER(DeviceProvider.class),
    AI_PROVIDER(AiProvider.class);

    private final Class<?> contractType;

    PluginService(Class<?> contractType) {
        this.contractType = contractType;
    }

    public Class<?> contractType() {
        return contractType;
    }
}

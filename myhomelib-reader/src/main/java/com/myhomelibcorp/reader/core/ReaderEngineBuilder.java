package com.myhomelibcorp.reader.core;

import com.myhomelibcorp.reader.api.BookFormatRegistry;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.core.cache.ImageCache;
import com.myhomelibcorp.reader.core.cache.PageCache;
import com.myhomelibcorp.reader.core.position.ReaderPositionManager;
import com.myhomelibcorp.reader.layout.FontMetricsProvider;
import com.myhomelibcorp.reader.layout.FontMetricsProviderImpl;
import com.myhomelibcorp.reader.layout.TextLayoutEngine;
import com.myhomelibcorp.reader.render.api.ReaderRenderer;

public class ReaderEngineBuilder {

    private BookFormatRegistry formatRegistry;
    private ReaderPositionManager positionManager;
    private PageCache pageCache;
    private ImageCache imageCache;
    private ReaderSettings settings;
    private TextLayoutEngine layoutEngine;
    private ReaderRenderer renderer;

    public ReaderEngineBuilder() {
        this.positionManager = new ReaderPositionManager();
        this.pageCache = new PageCache(5);
        this.imageCache = new ImageCache(32 * 1024 * 1024);
        this.settings = ReaderSettings.defaultSettings();
    }

    public ReaderEngineBuilder formatRegistry(BookFormatRegistry formatRegistry) {
        this.formatRegistry = formatRegistry;
        return this;
    }

    public ReaderEngineBuilder positionManager(ReaderPositionManager positionManager) {
        this.positionManager = positionManager;
        return this;
    }

    public ReaderEngineBuilder pageCache(PageCache pageCache) {
        this.pageCache = pageCache;
        return this;
    }

    public ReaderEngineBuilder imageCache(ImageCache imageCache) {
        this.imageCache = imageCache;
        return this;
    }

    public ReaderEngineBuilder settings(ReaderSettings settings) {
        this.settings = settings;
        return this;
    }

    public ReaderEngineBuilder renderer(ReaderRenderer renderer) {
        this.renderer = renderer;
        return this;
    }

    public ReaderEngineBuilder withLayoutEngine(TextLayoutEngine engine) {
        this.layoutEngine = engine;
        return this;
    }

    public ReaderEngine build() {
        if (formatRegistry == null) {
            throw new IllegalStateException("BookFormatRegistry must be set");
        }

        if (renderer == null) {
            throw new IllegalStateException("ReaderRenderer must be set");
        }

        if (layoutEngine == null) {
            FontMetricsProvider fontMetrics = new FontMetricsProviderImpl(settings);
            layoutEngine = new TextLayoutEngine(fontMetrics, settings);
        }

        return new ReaderEngine(
                formatRegistry,
                layoutEngine,
                renderer,
                pageCache,
                imageCache,
                positionManager,
                settings
        );
    }
}
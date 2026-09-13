package com.myhomelibcorp.plugins.samples;

import com.myhomelibcorp.application.dictionary.DictionaryEntry;
import com.myhomelibcorp.application.dictionary.DictionaryQuery;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import com.myhomelibcorp.plugin.api.DictionaryProvider;
import com.myhomelibcorp.plugin.api.PluginApiRange;
import com.myhomelibcorp.plugin.api.PluginEntrypoint;
import com.myhomelibcorp.plugin.api.PluginManifest;
import com.myhomelibcorp.plugin.api.PluginService;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal offline dictionary sample: no host capability is required. */
public final class SampleDictionaryPlugin implements PluginEntrypoint {
    private static final DictionaryProvider PROVIDER = new DictionaryProvider() {
        @Override public String id() { return "sample-dictionary"; }
        @Override public String displayName() { return "Sample Dictionary"; }
        @Override public boolean isOffline() { return true; }
        @Override
        public List<DictionaryEntry> lookup(DictionaryQuery query, TextProviderRequestContext context) {
            return List.of(new DictionaryEntry(
                    id(), displayName(), query.term(), query.language(), "sample",
                    "Example definition supplied by the SDK sample.", List.of(), "MyHomeLib SDK sample"
            ));
        }
    };

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(
                "sample.sdk.dictionary",
                "SDK Sample Dictionary",
                "1.0.0",
                PluginApiRange.currentMajor(),
                Set.of(PluginService.DICTIONARY_PROVIDER),
                Set.of(),
                Set.of()
        );
    }

    @Override
    public Map<PluginService, Object> services() {
        return Map.of(PluginService.DICTIONARY_PROVIDER, PROVIDER);
    }
}

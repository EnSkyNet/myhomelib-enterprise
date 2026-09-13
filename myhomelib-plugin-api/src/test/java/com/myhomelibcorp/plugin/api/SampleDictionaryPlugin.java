package com.myhomelibcorp.plugin.api;

import com.myhomelibcorp.application.dictionary.DictionaryEntry;
import com.myhomelibcorp.application.dictionary.DictionaryQuery;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SampleDictionaryPlugin implements PluginEntrypoint {
    @Override public PluginManifest manifest() {
        return new PluginManifest("sample.dictionary", "Sample Dictionary", "1.0.0",
                PluginApiRange.currentMajor(), Set.of(PluginService.DICTIONARY_PROVIDER), Set.of());
    }
    @Override public Map<PluginService,Object> services() {
        return Map.of(PluginService.DICTIONARY_PROVIDER, new DictionaryProvider() {
            public String id(){ return "sample"; }
            public String displayName(){ return "Sample"; }
            public boolean isOffline(){ return true; }
            public List<DictionaryEntry> lookup(DictionaryQuery query, TextProviderRequestContext context){ return List.of(); }
        });
    }
}

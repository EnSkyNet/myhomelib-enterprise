package com.myhomelibcorp.plugins.samples;

import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.application.metadata.MetadataQuery;
import com.myhomelibcorp.application.metadata.MetadataRequestContext;
import com.myhomelibcorp.application.metadata.MetadataSource;
import com.myhomelibcorp.plugin.api.MetadataProvider;
import com.myhomelibcorp.plugin.api.PluginApiRange;
import com.myhomelibcorp.plugin.api.PluginEntrypoint;
import com.myhomelibcorp.plugin.api.PluginManifest;
import com.myhomelibcorp.plugin.api.PluginPermission;
import com.myhomelibcorp.plugin.api.PluginService;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Remote-provider-shaped sample that demonstrates an explicit network capability declaration. */
public final class SampleMetadataPlugin implements PluginEntrypoint {
    private static final MetadataProvider PROVIDER = new MetadataProvider() {
        @Override public String id() { return "sample-metadata"; }
        @Override public String displayName() { return "Sample Metadata"; }
        @Override
        public List<MetadataCandidate> search(MetadataQuery query, MetadataRequestContext context) {
            String title = query.hasTitle() ? query.title() : "SDK Sample Result";
            String author = query.hasAuthor() ? query.author() : "Sample Author";
            return List.of(new MetadataCandidate(
                    new MetadataSource(id(), displayName(), "sample-record", ""),
                    0.5,
                    title,
                    List.of(author),
                    query.hasIsbn() ? query.isbn() : "",
                    null,
                    "",
                    "",
                    "Generated locally by the SDK sample; replace with a bounded remote lookup.",
                    ""
            ));
        }
    };

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(
                "sample.sdk.metadata",
                "SDK Sample Metadata",
                "1.0.0",
                PluginApiRange.currentMajor(),
                Set.of(PluginService.METADATA_PROVIDER),
                Set.of(),
                Set.of(PluginPermission.NETWORK_ACCESS)
        );
    }

    @Override
    public Map<PluginService, Object> services() {
        return Map.of(PluginService.METADATA_PROVIDER, PROVIDER);
    }
}

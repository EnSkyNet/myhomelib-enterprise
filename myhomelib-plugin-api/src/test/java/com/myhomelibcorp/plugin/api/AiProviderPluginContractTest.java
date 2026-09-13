package com.myhomelibcorp.plugin.api;

import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.ai.AiProviderCapabilities;
import com.myhomelibcorp.application.ai.AiProviderContext;
import com.myhomelibcorp.application.ai.AiProviderException;
import com.myhomelibcorp.application.ai.AiRequest;
import com.myhomelibcorp.application.ai.AiResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderPluginContractTest {
    @Test
    void networkAiProviderMustDeclareNetworkPermission() {
        PluginEntrypoint plugin = aiPlugin("ai.network.no-permission", true, Set.of());
        PluginLoader loader = new PluginLoader(Set.of());

        assertThatThrownBy(() -> loader.load(plugin, PluginApproval.trusted(plugin.manifest())))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("must declare NETWORK_ACCESS");
    }

    @Test
    void networkAiProviderLoadsWithExplicitNetworkPermission() {
        PluginEntrypoint plugin = aiPlugin("ai.network.approved", true, Set.of(PluginPermission.NETWORK_ACCESS));
        LoadedPlugin loaded = new PluginLoader(Set.of()).load(plugin, PluginApproval.trusted(plugin.manifest()));

        assertThat(loaded.services()).containsKey(PluginService.AI_PROVIDER);
        assertThat(loaded.securityContext().approvedPermissions()).containsExactly(PluginPermission.NETWORK_ACCESS);
    }

    @Test
    void localAiProviderDoesNotNeedNetworkPermission() {
        PluginEntrypoint plugin = aiPlugin("ai.local", false, Set.of());
        LoadedPlugin loaded = new PluginLoader(Set.of()).load(plugin, PluginApproval.trusted(plugin.manifest()));

        assertThat(loaded.manifest().permissions()).isEmpty();
    }

    private static PluginEntrypoint aiPlugin(String id, boolean network, Set<PluginPermission> permissions) {
        AiProvider provider = new AiProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public AiProviderCapabilities capabilities() {
                return new AiProviderCapabilities(Set.of(AiOperation.SUMMARY), network, Set.of());
            }
            @Override public AiResponse execute(AiRequest request, AiProviderContext context) throws AiProviderException {
                return new AiResponse("test");
            }
        };
        PluginManifest manifest = new PluginManifest(id, id, "1.0.0", PluginApiRange.currentMajor(),
                Set.of(PluginService.AI_PROVIDER), Set.of(), permissions);
        return new PluginEntrypoint() {
            @Override public PluginManifest manifest() { return manifest; }
            @Override public Map<PluginService, Object> services() { return Map.of(PluginService.AI_PROVIDER, provider); }
        };
    }
}

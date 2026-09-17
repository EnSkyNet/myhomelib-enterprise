package com.myhomelibcorp.integration;

import com.myhomelibcorp.plugin.api.PluginEntrypoint;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledPluginDiscoveryTest {

    @Test
    void calibreConverterIsDiscoverableThroughRuntimeServiceLoader() {
        Set<String> pluginIds = ServiceLoader.load(PluginEntrypoint.class,
                        Thread.currentThread().getContextClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(entrypoint -> entrypoint.manifest().pluginId())
                .collect(Collectors.toSet());

        assertTrue(pluginIds.contains("converter.calibre"),
                () -> "Bundled calibre converter was not found by ServiceLoader. Found: " + pluginIds);
    }
}

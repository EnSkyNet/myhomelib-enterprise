package com.myhomelibcorp.plugin.api;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Developer-side contract harness for plugin CI/tests.
 *
 * <p>The harness validates API compatibility, manifest/service consistency, declared core overrides and
 * permission declarations by running the same structural checks as the host loader. It deliberately uses
 * a synthetic trusted approval for the exact manifest under test; this is a build-time assertion only and
 * does not grant runtime trust in MyHomeLib.</p>
 */
public final class PluginTestHarness {
    private PluginTestHarness() {
    }

    public static PluginManifest verify(PluginEntrypoint entrypoint) {
        return verify(entrypoint, Set.of());
    }

    public static PluginManifest verify(PluginEntrypoint entrypoint, Set<PluginService> coreServices) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        Set<PluginService> protectedCore = copyServices(coreServices);
        PluginManifest manifest = Objects.requireNonNull(entrypoint.manifest(), "plugin manifest");
        PluginLoader loader = new PluginLoader(protectedCore);
        LoadedPlugin loaded = loader.load(entrypoint, PluginApproval.trusted(manifest));
        loader.unload(loaded.manifest().pluginId());
        return loaded.manifest();
    }

    /**
     * Validate every {@link PluginEntrypoint} discoverable from a test/sample classpath.
     * Any invalid plugin fails immediately with {@link PluginLoadException}.
     */
    public static List<PluginManifest> verifyServiceLoader(ClassLoader classLoader) {
        return verifyServiceLoader(classLoader, Set.of());
    }

    public static List<PluginManifest> verifyServiceLoader(
            ClassLoader classLoader,
            Set<PluginService> coreServices
    ) {
        Objects.requireNonNull(classLoader, "classLoader");
        Set<PluginService> protectedCore = copyServices(coreServices);
        List<PluginManifest> verified = new ArrayList<>();
        ServiceLoader.load(PluginEntrypoint.class, classLoader)
                .forEach(entrypoint -> verified.add(verify(entrypoint, protectedCore)));
        return List.copyOf(verified);
    }

    private static Set<PluginService> copyServices(Set<PluginService> services) {
        Objects.requireNonNull(services, "coreServices");
        return services.isEmpty() ? Set.of() : EnumSet.copyOf(services);
    }
}

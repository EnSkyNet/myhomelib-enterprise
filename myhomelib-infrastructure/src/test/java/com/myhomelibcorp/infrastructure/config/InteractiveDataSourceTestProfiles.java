package com.myhomelibcorp.infrastructure.config;

/** Test-only factory for opt-in same-machine A/B probes. Production defaults are not read from system properties. */
public final class InteractiveDataSourceTestProfiles {
    private InteractiveDataSourceTestProfiles() {
    }

    public static DataSourceConfig fromSystemProperties() {
        int poolSize = Integer.getInteger("mhl.ab.poolSize", DataSourceConfig.INTERACTIVE_MAX_POOL_SIZE);
        int cacheSizeKiB = Integer.getInteger("mhl.ab.cacheSizeKiB", DataSourceConfig.INTERACTIVE_CACHE_SIZE_KIB);
        long mmapSizeBytes = Long.getLong("mhl.ab.mmapSizeBytes", DataSourceConfig.INTERACTIVE_MMAP_SIZE_BYTES);
        return new DataSourceConfig(new DataSourceConfig.InteractiveProfile(poolSize, cacheSizeKiB, mmapSizeBytes));
    }
}

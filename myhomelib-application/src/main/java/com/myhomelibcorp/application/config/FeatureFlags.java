package com.myhomelibcorp.application.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.features")
public class FeatureFlags {
    private boolean newImporter = true;
    private boolean newNavigation = true;
    private boolean virtualTable = true;
    private boolean lazySearch = true;
    private boolean asyncCoverLoading = true;
    private boolean useFts5 = false;
}
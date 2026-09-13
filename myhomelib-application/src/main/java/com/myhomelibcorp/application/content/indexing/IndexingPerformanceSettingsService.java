package com.myhomelibcorp.application.content.indexing;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class IndexingPerformanceSettingsService {
    public static final String PROFILE_KEY = "content.indexing.profile";
    public static final String PAUSE_ON_BATTERY_KEY = "content.indexing.pauseOnBattery";
    private final ApplicationSettingsPort settings;

    public IndexingPerformanceSettingsService(ApplicationSettingsPort settings) { this.settings = settings; }

    public IndexingPerformanceSettings load() {
        IndexingResourceProfile profile;
        try { profile = IndexingResourceProfile.valueOf(settings.get(PROFILE_KEY, "BALANCED").trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ignored) { profile = IndexingResourceProfile.BALANCED; }
        boolean pause = settings.getBoolean(PAUSE_ON_BATTERY_KEY, profile.defaultPauseOnBattery());
        return new IndexingPerformanceSettings(profile, pause);
    }

    public void save(IndexingPerformanceSettings value) {
        IndexingPerformanceSettings effective = value == null
                ? new IndexingPerformanceSettings(IndexingResourceProfile.BALANCED, true) : value;
        settings.put(PROFILE_KEY, effective.profile().name());
        settings.putBoolean(PAUSE_ON_BATTERY_KEY, effective.pauseOnBattery());
    }
}

package com.myhomelibcorp.ui.opds;

import com.myhomelibcorp.application.opds.OpdsServerControl;
import com.myhomelibcorp.application.opds.OpdsSettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Starts/stops the OPDS sidecar with the desktop lifecycle; contains no server implementation. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpdsDesktopLifecycle {
    private final OpdsServerControl serverControl;
    private final OpdsSettingsService settingsService;

    @PostConstruct
    void autostart() {
        var settings = settingsService.load();
        if (!settings.autostart()) return;
        var result = serverControl.start(settings);
        if (!result.running()) log.warn("OPDS autostart failed: {}", result.message());
    }

    @PreDestroy
    void stop() {
        serverControl.stop();
    }
}

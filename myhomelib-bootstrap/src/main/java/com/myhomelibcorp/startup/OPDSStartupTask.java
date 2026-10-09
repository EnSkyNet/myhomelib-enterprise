package com.myhomelibcorp.startup;

import com.myhomelibcorp.application.opds.OpdsServerControl;
import com.myhomelibcorp.application.opds.OpdsServerSettings;
import com.myhomelibcorp.application.opds.OpdsServerStatus;
import com.myhomelibcorp.application.opds.OpdsSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OPDSStartupTask implements StartupTask {
    private final OpdsSettingsService settingsService;
    private final ObjectProvider<OpdsServerControl> serverControlProvider;

    @Override public String id() { return "OPDSStartupTask"; }
    @Override public StartupFailurePolicy failurePolicy() { return StartupFailurePolicy.BEST_EFFORT; }

    @Override
    public StartupTaskResult execute(StartupContext context) {
        OpdsServerSettings settings = settingsService.load();
        if (!settings.autostart()) {
            return StartupTaskResult.skipped("OPDS autostart is disabled");
        }

        OpdsServerStatus status = serverControlProvider.getObject().start(settings);
        if (!status.running()) {
            String detail = status.technicalDetail();
            throw new IllegalStateException(detail == null || detail.isBlank()
                    ? status.messageKey()
                    : detail);
        }
        return StartupTaskResult.success("OPDS started at " + status.baseUrl());
    }
}

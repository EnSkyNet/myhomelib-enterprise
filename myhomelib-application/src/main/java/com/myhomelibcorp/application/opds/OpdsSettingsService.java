package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpdsSettingsService {
    private static final String P = "opds.";
    private final ApplicationSettingsPort settings;

    public OpdsServerSettings load() {
        var defaults = OpdsServerSettings.defaults();
        return new OpdsServerSettings(
                settings.get(P + "bindAddress", defaults.bindAddress()),
                settings.getInt(P + "port", defaults.port()),
                settings.getBoolean(P + "basicAuthEnabled", false),
                settings.get(P + "username", ""),
                settings.get(P + "password", ""),
                settings.getBoolean(P + "autostart", false));
    }

    public void save(OpdsServerSettings value) {
        settings.put(P + "bindAddress", value.bindAddress());
        settings.putInt(P + "port", value.port());
        settings.putBoolean(P + "basicAuthEnabled", value.basicAuthEnabled());
        settings.put(P + "username", value.username());
        settings.put(P + "password", value.password());
        settings.putBoolean(P + "autostart", value.autostart());
    }
}

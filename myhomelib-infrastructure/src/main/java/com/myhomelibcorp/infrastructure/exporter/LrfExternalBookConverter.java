package com.myhomelibcorp.infrastructure.exporter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.stereotype.Component;
@Component public class LrfExternalBookConverter extends ExternalCommandBookConverter {
    public LrfExternalBookConverter(ApplicationSettingsPort s) { super(s, "converter.lrf.command", ".lrf", "LRF"); }
}

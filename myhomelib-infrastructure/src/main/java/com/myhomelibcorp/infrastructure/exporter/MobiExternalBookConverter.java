package com.myhomelibcorp.infrastructure.exporter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.stereotype.Component;
@Component public class MobiExternalBookConverter extends ExternalCommandBookConverter {
    public MobiExternalBookConverter(ApplicationSettingsPort s) { super(s, "converter.mobi.command", ".mobi", "MOBI"); }
}

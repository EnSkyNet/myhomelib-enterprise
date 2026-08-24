package com.myhomelibcorp.infrastructure.exporter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.stereotype.Component;
@Component public class PdfExternalBookConverter extends ExternalCommandBookConverter {
    public PdfExternalBookConverter(ApplicationSettingsPort s) { super(s, "converter.pdf.command", ".pdf", "PDF"); }
}

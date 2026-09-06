package com.myhomelibcorp.application.imports.detector;

import com.myhomelibcorp.shared.format.SupportedFormat;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class BookFormatDetector {
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();

    /** Legacy coarse categories retained for API compatibility; detection comes exclusively from the shared registry. */
    public enum Format { FB2, EPUB, TXT, ZIP, INPX, MOBI, PDF, DOCX, OTHER, UNKNOWN }

    public Format detect(Path file) {
        return FORMATS.detect(file).map(BookFormatDetector::legacyCategory).orElse(Format.UNKNOWN);
    }

    public boolean isSupported(Path file) {
        return FORMATS.isImportSupported(file);
    }

    private static Format legacyCategory(SupportedFormat format) {
        if (format.family() == SupportedFormat.Family.ARCHIVE) return Format.ZIP;
        return switch (format.searchFormat()) {
            case "FB2" -> Format.FB2;
            case "EPUB" -> Format.EPUB;
            case "TXT" -> Format.TXT;
            case "INPX" -> Format.INPX;
            case "MOBI", "AZW3" -> Format.MOBI;
            case "PDF" -> Format.PDF;
            case "DOC", "DOCX", "ODT", "RTF", "HTML" -> Format.DOCX;
            default -> Format.OTHER;
        };
    }
}

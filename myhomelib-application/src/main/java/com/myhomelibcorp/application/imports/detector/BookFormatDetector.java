package com.myhomelibcorp.application.imports.detector;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Component
public class BookFormatDetector {

    private static final List<String> FB2_EXTENSIONS = Arrays.asList(".fb2", ".fbd", ".fb2zip");
    private static final List<String> EPUB_EXTENSIONS = Arrays.asList(".epub");
    private static final List<String> ZIP_EXTENSIONS = Arrays.asList(".zip");
    private static final List<String> INPX_EXTENSIONS = Arrays.asList(".inpx", ".inp");
    private static final List<String> MOBI_EXTENSIONS = Arrays.asList(".mobi", ".azw", ".azw3");
    private static final List<String> PDF_EXTENSIONS = Arrays.asList(".pdf");
    private static final List<String> DOCX_EXTENSIONS = Arrays.asList(".docx");

    public enum Format {
        FB2, EPUB, ZIP, INPX, MOBI, PDF, DOCX, UNKNOWN
    }

    public Format detect(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        String extension = getExtension(fileName);

        if (FB2_EXTENSIONS.contains(extension)) return Format.FB2;
        if (EPUB_EXTENSIONS.contains(extension)) return Format.EPUB;
        if (ZIP_EXTENSIONS.contains(extension)) return Format.ZIP;
        if (INPX_EXTENSIONS.contains(extension)) return Format.INPX;
        if (MOBI_EXTENSIONS.contains(extension)) return Format.MOBI;
        if (PDF_EXTENSIONS.contains(extension)) return Format.PDF;
        if (DOCX_EXTENSIONS.contains(extension)) return Format.DOCX;

        return Format.UNKNOWN;
    }

    public boolean isSupported(Path file) {
        return detect(file) != Format.UNKNOWN;
    }

    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1) return "";
        return fileName.substring(lastDot);
    }
}
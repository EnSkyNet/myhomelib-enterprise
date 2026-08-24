package com.myhomelibcorp.application.imports.detector;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Component
public class BookFormatDetector {

    private static final List<String> FB2_EXTENSIONS = Arrays.asList(".fb2", ".fbd", ".fb2zip");
    private static final List<String> EPUB_EXTENSIONS = Arrays.asList(".epub");
    private static final List<String> ZIP_EXTENSIONS = Arrays.asList(".zip", ".fb2zip", ".7z", ".rar", ".cbz", ".jar");
    private static final List<String> TXT_EXTENSIONS = Arrays.asList(".txt", ".text");
    private static final List<String> INPX_EXTENSIONS = Arrays.asList(".inpx", ".inp");
    private static final List<String> MOBI_EXTENSIONS = Arrays.asList(".mobi", ".azw", ".azw3");
    private static final List<String> PDF_EXTENSIONS = Arrays.asList(".pdf");
    private static final List<String> DOCX_EXTENSIONS = Arrays.asList(".doc", ".docx", ".odt", ".rtf", ".html", ".htm", ".xhtml", ".md");
    private static final List<String> OTHER_EXTENSIONS = Arrays.asList(".djvu", ".djv", ".chm", ".cbr");

    public enum Format {
        FB2, EPUB, TXT, ZIP, INPX, MOBI, PDF, DOCX, OTHER, UNKNOWN
    }

    public Format detect(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        String extension = getExtension(fileName);

        if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tar.bz2") || fileName.endsWith(".tar.xz")
                || fileName.endsWith(".tgz") || fileName.endsWith(".tbz2") || fileName.endsWith(".txz")
                || fileName.endsWith(".tar") || fileName.endsWith(".cpio") || fileName.endsWith(".cbr")) return Format.ZIP;
        if (FB2_EXTENSIONS.contains(extension)) return Format.FB2;
        if (EPUB_EXTENSIONS.contains(extension)) return Format.EPUB;
        if (TXT_EXTENSIONS.contains(extension)) return Format.TXT;
        if (ZIP_EXTENSIONS.contains(extension)) return Format.ZIP;
        if (INPX_EXTENSIONS.contains(extension)) return Format.INPX;
        if (MOBI_EXTENSIONS.contains(extension)) return Format.MOBI;
        if (PDF_EXTENSIONS.contains(extension)) return Format.PDF;
        if (DOCX_EXTENSIONS.contains(extension)) return Format.DOCX;
        if (OTHER_EXTENSIONS.contains(extension)) return Format.OTHER;

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
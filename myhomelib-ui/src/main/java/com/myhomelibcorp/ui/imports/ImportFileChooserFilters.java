package com.myhomelibcorp.ui.imports;

import com.myhomelibcorp.shared.format.SupportedFormat;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import com.myhomelibcorp.ui.service.LocalizationService;
import javafx.stage.FileChooser;

import java.util.List;
import java.util.function.Predicate;

/** JavaFX filters derived from the shared format capability registry. */
public final class ImportFileChooserFilters {
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();

    private ImportFileChooserFilters() { }

    public static List<FileChooser.ExtensionFilter> standardGroups(LocalizationService i18n) {
        return List.of(
                filter(i18n.text("ui.import.filter.all_supported"), SupportedFormat::importSupported),
                filter(i18n.text("ui.import.filter.books"), f -> f.importSupported() && f.family() == SupportedFormat.Family.BOOK),
                filter(i18n.text("ui.import.filter.catalogs"), f -> f.importSupported() && f.family() == SupportedFormat.Family.CATALOG),
                filter(i18n.text("ui.import.filter.archives"), f -> f.importSupported() && f.family() == SupportedFormat.Family.ARCHIVE));
    }

    public static List<FileChooser.ExtensionFilter> booksAndArchives(LocalizationService i18n) {
        return List.of(filter(i18n.text("ui.import.filter.books_and_archives"), f -> f.importSupported() && f.family() != SupportedFormat.Family.CATALOG));
    }

    public static List<FileChooser.ExtensionFilter> catalogs(LocalizationService i18n) {
        return List.of(filter(i18n.text("ui.import.filter.inpx_files"), f -> f.importSupported() && f.family() == SupportedFormat.Family.CATALOG));
    }

    public static FileChooser.ExtensionFilter catalogAndZipSources(LocalizationService i18n) {
        var patterns = FORMATS.chooserPatterns(f -> "inpx".equals(f.id()) || "zip".equals(f.id()));
        return new FileChooser.ExtensionFilter(i18n.text("ui.import.filter.inpx_zip"), patterns.toArray(String[]::new));
    }

    static FileChooser.ExtensionFilter filter(String description, Predicate<SupportedFormat> predicate) {
        var patterns = FORMATS.chooserPatterns(predicate);
        return new FileChooser.ExtensionFilter(description, patterns.toArray(String[]::new));
    }
}

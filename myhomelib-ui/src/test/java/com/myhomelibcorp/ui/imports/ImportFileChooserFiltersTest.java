package com.myhomelibcorp.ui.imports;

import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class ImportFileChooserFiltersTest {
    private static LocalizationService i18n() {
        LocalizationService service = mock(LocalizationService.class);
        when(service.text(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> inv.getArgument(0));
        return service;
    }
    @Test
    void allSupportedChooserIncludesGenericFormatsRequiredByBacklog() {
        var all = ImportFileChooserFilters.standardGroups(i18n()).getFirst().getExtensions();
        assertThat(all).contains("*.pdf", "*.djvu", "*.mobi", "*.azw", "*.azw3",
                "*.docx", "*.rtf", "*.html", "*.chm");
    }

    @Test
    void chooserGroupsComeFromCapabilityRegistry() {
        var groups = ImportFileChooserFilters.standardGroups(i18n());
        assertThat(groups).hasSize(4);
        assertThat(groups.get(1).getExtensions()).contains("*.fb2", "*.epub", "*.txt", "*.pdf", "*.docx");
        assertThat(groups.get(2).getExtensions()).contains("*.inpx", "*.inp");
        assertThat(groups.get(3).getExtensions()).contains("*.zip", "*.7z", "*.rar", "*.tar.gz");
    }
}

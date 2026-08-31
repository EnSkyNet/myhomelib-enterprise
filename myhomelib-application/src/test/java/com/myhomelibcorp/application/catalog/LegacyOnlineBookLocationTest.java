package com.myhomelibcorp.application.catalog;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LegacyOnlineBookLocationTest {
    @Test
    void generatesUpstreamOnlineFb2ArchivePath() {
        assertThat(LegacyOnlineBookLocation.archivePath(
                "Романович Роман", "Алхимик 1-9", "882513", "882513.fb2"))
                .isEqualTo("Р/Романович Роман/882513 Алхимик 1-9.fb2.zip");
    }
}

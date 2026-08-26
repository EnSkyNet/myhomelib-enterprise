package com.myhomelibcorp.infrastructure.importengine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcBatchWriterStage9Test {

    @Test
    void fastInpxDerivesStableAuthorSortWithoutLoadingAuthorTable() {
        assertThat(JdbcBatchWriter.authorSortFromKeys("John||Smith,Amy|Q|Brown"))
                .isEqualTo("brown amy q");
        assertThat(JdbcBatchWriter.authorSortFromKeys("  ||  ")).isEmpty();
    }
}

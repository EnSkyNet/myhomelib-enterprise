package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.domain.model.author.AuthorNameKey;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookDenormalizedValues;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcBatchWriterStage9Test {

    @Test
    void fastInpxDerivesStableAuthorSortWithoutLoadingAuthorTable() {
        assertThat(BookDenormalizedValues.authorSort(List.of(
                new AuthorNameKey("John", "", "Smith"),
                new AuthorNameKey("Amy", "Q", "Brown"))))
                .isEqualTo("brown amy q");
        assertThat(BookDenormalizedValues.authorSort(List.of(new AuthorNameKey("", "", "")))).isEmpty();
    }
}

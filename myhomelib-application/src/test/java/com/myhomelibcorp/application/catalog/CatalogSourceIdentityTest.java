package com.myhomelibcorp.application.catalog;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSourceIdentityTest {
    @Test
    void remoteIdentityDependsOnCollectionNotDownloadedTempFile() {
        String source = CatalogSourceIdentity.remoteCollection("collection-123");
        assertThat(source).isEqualTo("remote-collection:collection-123");
        assertThat(CatalogSourceIdentity.stableId(source))
                .isEqualTo(CatalogSourceIdentity.stableId("remote-collection:collection-123"));
    }

    @Test
    void localIdentityIsPortableInsideCollectionRoot() {
        Path root = Path.of("library").toAbsolutePath().normalize();
        assertThat(CatalogSourceIdentity.localInpx(root.resolve("index/books.inpx"), root))
                .isEqualTo("local-inpx:index/books.inpx");
    }
}

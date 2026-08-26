package com.myhomelibcorp.infrastructure.download;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRemoteCatalogDownloadAdapterTest {

    @Test
    void resolvesAlex80InpxDirectoryToBothMyHomeLibServerRoots() {
        HttpRemoteCatalogDownloadAdapter.MhlBases bases = HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://alex80.github.io/mhl/download/inpx/"));

        assertThat(bases).isNotNull();
        assertThat(bases.inpxBase()).isEqualTo("https://alex80.github.io/mhl/download/inpx/");
        assertThat(bases.updateBase()).isEqualTo("https://alex80.github.io/mhl/update/");
    }

    @Test
    void resolvesAlex80UpdateDirectoryAndProjectRootTheSameWay() {
        HttpRemoteCatalogDownloadAdapter.MhlBases update = HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://alex80.github.io/mhl/update/"));
        HttpRemoteCatalogDownloadAdapter.MhlBases root = HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://alex80.github.io/mhl/"));

        assertThat(update).isEqualTo(root);
        assertThat(root.inpxBase()).isEqualTo("https://alex80.github.io/mhl/download/inpx/");
        assertThat(root.updateBase()).isEqualTo("https://alex80.github.io/mhl/update/");
    }

    @Test
    void doesNotTreatAnArbitraryDirectoryAsAMyHomeLibServer() {
        assertThat(HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://example.test/mhl/download/inpx/"))).isNull();
        assertThat(HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://alex80.github.io/something-else/"))).isNull();
    }
}

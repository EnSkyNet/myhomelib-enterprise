package com.myhomelibcorp.infrastructure.download.source;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.download.scenario.DownloadScenarioCommand;
import com.myhomelibcorp.infrastructure.download.scenario.DownloadScenarioParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloadSourceResolverTest {

    private final DownloadSourceResolver resolver = new DownloadSourceResolver();

    @Test
    void urlWithoutScript_shouldBeDirectHttp() {
        Collection collection = collectionWithUrl("https://server/books/");
        DownloadMode mode = resolver.resolve(collection);
        assertThat(mode).isEqualTo(DownloadMode.DIRECT_HTTP);
    }

    @Test
    void urlWithLegacyUrlScript_shouldBeDirectHttp() throws Exception {
        Collection collection = collectionWithUrlAndScript(
                "https://server/books/",
                "https://server/books/"
        );
        List<DownloadScenarioCommand> commands = DownloadScenarioParser.parse(collection.getConnectionScript());
        DownloadMode mode = resolver.resolve(collection, commands);
        assertThat(mode).isEqualTo(DownloadMode.DIRECT_HTTP);
    }

    @Test
    void urlWithGetCommand_shouldBeConnectionScript() throws Exception {
        Collection collection = collectionWithUrlAndScript(
                "https://server/",
                "GET /book.fb2"
        );
        List<DownloadScenarioCommand> commands = DownloadScenarioParser.parse(collection.getConnectionScript());
        DownloadMode mode = resolver.resolve(collection, commands);
        assertThat(mode).isEqualTo(DownloadMode.CONNECTION_SCRIPT);
    }

    @Test
    void urlWithPostCommand_shouldBeConnectionScript() throws Exception {
        Collection collection = collectionWithUrlAndScript(
                "https://server/",
                "POST /login\nGET /book.fb2"
        );
        List<DownloadScenarioCommand> commands = DownloadScenarioParser.parse(collection.getConnectionScript());
        DownloadMode mode = resolver.resolve(collection, commands);
        assertThat(mode).isEqualTo(DownloadMode.CONNECTION_SCRIPT);
    }

    @Test
    void onlyScriptWithGet_shouldBeConnectionScript() throws Exception {
        Collection collection = collectionWithUrlAndScript(
                null,
                "GET https://server/book.fb2"
        );
        List<DownloadScenarioCommand> commands = DownloadScenarioParser.parse(collection.getConnectionScript());
        DownloadMode mode = resolver.resolve(collection, commands);
        assertThat(mode).isEqualTo(DownloadMode.CONNECTION_SCRIPT);
    }

    @Test
    void onlyUrl_shouldBeDirectHttp() {
        Collection collection = collectionWithUrl("https://server/book.fb2");
        DownloadMode mode = resolver.resolve(collection);
        assertThat(mode).isEqualTo(DownloadMode.DIRECT_HTTP);
    }

    @Test
    void scriptWithOnlyCheckPauseAdd_shouldBeDirectHttp() throws Exception {
        Collection collection = collectionWithUrlAndScript(
                "https://server/",
                "CHECK\nPAUSE 1000\nADD name value"
        );
        List<DownloadScenarioCommand> commands = DownloadScenarioParser.parse(collection.getConnectionScript());
        DownloadMode mode = resolver.resolve(collection, commands);
        assertThat(mode).isEqualTo(DownloadMode.DIRECT_HTTP);
    }

    @Test
    void emptyUrlAndEmptyScript_shouldThrowInvalidConfiguration() {
        Collection collection = collectionWithUrlAndScript(null, null);
        assertThatThrownBy(() -> resolver.resolve(collection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не вдалося визначити джерело завантаження");
    }

    @Test
    void emptyUrlAndEmptyScript_noCommands_shouldThrowInvalidConfiguration() {
        Collection collection = collectionWithUrlAndScript(null, "");
        assertThatThrownBy(() -> resolver.resolve(collection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не вдалося визначити джерело завантаження");
    }

    @Test
    void hasNetworkRequestCommand_shouldDetectGetAndPost() {
        assertThat(resolver.hasNetworkRequestCommand("GET /book.fb2")).isTrue();
        assertThat(resolver.hasNetworkRequestCommand("POST /login")).isTrue();
        assertThat(resolver.hasNetworkRequestCommand("CHECK")).isFalse();
        assertThat(resolver.hasNetworkRequestCommand("PAUSE 1000")).isFalse();
        assertThat(resolver.hasNetworkRequestCommand("ADD name value")).isFalse();
        assertThat(resolver.hasNetworkRequestCommand("https://server/books/")).isFalse();
        assertThat(resolver.hasNetworkRequestCommand("")).isFalse();
        assertThat(resolver.hasNetworkRequestCommand(null)).isFalse();
    }

    private Collection collectionWithUrl(String url) {
        return new Collection(
                "test-id",
                "Test Collection",
                Path.of("."),
                "test.db",
                0,
                null,
                null,
                url,
                null,
                null
        );
    }

    private Collection collectionWithUrlAndScript(String url, String script) {
        return new Collection(
                "test-id",
                "Test Collection",
                Path.of("."),
                "test.db",
                0,
                null,
                null,
                url,
                null,
                script
        );
    }
}
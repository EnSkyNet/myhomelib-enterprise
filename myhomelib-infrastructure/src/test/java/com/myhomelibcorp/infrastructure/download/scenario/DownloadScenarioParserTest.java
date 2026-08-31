package com.myhomelibcorp.infrastructure.download.scenario;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloadScenarioParserTest {

    @Test
    void parsesCompleteUpstreamCommandSet() throws Exception {
        var commands = DownloadScenarioParser.parse("""
                ADD login user
                ADD password %PASS%
                POST https://example.test/login
                REDIR
                GET %RESURL%
                CHECK
                PAUSE 25
                """);

        assertThat(commands).extracting(c -> c.type().name())
                .containsExactly("ADD", "ADD", "POST", "REDIR", "GET", "CHECK", "PAUSE");
        assertThat(commands.get(0).first()).isEqualTo("login");
        assertThat(commands.get(0).second()).isEqualTo("user");
        assertThat(commands.get(0).line()).isEqualTo(1);
    }

    @Test
    void acceptsLegacyBareHttpUrlPreambleButStillParsesRealCommands() throws Exception {
        var commands = DownloadScenarioParser.parse("""
                https://flibusta.is/
                ADD name %USER%
                ADD password %PASS%
                POST %URL%b/%LIBID%/get
                GET %RESURL%
                CHECK
                """);

        assertThat(commands).hasSize(5);
        assertThat(commands.get(0).type()).isEqualTo(DownloadScenarioCommand.Type.ADD);
        assertThat(commands.get(2).type()).isEqualTo(DownloadScenarioCommand.Type.POST);
        assertThat(commands.get(4).type()).isEqualTo(DownloadScenarioCommand.Type.CHECK);
    }

    @Test
    void joinsLegacyBaseUrlAndRootRelativeCommandWithoutLosingSlash() throws Exception {
        var commands = DownloadScenarioParser.parse("https://flibusta.is\nGET /b/123/get");
        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().first()).isEqualTo("https://flibusta.is/b/123/get");
    }

    @Test
    void rejectsUnknownAndMalformedCommandsWithLineNumber() {
        assertThatThrownBy(() -> DownloadScenarioParser.parse("GET https://example.test\nEXEC rm -rf /"))
                .isInstanceOf(DownloadScenarioException.class)
                .hasMessageContaining("рядок 2")
                .hasMessageContaining("невідома команда EXEC");

        assertThatThrownBy(() -> DownloadScenarioParser.parse("ADD onlyName"))
                .isInstanceOf(DownloadScenarioException.class)
                .hasMessageContaining("ADD потребує name і value");
    }

    @Test
    void enforcesPauseSafetyLimit() {
        assertThatThrownBy(() -> DownloadScenarioParser.parse("PAUSE 60001"))
                .isInstanceOf(DownloadScenarioException.class)
                .hasMessageContaining("0..60000");
    }
}

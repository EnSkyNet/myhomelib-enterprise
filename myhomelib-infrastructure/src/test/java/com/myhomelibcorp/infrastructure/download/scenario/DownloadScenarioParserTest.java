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

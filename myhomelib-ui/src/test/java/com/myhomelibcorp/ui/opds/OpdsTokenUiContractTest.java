package com.myhomelibcorp.ui.opds;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpdsTokenUiContractTest {

    @Test
    void opdsSettingsExposeScopedCreateRevokeAndOneTimeSecretFlow() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/opds/OpdsUiService.java"), StandardCharsets.UTF_8);

        assertThat(source).contains(
                "private final OpdsAccessTokenService accessTokens;",
                "accessTokens.create(tokenDevice.getText(), scopes)",
                "accessTokens.revoke(selected.id())",
                "OpdsTokenScope.CATALOG_READ",
                "OpdsTokenScope.DOWNLOAD",
                "showTokenOnce(owner, created.token())",
                "tokenList.getItems().setAll(accessTokens.list())");
        assertThat(source).doesNotContain("settingsService.saveToken", "rawToken = accessTokens.list");
    }

    @Test
    void tokenUiLocalizationIsPresentAndBundledForEverySupportedLanguage() throws Exception {
        for (String language : new String[]{"uk", "en", "bg"}) {
            String root = Files.readString(Path.of("../Lang/" + language + ".json"), StandardCharsets.UTF_8);
            String bundled = Files.readString(Path.of("src/main/resources/lang/default/" + language + ".json"), StandardCharsets.UTF_8);
            assertThat(root).contains(
                    "ui.opds.tokens.section",
                    "ui.opds.tokens.scope.catalog",
                    "ui.opds.tokens.scope.download",
                    "ui.opds.tokens.created.once",
                    "ui.opds.tokens.revoke.confirm");
            assertThat(bundled).isEqualTo(root);
        }
    }
}

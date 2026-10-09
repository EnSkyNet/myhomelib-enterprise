package com.myhomelibcorp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopConfigurationOwnershipTest {

    private static final List<String> APPLICATION_CONFIGS = List.of(
            "application.yml",
            "application-dev.yml",
            "application-prod.yml"
    );

    @Test
    void sqliteAndFlywayConfigurationHasOneProgrammaticOwner() throws IOException {
        for (String resource : APPLICATION_CONFIGS) {
            String yaml = readResource(resource);
            assertThat(yaml)
                    .as("%s must not reintroduce Boot-managed SQLite/Flyway settings", resource)
                    .doesNotContain("datasource:")
                    .doesNotContain("connection-init-sql:")
                    .doesNotContain("jdbc:sqlite:placeholder")
                    .doesNotContain("flyway:");
        }
    }

    @Test
    void cachesAreConfiguredByTheirTypedInfrastructureOwners() throws IOException {
        for (String resource : APPLICATION_CONFIGS) {
            assertThat(readResource(resource))
                    .as("%s must not carry an unused global Spring cache policy", resource)
                    .doesNotContain("cache:")
                    .doesNotContain("caffeine:");
        }
    }


    @Test
    void productionKeepsOperationalMilestonesVisibleWithoutEnablingPackageWideInfoNoise() throws IOException {
        String yaml = readResource("application-prod.yml");
        assertThat(yaml)
                .contains("com.myhomelibcorp: WARN")
                .contains("com.myhomelibcorp.ui.service.BookDownloadCoordinator: INFO")
                .contains("com.myhomelibcorp.ui.details.BookDetailsAnalysisService: INFO")
                .contains("com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase: INFO")
                .contains("com.myhomelibcorp.converter.toolchain.ToolchainBookConverter: INFO")
                .contains("com.myhomelibcorp.converter.toolchain.engine.fbc.FbcEngine: INFO")
                .contains("com.myhomelibcorp.application.content.maintenance.ContentIndexMaintenanceService: INFO");
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream stream = DesktopConfigurationOwnershipTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(stream).as("resource %s", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

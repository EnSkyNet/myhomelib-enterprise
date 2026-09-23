package com.myhomelibcorp.integration.epub;

import com.myhomelibcorp.MyHomeLibApp;
import com.myhomelibcorp.epub.optimizer.EpubAcceptanceMode;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationReport;
import com.myhomelibcorp.epub.optimizer.EpubOptimizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in acceptance against a real EPUB file through the production Spring composition. */
@EnabledIfSystemProperty(named = "myhomelib.test.epub", matches = ".+")
class RealEpubOptimizerAcceptanceTest {
    @TempDir Path temp;

    @Test
    void realEpubPassesStandardReaderReopenAndStrictFailsClosedWithoutEpubCheck() throws Exception {
        Path source = Path.of(System.getProperty("myhomelib.test.epub")).toAbsolutePath().normalize();
        assertThat(source).isRegularFile();
        String previousDataDir = System.getProperty("myhomelib.dataDir");
        String previousHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("myhomelib.dataDir", temp.resolve("data").toString());
            System.setProperty("java.awt.headless", "true");
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MyHomeLibApp.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off", "spring.main.lazy-initialization=false", "logging.level.root=WARN")
                    .run()) {
                EpubOptimizer optimizer = context.getBean(EpubOptimizer.class);
                Path target = temp.resolve("optimized.epub");
                EpubOptimizationReport report = optimizer.optimize(
                        source, target, EpubOptimizationProfile.BALANCED_EPUB33, null, EpubAcceptanceMode.STANDARD);
                assertThat(target).isRegularFile();
                assertThat(Files.size(target)).isPositive();
                assertThat(report.readerReopenStatus()).isEqualTo("PASSED");
                assertThat(report.epubCheckStatus()).isEqualTo("NOT_AVAILABLE");
                assertThat(report.semanticVerificationStatus()).isNotBlank();

                Path strictTarget = temp.resolve("strict.epub");
                assertThatThrownBy(() -> optimizer.optimize(
                        source, strictTarget, EpubOptimizationProfile.NONE, null, EpubAcceptanceMode.STRICT))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("STRICT")
                        .hasMessageContaining("EPUBCheck");

                System.out.printf("REAL_EPUB_OPTIMIZER source=%d result=%d semantic=%s reader=%s epubcheck=%s warnings=%d%n",
                        report.sourceSize(), report.resultSize(), report.semanticVerificationStatus(),
                        report.readerReopenStatus(), report.epubCheckStatus(), report.warnings().size());
            }
        } finally {
            restore("myhomelib.dataDir", previousDataDir);
            restore("java.awt.headless", previousHeadless);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}

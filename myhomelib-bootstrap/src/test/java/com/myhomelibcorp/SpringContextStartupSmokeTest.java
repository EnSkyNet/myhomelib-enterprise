package com.myhomelibcorp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SpringContextStartupSmokeTest {

    @TempDir Path temp;

    @Test
    void completeSpringContextRefreshesWithAllSingletonBeans() {
        String previousDataDir = System.getProperty("myhomelib.dataDir");
        String previousHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty("myhomelib.dataDir", temp.resolve("data").toString());
            System.setProperty("java.awt.headless", "true");
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MyHomeLibApp.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.main.banner-mode=off",
                            "spring.main.lazy-initialization=false",
                            "logging.level.root=WARN")
                    .run()) {
                assertThat(context.isActive()).isTrue();
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

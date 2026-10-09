package com.myhomelibcorp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentScanBoundaryTest {

    @Test
    void everyDesktopSpringComponentIsInsideAnExplicitScanRoot() {
        SpringBootApplication application = MyHomeLibApp.class.getAnnotation(SpringBootApplication.class);
        List<String> scanRoots = Arrays.asList(application.scanBasePackages());

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);

        List<String> outsideScanRoots = scanner.findCandidateComponents("com.myhomelibcorp").stream()
                .map(definition -> definition.getBeanClassName())
                .filter(Objects::nonNull)
                .filter(className -> !className.equals(MyHomeLibApp.class.getName()))
                .filter(className -> scanRoots.stream().noneMatch(root ->
                        className.equals(root) || className.startsWith(root + ".")))
                .sorted()
                .toList();

        assertThat(outsideScanRoots)
                .as("Spring components outside MyHomeLibApp's explicit desktop scan roots")
                .isEmpty();
    }
}

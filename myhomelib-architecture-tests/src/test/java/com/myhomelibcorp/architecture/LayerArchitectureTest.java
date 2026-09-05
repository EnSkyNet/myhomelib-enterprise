package com.myhomelibcorp.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Hard architecture boundaries for the current codebase.
 *
 * <p>Important: rules use fully-qualified product package roots instead of
 * broad patterns such as {@code ..reader..} or {@code ..infrastructure..}.
 * Broad patterns also match legitimate nested package names such as
 * {@code domain.model.reader}, {@code ui.reader},
 * {@code infrastructure.reader}, and
 * {@code application.port.out.infrastructure}, producing false positives.</p>
 *
 * <p>Existing UI debt (direct use of application output ports and selected
 * domain model types) is tracked separately by tools/architecture-check.py so
 * the baseline can only improve, not silently grow.</p>
 */
class LayerArchitectureTest {

    private static final String SHARED = "com.myhomelibcorp.shared..";
    private static final String DOMAIN = "com.myhomelibcorp.domain..";
    private static final String APPLICATION = "com.myhomelibcorp.application..";
    private static final String INFRASTRUCTURE = "com.myhomelibcorp.infrastructure..";
    private static final String UI = "com.myhomelibcorp.ui..";
    private static final String READER = "com.myhomelibcorp.reader..";
    private static final String MCP = "com.myhomelibcorp.mcp..";
    private static final String OPDS = "com.myhomelibcorp.opds..";

    /**
     * Import production classes once for the whole suite. JUnit creates a new test
     * instance per method by default, so an instance field would rescan the complete
     * product classpath for every architecture rule.
     */
    private static final List<String> PRODUCTION_MODULES = List.of(
            "myhomelib-shared",
            "myhomelib-domain",
            "myhomelib-application",
            "myhomelib-infrastructure",
            "myhomelib-reader",
            "myhomelib-ui",
            "myhomelib-opds",
            "myhomelib-bootstrap",
            "myhomelib-mcp"
    );

    private static final JavaClasses CLASSES = importProductionClasses();

    /**
     * Import only reactor production output directories. Using importPackages() makes
     * ArchUnit enumerate the complete Surefire classpath (including the Maven cache),
     * which can turn this 12-rule suite into a multi-minute scan.
     */
    private static JavaClasses importProductionClasses() {
        Path root = findReactorRoot();
        List<Path> paths = PRODUCTION_MODULES.stream()
                .map(module -> root.resolve(module).resolve("target/classes"))
                .filter(Files::isDirectory)
                .toList();
        if (paths.size() != PRODUCTION_MODULES.size()) {
            throw new IllegalStateException(
                    "Expected compiled output for all production modules under " + root
                            + "; found " + paths.size() + " of " + PRODUCTION_MODULES.size());
        }
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPaths(paths);
    }

    private static Path findReactorRoot() {
        String configured = System.getProperty("maven.multiModuleProjectDirectory");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate.resolve("myhomelib-shared"))) {
                return candidate;
            }
        }
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("myhomelib-shared"))
                    && Files.isDirectory(candidate.resolve("myhomelib-architecture-tests"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Unable to locate MyHomeLib reactor root");
    }

    @Test
    void sharedIsIndependentFromProductModulesAndFrameworks() {
        noClasses()
                .that().resideInAnyPackage(SHARED)
                .should().dependOnClassesThat().resideInAnyPackage(
                        DOMAIN,
                        APPLICATION,
                        INFRASTRUCTURE,
                        UI,
                        READER,
                        MCP,
                        OPDS,
                        "org.springframework..",
                        "javafx..",
                        "java.sql..",
                        "org.apache.lucene.."
                )
                .check(CLASSES);
    }

    @Test
    void domainIsFrameworkAndOuterLayerIndependent() {
        noClasses()
                .that().resideInAnyPackage(DOMAIN)
                .should().dependOnClassesThat().resideInAnyPackage(
                        APPLICATION,
                        INFRASTRUCTURE,
                        UI,
                        READER,
                        MCP,
                        OPDS,
                        "org.springframework..",
                        "javafx..",
                        "java.sql..",
                        "javax.sql..",
                        "org.apache.lucene.."
                )
                .check(CLASSES);
    }

    @Test
    void applicationDoesNotDependOnAdaptersOrStorageFrameworks() {
        noClasses()
                .that().resideInAnyPackage(APPLICATION)
                .should().dependOnClassesThat().resideInAnyPackage(
                        INFRASTRUCTURE,
                        UI,
                        READER,
                        MCP,
                        OPDS,
                        "javafx..",
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.lucene.."
                )
                .check(CLASSES);
    }

    @Test
    void applicationOutputPortsAreInterfaces() {
        classes()
                .that().resideInAnyPackage("com.myhomelibcorp.application.port.out..")
                .and().areTopLevelClasses()
                .should().beInterfaces()
                .check(CLASSES);
    }

    @Test
    void infrastructureDoesNotDependOnUiReaderOrJavaFx() {
        noClasses()
                .that().resideInAnyPackage(INFRASTRUCTURE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        UI,
                        READER,
                        OPDS,
                        "javafx.."
                )
                .check(CLASSES);
    }

    @Test
    void uiDoesNotReachInfrastructureJdbcOrLuceneDirectly() {
        noClasses()
                .that().resideInAnyPackage(UI)
                .should().dependOnClassesThat().resideInAnyPackage(
                        INFRASTRUCTURE,
                        OPDS,
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.lucene.."
                )
                .check(CLASSES);
    }

    @Test
    void readerEngineDoesNotDependOnDesktopApplicationModules() {
        noClasses()
                .that().resideInAnyPackage(READER)
                .should().dependOnClassesThat().resideInAnyPackage(
                        DOMAIN,
                        APPLICATION,
                        INFRASTRUCTURE,
                        UI,
                        MCP,
                        OPDS,
                        "org.springframework..",
                        "java.sql..",
                        "javax.sql..",
                        "org.apache.lucene.."
                )
                .check(CLASSES);
    }

    @Test
    void portableReaderPackagesDoNotDependOnJavaFx() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.myhomelibcorp.reader.api..",
                        "com.myhomelibcorp.reader.core..",
                        "com.myhomelibcorp.reader.format..",
                        "com.myhomelibcorp.reader.layout..",
                        "com.myhomelibcorp.reader.model..",
                        "com.myhomelibcorp.reader.service..",
                        "com.myhomelibcorp.reader.render.api.."
                )
                .should().dependOnClassesThat().resideInAnyPackage("javafx..")
                .check(CLASSES);
    }

    @Test
    void mcpSidecarStaysIndependentFromDesktopModulesAndFrameworks() {
        noClasses()
                .that().resideInAnyPackage(MCP)
                .should().dependOnClassesThat().resideInAnyPackage(
                        DOMAIN,
                        APPLICATION,
                        INFRASTRUCTURE,
                        UI,
                        READER,
                        OPDS,
                        "org.springframework..",
                        "javafx.."
                )
                .check(CLASSES);
    }

    @Test
    void opdsSidecarDependsOnApplicationApiButNotDesktopAdapters() {
        noClasses()
                .that().resideInAnyPackage(OPDS)
                .should().dependOnClassesThat().resideInAnyPackage(
                        INFRASTRUCTURE,
                        UI,
                        READER,
                        MCP,
                        "javafx..",
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework.jdbc..",
                        "org.apache.lucene.."
                )
                .check(CLASSES);
    }

    @Test
    void navigationPanelUsesApplicationNavigationBoundaryInsteadOfRepositoriesOrDomainEntities() {
        noClasses()
                .that().haveSimpleName("NavigationPanelController")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.myhomelibcorp.application.port.out..",
                        "com.myhomelibcorp.domain.model.author..",
                        "com.myhomelibcorp.domain.model.series..",
                        "com.myhomelibcorp.domain.model.genre.."
                )
                .check(CLASSES);
    }

    @Test
    void topLevelProductPackagesAreFreeOfCycles() {
        slices()
                .matching("com.myhomelibcorp.(*)..")
                .should().beFreeOfCycles()
                .check(CLASSES);
    }
}

package com.myhomelibcorp.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

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

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.myhomelibcorp");

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
                .check(classes);
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
                .check(classes);
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
                .check(classes);
    }

    @Test
    void applicationOutputPortsAreInterfaces() {
        classes()
                .that().resideInAnyPackage("com.myhomelibcorp.application.port.out..")
                .and().areTopLevelClasses()
                .should().beInterfaces()
                .check(classes);
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
                .check(classes);
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
                .check(classes);
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
                .check(classes);
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
                .check(classes);
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
                .check(classes);
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
                .check(classes);
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
                .check(classes);
    }

    @Test
    void topLevelProductPackagesAreFreeOfCycles() {
        slices()
                .matching("com.myhomelibcorp.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }
}

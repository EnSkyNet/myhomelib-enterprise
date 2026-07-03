package com.myhomelibcorp.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

class LayerArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.myhomelibcorp");

    @Test
    void domainDoesNotDependOnFrameworkOrOuterLayers() {
        noClasses()
                .that().resideInAnyPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..",
                        "..infrastructure..",
                        "..ui..",
                        "org.springframework..",
                        "javafx..",
                        "java.sql.."
                )
                .check(classes);
    }

    @Test
    void applicationDoesNotDependOnInfrastructureOrUi() {
        noClasses()
                .that().resideInAnyPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure..",
                        "..ui.."
                )
                .check(classes);
    }

    @Test
    void infrastructureDoesNotDependOnUi() {
        noClasses()
                .that().resideInAnyPackage("..infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui..", "javafx..")
                .check(classes);
    }

    @Test
    void uiDoesNotDependOnConcreteInfrastructure() {
        noClasses()
                .that().resideInAnyPackage("..ui..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..")
                .check(classes);
    }

    @Test
    void uiDoesNotDependOnRepositoryDirectly() {
        noClasses()
                .that().resideInAnyPackage("..ui..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..repository..",
                        "..persistence..",
                        "..jdbc..",
                        "..sqlite.."
                )
                .check(classes);
    }

    @Test
    void applicationPortsAreInterfacesOnly() {
        classes()
                .that().resideInAnyPackage("..application.port.out..")
                .should().beInterfaces()
                .check(classes);
    }

    @Test
    void domainDoesNotDependOnUiModels() {
        noClasses()
                .that().resideInAnyPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui..")
                .check(classes);
    }

    @Test
    void layeredArchitectureIsRespected() {
        layeredArchitecture()
                .consideringAllDependencies()
                .layer("UI").definedBy("..ui..")
                .layer("Application").definedBy("..application..")
                .layer("Domain").definedBy("..domain..")
                .layer("Infrastructure").definedBy("..infrastructure..")
                .layer("Shared").definedBy("..shared..")

                .whereLayer("UI").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("UI")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure")
                .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Application", "UI")
                // Shared layer can be accessed by anyone, so we don't restrict it.
                // .whereLayer("Shared").mayBeAccessedByAnyLayer() // <-- remove this line

                .check(classes);
    }

    @Test
    void applicationDoesNotDependOnSpringJdbcOrJavaFx() {
        noClasses()
                .that().resideInAnyPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..",
                        "org.springframework.transaction..",
                        "javafx.."
                )
                .check(classes);
    }

    @Test
    void infrastructureOnlyUsesApplicationPortsNotDomainDirectly() {
        // Infrastructure may depend on domain for models,
        // but should not depend on application directly (except ports)
        // Actually infrastructure depends on application (because it implements ports),
        // so this rule is refined: infrastructure should not depend on application.usecase
        noClasses()
                .that().resideInAnyPackage("..infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application.usecase.."
                )
                .check(classes);
    }

    @Test
    void servicesInInfrastructureDoNotDependOnEachOtherCircularly() {
        // Basic check for circular dependencies within infrastructure
        noClasses()
                .that().resideInAnyPackage("..infrastructure.service..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure.service..")
                .check(classes);
    }
}
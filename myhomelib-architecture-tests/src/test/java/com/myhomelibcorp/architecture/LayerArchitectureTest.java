package com.myhomelibcorp.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..ui.." // дозволено використання javafx, якщо це технічна необхідність
                )
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
    void domainDoesNotDependOnUiModels() {
        noClasses()
                .that().resideInAnyPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui..")
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
    void noJavaFxInDomain() {
        noClasses()
                .that().resideInAnyPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("javafx..")
                .check(classes);
    }

    @Test
    void noSpringInDomain() {
        noClasses()
                .that().resideInAnyPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .check(classes);
    }

    @Test
    void noJdbcInApplication() {
        noClasses()
                .that().resideInAnyPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
                .check(classes);
    }

    @Test
    void noJdbcInUi() {
        noClasses()
                .that().resideInAnyPackage("..ui..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
                .check(classes);
    }
}
package com.myhomelibcorp.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

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
                        "javafx.."
                )
                .check(classes);
    }

    @Test
    void applicationDoesNotDependOnInfrastructureOrUi() {
        noClasses()
                .that().resideInAnyPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure..",
                        "..ui..",
                        "javafx.."
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
}

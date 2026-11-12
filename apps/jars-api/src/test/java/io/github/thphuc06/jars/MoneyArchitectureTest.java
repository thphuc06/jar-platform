package io.github.thphuc06.jars;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Money is never a floating-point number (ADR-0014, conventions.md). Only production classes are checked, and
 * only the places where money lives: the shared value types and every module's domain package.
 */
class MoneyArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.thphuc06.jars");

    @Test
    void domainAndSharedTypes_haveNoFloatingPointFields() {
        noFields()
                .that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage("io.github.thphuc06.jars.shared..", "..internal.domain..")
                .should()
                .haveRawType(double.class)
                .orShould()
                .haveRawType(float.class)
                .orShould()
                .haveRawType(Double.class)
                .orShould()
                .haveRawType(Float.class)
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void domainAndSharedTypes_haveNoFloatingPointReturnTypes() {
        noMethods()
                .that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage("io.github.thphuc06.jars.shared..", "..internal.domain..")
                .should()
                .haveRawReturnType(double.class)
                .orShould()
                .haveRawReturnType(float.class)
                .orShould()
                .haveRawReturnType(Double.class)
                .orShould()
                .haveRawReturnType(Float.class)
                .allowEmptyShould(true)
                .check(CLASSES);
    }
}

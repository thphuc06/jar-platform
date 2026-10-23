package io.github.thphuc06.jars;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;
import org.springframework.modulith.docs.Documenter;

/**
 * Architecture rules enforced on every build (see docs/02-architecture/modules.md):
 * no cyclic module dependencies and no access to another module's internal packages.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(JarsApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        MODULES.forEach(System.out::println);
        MODULES.verify();
    }

    @Test
    void writesModuleDocumentation() {
        // generates PlantUML component diagrams + module canvases into target/spring-modulith-docs
        new Documenter(MODULES).writeDocumentation();
    }

    /**
     * Guards the guard: a fixture where module "beta" uses a class from "alpha.internal" must be reported.
     * Without this, a verify() that silently checks nothing would also be green.
     */
    @Test
    void verifyDetectsAccessToAnotherModulesInternals() {
        // fixture classes live in src/test, so test classes must be included in the import
        ApplicationModules fixture = ApplicationModules.of("io.github.thphuc06.modulithfixture", location -> true);

        Violations violations = fixture.detectViolations();

        assertThat(violations.hasViolations()).isTrue();
        assertThat(violations.getMessage()).contains("beta").contains("AlphaSecret");
    }
}

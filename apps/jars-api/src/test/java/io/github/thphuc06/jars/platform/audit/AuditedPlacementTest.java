package io.github.thphuc06.jars.platform.audit;

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import io.github.thphuc06.auditfixture.FinalBean;
import io.github.thphuc06.auditfixture.MisplacedAudited;
import io.github.thphuc06.auditfixture.NotABean;
import io.github.thphuc06.auditfixture.WellPlaced;
import io.github.thphuc06.auditfixture.WellPlacedCaller;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/**
 * An {@code @Audited} that the aspect cannot reach does not fail, it just does nothing: the change commits and no row
 * is written. Spring AOP works through proxies, so the annotation only works on a public, non-final, non-static method
 * of a non-final Spring bean class, called from another bean. These rules turn each way to get that wrong into a
 * failing build, in the spirit of {@code MoneyArchitectureTest}; {@link #theRulesFireOnTheMistakesTheyAreFor} proves
 * they are not vacuous. What they cannot see (a call through {@code this} from an inner class or via reflection) is
 * covered at run time: {@code Audit.entity} refuses to run outside an audited call.
 */
class AuditedPlacementTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.thphuc06.jars");

    private static final JavaClasses MISPLACED =
            new ClassFileImporter().importClasses(MisplacedAudited.class, NotABean.class, FinalBean.class);

    private static final JavaClasses WELL_PLACED =
            new ClassFileImporter().importClasses(WellPlaced.class, WellPlaced.AlsoABean.class, WellPlacedCaller.class);

    static final ArchRule ONLY_PUBLIC =
            methods().that().areAnnotatedWith(Audited.class).should().bePublic().allowEmptyShould(true);

    static final ArchRule NOT_STATIC_OR_FINAL = methods()
            .that()
            .areAnnotatedWith(Audited.class)
            .should()
            .notHaveModifier(JavaModifier.STATIC)
            .andShould()
            .notHaveModifier(JavaModifier.FINAL)
            .allowEmptyShould(true);

    static final ArchRule ON_SPRING_BEANS = methods()
            .that()
            .areAnnotatedWith(Audited.class)
            .should()
            .beDeclaredInClassesThat(springBeanClass())
            .allowEmptyShould(true);

    static final ArchRule ON_NON_FINAL_CLASSES = methods()
            .that()
            .areAnnotatedWith(Audited.class)
            .should()
            .beDeclaredInClassesThat()
            .doNotHaveModifier(JavaModifier.FINAL)
            .allowEmptyShould(true);

    static final ArchRule NOT_CALLED_ON_THIS = methods()
            .that()
            .areAnnotatedWith(Audited.class)
            .should(notBeCalledFromTheirOwnClass())
            .allowEmptyShould(true);

    private static final List<ArchRule> PLACEMENT_RULES =
            List.of(ONLY_PUBLIC, NOT_STATIC_OR_FINAL, ON_SPRING_BEANS, ON_NON_FINAL_CLASSES, NOT_CALLED_ON_THIS);

    // ----------------------------------------------------------- the real code

    @Test
    void auditedIsOnlyOnPublicMethods() {
        ONLY_PUBLIC.check(PRODUCTION);
    }

    @Test
    void auditedIsNeverOnAStaticOrFinalMethod() {
        NOT_STATIC_OR_FINAL.check(PRODUCTION);
    }

    @Test
    void auditedIsOnlyOnSpringBeans() {
        ON_SPRING_BEANS.check(PRODUCTION);
    }

    @Test
    void auditedIsNeverOnAFinalClass() {
        ON_NON_FINAL_CLASSES.check(PRODUCTION);
    }

    @Test
    void anAuditedMethodIsNeverCalledOnThis() {
        NOT_CALLED_ON_THIS.check(PRODUCTION);
    }

    @Test
    void theRulesSeeTheAuditedMethodsOfTheApplication() {
        // not vacuous: the application really has a dozen of them
        long audited = PRODUCTION.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(Audited.class))
                .count();

        assertThat(audited).isGreaterThanOrEqualTo(15);
    }

    // ------------------------------------------------------- guard the guards

    @Test
    void theRulesFireOnTheMistakesTheyAreFor() {
        assertThatThrownBy(() -> ONLY_PUBLIC.check(MISPLACED))
                .hasMessageContaining("packagePrivate")
                .hasMessageContaining("hidden");
        assertThatThrownBy(() -> NOT_STATIC_OR_FINAL.check(MISPLACED))
                .hasMessageContaining("staticMethod")
                .hasMessageContaining("finalMethod");
        assertThatThrownBy(() -> ON_SPRING_BEANS.check(MISPLACED)).hasMessageContaining("NotABean");
        assertThatThrownBy(() -> ON_NON_FINAL_CLASSES.check(MISPLACED)).hasMessageContaining("FinalBean");
        assertThatThrownBy(() -> NOT_CALLED_ON_THIS.check(MISPLACED))
                .hasMessageContaining("callee")
                .hasMessageContaining("caller");
    }

    @Test
    void theRulesAcceptTheRightPlacement() {
        for (ArchRule rule : PLACEMENT_RULES) {
            rule.check(WELL_PLACED);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static DescribedPredicate<JavaClass> springBeanClass() {
        return new DescribedPredicate<>("a Spring bean (annotated with a @Component stereotype)") {
            @Override
            public boolean test(JavaClass type) {
                return type.isMetaAnnotatedWith(Component.class);
            }
        };
    }

    private static ArchCondition<JavaMethod> notBeCalledFromTheirOwnClass() {
        return new ArchCondition<>("not be called from their own class (this.method() skips the proxy)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaMethodCall call : method.getCallsOfSelf()) {
                    if (call.getOriginOwner().isAssignableTo(method.getOwner().getName())) {
                        events.add(violated(
                                method,
                                method.getFullName() + " is called by "
                                        + call.getOrigin().getFullName() + " in the same class"));
                    }
                }
            }
        };
    }
}

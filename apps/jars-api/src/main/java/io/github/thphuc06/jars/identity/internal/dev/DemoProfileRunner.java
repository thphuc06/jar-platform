package io.github.thphuc06.jars.identity.internal.dev;

import io.github.thphuc06.jars.identity.AgeBand;
import io.github.thphuc06.jars.identity.IncomeSituation;
import io.github.thphuc06.jars.identity.internal.persistence.RiskAssessmentRepository;
import io.github.thphuc06.jars.identity.internal.service.RiskProfileService;
import io.github.thphuc06.jars.identity.internal.service.UserService;
import io.github.thphuc06.jars.shared.UserId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Gives the demo user of {@code --jars.seed.enabled=true} (token subject {@code demo}) a complete profile, so the
 * advisor can recommend products to it straight away: a balanced risk profile, the age band 25-34 and a regular salary,
 * matching the demo transactions (a salary on the first of the month). It lives in the identity module, which owns these
 * tables, next to the ledger's {@code SeedDataRunner} that creates the money; it does nothing when the user already has
 * a risk profile, so it is safe to leave on and never overwrites what someone chose in the app.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "jars.seed.enabled", havingValue = "true")
class DemoProfileRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoProfileRunner.class);

    static final String DEMO_SUBJECT = "demo";

    /** Moderate on every question except the objective "save for a specific goal": score 50, BALANCED. */
    static final List<Integer> ANSWERS = List.of(1, 2, 1, 1, 2);

    private final UserService users;
    private final RiskProfileService riskProfiles;
    private final RiskAssessmentRepository assessments;

    DemoProfileRunner(UserService users, RiskProfileService riskProfiles, RiskAssessmentRepository assessments) {
        this.users = users;
        this.riskProfiles = riskProfiles;
        this.assessments = assessments;
    }

    @Override
    public void run(ApplicationArguments args) {
        UserId demo = users.resolve(DEMO_SUBJECT);
        if (assessments.existsByUserId(demo.value())) {
            log.info("Demo user already has a risk profile, nothing to do");
            return;
        }
        users.updateProfile(demo, null, null, AgeBand.AGE_25_34, IncomeSituation.REGULAR_SALARY);
        riskProfiles.submit(demo, ANSWERS);
        log.info("Demo user profile ready: balanced, 25-34, regular salary");
    }
}

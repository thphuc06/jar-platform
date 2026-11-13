package io.github.thphuc06.jars.ledger.internal.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.ledger.internal.service.ReconciliationJob;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * The SQS adapter exists only in the {@code aws} profile (AwsProfileTest checks that it is there). These tests never
 * connect to AWS: they check the wiring by reflection and that the default (local/test) context has no listener.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReconciliationQueueListenerTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Test
    void theListenerIsOnlyCreatedInTheAwsProfile() {
        Profile profile = ReconciliationQueueListener.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("aws");
        assertThat(environment.getActiveProfiles()).doesNotContain("aws");
        assertThat(context.getBeanNamesForType(ReconciliationQueueListener.class))
                .as("no queue listener without the aws profile")
                .isEmpty();
    }

    @Test
    void theListenerReadsItsOwnQueueNotTheJobsQueue() throws Exception {
        Method onMessage = ReconciliationQueueListener.class.getDeclaredMethod("onMessage", String.class);

        SqsListener listener = onMessage.getAnnotation(SqsListener.class);

        assertThat(listener).isNotNull();
        assertThat(Arrays.asList(listener.value())).containsExactly("${jars.jobs.reconciliation-queue-name}");
        // the property exists (blank unless JARS_RECONCILIATION_QUEUE is set), so the placeholder resolves
        assertThat(environment.containsProperty("jars.jobs.reconciliation-queue-name"))
                .isTrue();
    }

    @Test
    void aMessageIsHandedToTheJobUnchanged() {
        ReconciliationJob job = mock(ReconciliationJob.class);

        new ReconciliationQueueListener(job).onMessage("{\"job\":\"LEDGER_RECONCILIATION\"}");

        verify(job).handle("{\"job\":\"LEDGER_RECONCILIATION\"}");
    }

    @Test
    void theJobItselfIsAvailableEverywhere_soTestsAndOtherProfilesCanCallIt() {
        assertThat(context.getBeanNamesForType(ReconciliationJob.class)).hasSize(1);
    }
}

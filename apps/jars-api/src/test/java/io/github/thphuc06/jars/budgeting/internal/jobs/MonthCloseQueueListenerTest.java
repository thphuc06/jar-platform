package io.github.thphuc06.jars.budgeting.internal.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseJob;
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
 * The SQS adapter exists only in the {@code aws} profile. These tests never connect to AWS: they check the wiring by
 * reflection and that the default (local/test) application context does not contain the listener.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MonthCloseQueueListenerTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Test
    void theListenerIsOnlyCreatedInTheAwsProfile() {
        Profile profile = MonthCloseQueueListener.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("aws");
        assertThat(environment.getActiveProfiles()).doesNotContain("aws");
        assertThat(context.getBeanNamesForType(MonthCloseQueueListener.class))
                .as("no queue listener without the aws profile")
                .isEmpty();
    }

    @Test
    void theListenerReadsTheQueueNameFromTheJobsQueueProperty() throws Exception {
        Method onMessage = MonthCloseQueueListener.class.getDeclaredMethod("onMessage", String.class);

        SqsListener listener = onMessage.getAnnotation(SqsListener.class);

        assertThat(listener).isNotNull();
        assertThat(Arrays.asList(listener.value())).containsExactly("${jars.jobs.queue-name}");
        // the property exists (blank unless JARS_JOBS_QUEUE is set), so the placeholder resolves in the aws profile
        assertThat(environment.containsProperty("jars.jobs.queue-name")).isTrue();
    }

    @Test
    void aMessageIsHandedToTheJobUnchanged() {
        MonthCloseJob job = mock(MonthCloseJob.class);

        new MonthCloseQueueListener(job).onMessage("{\"job\":\"MONTH_CLOSE\"}");

        verify(job).handle("{\"job\":\"MONTH_CLOSE\"}");
    }

    @Test
    void theJobItselfIsAvailableEverywhere_soTestsAndOtherProfilesCanCallIt() {
        assertThat(context.getBeanNamesForType(MonthCloseJob.class)).hasSize(1);
    }
}

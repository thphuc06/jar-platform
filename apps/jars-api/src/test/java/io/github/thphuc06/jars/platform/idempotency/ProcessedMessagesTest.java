package io.github.thphuc06.jars.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProcessedMessagesTest {

    @Autowired
    ProcessedMessages inbox;

    @Autowired
    TransactionTemplate tx;

    @Test
    void firstTime_isTrueOnceAndFalseForARepeat() {
        String consumer = "test." + UUID.randomUUID();

        Boolean first = tx.execute(s -> inbox.firstTime(consumer, "m1"));
        Boolean again = tx.execute(s -> inbox.firstTime(consumer, "m1"));
        Boolean other = tx.execute(s -> inbox.firstTime(consumer, "m2"));

        assertThat(first).isTrue();
        assertThat(again).isFalse();
        assertThat(other).isTrue();
    }

    @Test
    void contains_seesOnlyCommittedMarkers_withoutWritingAnything() {
        String consumer = "test." + UUID.randomUUID();

        assertThat(inbox.contains(consumer, "m1")).isFalse();
        assertThat(inbox.contains(consumer, "m1")).as("asking does not record").isFalse();

        tx.executeWithoutResult(s -> inbox.firstTime(consumer, "m1"));

        assertThat(inbox.contains(consumer, "m1")).isTrue();
        assertThat(inbox.contains(consumer, "m2")).isFalse();
        assertThat(inbox.contains(consumer + "-other", "m1")).isFalse();
    }

    @Test
    void contains_alsoWorksInsideATransaction() {
        String consumer = "test." + UUID.randomUUID();

        Boolean[] seen = new Boolean[2];
        tx.executeWithoutResult(s -> {
            seen[0] = inbox.contains(consumer, "m1");
            inbox.firstTime(consumer, "m1");
            seen[1] = inbox.contains(consumer, "m1");
        });

        assertThat(seen).containsExactly(false, true);
    }
}

package io.github.thphuc06.jars.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.UserApiTestSupport;
import io.github.thphuc06.jars.shared.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link IdentityApi#userIdsAfter}: the keyset paging the month-close batch uses to visit every user. */
class IdentityApiPagingTest extends UserApiTestSupport {

    @Autowired
    IdentityApi identity;

    @Test
    void pagingVisitsEveryUserOnceInAscendingOrder() {
        List<UserId> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mine.add(newTestUser().id());
        }

        List<UserId> all = new ArrayList<>();
        UUID cursor = null;
        int pages = 0;
        while (true) {
            List<UserId> page = identity.userIdsAfter(cursor, 3);
            assertThat(page).hasSizeLessThanOrEqualTo(3);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            cursor = page.getLast().value();
            pages++;
        }

        assertThat(pages).isGreaterThanOrEqualTo(2);
        assertThat(all).as("no duplicates").doesNotHaveDuplicates();
        assertThat(all).extracting(UserId::value).isSorted();
        assertThat(all).containsAll(mine);
    }

    @Test
    void theCursorIsExclusive_andAUserCreatedAfterwardsIsSeenByTheNextPage() {
        UserId first = newTestUser().id();
        UserId second = newTestUser().id();

        List<UserId> afterFirst = identity.userIdsAfter(first.value(), IdentityApi.MAX_PAGE);

        assertThat(afterFirst).doesNotContain(first).contains(second);
        assertThat(identity.userIdsAfter(second.value(), IdentityApi.MAX_PAGE)).doesNotContain(first, second);
    }

    @Test
    void aPageSizeOutsideTheAllowedRange_isRejected() {
        assertThatThrownBy(() -> identity.userIdsAfter(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> identity.userIdsAfter(null, IdentityApi.MAX_PAGE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

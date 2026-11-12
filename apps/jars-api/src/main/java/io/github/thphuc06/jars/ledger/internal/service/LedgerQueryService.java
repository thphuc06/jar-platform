package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.ledger.internal.domain.TransactionType;
import io.github.thphuc06.jars.ledger.internal.persistence.CategoryRepository;
import io.github.thphuc06.jars.ledger.internal.persistence.JarRepository;
import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries;
import io.github.thphuc06.jars.ledger.internal.service.Views.CategoryView;
import io.github.thphuc06.jars.ledger.internal.service.Views.DailyBalanceView;
import io.github.thphuc06.jars.ledger.internal.service.Views.JarView;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionListItem;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionPage;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the ledger. Every query is scoped to the caller. */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    /** "Today" and the day boundaries of balance charts are Vietnam time (data-model.md). */
    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_BALANCE_DAYS = 366;

    private final JarRepository jars;
    private final CategoryRepository categories;
    private final LedgerQueries queries;
    private final Clock clock;

    LedgerQueryService(JarRepository jars, CategoryRepository categories, LedgerQueries queries, Clock clock) {
        this.jars = jars;
        this.categories = categories;
        this.queries = queries;
        this.clock = clock;
    }

    public List<JarView> jars(UserId userId) {
        return jars.findByUserIdAndArchivedAtIsNullOrderByCreatedAtAscIdAsc(userId.value()).stream()
                .map(JarView::of)
                .toList();
    }

    public List<CategoryView> categories(UserId userId) {
        return categories.findVisibleTo(userId.value()).stream()
                .map(CategoryView::of)
                .toList();
    }

    /** One page of the Money Inbox, newest first. {@code nextCursor} is null on the last page. */
    public TransactionPage transactions(
            UserId userId,
            UUID jarId,
            UUID categoryId,
            TransactionType type,
            Instant from,
            Instant to,
            Integer limit,
            String cursor) {
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new DomainException(ProblemTypes.BAD_REQUEST, "limit phải từ 1 đến " + MAX_PAGE_SIZE + ".");
        }
        Cursor position = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor);
        LedgerQueries.Filter filter = new LedgerQueries.Filter(jarId, categoryId, type, from, to);

        // ask for one row more than the page to learn whether there is a next page without a second query
        List<LedgerQueries.TransactionRow> rows = queries.page(
                userId.value(),
                filter,
                position == null ? null : position.occurredAt(),
                position == null ? null : position.id(),
                pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<LedgerQueries.TransactionRow> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
        List<TransactionListItem> items =
                pageRows.stream().map(TransactionListItem::of).toList();
        String next = hasMore
                ? new Cursor(pageRows.getLast().occurredAt(), pageRows.getLast().id()).encode()
                : null;
        return new TransactionPage(items, next);
    }

    /** Total balance at the end of each of the last {@code days} days, oldest first, ending today. */
    public List<DailyBalanceView> dailyBalances(UserId userId, int days) {
        if (days < 1 || days > MAX_BALANCE_DAYS) {
            throw new DomainException(ProblemTypes.BAD_REQUEST, "days phải từ 1 đến " + MAX_BALANCE_DAYS + ".");
        }
        LocalDate today = LocalDate.now(clock.withZone(VIETNAM));
        return queries.dailyBalances(userId.value(), today.minusDays(days - 1L), today).stream()
                .map(d -> new DailyBalanceView(d.day(), new Money(d.balance(), Money.VND)))
                .toList();
    }
}

package io.github.thphuc06.jars.ledger.internal;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the ledger for GET /jars and GET /categories. */
@Service
public class LedgerQueries {

    /** Standard jars in BR-1 order (NEC first), custom jars after them by name. */
    private static final Comparator<Jar> DISPLAY_ORDER =
            Comparator.comparing(Jar::code).thenComparing(Jar::name);

    private final JarRepository jars;
    private final CategoryRepository categories;
    private final DefaultJarsProvisioner defaultJars;

    LedgerQueries(JarRepository jars, CategoryRepository categories, DefaultJarsProvisioner defaultJars) {
        this.jars = jars;
        this.categories = categories;
        this.defaultJars = defaultJars;
    }

    /**
     * BR-2: {@code unallocated} is the balance of the hidden POOL jar, {@code total} the sum of every jar including
     * POOL. {@code poolJarId} is what a client sends as {@code fromJarId} to allocate income to a jar (found when the
     * web client was built: without it nothing outside the database knew the id).
     */
    public record JarsOverview(
            List<JarView> jars, UUID poolJarId, BigDecimal unallocated, BigDecimal total, String currency) {}

    /**
     * Not read-only: the default jars are created asynchronously after the user's first request (flow F1), so a very
     * first GET /jars may arrive before them, or after the listener failed. It then creates them itself; this is safe
     * because creation is idempotent and race-safe.
     */
    @Transactional
    public JarsOverview jarsOf(UUID userId) {
        List<Jar> active = jars.findByUserIdAndArchivedAtIsNull(userId);
        if (active.isEmpty()) { // never empty once created: POOL cannot be archived
            defaultJars.ensureFor(userId);
            active = jars.findByUserIdAndArchivedAtIsNull(userId);
        }
        Jar pool = active.stream().filter(Jar::isPool).findFirst().orElseThrow(); // exactly one per user (V3)
        BigDecimal total = active.stream().map(Jar::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<JarView> visible = active.stream()
                .filter(jar -> !jar.isPool())
                .sorted(DISPLAY_ORDER)
                .map(JarView::of)
                .toList();
        return new JarsOverview(visible, pool.id(), pool.balance(), total, Jar.VND);
    }

    @Transactional(readOnly = true)
    public List<CategoryView> categoriesOf(UUID userId) {
        return categories.findVisibleTo(userId).stream().map(CategoryView::of).toList();
    }
}

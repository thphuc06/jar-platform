package io.github.thphuc06.jars.insights.internal.service;

import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the dashboard's monthly read model up to date. Every change is an additive upsert (a delta), so applying the
 * same set of events in any order gives the same totals, and moving an expense between jars or categories is just a
 * negative delta on one row and a positive one on another.
 */
@Component
class ReadModelWriter {

    /** Stored in place of a category id for expenses that have no category, so the id can be part of the key. */
    static final UUID UNCATEGORIZED = new UUID(0L, 0L);

    private final JdbcClient jdbc;

    ReadModelWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void addExpense(UserId user, UUID jarId, UUID categoryId, LocalDate month, Money amount) {
        jarSpend(user, jarId, month, amount, 1);
        categorySpend(user, categoryId, month, amount, 1);
        cashflow(user, month, Money.zero(amount.currency()), amount);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void addIncome(UserId user, LocalDate month, Money amount) {
        cashflow(user, month, amount, Money.zero(amount.currency()));
    }

    /** An expense moved from one jar to another: it no longer counts for {@code from} and now counts for {@code to}. */
    @Transactional(propagation = Propagation.MANDATORY)
    void moveBetweenJars(UserId user, UUID from, UUID to, LocalDate month, Money amount) {
        jarSpend(user, from, month, amount.negate(), -1);
        jarSpend(user, to, month, amount, 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void moveBetweenCategories(UserId user, UUID from, UUID to, LocalDate month, Money amount) {
        categorySpend(user, from, month, amount.negate(), -1);
        categorySpend(user, to, month, amount, 1);
    }

    private void jarSpend(UserId user, UUID jarId, LocalDate month, Money delta, int countDelta) {
        jdbc.sql("""
                        INSERT INTO monthly_jar_spend (user_id, jar_id, month, spent, txn_count)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, jar_id, month) DO UPDATE
                        SET spent = monthly_jar_spend.spent + EXCLUDED.spent,
                            txn_count = monthly_jar_spend.txn_count + EXCLUDED.txn_count
                        """)
                .params(user.value(), jarId, month, delta.amount(), countDelta)
                .update();
    }

    private void categorySpend(UserId user, UUID categoryId, LocalDate month, Money delta, int countDelta) {
        jdbc.sql("""
                        INSERT INTO monthly_category_spend (user_id, category_id, month, spent, txn_count)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, category_id, month) DO UPDATE
                        SET spent = monthly_category_spend.spent + EXCLUDED.spent,
                            txn_count = monthly_category_spend.txn_count + EXCLUDED.txn_count
                        """)
                .params(
                        user.value(),
                        categoryId == null ? UNCATEGORIZED : categoryId,
                        month,
                        delta.amount(),
                        countDelta)
                .update();
    }

    private void cashflow(UserId user, LocalDate month, Money income, Money expense) {
        jdbc.sql("""
                        INSERT INTO monthly_cashflow (user_id, month, income, expense)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (user_id, month) DO UPDATE
                        SET income = monthly_cashflow.income + EXCLUDED.income,
                            expense = monthly_cashflow.expense + EXCLUDED.expense
                        """)
                .params(user.value(), month, income.amount(), expense.amount())
                .update();
    }
}

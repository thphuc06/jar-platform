package io.github.thphuc06.jars.goals.internal.web;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.github.thphuc06.jars.goals.internal.GoalService;
import io.github.thphuc06.jars.goals.internal.GoalStatus;
import io.github.thphuc06.jars.goals.internal.GoalView;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.shared.MoneyFormat;
import io.github.thphuc06.jars.shared.VndAmount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/goals")
class GoalController {

    private final IdentityApi identity;
    private final GoalService goals;

    GoalController(IdentityApi identity, GoalService goals) {
        this.identity = identity;
        this.goals = goals;
    }

    /** Goals that are not archived, nearest deadline first, with the progress of their funding jar. */
    @GetMapping
    GoalsResponse listGoals() {
        return new GoalsResponse(
                goals.list(userId()).stream().map(GoalResponse::from).toList());
    }

    @GetMapping("/{id}")
    GoalResponse getGoal(@PathVariable UUID id) {
        return GoalResponse.from(goals.get(userId(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<GoalResponse> createGoal(@Valid @RequestBody CreateGoalRequest request) {
        GoalView created = goals.create(userId(), request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/goals/" + created.id()))
                .body(GoalResponse.from(created));
    }

    /** Fields left out stay as they are; {@code "fundingJarId": null} detaches the jar. */
    @PatchMapping("/{id}")
    GoalResponse updateGoal(@PathVariable UUID id, @Valid @RequestBody UpdateGoalRequest request) {
        return GoalResponse.from(goals.update(userId(), id, request.toCommand()));
    }

    /** Archives (soft delete). 204 also when the goal was already archived. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> archiveGoal(@PathVariable UUID id) {
        goals.archive(userId(), id);
        return ResponseEntity.noContent().build();
    }

    private UUID userId() {
        return identity.currentUser().id();
    }

    record CreateGoalRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @VndAmount BigDecimal targetAmount,
            @NotNull LocalDate targetDate,
            @Nullable UUID fundingJarId) {

        GoalService.NewGoal toCommand() {
            return new GoalService.NewGoal(name, targetAmount, targetDate, fundingJarId);
        }
    }

    /** A class, not a record, to tell "fundingJarId not sent" from "sent as null" (see the PATCH of transactions). */
    static final class UpdateGoalRequest {

        @Size(min = 1, max = 100)
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        private @Nullable String name;

        @VndAmount
        private @Nullable BigDecimal targetAmount;

        private @Nullable LocalDate targetDate;

        private boolean fundingJarSent;
        private @Nullable UUID fundingJarId;

        private @Nullable GoalStatus status;

        @JsonSetter("name")
        void name(String value) {
            name = value;
        }

        @JsonSetter("targetAmount")
        void targetAmount(BigDecimal value) {
            targetAmount = value;
        }

        @JsonSetter("targetDate")
        void targetDate(LocalDate value) {
            targetDate = value;
        }

        @JsonSetter("fundingJarId")
        void fundingJarId(UUID value) {
            fundingJarSent = true;
            fundingJarId = value;
        }

        @JsonSetter("status")
        void status(GoalStatus value) {
            status = value;
        }

        GoalService.GoalChanges toCommand() {
            return new GoalService.GoalChanges(
                    name, targetAmount, targetDate, fundingJarSent ? Optional.ofNullable(fundingJarId) : null, status);
        }
    }

    record GoalsResponse(List<GoalResponse> items) {}

    record GoalResponse(
            UUID id,
            String name,
            String targetAmount,
            LocalDate targetDate,
            GoalStatus status,
            @Nullable FundingJar fundingJar,
            @Nullable Integer progressPct) {

        record FundingJar(UUID id, String code, String name, String balance, boolean archived) {

            static FundingJar from(JarSummary jar) {
                return jar == null
                        ? null
                        : new FundingJar(
                                jar.id(), jar.code(), jar.name(), MoneyFormat.format(jar.balance()), jar.archived());
            }
        }

        static GoalResponse from(GoalView goal) {
            return new GoalResponse(
                    goal.id(),
                    goal.name(),
                    MoneyFormat.format(goal.targetAmount()),
                    goal.targetDate(),
                    goal.status(),
                    FundingJar.from(goal.fundingJar()),
                    goal.progressPct());
        }
    }
}

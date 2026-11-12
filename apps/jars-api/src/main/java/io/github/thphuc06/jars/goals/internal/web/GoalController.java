package io.github.thphuc06.jars.goals.internal.web;

import io.github.thphuc06.jars.goals.internal.domain.GoalStatus;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse;
import io.github.thphuc06.jars.goals.internal.service.FeasibilityService;
import io.github.thphuc06.jars.goals.internal.service.FeasibilityService.FeasibilityView;
import io.github.thphuc06.jars.goals.internal.service.GoalService;
import io.github.thphuc06.jars.goals.internal.service.GoalService.CreateGoal;
import io.github.thphuc06.jars.goals.internal.service.GoalService.GoalView;
import io.github.thphuc06.jars.goals.internal.service.GoalService.UpdateGoal;
import io.github.thphuc06.jars.goals.internal.service.RoadmapService;
import io.github.thphuc06.jars.goals.internal.service.ScenarioService;
import io.github.thphuc06.jars.goals.internal.service.ScenarioService.ScenarioCommand;
import io.github.thphuc06.jars.goals.internal.service.ScenarioService.ScenarioView;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

    private final GoalService goals;
    private final FeasibilityService feasibility;
    private final ScenarioService scenarios;
    private final RoadmapService roadmaps;

    GoalController(
            GoalService goals, FeasibilityService feasibility, ScenarioService scenarios, RoadmapService roadmaps) {
        this.goals = goals;
        this.feasibility = feasibility;
        this.scenarios = scenarios;
        this.roadmaps = roadmaps;
    }

    @GetMapping
    List<GoalView> listGoals(UserId userId) {
        return goals.list(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GoalView createGoal(UserId userId, @Valid @RequestBody CreateGoalRequest request) {
        return goals.create(
                userId,
                new CreateGoal(
                        request.name(),
                        new Money(request.targetAmount(), Money.VND),
                        request.targetDate(),
                        request.fundingJarId()));
    }

    @PatchMapping("/{id}")
    GoalView updateGoal(UserId userId, @PathVariable UUID id, @Valid @RequestBody UpdateGoalRequest request) {
        return goals.update(
                userId,
                id,
                new UpdateGoal(
                        request.name(),
                        request.targetAmount() == null ? null : new Money(request.targetAmount(), Money.VND),
                        request.targetDate(),
                        request.fundingJarId(),
                        request.status()));
    }

    /** Archives the goal: it disappears from the list and every operation on it answers 404. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archiveGoal(UserId userId, @PathVariable UUID id) {
        goals.archive(userId, id);
    }

    /** Can the target be reached by the date at the current saving pace, and how much per month does it take? */
    @GetMapping("/{id}/feasibility")
    FeasibilityView goalFeasibility(UserId userId, @PathVariable UUID id) {
        return feasibility.feasibility(userId, id);
    }

    /** What-if: the same goal with a changed saving, income or spending. Computes only; nothing is stored. */
    @PostMapping("/{id}/scenarios")
    ScenarioView goalScenarios(UserId userId, @PathVariable UUID id, @Valid @RequestBody ScenarioRequest request) {
        return scenarios.scenarios(
                userId,
                id,
                new ScenarioCommand(
                        money(request.monthlySavingDelta()),
                        money(request.incomeDelta()),
                        money(request.spendingDelta()),
                        money(request.oneOffContribution()),
                        request.horizonMonths(),
                        request.seed()));
    }

    /** The staged plan of the goal, in the shape of the legacy UI's {@code RoadmapResponse}. */
    @GetMapping("/{id}/roadmap")
    RoadmapResponse goalRoadmap(UserId userId, @PathVariable UUID id) {
        return roadmaps.roadmap(userId, id);
    }

    private static Money money(BigDecimal amount) {
        return amount == null ? null : new Money(amount, Money.VND);
    }

    record CreateGoalRequest(
            @NotBlank(message = "Tên mục tiêu không được để trống")
            @Size(max = 80, message = "Tên mục tiêu tối đa 80 ký tự")
            String name,

            @NotNull(message = "Thiếu số tiền mục tiêu")
            @Positive(message = "Số tiền mục tiêu phải lớn hơn 0")
            @Digits(integer = 17, fraction = 2, message = "Số tiền tối đa 2 chữ số thập phân")
            BigDecimal targetAmount,

            @NotNull(message = "Thiếu ngày đạt mục tiêu") LocalDate targetDate,

            UUID fundingJarId) {}

    /** Every field is optional; a missing field keeps the current value. */
    record UpdateGoalRequest(
            @Size(min = 1, max = 80, message = "Tên mục tiêu từ 1 đến 80 ký tự")
            String name,

            @Positive(message = "Số tiền mục tiêu phải lớn hơn 0")
            @Digits(integer = 17, fraction = 2, message = "Số tiền tối đa 2 chữ số thập phân")
            BigDecimal targetAmount,

            LocalDate targetDate,
            UUID fundingJarId,
            GoalStatus status) {}

    /**
     * Every field is optional. With none of the four changes the response compares the base case with three default
     * scenarios; with at least one it compares the base case with that change.
     */
    record ScenarioRequest(
            @Digits(integer = 15, fraction = 2, message = "Tối đa 15 chữ số và 2 chữ số thập phân")
            BigDecimal monthlySavingDelta,

            @Digits(integer = 15, fraction = 2, message = "Tối đa 15 chữ số và 2 chữ số thập phân")
            BigDecimal incomeDelta,

            @Digits(integer = 15, fraction = 2, message = "Tối đa 15 chữ số và 2 chữ số thập phân")
            BigDecimal spendingDelta,

            @Digits(integer = 15, fraction = 2, message = "Tối đa 15 chữ số và 2 chữ số thập phân")
            BigDecimal oneOffContribution,

            @Min(value = 1, message = "horizonMonths tối thiểu 1")
            @Max(value = ScenarioService.MAX_HORIZON_MONTHS, message = "horizonMonths tối đa 120")
            Integer horizonMonths,

            Long seed) {}
}

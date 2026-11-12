package io.github.thphuc06.jars.insights.internal.web;

import io.github.thphuc06.jars.insights.internal.model.AnomalyStatus;
import io.github.thphuc06.jars.insights.internal.service.AnomalyService;
import io.github.thphuc06.jars.insights.internal.service.AnomalyService.AnomalyPage;
import io.github.thphuc06.jars.insights.internal.service.AnomalyService.AnomalyView;
import io.github.thphuc06.jars.insights.internal.service.DashboardService;
import io.github.thphuc06.jars.insights.internal.service.DashboardService.Dashboard;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class InsightsController {

    private final DashboardService dashboards;
    private final AnomalyService anomalies;

    InsightsController(DashboardService dashboards, AnomalyService anomalies) {
        this.dashboards = dashboards;
        this.anomalies = anomalies;
    }

    /** Everything the home screen shows for one month ({@code YYYY-MM}, default: the current month). */
    @GetMapping("/dashboard")
    Dashboard dashboard(UserId userId, @RequestParam(required = false) String month) {
        return dashboards.dashboard(userId, month == null ? dashboards.currentMonth() : parse(month));
    }

    @GetMapping("/anomalies")
    AnomalyPage anomalies(
            UserId userId,
            @RequestParam(required = false) AnomalyStatus status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return anomalies.list(userId, status, limit, cursor);
    }

    @PostMapping("/anomalies/{id}/feedback")
    AnomalyView feedback(UserId userId, @PathVariable UUID id, @Valid @RequestBody FeedbackRequest request) {
        return anomalies.feedback(userId, id, request.label());
    }

    record FeedbackRequest(
            @NotBlank(message = "Thiếu label") String label) {}

    private static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new DomainException(ProblemTypes.BAD_REQUEST, "month phải có dạng YYYY-MM, ví dụ 2026-11.");
        }
    }
}

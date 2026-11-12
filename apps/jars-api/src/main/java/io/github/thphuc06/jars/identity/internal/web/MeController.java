package io.github.thphuc06.jars.identity.internal.web;

import io.github.thphuc06.jars.identity.internal.service.RiskProfileService;
import io.github.thphuc06.jars.identity.internal.service.UserService;
import io.github.thphuc06.jars.shared.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own profile and onboarding risk profile. The user always comes from the token, never from the URL. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final UserService users;
    private final RiskProfileService riskProfiles;

    MeController(UserService users, RiskProfileService riskProfiles) {
        this.users = users;
        this.riskProfiles = riskProfiles;
    }

    @GetMapping
    MeResponse me(UserId userId) {
        return MeResponse.from(users.profile(userId));
    }

    @PatchMapping
    MeResponse update(UserId userId, @Valid @RequestBody UpdateMeRequest request) {
        return MeResponse.from(users.updateProfile(userId, request.displayName(), request.locale()));
    }

    @PutMapping("/risk-profile")
    RiskProfileResponse submitRiskProfile(UserId userId, @Valid @RequestBody RiskAnswersRequest request) {
        return RiskProfileResponse.from(riskProfiles.submit(userId, request.answers()));
    }

    @GetMapping("/risk-profile")
    RiskProfileResponse riskProfile(UserId userId) {
        return RiskProfileResponse.from(riskProfiles.current(userId));
    }

    record MeResponse(UUID id, String email, String displayName, String locale, boolean onboardingCompleted) {
        static MeResponse from(UserService.Profile p) {
            return new MeResponse(p.id(), p.email(), p.displayName(), p.locale(), p.onboardingCompleted());
        }
    }

    record UpdateMeRequest(
            @Size(max = 80, message = "Tên hiển thị tối đa 80 ký tự")
            String displayName,

            @Pattern(regexp = "vi-VN|en-US", message = "locale phải là vi-VN hoặc en-US")
            String locale) {}

    record RiskAnswersRequest(
            @NotNull(message = "Thiếu danh sách câu trả lời")
            @Size(min = 5, max = 5, message = "Cần đúng 5 câu trả lời")
            List<@NotNull Integer> answers) {}

    record RiskProfileResponse(int score, String riskLevel, Instant assessedAt) {
        static RiskProfileResponse from(RiskProfileService.RiskProfile p) {
            return new RiskProfileResponse(p.score(), p.level().name(), p.assessedAt());
        }
    }
}

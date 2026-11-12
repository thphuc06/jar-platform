package io.github.thphuc06.jars.advisor.internal.service;

import io.github.thphuc06.jars.shared.ProblemType;

/** Problem types of the advisor API. */
public final class AdvisorProblems {

    public static final ProblemType RATE_LIMITED =
            new ProblemType("advisor-rate-limited", 429, "Đã hết lượt hỏi trợ lý");

    public static final ProblemType ADVISOR_UNAVAILABLE =
            new ProblemType("advisor-unavailable", 503, "Trợ lý AI chưa sẵn sàng");

    public static final ProblemType INVALID_MESSAGE = new ProblemType("invalid-message", 422, "Tin nhắn không hợp lệ");

    private AdvisorProblems() {}
}

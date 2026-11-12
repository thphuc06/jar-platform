package io.github.thphuc06.jars.shared;

/** Problem types shared by every module. Module-specific ones are declared next to the module that throws them. */
public final class ProblemTypes {

    public static final ProblemType BAD_REQUEST = new ProblemType("bad-request", 400, "Yêu cầu không hợp lệ");
    public static final ProblemType UNAUTHENTICATED = new ProblemType("unauthenticated", 401, "Chưa xác thực");
    public static final ProblemType FORBIDDEN = new ProblemType("forbidden", 403, "Không có quyền truy cập");
    public static final ProblemType NOT_FOUND = new ProblemType("not-found", 404, "Không tìm thấy tài nguyên");
    public static final ProblemType VERSION_CONFLICT =
            new ProblemType("version-conflict", 409, "Dữ liệu vừa bị thay đổi, hãy thử lại");
    public static final ProblemType VALIDATION_FAILED =
            new ProblemType("validation-failed", 422, "Dữ liệu không hợp lệ");
    public static final ProblemType IDEMPOTENCY_KEY_INVALID =
            new ProblemType("idempotency-key-invalid", 400, "Idempotency-Key không hợp lệ");
    public static final ProblemType IDEMPOTENCY_KEY_REUSED =
            new ProblemType("idempotency-key-reused", 422, "Idempotency-Key đã được dùng cho request khác");
    public static final ProblemType INTERNAL_ERROR = new ProblemType("internal-error", 500, "Lỗi hệ thống");

    private ProblemTypes() {}
}

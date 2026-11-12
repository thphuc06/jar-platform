/**
 * RFC 9457 `application/problem+json` as jars-api sends it (docs/03-api/api-contract.md section 1) and the
 * typed error the rest of the app works with.
 */

export interface FieldError {
  field?: string;
  message?: string;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  traceId?: string;
  errors?: FieldError[];
}

const FALLBACK_TITLE: Record<number, string> = {
  400: "Yêu cầu không hợp lệ",
  401: "Phiên đăng nhập đã hết hạn",
  403: "Không có quyền truy cập",
  404: "Không tìm thấy dữ liệu",
  409: "Dữ liệu vừa được thay đổi, vui lòng thử lại",
  422: "Dữ liệu không hợp lệ",
  429: "Bạn thao tác quá nhanh, vui lòng thử lại sau",
  503: "Dịch vụ tạm thời không khả dụng",
};

export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly detail?: string;
  /** Problem type URI, e.g. https://jars.local/problems/insufficient-jar-balance */
  readonly type?: string;
  readonly traceId?: string;
  readonly errors: FieldError[];
  readonly retryAfterSeconds?: number;

  constructor(status: number, problem: ProblemDetail = {}, retryAfterSeconds?: number) {
    const title = problem.title || FALLBACK_TITLE[status] || `Lỗi máy chủ (${status})`;
    super(problem.detail || title);
    this.name = "ApiError";
    this.status = status;
    this.title = title;
    this.detail = problem.detail;
    this.type = problem.type;
    this.traceId = problem.traceId;
    this.errors = Array.isArray(problem.errors) ? problem.errors : [];
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** Last path segment of the problem type: "insufficient-jar-balance". Empty when the server sent none. */
  get code(): string {
    if (!this.type) return "";
    const path = this.type.split(/[?#]/)[0] ?? "";
    return path.split("/").filter(Boolean).pop() ?? "";
  }

  get isUnauthorized() {
    return this.status === 401;
  }
  get isNotFound() {
    return this.status === 404;
  }
  get isConflict() {
    return this.status === 409;
  }
  get isValidation() {
    return this.status === 400 || this.status === 422;
  }

  /** Message for a toast: the server's `detail` is already written for the user, in Vietnamese. */
  get userMessage(): string {
    const fields = this.errors.map((e) => e.message).filter(Boolean);
    const base = this.detail || this.title;
    return fields.length > 0 && !fields.every((f) => base.includes(f as string)) ? `${base} (${fields.join("; ")})` : base;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value !== "" ? value : undefined;
}

/** Keep only the fields we know, with the right types: a proxy page or a future field must not break the UI. */
export function parseProblem(body: unknown): ProblemDetail {
  if (!isRecord(body)) return {};
  const errors = Array.isArray(body.errors)
    ? body.errors.filter(isRecord).map((e) => ({ field: asString(e.field), message: asString(e.message) }))
    : undefined;
  return {
    type: asString(body.type),
    title: asString(body.title),
    status: typeof body.status === "number" ? body.status : undefined,
    detail: asString(body.detail),
    instance: asString(body.instance),
    traceId: asString(body.traceId),
    errors,
  };
}

function parseRetryAfter(value: string | null): number | undefined {
  if (value === null) return undefined;
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}

/**
 * Turns a failed response into an ApiError. The body is read from a clone, so the caller can still use the
 * response. A non-JSON body (HTML from a proxy, an empty 502) falls back to a message chosen by status.
 */
export async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail = {};
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("json")) {
    try {
      problem = parseProblem(await response.clone().json());
    } catch {
      problem = {};
    }
  }
  return new ApiError(response.status, problem, parseRetryAfter(response.headers.get("retry-after")));
}

/** Any thrown value -> text for a toast or an inline message. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) return error.userMessage;
  if (error instanceof TypeError) return "Không kết nối được máy chủ. Hãy kiểm tra mạng và thử lại.";
  if (error instanceof Error && error.message) return error.message;
  return "Đã có lỗi xảy ra. Vui lòng thử lại.";
}

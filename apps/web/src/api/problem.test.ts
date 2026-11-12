import { describe, expect, it } from "vitest";
import { ApiError, describeError, parseProblem, toApiError } from "./problem";

const problemResponse = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/problem+json", ...headers },
  });

describe("toApiError (RFC 9457 problem+json -> ApiError)", () => {
  it("maps every field of the contract example", async () => {
    const response = problemResponse(422, {
      type: "https://jars.local/problems/insufficient-jar-balance",
      title: "Số dư hũ không đủ",
      status: 422,
      detail: "Hũ PLAY còn 120000.00 VND, yêu cầu chi 200000.00 VND",
      instance: "/api/v1/transactions",
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      errors: [{ field: "amount", message: "vượt số dư" }],
    });

    const error = await toApiError(response);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toBeInstanceOf(Error);
    expect(error.status).toBe(422);
    expect(error.title).toBe("Số dư hũ không đủ");
    expect(error.detail).toBe("Hũ PLAY còn 120000.00 VND, yêu cầu chi 200000.00 VND");
    expect(error.traceId).toBe("4bf92f3577b34da6a3ce929d0e0e4736");
    expect(error.errors).toEqual([{ field: "amount", message: "vượt số dư" }]);
    expect(error.code).toBe("insufficient-jar-balance");
    expect(error.isValidation).toBe(true);
    expect(error.message).toBe(error.detail);
  });

  it("uses the title when there is no detail, and tolerates absent optional fields", async () => {
    const error = await toApiError(problemResponse(404, { title: "Không tìm thấy", status: 404 }));
    expect(error.message).toBe("Không tìm thấy");
    expect(error.errors).toEqual([]);
    expect(error.traceId).toBeUndefined();
    expect(error.code).toBe("");
    expect(error.isNotFound).toBe(true);
  });

  it("flags 401 and 409", async () => {
    expect((await toApiError(problemResponse(401, {}))).isUnauthorized).toBe(true);
    expect((await toApiError(problemResponse(409, {}))).isConflict).toBe(true);
  });

  it("reads Retry-After on a 429", async () => {
    const error = await toApiError(problemResponse(429, { title: "Quá nhiều yêu cầu" }, { "retry-after": "12" }));
    expect(error.retryAfterSeconds).toBe(12);
  });

  it("falls back to a message by status when the body is not JSON (proxy HTML, empty 502)", async () => {
    const html = new Response("<html>Bad gateway</html>", { status: 502, headers: { "content-type": "text/html" } });
    const error = await toApiError(html);
    expect(error.status).toBe(502);
    expect(error.title).toBe("Lỗi máy chủ (502)");
    expect(error.errors).toEqual([]);
  });

  it("falls back when the JSON is malformed or has the wrong shape", async () => {
    const broken = new Response("{not json", { status: 500, headers: { "content-type": "application/json" } });
    expect((await toApiError(broken)).status).toBe(500);
    const array = problemResponse(400, ["x"]);
    expect((await toApiError(array)).title).toBe("Yêu cầu không hợp lệ");
  });

  it("leaves the response body readable for the caller", async () => {
    const response = problemResponse(422, { title: "x" });
    await toApiError(response);
    expect(await response.json()).toEqual({ title: "x" });
  });
});

describe("parseProblem", () => {
  it("drops fields of the wrong type", () => {
    expect(parseProblem({ title: 5, status: "422", detail: ["x"], traceId: "abc", errors: [{ field: 1, message: "m" }, 7] })).toEqual({
      type: undefined,
      title: undefined,
      status: undefined,
      detail: undefined,
      instance: undefined,
      traceId: "abc",
      errors: [{ field: undefined, message: "m" }],
    });
  });

  it("returns an empty problem for non-objects", () => {
    expect(parseProblem(null)).toEqual({});
    expect(parseProblem("text")).toEqual({});
  });
});

describe("ApiError.userMessage and describeError", () => {
  it("appends field messages that the detail does not already contain", () => {
    const error = new ApiError(422, {
      detail: "Dữ liệu không hợp lệ",
      errors: [{ field: "amount", message: "vượt số dư" }],
    });
    expect(error.userMessage).toBe("Dữ liệu không hợp lệ (vượt số dư)");
  });

  it("describes network failures and unknown values", () => {
    expect(describeError(new TypeError("Failed to fetch"))).toContain("Không kết nối được máy chủ");
    expect(describeError(new ApiError(403, { detail: "Không có quyền" }))).toBe("Không có quyền");
    expect(describeError("boom")).toBe("Đã có lỗi xảy ra. Vui lòng thử lại.");
  });
});

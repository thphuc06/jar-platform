import { ApiError } from "./problem";

/**
 * Result of a successful call. The generated response types are lax (springdoc marks no field as required and
 * describes Money wrongly, see types.ts), so the caller names the tightened type from `types.ts` as `R`.
 */
export async function unwrap<R>(
  request: Promise<{ data?: unknown; error?: unknown; response: Response }>,
): Promise<R> {
  const { data, response } = await request;
  if (data === undefined) throw new ApiError(response.status, { title: "Máy chủ trả về nội dung rỗng" });
  return data as R;
}

/** For 204 responses and calls whose body is not used. */
export async function unwrapEmpty(request: Promise<{ response: Response }>): Promise<void> {
  await request;
}

/**
 * Request bodies carry money as decimal strings ("85000"), as api-contract.md section 1 specifies, while the
 * generated request types say `number` (springdoc reflects the Java `BigDecimal`). Jackson reads either.
 * This is the single place that bridges the two.
 */
export function wireBody(body: object): never {
  return body as never;
}

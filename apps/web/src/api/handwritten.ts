/**
 * TODO(WP5-openapi): delete this file together with `GoalsPaths` (goals.ts) and `BudgetPaths` (budget.ts) once
 * contracts/openapi/jars-api.yaml contains `/api/v1/goals/**` and `/api/v1/budget/**`.
 *
 * It lets hand-written endpoint descriptions have the same shape as openapi-typescript's output (`paths`), so the
 * hooks that call `api.GET("/api/v1/goals")` do not change when the generated types take over: `client.ts` only has to
 * drop the `& GoalsPaths & BudgetPaths` intersection.
 */

type NoParams = { query?: never; header?: never; path?: never; cookie?: never };

type QueryPart<Q> = [Q] extends [never] ? { query?: never } : { query?: Q };
type PathPart<P> = [P] extends [never] ? { path?: never } : { path: P };
type HeaderPart<H> = [H] extends [never] ? { header?: never } : { header: H };
type BodyPart<B> = [B] extends [never]
  ? { requestBody?: never }
  : { requestBody: { content: { "application/json": B } } };

/** One operation in the shape openapi-typescript generates for it. */
export type Operation<
  Res,
  Opts extends { body?: unknown; query?: object; path?: object; header?: object; status?: number } = object,
> = {
  parameters: QueryPart<Opts extends { query: infer Q } ? Q : never> &
    PathPart<Opts extends { path: infer P } ? P : never> &
    HeaderPart<Opts extends { header: infer H } ? H : never> & { cookie?: never };
  responses: {
    [S in Opts extends { status: infer N extends number } ? N : 200]: {
      headers: { [name: string]: unknown };
      content: [Res] extends [never] ? never : { "application/json": Res };
    };
  };
} & BodyPart<Opts extends { body: infer B } ? B : never>;

/** A path with the given operations; the other methods are `never`, as in the generated `paths`. */
export type PathItem<Methods extends { get?: unknown; post?: unknown; put?: unknown; patch?: unknown; delete?: unknown }> =
  Omit<
    {
      parameters: NoParams;
      get?: never;
      put?: never;
      post?: never;
      delete?: never;
      options?: never;
      head?: never;
      patch?: never;
      trace?: never;
    },
    keyof Methods
  > &
    Methods;

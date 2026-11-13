/**
 * Domain types of the API, derived from the generated `schema.d.ts` (contracts/openapi/jars-api.yaml).
 *
 * The generated types are a faithful copy of what springdoc wrote, which differs from the JSON on the wire in two
 * ways, and this file corrects both in one place:
 *
 * 1. springdoc marks no property as required, so every field is optional in `schema.d.ts`. The server always sends
 *    every field (Java records), a few of them as `null`. `Wire<T, M, N>` makes all fields present and lists the
 *    nullable ones (`N`).
 * 2. springdoc describes `Money` by reflecting the Java record (`amount: number`, `currency: {...}`). On the wire
 *    it is `{"amount":"85000.00","currency":"VND"}` (api-contract.md section 1, ADR-0014). `M` lists the Money fields.
 *
 * If the backend fixes its OpenAPI description, drop the overrides here and let `schema.d.ts` speak for itself.
 */
import type { Money } from "@/lib/money";
import type { components } from "./schema";

export type { Money };

type S = components["schemas"];

type Wire<T, M extends keyof T = never, N extends keyof T = never> = Omit<Required<T>, M | N> & {
  [K in M]: Money;
} & {
  [K in N]: Exclude<T[K], undefined> | null;
};

/**
 * `Wire` for payloads with nested objects (roadmap, scenarios, budget periods): the same two corrections, applied at
 * every level. Fields are all present, and every generated `Money` (`{amount, currency}` and nothing else) is the
 * wire `Money`. A field the server can send as `null` is not representable here: widen it where the type is used
 * (see `Goal.fundingJarId`).
 */
export type DeepWire<T> = DeepWireDefined<Exclude<T, undefined>>;
type DeepWireDefined<T> = T extends readonly (infer U)[]
  ? DeepWire<U>[]
  : T extends object
    ? keyof T extends "amount" | "currency"
      ? Money
      : { [K in keyof T]-?: DeepWire<T[K]> }
    : T;

// ---- identity -----------------------------------------------------------------------------------------------

export type Me = Wire<S["MeResponse"], never, "email" | "displayName" | "ageBand" | "incomeSituation">;
export type RiskProfile = Wire<S["RiskProfileResponse"]>;

// ---- ledger -------------------------------------------------------------------------------------------------

export type TransactionType = "INCOME" | "EXPENSE" | "JAR_TRANSFER";

export type Jar = Wire<S["JarView"], "balance">;
export type Category = Wire<S["CategoryView"], never, "parentId">;

/** A Money Inbox row (jar and category names are joined in by the server). */
export type Transaction = Omit<
  Wire<
    S["TransactionListItem"],
    "amount",
    "jarId" | "jarName" | "toJarId" | "toJarName" | "categoryId" | "categoryName" | "counterparty" | "note"
  >,
  "type"
> & { type: TransactionType };

export interface TransactionPage {
  items: Transaction[];
  nextCursor: string | null;
}

/** A transaction right after it was written (no joined names). */
export type PostedTransaction = Omit<
  Wire<S["TransactionView"], "amount", "jarId" | "toJarId" | "categoryId" | "counterparty" | "note">,
  "type"
> & { type: TransactionType };

export interface TransactionReceipt {
  transaction: PostedTransaction;
  jarBalances: Array<{ jarId: string; balance: Money }>;
}

/** Result of a money-changing POST: the receipt and whether the server replayed an earlier attempt. */
export interface Posted {
  receipt: TransactionReceipt;
  /** True when the server answered from the stored result of an earlier attempt with the same Idempotency-Key */
  replayed: boolean;
}

export type DailyBalance = Wire<S["DailyBalanceView"], "balance">;

export interface PostTransactionBody {
  type: TransactionType;
  /** whole VND as a decimal string, from `parseVndInput` */
  amount: string;
  jarId?: string;
  categoryId?: string;
  counterparty?: string;
  note?: string;
  occurredAt?: string;
}

export interface TransferBody {
  fromJarId: string;
  toJarId: string;
  amount: string;
  note?: string;
}

export interface CreateJarBody {
  name: string;
  targetPct?: number;
}

export interface UpdateJarBody {
  name?: string;
  targetPct?: number;
}

export interface PatchTransactionBody {
  categoryId?: string;
  jarId?: string;
}

// ---- insights -----------------------------------------------------------------------------------------------

export type JarSpend = Wire<S["JarSpend"], "balance" | "spent">;
export type CategorySpend = Wire<S["CategorySpend"], "spent", "categoryId">;
export type MonthFlow = Wire<S["MonthFlow"], "income" | "expense">;

export type Dashboard = Omit<
  Wire<S["Dashboard"], "balance" | "income" | "expense" | "net">,
  "spendByJar" | "spendByCategory" | "trend"
> & {
  spendByJar: JarSpend[];
  spendByCategory: CategorySpend[];
  trend: MonthFlow[];
};

export type AnomalyStatus = "OPEN" | "CONFIRMED" | "EXPECTED" | "FALSE_POSITIVE";
export type AnomalyFeedback = Exclude<AnomalyStatus, "OPEN">;

export type Anomaly = Omit<Wire<S["AnomalyView"], "amount", "transactionId">, "status"> & { status: AnomalyStatus };

export interface AnomalyPage {
  items: Anomaly[];
  nextCursor: string | null;
}

// ---- notifications ------------------------------------------------------------------------------------------

export type Notification = Wire<S["NotificationView"], never, "body" | "refType" | "refId" | "readAt">;

export interface NotificationPage {
  items: Notification[];
  nextCursor: string | null;
  /** All unread notifications of the user, not just the ones on this page: it drives the badge. */
  unreadCount: number;
}

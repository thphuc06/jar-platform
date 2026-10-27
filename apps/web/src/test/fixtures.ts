import type { components } from "@/api";

type Schemas = components["schemas"];

export const FOOD = "01900000-0000-7000-8000-000000000001";
export const FUN = "01900000-0000-7000-8000-000000000008";
export const SALARY = "01900000-0000-7000-8000-000000000101";

export const categories: Schemas["CategoriesResponse"] = {
  items: [
    { id: FOOD, name: "Ăn uống", kind: "EXPENSE", defaultJarCode: "NEC", system: true },
    { id: FUN, name: "Giải trí", kind: "EXPENSE", defaultJarCode: "PLAY", system: true },
    { id: SALARY, name: "Lương", kind: "INCOME", defaultJarCode: null, system: true },
  ],
};

export const POOL = { id: "aaaaaaaa-0000-7000-8000-000000000000", code: "POOL" as const, name: "Pool" };
export const NEC = { id: "aaaaaaaa-0000-7000-8000-000000000001", code: "NEC" as const, name: "Necessities" };
export const PLAY = { id: "aaaaaaaa-0000-7000-8000-000000000005", code: "PLAY" as const, name: "Play" };

export const jars: Schemas["JarsResponse"] = {
  jars: [
    { ...NEC, targetPct: 55, balance: "2130000.00" },
    { ...PLAY, targetPct: 10, balance: "1670000.00" },
  ],
  poolJarId: POOL.id,
  unallocated: "13000000.00",
  total: "16800000.00",
  currency: "VND",
};

let sequence = 0;

export function tx(overrides: Partial<Schemas["TransactionItem"]> = {}): Schemas["TransactionItem"] {
  sequence += 1;
  return {
    id: `bbbbbbbb-0000-7000-8000-${String(sequence).padStart(12, "0")}`,
    type: "EXPENSE",
    amount: "50000.00",
    currency: "VND",
    jar: NEC,
    toJar: null,
    category: { id: FOOD, name: "Ăn uống" },
    counterparty: null,
    note: null,
    occurredAt: "2026-09-15T05:00:00Z",
    ...overrides,
  };
}

export const posting = (transaction: Schemas["TransactionItem"]): Schemas["PostingResponse"] => ({
  transaction: {
    id: transaction.id,
    type: transaction.type,
    amount: transaction.amount,
    currency: "VND",
    jarId: transaction.jar.id,
    toJarId: transaction.toJar?.id ?? null,
    categoryId: transaction.category?.id ?? null,
    counterparty: transaction.counterparty,
    note: transaction.note,
    occurredAt: transaction.occurredAt,
  },
  jars: [],
});

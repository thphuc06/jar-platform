import { describe, expect, it } from "vitest";
import { NEC, PLAY, POOL, tx } from "@/test/fixtures";
import { describe as describeTx } from "./transactions";

describe("describe(transaction)", () => {
  it("money in is +, money out is -, a move between own jars has no sign (the total does not change)", () => {
    expect(describeTx(tx({ type: "INCOME", jar: POOL, category: null, counterparty: "ACME" }))).toEqual({
      title: "ACME",
      subtitle: "Uncategorized · into Pool",
      sign: "+",
    });
    expect(describeTx(tx({ jar: NEC, counterparty: null }))).toEqual({
      title: "Ăn uống",
      subtitle: "Ăn uống · from Necessities",
      sign: "-",
    });
    expect(describeTx(tx({ type: "JAR_TRANSFER", jar: POOL, toJar: PLAY, category: null }))).toMatchObject({
      title: "Pool → Play",
      sign: "",
    });
  });
});

import { describe, expect, it } from "vitest";
import { canRecategorize, transactionSubtitle, transactionTitle } from "./transactions";

const base = { counterparty: null, note: null, categoryName: null, jarName: null, toJarName: null };

describe("transaction display", () => {
  it("prefers counterparty, then note, then category for the title", () => {
    expect(transactionTitle({ ...base, type: "EXPENSE", counterparty: "Grab Food", note: "ăn trưa", categoryName: "Ăn uống" })).toBe("Grab Food");
    expect(transactionTitle({ ...base, type: "EXPENSE", note: "ăn trưa", categoryName: "Ăn uống" })).toBe("ăn trưa");
    expect(transactionTitle({ ...base, type: "EXPENSE", categoryName: "Ăn uống" })).toBe("Ăn uống");
    expect(transactionTitle({ ...base, type: "INCOME" })).toBe("Thu nhập");
  });

  it("shows the two jars of a jar transfer", () => {
    expect(transactionTitle({ ...base, type: "JAR_TRANSFER", jarName: "Thiết yếu", toJarName: "Giáo dục" })).toBe("Thiết yếu → Giáo dục");
    expect(transactionSubtitle({ type: "JAR_TRANSFER", categoryName: null, jarName: "Thiết yếu" })).toBe("Chuyển hũ");
  });

  it("puts category and jar on the second line", () => {
    expect(transactionSubtitle({ type: "EXPENSE", categoryName: "Ăn uống", jarName: "Thiết yếu" })).toBe("Ăn uống · Thiết yếu");
    expect(transactionSubtitle({ type: "INCOME", categoryName: null, jarName: null })).toBe("Chưa phân loại");
  });

  it("allows editing income and expenses but not jar transfers", () => {
    expect(canRecategorize({ type: "EXPENSE" })).toBe(true);
    expect(canRecategorize({ type: "INCOME" })).toBe(true);
    expect(canRecategorize({ type: "JAR_TRANSFER" })).toBe(false);
  });
});

import type { InfiniteData } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { flattenPages, mergePages, nextCursor, type Page } from "./paging";

interface Row {
  id: string;
}
const page = (ids: string[], next: string | null): Page<Row> => ({ items: ids.map((id) => ({ id })), nextCursor: next });

describe("cursor paging", () => {
  it("continues with the cursor of the last page and stops when there is none", () => {
    expect(nextCursor(page(["a"], "opaque-cursor-1"))).toBe("opaque-cursor-1");
    expect(nextCursor(page(["a"], null))).toBeUndefined();
    expect(nextCursor({ items: [] })).toBeUndefined();
  });

  it("merges pages in order, newest first as the server sends them", () => {
    const merged = mergePages([page(["t5", "t4"], "c1"), page(["t3", "t2"], "c2"), page(["t1"], null)]);
    expect(merged.map((r) => r.id)).toEqual(["t5", "t4", "t3", "t2", "t1"]);
  });

  it("keeps a row once when it shows up on two pages (a row was inserted while paging)", () => {
    const merged = mergePages([page(["t5", "t4"], "c1"), page(["t4", "t3"], null)]);
    expect(merged.map((r) => r.id)).toEqual(["t5", "t4", "t3"]);
  });

  it("flattens TanStack's InfiniteData and tolerates no data yet", () => {
    const data: InfiniteData<Page<Row>> = { pages: [page(["a"], "c"), page(["b"], null)], pageParams: [undefined, "c"] };
    expect(flattenPages(data).map((r) => r.id)).toEqual(["a", "b"]);
    expect(flattenPages(undefined)).toEqual([]);
  });

  it("handles empty pages", () => {
    expect(mergePages([page([], null)])).toEqual([]);
    expect(mergePages([])).toEqual([]);
  });
});

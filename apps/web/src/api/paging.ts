import type { InfiniteData } from "@tanstack/react-query";

/** Keyset page as the API returns it: `{items, nextCursor}`; the last page has no cursor. */
export interface Page<T> {
  items: T[];
  nextCursor?: string | null;
}

/** `getNextPageParam` for every cursor-paged list. Returning undefined ends the pagination. */
export function nextCursor(page: Page<unknown>): string | undefined {
  return page.nextCursor ?? undefined;
}

/**
 * All loaded pages as one list, newest first as the server sends them. An item that appears on two pages (a row
 * inserted at the head while a later page was being refetched) is kept once, at its first position.
 */
export function mergePages<T extends { id: string }>(pages: readonly Page<T>[]): T[] {
  const seen = new Set<string>();
  const merged: T[] = [];
  for (const page of pages) {
    for (const item of page.items) {
      if (seen.has(item.id)) continue;
      seen.add(item.id);
      merged.push(item);
    }
  }
  return merged;
}

export function flattenPages<T extends { id: string }>(data: InfiniteData<Page<T>> | undefined): T[] {
  return data ? mergePages(data.pages) : [];
}

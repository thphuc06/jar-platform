import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import { unwrap } from "./http";
import { qk } from "./keys";
import type { Category } from "./types";

export function useCategories() {
  return useQuery({
    queryKey: qk.categories,
    queryFn: ({ signal }) => unwrap<Category[]>(api.GET("/api/v1/categories", { signal })),
    staleTime: 10 * 60_000,
  });
}

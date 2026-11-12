import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { unwrap, unwrapEmpty, wireBody } from "./http";
import { invalidateMoney, qk } from "./keys";
import type { CreateJarBody, Jar, Posted, TransactionReceipt, TransferBody, UpdateJarBody } from "./types";

export function useJars() {
  return useQuery({
    queryKey: qk.jars,
    queryFn: ({ signal }) => unwrap<Jar[]>(api.GET("/api/v1/jars", { signal })),
  });
}

export function useCreateJar() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateJarBody) => unwrap<Jar>(api.POST("/api/v1/jars", { body })),
    onSuccess: () => invalidateMoney(queryClient),
  });
}

/** Rename or change the target share. The shares of all active jars must stay <= 100 (422 allocation-exceeds-100). */
export function useUpdateJar() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: UpdateJarBody & { id: string }) =>
      unwrap<Jar>(api.PATCH("/api/v1/jars/{id}", { params: { path: { id } }, body })),
    onSuccess: () => invalidateMoney(queryClient),
  });
}

/** Archive: only allowed when the balance is zero. */
export function useArchiveJar() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrapEmpty(api.DELETE("/api/v1/jars/{id}", { params: { path: { id } } })),
    onSuccess: () => invalidateMoney(queryClient),
  });
}

export interface TransferVariables {
  body: TransferBody;
  /** One key per intent, see `useIntentKey`: reuse it when the same intent is retried */
  idempotencyKey: string;
}

/** Move money from one jar to another. The source jar must have enough balance. */
export function useTransferBetweenJars() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ body, idempotencyKey }: TransferVariables): Promise<Posted> => {
      const result = await api.POST("/api/v1/jars/transfers", {
        params: { header: { "Idempotency-Key": idempotencyKey } },
        body: wireBody(body),
      });
      return {
        receipt: result.data as unknown as TransactionReceipt,
        replayed: result.response.headers.get("Idempotency-Replayed") === "true",
      };
    },
    onSuccess: () => invalidateMoney(queryClient),
  });
}

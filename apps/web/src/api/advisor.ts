import { EventStreamContentType, fetchEventSource } from "@microsoft/fetch-event-source";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getAccessToken } from "@/auth/session";
import { api } from "./client";
import { unwrap } from "./http";
import { qk } from "./keys";
import { ApiError, toApiError } from "./problem";
import type { components } from "./schema";

type S = components["schemas"];
export type ChatSession = Required<S["SessionView"]>;
export type ChatMessage = Required<Omit<S["MessageView"], "role">> & { role: "USER" | "ASSISTANT" };

export function useChatSessions() {
  return useQuery({
    queryKey: [...qk.advisor, "sessions"] as const,
    queryFn: ({ signal }) => unwrap<ChatSession[]>(api.GET("/api/v1/chat/sessions", { signal })),
  });
}

export function useChatMessages(sessionId: string | undefined) {
  return useQuery({
    queryKey: [...qk.advisor, "messages", sessionId] as const,
    enabled: sessionId !== undefined,
    queryFn: ({ signal }) =>
      unwrap<ChatMessage[]>(
        api.GET("/api/v1/chat/sessions/{sessionId}/messages", { params: { path: { sessionId: sessionId! } }, signal }),
      ),
  });
}

export function useCreateChatSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (title?: string) => unwrap<ChatSession>(api.POST("/api/v1/chat/sessions", { body: { title } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...qk.advisor, "sessions"] }),
  });
}

export interface AnswerHandlers {
  /** A piece of the answer, in order. */
  onToken: (text: string) => void;
  /** The model is looking something up (getJars, getMonthlyCashflow, ...). */
  onTool?: (name: string, status: "started" | "done") => void;
  /** The output guardrail masked the answer: show this instead of what was streamed. */
  onReplace?: (text: string) => void;
  onDone?: (done: { messageId: string; disclaimer: string; usage: { inputTokens: number; outputTokens: number } }) => void;
}

class StopStream extends Error {}

/**
 * Sends one message and streams the answer (api-contract.md section 3). A refused request (404, 422, 429 with
 * Retry-After, 503) rejects with an {@link ApiError} before any token; an `error` event during the answer rejects too.
 * Never reconnects: a turn is not idempotent.
 */
export async function sendChatMessage(
  sessionId: string,
  content: string,
  handlers: AnswerHandlers,
  signal?: AbortSignal,
  doFetch: (request: Request) => Promise<Response> = (request) => fetch(request),
): Promise<void> {
  let failure: unknown;
  try {
    await fetchEventSource(`/api/v1/chat/sessions/${sessionId}/messages`, {
      method: "POST",
      signal,
      openWhenHidden: true,
      headers: { "Content-Type": "application/json", Accept: EventStreamContentType },
      body: JSON.stringify({ content }),
      fetch: async (input, init) => {
        const token = await getAccessToken();
        const headers = new Headers(init?.headers);
        if (token) headers.set("Authorization", `Bearer ${token}`);
        return doFetch(new Request(input, { ...init, headers }));
      },
      async onopen(response) {
        if (response.ok && response.headers.get("content-type")?.startsWith(EventStreamContentType)) return;
        failure = await toApiError(response);
        throw new StopStream();
      },
      onmessage(frame) {
        const data = frame.data ? JSON.parse(frame.data) : {};
        switch (frame.event) {
          case "token":
            handlers.onToken(data.text);
            break;
          case "tool":
            handlers.onTool?.(data.name, data.status);
            break;
          case "replace":
            handlers.onReplace?.(data.text);
            break;
          case "done":
            handlers.onDone?.(data);
            break;
          case "error":
            failure = new ApiError(data.status ?? 502, data);
            throw new StopStream();
        }
      },
      onclose() {
        throw new StopStream();
      },
      onerror(error) {
        // no retry: stop on the first problem
        throw error;
      },
    });
  } catch (error) {
    if (!(error instanceof StopStream)) throw error;
  }
  if (failure) throw failure;
}

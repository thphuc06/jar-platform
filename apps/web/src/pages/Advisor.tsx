import { useEffect, useRef, useState } from "react";
import { Bot, Loader2, Plus, Send, User } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { sendChatMessage, useChatMessages, useChatSessions, useCreateChatSession, type ChatMessage } from "@/api/advisor";
import { qk } from "@/api/keys";
import { describeError } from "@/api/problem";
import ErrorNotice from "@/components/ErrorNotice";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

const TOOL_LABEL: Record<string, string> = {
  getJars: "Đang xem các hũ của bạn…",
  getMonthlyCashflow: "Đang xem thu chi theo tháng…",
  getGoals: "Đang xem các mục tiêu…",
  getOpenAnomalyCount: "Đang xem cảnh báo chi tiêu…",
};

const SUGGESTIONS = [
  "Tháng này tôi chi nhiều nhất cho gì?",
  "Hũ nào của tôi đang thấp?",
  "Tôi có kịp đạt mục tiêu tiết kiệm không?",
];

const Bubble = ({ role, children }: { role: "USER" | "ASSISTANT"; children: React.ReactNode }) => (
  <div className={cn("flex gap-2", role === "USER" && "flex-row-reverse")}>
    <span className="mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-muted" aria-hidden="true">
      {role === "USER" ? <User className="h-4 w-4" /> : <Bot className="h-4 w-4" />}
    </span>
    <div
      className={cn(
        "max-w-[80%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm",
        role === "USER" ? "bg-primary text-primary-foreground" : "bg-muted",
      )}
    >
      {children}
    </div>
  </div>
);

const Advisor = () => {
  const queryClient = useQueryClient();
  const sessions = useChatSessions();
  const createSession = useCreateChatSession();
  // undefined: the most recent conversation; null: a new one the user asked for
  const [chosen, setSessionId] = useState<string | null>();
  const sessionId = chosen === undefined ? sessions.data?.[0]?.id : (chosen ?? undefined);
  const messages = useChatMessages(sessionId);
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState<{ question: string; answer: string; tool?: string } | null>(null);
  const [disclaimer, setDisclaimer] = useState<string>();
  const [error, setError] = useState<unknown>(null);
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => bottom.current?.scrollIntoView?.({ block: "end" }), [messages.data, pending]);

  const send = async (text: string) => {
    const question = text.trim();
    if (!question || pending) return;
    setError(null);
    setDraft("");
    let id = sessionId;
    try {
      if (!id) {
        id = (await createSession.mutateAsync(question.slice(0, 60))).id;
        setSessionId(id);
      }
      setPending({ question, answer: "" });
      await sendChatMessage(id, question, {
        onToken: (t) => setPending((p) => (p ? { ...p, answer: p.answer + t, tool: undefined } : p)),
        onTool: (name, status) => setPending((p) => (p ? { ...p, tool: status === "started" ? name : undefined } : p)),
        onReplace: (t) => setPending((p) => (p ? { ...p, answer: t } : p)),
        onDone: (d) => setDisclaimer(d.disclaimer),
      });
    } catch (e) {
      setError(e);
    } finally {
      await queryClient.invalidateQueries({ queryKey: [...qk.advisor, "messages", id] });
      await queryClient.invalidateQueries({ queryKey: [...qk.advisor, "sessions"] });
      setPending(null);
    }
  };

  const shown: ChatMessage[] = messages.data ?? [];

  return (
    <div className="animate-slide-up flex flex-col gap-4 pb-32 md:pb-6">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-display font-bold">Trợ lý AI</h1>
          <p className="text-sm text-muted-foreground">Hỏi về hũ, chi tiêu và mục tiêu; số liệu lấy từ dữ liệu của bạn.</p>
        </div>
        <Button
          variant="outline"
          size="sm"
          className="gap-2"
          onClick={() => {
            setSessionId(null);
            setPending(null);
            setDisclaimer(undefined);
          }}
        >
          <Plus className="h-4 w-4" aria-hidden="true" /> Cuộc mới
        </Button>
      </div>

      {sessions.data && sessions.data.length > 1 && (
        <div className="flex gap-2 overflow-x-auto pb-1" aria-label="Các cuộc trò chuyện">
          {sessions.data.slice(0, 8).map((s) => (
            <Button key={s.id} size="sm" variant={s.id === sessionId ? "secondary" : "ghost"} onClick={() => setSessionId(s.id)}>
              <span className="max-w-[10rem] truncate">{s.title}</span>
            </Button>
          ))}
        </div>
      )}

      <Card>
        <CardContent className="space-y-3 p-4" aria-live="polite">
          {sessions.isError && <ErrorNotice error={sessions.error} what="các cuộc trò chuyện" onRetry={() => void sessions.refetch()} />}
          {messages.isPending && sessionId ? (
            <Skeleton className="h-16 w-full" />
          ) : shown.length === 0 && !pending ? (
            <div className="space-y-2 text-sm text-muted-foreground">
              <p>Bắt đầu bằng một câu hỏi, ví dụ:</p>
              <div className="flex flex-wrap gap-2">
                {SUGGESTIONS.map((s) => (
                  <Button key={s} variant="outline" size="sm" onClick={() => void send(s)}>
                    {s}
                  </Button>
                ))}
              </div>
            </div>
          ) : (
            shown.map((m) => (
              <Bubble key={m.id} role={m.role}>
                {m.content}
              </Bubble>
            ))
          )}
          {pending && (
            <>
              <Bubble role="USER">{pending.question}</Bubble>
              <Bubble role="ASSISTANT">
                {pending.answer ||
                  (
                    <span className="flex items-center gap-2 text-muted-foreground">
                      <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
                      {(pending.tool && TOOL_LABEL[pending.tool]) || "Đang suy nghĩ…"}
                    </span>
                  )}
              </Bubble>
            </>
          )}
          {error !== null && (
            <p className="text-sm text-destructive" role="alert">
              {describeError(error)}
            </p>
          )}
          {disclaimer && <p className="text-[11px] text-muted-foreground">{disclaimer}</p>}
          <div ref={bottom} />
        </CardContent>
      </Card>

      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          void send(draft);
        }}
      >
        <Input
          aria-label="Câu hỏi cho trợ lý"
          placeholder="Hỏi trợ lý…"
          value={draft}
          maxLength={2000}
          onChange={(e) => setDraft(e.target.value)}
          disabled={pending !== null}
        />
        <Button type="submit" size="icon" aria-label="Gửi" disabled={!draft.trim() || pending !== null}>
          {pending ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Send className="h-4 w-4" aria-hidden="true" />}
        </Button>
      </form>
    </div>
  );
};

export default Advisor;

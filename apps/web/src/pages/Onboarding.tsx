import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowRight, Check, Loader2, TrendingUp } from "lucide-react";
import { useSubmitRiskProfile, useUpdateMe } from "@/api/me";
import { describeError } from "@/api/problem";
import type { RiskProfile } from "@/api/types";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { useToast } from "@/hooks/use-toast";
import { AGE_BANDS, buildProfileFacts, INCOME_SITUATIONS } from "@/lib/profileFacts";
import { buildAnswers, RISK_QUESTIONS, riskLevelLabel, type RiskQuestion } from "@/lib/riskQuestionnaire";
import { cn } from "@/lib/utils";

/**
 * The five risk questions (answered by option index, scored by the server) and the two facts asked after them (age band
 * and income situation: profile facts for the advisor, not part of the score). The last two are sent first, with
 * `PATCH /me`, so that a user is never "onboarded" without them; the questionnaire is sent last and marks the end.
 */
const STEPS: readonly RiskQuestion[] = [
  ...RISK_QUESTIONS,
  { title: "Độ tuổi", question: "Bạn thuộc nhóm tuổi nào?", options: AGE_BANDS.map((o) => o.label) },
  { title: "Thu nhập", question: "Thu nhập hiện tại của bạn thế nào?", options: INCOME_SITUATIONS.map((o) => o.label) },
];

const Onboarding = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const submit = useSubmitRiskProfile();
  const updateMe = useUpdateMe();
  const [currentStep, setCurrentStep] = useState(-1); // -1 = welcome
  const [selected, setSelected] = useState<Record<number, number>>({});
  const [result, setResult] = useState<RiskProfile | null>(null);

  const handleNext = () => {
    if (currentStep < STEPS.length - 1) {
      setCurrentStep(currentStep + 1);
      return;
    }
    const answers = buildAnswers(selected);
    const facts = buildProfileFacts(selected[RISK_QUESTIONS.length], selected[RISK_QUESTIONS.length + 1]);
    if (!answers || !facts) return;
    updateMe.mutate(facts, {
      onSuccess: () =>
        submit.mutate(answers, {
          onSuccess: setResult,
          onError: (error) => toast({ title: "Không lưu được hồ sơ rủi ro", description: describeError(error), variant: "destructive" }),
        }),
      onError: (error) => toast({ title: "Không lưu được hồ sơ", description: describeError(error), variant: "destructive" }),
    });
  };
  const saving = updateMe.isPending || submit.isPending;

  if (result) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center bg-background px-6">
        <div className="max-w-sm animate-slide-up space-y-6 text-center">
          <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-2xl bg-primary">
            <Check className="h-10 w-10 text-primary-foreground" aria-hidden="true" />
          </div>
          <div>
            <h1 className="text-2xl font-display font-bold">Hồ sơ của bạn đã sẵn sàng</h1>
            <p className="mt-2 text-muted-foreground">
              Khẩu vị rủi ro: <strong>{riskLevelLabel(result.riskLevel)}</strong> ({result.score}/100)
            </p>
            <p className="mt-2 text-xs text-muted-foreground">
              Kết quả chỉ mô tả thái độ của bạn với rủi ro; trợ lý dùng nó cùng độ tuổi và thu nhập để chọn sản phẩm minh họa, không phải lời khuyên đầu tư cá nhân.
            </p>
          </div>
          <Button onClick={() => navigate("/", { replace: true })} className="w-full gap-2" size="lg">
            Vào ứng dụng <ArrowRight className="h-4 w-4" aria-hidden="true" />
          </Button>
        </div>
      </div>
    );
  }

  if (currentStep === -1) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center bg-background px-6">
        <div className="max-w-sm animate-slide-up space-y-6 text-center">
          <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-2xl bg-primary">
            <TrendingUp className="h-10 w-10 text-primary-foreground" aria-hidden="true" />
          </div>
          <div>
            <h1 className="text-3xl font-display font-bold">FinAdvisor</h1>
            <p className="mt-2 text-muted-foreground">
              Trả lời 7 câu hỏi ngắn để chúng tôi hiểu khẩu vị rủi ro và hoàn cảnh tài chính của bạn.
            </p>
          </div>
          <Button onClick={() => setCurrentStep(0)} className="w-full gap-2" size="lg">
            Bắt đầu <ArrowRight className="h-4 w-4" aria-hidden="true" />
          </Button>
        </div>
      </div>
    );
  }

  const step = STEPS[currentStep];
  const progress = ((currentStep + 1) / STEPS.length) * 100;
  const isLast = currentStep === STEPS.length - 1;
  const questionId = `question-${currentStep}`;

  return (
    <div className="flex min-h-screen flex-col bg-background px-6 py-8">
      <div className="mx-auto flex w-full max-w-md flex-1 animate-slide-up flex-col">
        <div className="mb-8">
          <div className="mb-2 flex justify-between text-xs text-muted-foreground">
            <span>
              Bước {currentStep + 1} / {STEPS.length}
            </span>
            <span>{step.title}</span>
          </div>
          <Progress value={progress} className="h-2" aria-label="Tiến độ bảng câu hỏi" />
        </div>

        <h2 id={questionId} className="mb-6 text-xl font-display font-bold">
          {step.question}
        </h2>

        <div role="radiogroup" aria-labelledby={questionId} className="flex-1 space-y-3">
          {step.options.map((option, i) => {
            const checked = selected[currentStep] === i;
            return (
              <button
                key={option}
                type="button"
                role="radio"
                aria-checked={checked}
                onClick={() => setSelected({ ...selected, [currentStep]: i })}
                className={cn(
                  "flex w-full items-center justify-between rounded-lg border bg-card p-4 text-left text-sm font-medium shadow-sm transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                  checked ? "border-primary ring-2 ring-primary/20" : "hover:border-primary/30",
                )}
              >
                <span>{option}</span>
                {checked && (
                  <span className="flex h-5 w-5 items-center justify-center rounded-full bg-primary">
                    <Check className="h-3 w-3 text-primary-foreground" aria-hidden="true" />
                  </span>
                )}
              </button>
            );
          })}
        </div>

        <div className="mt-8 flex gap-3">
          {currentStep > 0 && (
            <Button variant="outline" size="lg" onClick={() => setCurrentStep(currentStep - 1)} disabled={saving}>
              Quay lại
            </Button>
          )}
          <Button
            onClick={handleNext}
            disabled={selected[currentStep] === undefined || saving}
            className="flex-1 gap-2"
            size="lg"
          >
            {saving && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
            {isLast ? "Hoàn tất hồ sơ" : "Tiếp tục"}
            {!saving && <ArrowRight className="h-4 w-4" aria-hidden="true" />}
          </Button>
        </div>
      </div>
    </div>
  );
};

export default Onboarding;

import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { TrendingUp, ArrowRight, Check, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { useToast } from "@/hooks/use-toast";
import { useAssessRiskProfile, type RiskAnswers } from "@/api/queries";

type Step<K extends keyof RiskAnswers> = {
  field: K;
  title: string;
  question: string;
  options: { label: string; value: RiskAnswers[K] }[];
};

// BR-4: one question per field of the API's RiskAnswersRequest; the server scores the answers (never the UI)
const steps: [
  Step<"riskTolerance">,
  Step<"horizon">,
  Step<"liquidity">,
  Step<"experience">,
  Step<"mainGoal">,
] = [
  {
    field: "riskTolerance",
    title: "Risk Tolerance",
    question: "How comfortable are you with investment risk?",
    options: [
      { label: "Conservative - Avoid losses", value: "CONSERVATIVE" },
      { label: "Moderate - Balanced approach", value: "MODERATE" },
      { label: "Aggressive - Maximize returns", value: "AGGRESSIVE" },
    ],
  },
  {
    field: "horizon",
    title: "Investment Horizon",
    question: "When will you need this money?",
    options: [
      { label: "Less than 2 years", value: "UNDER_2_YEARS" },
      { label: "2-5 years", value: "YEARS_2_TO_5" },
      { label: "5-10 years", value: "YEARS_5_TO_10" },
      { label: "10+ years", value: "OVER_10_YEARS" },
    ],
  },
  {
    field: "liquidity",
    title: "Liquidity Needs",
    question: "How quickly might you need access to your funds?",
    options: [
      { label: "Immediate access needed", value: "IMMEDIATE" },
      { label: "Within a month", value: "WITHIN_A_MONTH" },
      { label: "Flexible - no rush", value: "FLEXIBLE" },
    ],
  },
  {
    field: "experience",
    title: "Experience",
    question: "What's your investment experience?",
    options: [
      { label: "Beginner - Just starting", value: "BEGINNER" },
      { label: "Intermediate - Some experience", value: "EXPERIENCED" },
      { label: "Advanced - Experienced investor", value: "VERY_EXPERIENCED" },
    ],
  },
  {
    field: "mainGoal",
    title: "Objectives",
    question: "What's your primary financial objective?",
    options: [
      { label: "Build emergency fund", value: "EMERGENCY_FUND" },
      { label: "Grow wealth long-term", value: "LONG_TERM_GROWTH" },
      { label: "Save for a specific goal", value: "SPECIFIC_GOAL" },
      { label: "Generate passive income", value: "PASSIVE_INCOME" },
    ],
  },
];

const Onboarding = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const assess = useAssessRiskProfile();
  const [currentStep, setCurrentStep] = useState(-1); // -1 = welcome
  const [answers, setAnswers] = useState<Record<number, number>>({});

  const handleSelect = (optionIndex: number) => {
    setAnswers({ ...answers, [currentStep]: optionIndex });
  };

  const handleNext = () => {
    if (currentStep < steps.length - 1) {
      setCurrentStep(currentStep + 1);
      return;
    }
    const body = Object.fromEntries(
      steps.map((s, i) => [s.field, s.options[answers[i]].value]),
    ) as RiskAnswers;
    assess.mutate(body, {
      onSuccess: (profile) => {
        toast({
          title: `Risk profile: ${profile.riskLevel}`,
          description: `Score ${profile.score} / ${profile.maxScore}${profile.capped ? " (capped by your short horizon or liquidity needs)" : ""}`,
        });
        navigate("/", { replace: true });
      },
      onError: (err) => toast({ title: "Could not save your answers", description: err.message, variant: "destructive" }),
    });
  };

  // Welcome screen
  if (currentStep === -1) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center px-6 bg-background">
        <div className="text-center space-y-6 max-w-sm animate-slide-up">
          <div className="h-20 w-20 rounded-2xl bg-primary flex items-center justify-center mx-auto">
            <TrendingUp className="h-10 w-10 text-primary-foreground" />
          </div>
          <div>
            <h1 className="text-3xl font-display font-bold">Jars</h1>
            <p className="text-muted-foreground mt-2">Five quick questions about how you handle money, so suggestions fit your risk profile.</p>
          </div>
          <Button onClick={() => setCurrentStep(0)} className="w-full gap-2" size="lg">
            Get Started <ArrowRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    );
  }

  const step = steps[currentStep];
  const progress = ((currentStep + 1) / steps.length) * 100;

  return (
    <div className="min-h-screen flex flex-col px-6 py-8 bg-background">
      <div className="max-w-md mx-auto w-full flex-1 flex flex-col animate-slide-up">
        {/* Progress */}
        <div className="mb-8">
          <div className="flex justify-between text-xs text-muted-foreground mb-2">
            <span>Step {currentStep + 1} of {steps.length}</span>
            <span>{step.title}</span>
          </div>
          <Progress value={progress} className="h-2" />
        </div>

        {/* Question */}
        <h2 className="text-xl font-display font-bold mb-6">{step.question}</h2>

        {/* Options */}
        <div className="space-y-3 flex-1">
          {step.options.map(({ label }, i) => (
            <Card
              key={i}
              className={`cursor-pointer transition-all ${answers[currentStep] === i ? "border-primary ring-2 ring-primary/20" : "hover:border-primary/30"}`}
              onClick={() => handleSelect(i)}
            >
              <CardContent className="p-4 flex items-center justify-between">
                <span className="text-sm font-medium">{label}</span>
                {answers[currentStep] === i && (
                  <div className="h-5 w-5 rounded-full bg-primary flex items-center justify-center">
                    <Check className="h-3 w-3 text-primary-foreground" />
                  </div>
                )}
              </CardContent>
            </Card>
          ))}
        </div>

        {/* Next */}
        <Button
          onClick={handleNext}
          disabled={answers[currentStep] === undefined || assess.isPending}
          className="w-full mt-8 gap-2"
          size="lg"
        >
          {currentStep === steps.length - 1 ? "Complete Profile" : "Continue"}
          {assess.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <ArrowRight className="h-4 w-4" />}
        </Button>
      </div>
    </div>
  );
};

export default Onboarding;

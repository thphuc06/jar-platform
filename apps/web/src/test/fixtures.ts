/**
 * Response bodies in the shape jars-api really sends: money as {"amount":"85000.00","currency":"VND"}, nullable
 * fields present as null. Used by the page tests; keep them in step with contracts/openapi/jars-api.yaml.
 */
import type { AllocationResult, AllocationSuggestion, BudgetPeriod } from "@/api/budget";
import type { GoalFeasibility, GoalScenarios, ProjectionSummary, RoadmapResponse, ScenarioResult } from "@/api/goals";

// The goals and budget fixtures are typed with the types generated from the OpenAPI file: when the backend renames or
// drops a field, `npm run typecheck` fails here instead of a test passing against an outdated shape.
const vnd = (amount: string) => ({ amount, currency: "VND" as const });

export const ids = {
  nec: "11111111-1111-4111-8111-000000000001",
  ffa: "11111111-1111-4111-8111-000000000002",
  ltss: "11111111-1111-4111-8111-000000000003",
  edu: "11111111-1111-4111-8111-000000000004",
  play: "11111111-1111-4111-8111-000000000005",
  give: "11111111-1111-4111-8111-000000000006",
  catFood: "22222222-2222-4222-8222-000000000001",
  catSalary: "22222222-2222-4222-8222-000000000002",
  tx1: "33333333-3333-4333-8333-000000000001",
  tx2: "33333333-3333-4333-8333-000000000002",
  tx3: "33333333-3333-4333-8333-000000000003",
  anomaly: "44444444-4444-4444-8444-000000000001",
  notification1: "55555555-5555-4555-8555-000000000001",
  notification2: "55555555-5555-4555-8555-000000000002",
  goal: "66666666-6666-4666-8666-000000000001",
};

export const jars = [
  { id: ids.nec, code: "NEC", name: "Thiết yếu", targetPct: 55, balance: vnd("11000000.00") },
  { id: ids.ffa, code: "FFA", name: "Tự do tài chính", targetPct: 10, balance: vnd("2000000.00") },
  { id: ids.ltss, code: "LTSS", name: "Tiết kiệm dài hạn", targetPct: 10, balance: vnd("2000000.00") },
  { id: ids.edu, code: "EDU", name: "Giáo dục", targetPct: 10, balance: vnd("2000000.00") },
  { id: ids.play, code: "PLAY", name: "Hưởng thụ", targetPct: 10, balance: vnd("1200000.00") },
  { id: ids.give, code: "GIVE", name: "Cho đi", targetPct: 5, balance: vnd("0.00") },
];

export const categories = [
  { id: ids.catFood, name: "Ăn uống", kind: "EXPENSE", parentId: null, system: true },
  { id: ids.catSalary, name: "Lương", kind: "INCOME", parentId: null, system: true },
];

export const me = { id: "77777777-7777-4777-8777-000000000001", email: null, displayName: "Phúc", locale: "vi-VN", onboardingCompleted: true };

export const riskProfile = { score: 52, riskLevel: "BALANCED", assessedAt: "2026-11-01T03:21:09Z" };

export const dashboard = {
  month: "2026-11",
  balance: vnd("18200000.00"),
  income: vnd("20000000.00"),
  expense: vnd("1800000.00"),
  net: vnd("18200000.00"),
  spendByJar: jars.map((j) => ({ ...j, spent: vnd("300000.00"), txnCount: 2 })),
  spendByCategory: [
    { categoryId: ids.catFood, name: "Ăn uống", spent: vnd("1200000.00"), txnCount: 9 },
    { categoryId: null, name: "Chưa phân loại", spent: vnd("600000.00"), txnCount: 3 },
  ],
  trend: [
    { month: "2026-09", income: vnd("20000000.00"), expense: vnd("9000000.00") },
    { month: "2026-10", income: vnd("20000000.00"), expense: vnd("11000000.00") },
    { month: "2026-11", income: vnd("20000000.00"), expense: vnd("1800000.00") },
  ],
  openAnomalies: 1,
};

export const transactionPage = {
  items: [
    {
      id: ids.tx1, type: "EXPENSE", amount: vnd("85000.00"), jarId: ids.nec, jarName: "Thiết yếu", toJarId: null, toJarName: null,
      categoryId: ids.catFood, categoryName: "Ăn uống", counterparty: "Grab Food", note: null, occurredAt: "2026-11-10T05:00:00Z", source: "MANUAL",
    },
    {
      id: ids.tx2, type: "INCOME", amount: vnd("20000000.00"), jarId: null, jarName: null, toJarId: null, toJarName: null,
      categoryId: null, categoryName: null, counterparty: "Công ty ABC", note: "Lương tháng 11", occurredAt: "2026-11-05T05:00:00Z", source: "MANUAL",
    },
    {
      id: ids.tx3, type: "JAR_TRANSFER", amount: vnd("500000.00"), jarId: ids.nec, jarName: "Thiết yếu", toJarId: ids.edu, toJarName: "Giáo dục",
      categoryId: null, categoryName: null, counterparty: null, note: null, occurredAt: "2026-11-04T05:00:00Z", source: "MANUAL",
    },
  ],
  nextCursor: "opaque-cursor-1",
};

export const anomalyPage = {
  items: [
    {
      id: ids.anomaly, jarId: ids.play, jarName: "Hưởng thụ", transactionId: ids.tx1, detector: "MAD", direction: "UP", severity: "HIGH",
      score: 8.2, threshold: 3.5, amount: vnd("2500000.00"), status: "OPEN", detectedAt: "2026-11-11T08:00:00Z",
    },
  ],
  nextCursor: null,
};

export const notificationPage = {
  items: [
    {
      id: ids.notification2, type: "ANOMALY", severity: "HIGH", title: "Hũ Hưởng thụ tăng chi bất thường", body: "Khoản chi 2.500.000 ₫ cao hơn thường lệ",
      refType: "ANOMALY", refId: ids.anomaly, readAt: null, createdAt: "2026-11-11T08:00:01Z",
    },
    {
      id: ids.notification1, type: "ANOMALY", severity: "LOW", title: "Chi tiêu giảm kéo dài", body: null,
      refType: "ANOMALY", refId: null, readAt: "2026-11-09T08:00:00Z", createdAt: "2026-11-08T08:00:01Z",
    },
  ],
  nextCursor: null,
  unreadCount: 1,
};

export const dailyBalances = [
  { date: "2026-09-30", balance: vnd("9000000.00") },
  { date: "2026-10-31", balance: vnd("14000000.00") },
  { date: "2026-11-10", balance: vnd("18200000.00") },
];

export const goals = [
  { id: ids.goal, name: "Quỹ khẩn cấp", targetAmount: vnd("60000000.00"), targetDate: "2027-12-31", fundingJarId: ids.ffa, status: "ACTIVE" },
];

const pace = {
  monthsUsed: 3, currentMonthOnly: false, averageIncome: vnd("20000000.00"), averageExpense: vnd("16762340.00"),
  averageNet: vnd("3237660.00"), netStdDev: vnd("900000.00"),
};

export const feasibility: GoalFeasibility = {
  goalId: ids.goal, goalName: "Quỹ khẩn cấp", verdict: "OFF_TRACK", targetAmount: vnd("60000000.00"), targetDate: "2027-12-31",
  currentAmount: vnd("11000000.00"), fundingJarLinked: true, monthsLeft: 13, remaining: vnd("49000000.00"),
  requiredMonthly: vnd("3769231.00"), currentMonthlyPace: vnd("3237660.00"), projectedAtDeadline: vnd("53000000.00"),
  gap: vnd("7000000.00"), monthlyShortfall: vnd("531571.00"), probabilityOfSuccess: 0.41, grade: "C", feasibilityHint: "stretch",
  pace, seed: 42, notes: [],
};

const projection = (probabilityOfSuccess: number, p50: string): ProjectionSummary => ({
  strategy: "MONTE_CARLO", p10: vnd("48000000.00"), p50: vnd(p50), p90: vnd("66000000.00"), probabilityOfSuccess,
  reachesTarget: probabilityOfSuccess >= 0.5, path: [{ month: 1, p10: vnd("11000000.00"), p50: vnd("14000000.00"), p90: vnd("17000000.00") }],
});

const scenarioResult = (name: string, probabilityOfSuccess: number, p50: string, medianVsBase: string, probabilityVsBase: number): ScenarioResult => ({
  name, deltas: { monthlySaving: vnd("0.00"), income: vnd("0.00"), spending: vnd("0.00"), oneOff: vnd("0.00") }, monthlyNet: vnd("3237660.00"),
  deterministic: projection(probabilityOfSuccess, p50), stress: projection(probabilityOfSuccess, p50), monteCarlo: projection(probabilityOfSuccess, p50),
  medianVsBase: vnd(medianVsBase), probabilityVsBase,
});

/** Answer to a what-if with an extra monthly saving: the unchanged base plus one CUSTOM scenario. */
export const scenarios: GoalScenarios = {
  goalId: ids.goal, seed: 42, horizonMonths: 13, startingAmount: vnd("11000000.00"), targetAmount: vnd("60000000.00"), pace,
  base: scenarioResult("BASE", 0.41, "53000000.00", "0.00", 0),
  scenarios: [scenarioResult("CUSTOM", 0.63, "59000000.00", "6000000.00", 0.22)],
  notes: [],
};

const line = (jarId: string, code: string, name: string, pct: number, amount: string) => ({ jarId, code, name, targetPct: pct, sharePct: pct, amount: vnd(amount) });

/** The server's suggestion for an income of 20.000.000 VND: the jars' own shares, 55 / 10 / 10 / 10 / 10 / 5. */
export const suggestion: AllocationSuggestion = {
  amount: vnd("20000000.00"), monthsOfHistory: 3, historyWeight: 0.5,
  lines: [
    line(ids.nec, "NEC", "Thiết yếu", 55, "11000000.00"), line(ids.ffa, "FFA", "Tự do tài chính", 10, "2000000.00"),
    line(ids.ltss, "LTSS", "Tiết kiệm dài hạn", 10, "2000000.00"), line(ids.edu, "EDU", "Giáo dục", 10, "2000000.00"),
    line(ids.play, "PLAY", "Hưởng thụ", 10, "2000000.00"), line(ids.give, "GIVE", "Cho đi", 5, "1000000.00"),
  ],
};

export const allocationResult: AllocationResult = {
  decisionId: "88888888-8888-4888-8888-000000000001", decision: "APPLIED", transactionId: ids.tx1, amount: vnd("20000000.00"), month: "2026-11",
  splits: [{ jarId: ids.nec, amount: vnd("11000000.00"), balance: vnd("22000000.00") }],
};

export const budgetPeriod: BudgetPeriod = {
  month: "2026-11", closed: false, planned: vnd("13000000.00"), spent: vnd("5000000.00"),
  jars: [
    { jarId: ids.nec, code: "NEC", name: "Thiết yếu", planned: vnd("11000000.00"), spent: vnd("4000000.00"), remaining: vnd("7000000.00"), closedAt: null },
    { jarId: ids.play, code: "PLAY", name: "Hưởng thụ", planned: vnd("2000000.00"), spent: vnd("1000000.00"), remaining: vnd("1000000.00"), closedAt: null },
  ],
};

const phase = (type: string, status: "current" | "upcoming" | "completed") => ({
  phaseType: type, title: `Giai đoạn ${type}`, objective: `Mục tiêu của ${type}`, entryConditions: ["Đã có quỹ đệm"], exitConditions: ["Đạt 3 tháng chi tiêu"],
  expectedResults: ["Ổn định dòng tiền"], serviceIds: ["svc"], status,
});

export const roadmap: RoadmapResponse = {
  schemaVersion: "v1", agentId: "service", status: "ok",
  result: { summary: "Lộ trình tiết kiệm quỹ khẩn cấp.", roadmapStatus: "ready", journeyPattern: "protect_then_grow", currentPhase: "cashflow_protection", phaseSequence: ["cashflow_protection", "accumulation"] },
  roadmapContract: {
    schemaVersion: "v1", status: "ready", missingFields: [], warnings: [], maturityEvents: [],
    goal: { goalType: "emergency_fund", targetAmount: 60000000, targetTimelineMonths: 24, priority: "high" },
    plannerState: {
      cashflowStatus: "tight", savingsCapacity: { amountMonthly: 3237660, label: "limited" }, runwayMonths: 2.4, liquidityPressure: "moderate",
      anomalyState: "active", readinessLabel: "chưa sẵn sàng", feasibilityHint: "low", riskBand: "high", goalProgressRatio: 0.35, bufferStatus: "present", source: "ledger",
    },
    journeyPattern: "protect_then_grow", currentPhase: "cashflow_protection", phaseSequence: ["cashflow_protection", "accumulation"],
    phases: [phase("cashflow_protection", "current"), phase("accumulation", "upcoming")],
    milestones: [{ milestoneId: "m1", phaseType: "cashflow_protection", title: "Ổn định dòng tiền", unlockRule: "Hết cảnh báo", expectedResult: "Dòng tiền dương", status: "current" }],
    projection: { confidenceLabel: "cautious", basis: "6 tháng gần nhất", targetAmount: 60000000, projectedEndAmount: 40000000, targetTimelineMonths: 24, series: [], caution: "Dự phóng" },
    nextBestAction: { title: "Xử lý cảnh báo", why: "Có chi tiêu bất thường", timeframe: "tuần này", expectedOutcome: "Hết cảnh báo", successSignal: "Không còn bất thường", followOnTransition: "Sau đó bắt đầu tích lũy" },
    visualizationSupport: {
      timelineNodes: [
        { phaseType: "cashflow_protection", title: "Bảo vệ dòng tiền", status: "current", objective: "Xử lý bất thường" },
        { phaseType: "accumulation", title: "Tích lũy", status: "upcoming", objective: "Đóng góp đều đặn" },
      ],
      phaseCards: [phase("cashflow_protection", "current"), phase("accumulation", "upcoming")],
      milestoneCards: [],
      projectionChart: {
        confidenceLabel: "cautious", series: [{ month: 1, projectedAmount: 21000000 }, { month: 12, projectedAmount: 33000000 }, { month: 24, projectedAmount: 40000000 }],
        targetAmount: 60000000, projectedEndAmount: 40000000, gapToTarget: 20000000, trend: "growing",
      },
      nextActionCard: { title: "Xử lý cảnh báo", why: "Có chi tiêu bất thường", timeframe: "tuần này", expectedOutcome: "Hết cảnh báo", successSignal: "Không còn bất thường", followOnTransition: "Sau đó bắt đầu tích lũy" },
      statusBanner: { tone: "cautious", title: "Cần thận trọng", message: "Đang có cảnh báo chi tiêu" },
      keyMetrics: {
        currentMonthlyCapacity: { amount: 3237660, label: "limited" }, currentProgressAmount: 21000000, currentProgressRatio: 0.35, targetAmount: 60000000,
        timelineMonths: 24, projectedEndAmount: 40000000, feasibilityHint: "low", readinessLabel: "chưa sẵn sàng",
      },
      unlockConditions: [{ phaseType: "accumulation", status: "upcoming", conditions: ["Hết cảnh báo"] }],
    },
  },
  uiExplanation: { summary: "Tóm tắt", whyThisRoadmap: "Vì bạn có cảnh báo", whyCurrentPhase: "Cần ổn định trước", whatHasToChangeNext: "Giảm chi tiêu hũ Hưởng thụ" },
  explanation: {
    summary: "Giải thích", projectionExplanation: { basis: "6 tháng", paceAssessment: "Chậm", targetGapCommentary: "Thiếu 20 triệu", trendCommentary: "Đang tăng" },
    cautions: ["Dự phóng chỉ mang tính tham khảo"], confidenceNote: "Độ tin cậy thận trọng",
  },
};

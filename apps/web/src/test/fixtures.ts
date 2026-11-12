/**
 * Response bodies in the shape jars-api really sends: money as {"amount":"85000.00","currency":"VND"}, nullable
 * fields present as null. Used by the page tests; keep them in step with contracts/openapi/jars-api.yaml.
 */
const vnd = (amount: string) => ({ amount, currency: "VND" });

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

export const feasibility = { feasible: false, requiredMonthlySaving: vnd("3200000.00"), monthsRemaining: 13 };

const phase = (type: string, status: "current" | "upcoming" | "completed") => ({
  phase_type: type, title: `Giai đoạn ${type}`, objective: `Mục tiêu của ${type}`, entry_conditions: ["Đã có quỹ đệm"], exit_conditions: ["Đạt 3 tháng chi tiêu"],
  expected_results: ["Ổn định dòng tiền"], service_ids: ["svc"], status,
});

export const roadmap = {
  schema_version: "v1", agent_id: "service", status: "ok",
  result: { summary: "Lộ trình tiết kiệm quỹ khẩn cấp.", roadmap_status: "ready", journey_pattern: "protect_then_grow", current_phase: "cashflow_protection", phase_sequence: ["cashflow_protection", "accumulation"] },
  roadmap_contract: {
    schema_version: "v1", status: "ready",
    goal: { goal_type: "emergency_fund", target_amount: 60000000, target_timeline_months: 24, priority: "high" },
    planner_state: {
      cashflow_status: "tight", savings_capacity: { amount_monthly: 3237660, label: "limited" }, runway_months: 2.4, liquidity_pressure: "moderate",
      anomaly_state: "active", readiness_label: "chưa sẵn sàng", feasibility_hint: "low", risk_band: "high", goal_progress_ratio: 0.35, buffer_status: "present", source: "ledger",
    },
    journey_pattern: "protect_then_grow", current_phase: "cashflow_protection", phase_sequence: ["cashflow_protection", "accumulation"],
    phases: [phase("cashflow_protection", "current"), phase("accumulation", "upcoming")],
    milestones: [{ milestone_id: "m1", phase_type: "cashflow_protection", title: "Ổn định dòng tiền", unlock_rule: "Hết cảnh báo", expected_result: "Dòng tiền dương", status: "current" }],
    projection: { confidence_label: "cautious", basis: "6 tháng gần nhất", target_amount: 60000000, projected_end_amount: 40000000, target_timeline_months: 24, series: [], caution: "Dự phóng" },
    next_best_action: { title: "Xử lý cảnh báo", why: "Có chi tiêu bất thường", timeframe: "tuần này", expected_outcome: "Hết cảnh báo", success_signal: "Không còn bất thường", follow_on_transition: "Sau đó bắt đầu tích lũy" },
    visualization_support: {
      timeline_nodes: [
        { phase_type: "cashflow_protection", title: "Bảo vệ dòng tiền", status: "current", objective: "Xử lý bất thường" },
        { phase_type: "accumulation", title: "Tích lũy", status: "upcoming", objective: "Đóng góp đều đặn" },
      ],
      phase_cards: [phase("cashflow_protection", "current"), phase("accumulation", "upcoming")],
      milestone_cards: [],
      projection_chart: {
        confidence_label: "cautious", series: [{ month: 1, projected_amount: 21000000 }, { month: 12, projected_amount: 33000000 }, { month: 24, projected_amount: 40000000 }],
        target_amount: 60000000, projected_end_amount: 40000000, gap_to_target: 20000000, trend: "growing",
      },
      next_action_card: { title: "Xử lý cảnh báo", why: "Có chi tiêu bất thường", timeframe: "tuần này", expected_outcome: "Hết cảnh báo", success_signal: "Không còn bất thường", follow_on_transition: "Sau đó bắt đầu tích lũy" },
      status_banner: { tone: "cautious", title: "Cần thận trọng", message: "Đang có cảnh báo chi tiêu" },
      key_metrics: {
        current_monthly_capacity: { amount: 3237660, label: "limited" }, current_progress_amount: 21000000, current_progress_ratio: 0.35, target_amount: 60000000,
        timeline_months: 24, projected_end_amount: 40000000, feasibility_hint: "low", readiness_label: "chưa sẵn sàng",
      },
      unlock_conditions: [{ phase_type: "accumulation", status: "upcoming", conditions: ["Hết cảnh báo"] }],
    },
  },
  ui_explanation: { summary: "Tóm tắt", why_this_roadmap: "Vì bạn có cảnh báo", why_current_phase: "Cần ổn định trước", what_has_to_change_next: "Giảm chi tiêu hũ Hưởng thụ" },
  explanation: {
    summary: "Giải thích", projection_explanation: { basis: "6 tháng", pace_assessment: "Chậm", target_gap_commentary: "Thiếu 20 triệu", trend_commentary: "Đang tăng" },
    cautions: ["Dự phóng chỉ mang tính tham khảo"], confidence_note: "Độ tin cậy thận trọng",
  },
};

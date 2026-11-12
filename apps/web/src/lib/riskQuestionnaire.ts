/**
 * The five onboarding questions. The ORDER of the questions and of the options is part of the API contract: the answer
 * sent to `PUT /me/risk-profile` is the INDEX of the chosen option, and jars-api scores it with a table in the same
 * order (RiskQuestionnaire.java). Change the wording freely; adding, removing or reordering an option needs the same
 * change on the server (the unit test pins the option counts 3, 4, 3, 3, 4).
 */
export interface RiskQuestion {
  title: string;
  question: string;
  options: string[];
}

export const RISK_QUESTIONS: readonly RiskQuestion[] = [
  {
    title: "Khẩu vị rủi ro",
    question: "Bạn thoải mái đến mức nào với rủi ro khi đầu tư?",
    options: ["Thận trọng - Tránh thua lỗ", "Vừa phải - Cân bằng", "Mạo hiểm - Tối đa hóa lợi nhuận"],
  },
  {
    title: "Thời gian đầu tư",
    question: "Khi nào bạn cần dùng số tiền này?",
    options: ["Dưới 2 năm", "2-5 năm", "5-10 năm", "Trên 10 năm"],
  },
  {
    title: "Nhu cầu thanh khoản",
    question: "Bạn có thể cần rút tiền nhanh đến mức nào?",
    options: ["Cần rút ngay", "Trong vòng một tháng", "Linh hoạt - không gấp"],
  },
  {
    title: "Kinh nghiệm",
    question: "Kinh nghiệm đầu tư của bạn thế nào?",
    options: ["Mới bắt đầu", "Có một ít kinh nghiệm", "Nhiều kinh nghiệm"],
  },
  {
    title: "Mục tiêu",
    question: "Mục tiêu tài chính chính của bạn là gì?",
    options: ["Xây quỹ khẩn cấp", "Gia tăng tài sản dài hạn", "Tiết kiệm cho một mục tiêu cụ thể", "Tạo thu nhập thụ động"],
  },
];

/** The `answers` array of the request (one option index per question), or null while a question is unanswered. */
export function buildAnswers(selected: Readonly<Record<number, number>>): number[] | null {
  const answers: number[] = [];
  for (let i = 0; i < RISK_QUESTIONS.length; i++) {
    const choice = selected[i];
    if (choice === undefined || choice < 0 || choice >= RISK_QUESTIONS[i].options.length) return null;
    answers.push(choice);
  }
  return answers;
}

const LEVEL_LABEL: Record<string, string> = {
  CONSERVATIVE: "Thận trọng",
  BALANCED: "Cân bằng",
  GROWTH: "Tăng trưởng",
};

export function riskLevelLabel(level: string): string {
  return LEVEL_LABEL[level] ?? level;
}

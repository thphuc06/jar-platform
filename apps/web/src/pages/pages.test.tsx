import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext } from "@/auth/useAuth";
import * as fx from "@/test/fixtures";
import { calls, fetchMock, json, problem, serve, type Call } from "@/test/network";
import Alerts from "./Alerts";
import BudgetJars from "./BudgetJars";
import GoalPlanner from "./GoalPlanner";
import Index from "./Index";
import MoneyInbox from "./MoneyInbox";
import Onboarding from "./Onboarding";
import Profile from "./Profile";

// Intl puts a no-break space between the number and the currency symbol
const plain = (text: string | null) => (text ?? "").replace(new RegExp("[" + String.fromCharCode(160, 8239) + "]", "g"), " ");

/** What a healthy backend answers, in the wire shape of jars-api. Individual tests override single routes. */
function backend(overrides: (call: Call) => Response | undefined = () => undefined) {
  serve((call) => {
    const custom = overrides(call);
    if (custom) return custom;
    const { method, pathname } = call;
    if (method === "GET") {
      if (pathname === "/api/v1/me") return json(fx.me);
      if (pathname === "/api/v1/me/risk-profile") return json(fx.riskProfile);
      if (pathname === "/api/v1/jars") return json(fx.jars);
      if (pathname === "/api/v1/categories") return json(fx.categories);
      if (pathname === "/api/v1/dashboard") return json(fx.dashboard);
      if (pathname === "/api/v1/transactions") return json(fx.transactionPage);
      if (pathname === "/api/v1/anomalies") return json(fx.anomalyPage);
      if (pathname === "/api/v1/notifications") return json(fx.notificationPage);
      if (pathname === "/api/v1/balances/daily") return json(fx.dailyBalances);
      if (pathname === "/api/v1/goals") return json(fx.goals);
      if (pathname.endsWith("/feasibility")) return json(fx.feasibility);
      if (pathname.endsWith("/roadmap")) return json(fx.roadmap);
      if (pathname.startsWith("/api/v1/budget/periods/")) return json(fx.budgetPeriod);
    }
    if (method === "POST") {
      if (pathname === "/api/v1/budget/allocation-suggestions") return json(fx.suggestion);
      if (pathname === "/api/v1/budget/allocations") return json(fx.allocationResult, 201, { "Idempotency-Replayed": "false" });
      if (pathname.endsWith("/scenarios")) return json(fx.scenarios);
    }
    return undefined;
  });
}

function renderPage(ui: ReactElement, path = "/") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider
        value={{ status: "authenticated", user: { sub: "u1", email: "phuc@example.com" }, devMode: false, error: null, refresh: vi.fn(), signIn: vi.fn(), signOut: vi.fn() }}
      >
        <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  fetchMock.mockReset(); // not returned: a function returned from beforeEach would run as a cleanup hook
});

describe("Dashboard", () => {
  it("shows the balance, the month's flow, recent transactions and the open anomalies from /dashboard", async () => {
    backend();
    renderPage(<Index />);

    expect(await screen.findByText("18.200.000 ₫", { exact: false })).toBeInTheDocument();
    expect(screen.getByText(/Phúc/)).toBeInTheDocument();
    expect(await screen.findByText("Grab Food")).toBeInTheDocument();
    expect(screen.getByText(/cảnh báo chi tiêu bất thường/)).toBeInTheDocument();
    // one /dashboard call carries everything the home screen needs
    expect(calls().filter((c) => c.pathname === "/api/v1/dashboard")).toHaveLength(1);
    expect(calls().find((c) => c.pathname === "/api/v1/transactions")?.searchParams.get("limit")).toBe("5");
  });

  it("explains a failure of the dashboard and offers a retry", async () => {
    backend(({ pathname }) => (pathname === "/api/v1/dashboard" ? problem(503, "Dịch vụ tạm thời không khả dụng", "Aurora đang khởi động") : undefined));
    renderPage(<Index />);

    expect(await screen.findByText("Không tải được tổng quan tài chính")).toBeInTheDocument();
    expect(screen.getByText("Aurora đang khởi động")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Thử lại" })).toBeInTheDocument();
  });
});

describe("Money Inbox", () => {
  it("lists transactions with direction and amount, and loads the next page with the cursor", async () => {
    backend(({ pathname, searchParams }) => {
      if (pathname === "/api/v1/transactions" && searchParams.get("cursor") === "opaque-cursor-1") {
        return json({
          items: [{ ...fx.transactionPage.items[0], id: "33333333-3333-4333-8333-000000000099", counterparty: "Highlands Coffee" }],
          nextCursor: null,
        });
      }
      return undefined;
    });
    renderPage(<MoneyInbox />);

    expect(await screen.findByText("Grab Food")).toBeInTheDocument();
    expect(plain(screen.getByText(/20\.000\.000/).textContent)).toBe("+20.000.000 ₫");
    expect(plain(screen.getByText(/85\.000/).textContent)).toBe("-85.000 ₫");
    expect(screen.getByText("Thiết yếu → Giáo dục")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Tải thêm giao dịch/ }));
    expect(await screen.findByText("Highlands Coffee")).toBeInTheDocument();
    expect(screen.getByText("Grab Food")).toBeInTheDocument(); // the first page is kept
    expect(screen.queryByRole("button", { name: /Tải thêm giao dịch/ })).not.toBeInTheDocument(); // no cursor left
  });

  it("filters on the server by type", async () => {
    backend();
    renderPage(<MoneyInbox />);
    await screen.findByText("Grab Food");

    fireEvent.click(screen.getByRole("button", { name: "Thu nhập" }));
    await waitFor(() => expect(calls().some((c) => c.pathname === "/api/v1/transactions" && c.searchParams.get("type") === "INCOME")).toBe(true));
  });

  it("offers no category change for a jar transfer", async () => {
    backend();
    renderPage(<MoneyInbox />);
    await screen.findByText("Grab Food");

    expect(screen.getAllByRole("button", { name: /Đổi danh mục/ })).toHaveLength(2); // expense and income, not the transfer
  });
});

describe("Budget jars", () => {
  it("shows the jars from /jars with exact totals and the month's plan against spending", async () => {
    backend();
    renderPage(<BudgetJars />);

    expect(await screen.findByText("6 hũ")).toBeInTheDocument();
    // 11.000.000 + 2.000.000 * 3 + 1.200.000 + 0 = 18.200.000, summed on minor units
    expect(plain(screen.getByText(/18\.200\.000/).textContent)).toBe("18.200.000 ₫");
    expect(screen.getByText(/Tỷ lệ đã phân bổ: 100%/)).toBeInTheDocument();
    // GET /budget/periods/{month}: totals of the month, then one line per jar
    expect(await screen.findByText(/Kế hoạch tháng 2026-11/)).toBeInTheDocument();
    expect(plain(screen.getByText(/Đã chi .* \/ kế hoạch/).textContent)).toBe("Đã chi 5.000.000 ₫ / kế hoạch 13.000.000 ₫");
    expect(plain(screen.getByText(/4\.000\.000 ₫ \/ 11\.000\.000 ₫/).textContent)).toContain("4.000.000 ₫ / 11.000.000 ₫");
    expect(screen.getAllByText("Thiết yếu").length).toBeGreaterThanOrEqual(2); // the jar card and its line of the plan
  });

  it("splits an income into amounts that add up to it to the dong, with one Idempotency-Key", async () => {
    backend();
    renderPage(<BudgetJars />);

    fireEvent.click(await screen.findByRole("button", { name: /Phân bổ thu nhập/ }));
    fireEvent.change(await screen.findByLabelText("Khoản thu nhập (VND)"), { target: { value: "20000001" } });
    fireEvent.click(screen.getByRole("button", { name: /Gợi ý phân bổ/ }));
    // the suggestion fills the shares the server proposed (sharePct)
    await waitFor(() => expect(screen.getByLabelText("Thiết yếu")).toHaveValue("55"));
    expect(screen.getByText(/Tổng: 100% \(đủ 100%\)/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Áp dụng phân bổ" }));
    await waitFor(() => expect(calls().some((c) => c.pathname === "/api/v1/budget/allocations")).toBe(true));

    const post = calls().find((c) => c.pathname === "/api/v1/budget/allocations")!;
    const body = (await post.request.clone().json()) as { amount: string; splits: Array<{ jarId: string; amount: string }> };
    expect(body.amount).toBe("20000001");
    // amounts, not percentages: 55 % of 20.000.001 is 11.000.000,55 and the leftover dong are handed out, none lost
    expect(body.splits).toHaveLength(6);
    expect(body.splits.reduce((sum, split) => sum + BigInt(split.amount), 0n)).toBe(20000001n);
    expect(post.request.headers.get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("expands a jar and cannot archive one that still holds money", async () => {
    backend();
    renderPage(<BudgetJars />);

    fireEvent.click(await screen.findByRole("button", { name: /Thiết yếu/ }));
    expect(screen.getByRole("button", { name: /Sửa tỷ lệ/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Chỉ lưu trữ được hũ có số dư bằng 0" })).toBeDisabled();
  });
});

describe("Alerts", () => {
  it("lists open anomalies and sends the user's verdict", async () => {
    backend(({ method, pathname }) =>
      method === "POST" && pathname === `/api/v1/anomalies/${fx.ids.anomaly}/feedback` ? json({ ...fx.anomalyPage.items[0], status: "EXPECTED" }) : undefined,
    );
    renderPage(<Alerts />);

    expect(await screen.findByText("Khoản chi đột biến ở hũ Hưởng thụ")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Tôi đã dự kiến" }));

    await waitFor(() => expect(calls().some((c) => c.method === "POST")).toBe(true));
    const post = calls().find((c) => c.method === "POST")!;
    expect(post.pathname).toBe(`/api/v1/anomalies/${fx.ids.anomaly}/feedback`);
    expect(await post.request.clone().json()).toEqual({ label: "EXPECTED" });
  });

  it("shows notifications with the unread count and marks one as read", async () => {
    backend(({ method, pathname }) =>
      method === "POST" && pathname === `/api/v1/notifications/${fx.ids.notification2}/read`
        ? json({ ...fx.notificationPage.items[0], readAt: "2026-11-12T01:00:00Z" })
        : undefined,
    );
    renderPage(<Alerts />);

    fireEvent.click(await screen.findByRole("tab", { name: "Thông báo" }));
    expect(await screen.findByText("Hũ Hưởng thụ tăng chi bất thường")).toBeInTheDocument();
    expect(screen.getByText("1 mới")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Đánh dấu đã đọc/ }));
    await waitFor(() => expect(screen.queryByText("1 mới")).not.toBeInTheDocument());
  });
});

describe("Goal planner", () => {
  it("renders goals, their feasibility and the roadmap payload", async () => {
    backend();
    renderPage(<GoalPlanner />);

    expect(await screen.findByText("Quỹ khẩn cấp")).toBeInTheDocument();
    // GET /goals/{id}/feasibility: required monthly saving, the verdict and the probability of success
    expect(await screen.findByText(/Cần tiết kiệm ~/)).toBeInTheDocument();
    expect(plain(screen.getByText(/Cần tiết kiệm ~/).textContent)).toContain("3.769.231 ₫/tháng");
    expect(screen.getByText("chưa kịp tiến độ")).toBeInTheDocument();
    expect(screen.getByText(/khoảng 41% khả năng đạt/)).toBeInTheDocument();
    // the status banner of the roadmap (its title also appears in the overview card)
    expect((await screen.findAllByText("Cần thận trọng")).length).toBeGreaterThan(0);
    expect(screen.getByText("Lộ trình theo giai đoạn")).toBeInTheDocument();
  });

  it("tries a what-if and compares the chance of success with keeping things as they are", async () => {
    backend();
    renderPage(<GoalPlanner />);

    fireEvent.change(await screen.findByLabelText("Tiết kiệm thêm mỗi tháng (VND)"), { target: { value: "1000000" } });
    fireEvent.click(screen.getByRole("button", { name: "Tính thử" }));

    expect(await screen.findByText("Khả năng đạt mục tiêu: 63% (giữ nguyên: 41%)")).toBeInTheDocument();
    const post = calls().find((c) => c.pathname.endsWith("/scenarios"))!;
    expect(await post.request.clone().json()).toEqual({ monthlySavingDelta: "1000000" });
  });

  it("shows a neutral message when a goal has no roadmap yet", async () => {
    backend(({ pathname }) => (pathname.endsWith("/roadmap") ? problem(404, "Không tìm thấy") : undefined));
    renderPage(<GoalPlanner />);

    expect(await screen.findByText("Chưa có lộ trình cho mục tiêu này.")).toBeInTheDocument();
  });
});

describe("Profile", () => {
  it("shows the account, the risk profile and a sign-out button", async () => {
    backend();
    renderPage(<Profile />);

    expect(await screen.findByText("Phúc")).toBeInTheDocument();
    expect(screen.getByText("phuc@example.com")).toBeInTheDocument(); // /me has no email: the ID token's is used
    expect(await screen.findByText("Cân bằng")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Đăng xuất/ })).toBeInTheDocument();
  });

  it("shows the age band and the income situation the user gave", async () => {
    backend();
    renderPage(<Profile />);

    expect(await screen.findByText("25-34 tuổi")).toBeInTheDocument();
    expect(screen.getByText("Lương đều đặn hằng tháng")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sửa thông tin" })).toBeInTheDocument();
  });

  it("shows the two answers of a user who registered before the questions as missing, and lets them fill them in", async () => {
    backend(({ method, pathname }) => {
      if (method === "GET" && pathname === "/api/v1/me") return json(fx.meWithoutFacts);
      if (method === "PATCH" && pathname === "/api/v1/me") return json({ ...fx.meWithoutFacts, ageBand: "AGE_35_44", incomeSituation: "IRREGULAR_INCOME" });
      return undefined;
    });
    renderPage(<Profile />);

    expect(await screen.findAllByText("Chưa cung cấp")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Bổ sung thông tin" }));
    const save = screen.getByRole("button", { name: /Lưu/ });
    expect(save).toBeDisabled(); // both are needed
    fireEvent.click(within(screen.getByRole("radiogroup", { name: "Độ tuổi" })).getByRole("radio", { name: "35-44 tuổi" }));
    fireEvent.click(within(screen.getByRole("radiogroup", { name: "Thu nhập" })).getByRole("radio", { name: /Thu nhập không đều/ }));
    fireEvent.click(save);

    await waitFor(() => expect(calls().some((c) => c.method === "PATCH")).toBe(true));
    const patch = calls().find((c) => c.method === "PATCH")!;
    expect(await patch.request.clone().json()).toEqual({ ageBand: "AGE_35_44", incomeSituation: "IRREGULAR_INCOME" });
    expect(await screen.findByText("35-44 tuổi")).toBeInTheDocument();
  });
});

describe("Onboarding", () => {
  it("asks seven questions and sends the facts to PATCH /me, then the five option INDEXES to PUT /me/risk-profile", async () => {
    backend(({ method, pathname }) => {
      if (method === "PUT" && pathname === "/api/v1/me/risk-profile") return json(fx.riskProfile);
      if (method === "PATCH" && pathname === "/api/v1/me") return json(fx.me);
      return undefined;
    });
    renderPage(<Onboarding />);

    fireEvent.click(screen.getByRole("button", { name: /Bắt đầu/ }));
    // option index per question: five risk questions (the fourth has three options, the last four), then age band (five
    // options) and income situation (three)
    for (const index of [2, 3, 2, 2, 1, 1, 2]) {
      const group = screen.getByRole("radiogroup");
      fireEvent.click(within(group).getAllByRole("radio")[index]);
      fireEvent.click(screen.getByRole("button", { name: /Tiếp tục|Hoàn tất hồ sơ/ }));
    }

    expect(await screen.findByText(/Cân bằng/)).toBeInTheDocument();
    const sent = calls().filter((c) => c.method === "PUT" || c.method === "PATCH");
    // the facts first, the questionnaire last: a user is never "onboarded" without them
    expect(sent.map((c) => `${c.method} ${c.pathname}`)).toEqual(["PATCH /api/v1/me", "PUT /api/v1/me/risk-profile"]);
    expect(await sent[0].request.clone().json()).toEqual({ ageBand: "AGE_25_34", incomeSituation: "NO_INCOME_YET" });
    expect(await sent[1].request.clone().json()).toEqual({ answers: [2, 3, 2, 2, 1] });
  });

  it("shows the progress over seven steps and the two new questions", async () => {
    backend();
    renderPage(<Onboarding />);

    fireEvent.click(screen.getByRole("button", { name: /Bắt đầu/ }));
    for (let step = 0; step < 5; step++) {
      fireEvent.click(within(screen.getByRole("radiogroup")).getAllByRole("radio")[0]);
      fireEvent.click(screen.getByRole("button", { name: "Tiếp tục" }));
    }

    expect(screen.getByText("Bước 6 / 7")).toBeInTheDocument();
    expect(screen.getByText("Bạn thuộc nhóm tuổi nào?")).toBeInTheDocument();
    expect(within(screen.getByRole("radiogroup")).getAllByRole("radio")).toHaveLength(5);
    fireEvent.click(within(screen.getByRole("radiogroup")).getAllByRole("radio")[0]);
    fireEvent.click(screen.getByRole("button", { name: "Tiếp tục" }));
    expect(screen.getByText("Bước 7 / 7")).toBeInTheDocument();
    expect(within(screen.getByRole("radiogroup")).getAllByRole("radio")).toHaveLength(3);
    expect(screen.getByRole("button", { name: /Hoàn tất hồ sơ/ })).toBeDisabled(); // nothing chosen yet
  });
});

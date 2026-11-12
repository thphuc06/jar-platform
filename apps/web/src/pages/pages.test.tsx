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
const plain = (text: string | null) => (text ?? "").replace(/[  ]/g, " ");

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
      // the budgeting endpoints are not deployed yet (WP5)
      if (pathname.startsWith("/api/v1/budget/")) return problem(404, "Không tìm thấy");
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
  it("shows the jars from /jars with exact totals, and hides the monthly plan while that endpoint is missing", async () => {
    backend();
    renderPage(<BudgetJars />);

    expect(await screen.findByText("6 hũ")).toBeInTheDocument();
    // 11.000.000 + 2.000.000 * 3 + 1.200.000 + 0 = 18.200.000, summed on minor units
    expect(plain(screen.getByText(/18\.200\.000/).textContent)).toBe("18.200.000 ₫");
    expect(screen.getByText(/Tỷ lệ đã phân bổ: 100%/)).toBeInTheDocument();
    expect(screen.getByText("Thiết yếu")).toBeInTheDocument();
    expect(screen.queryByText(/Kế hoạch tháng/)).not.toBeInTheDocument();
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

describe("Goal planner (hand-written types until WP5 is in the OpenAPI file)", () => {
  it("renders goals, their feasibility and the roadmap payload", async () => {
    backend();
    renderPage(<GoalPlanner />);

    expect(await screen.findByText("Quỹ khẩn cấp")).toBeInTheDocument();
    expect(await screen.findByText(/Cần tiết kiệm ~/)).toBeInTheDocument();
    expect(await screen.findByText("Cần thận trọng")).toBeInTheDocument(); // status banner of the roadmap
    expect(screen.getByText("Lộ trình theo giai đoạn")).toBeInTheDocument();
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
});

describe("Onboarding", () => {
  it("sends the chosen option INDEX of each of the five questions to PUT /me/risk-profile", async () => {
    backend(({ method, pathname }) => (method === "PUT" && pathname === "/api/v1/me/risk-profile" ? json(fx.riskProfile) : undefined));
    renderPage(<Onboarding />);

    fireEvent.click(screen.getByRole("button", { name: /Bắt đầu/ }));
    // option index per question; the last question has four options
    for (const index of [2, 3, 2, 2, 1]) {
      const group = screen.getByRole("radiogroup");
      fireEvent.click(within(group).getAllByRole("radio")[index]);
      fireEvent.click(screen.getByRole("button", { name: /Tiếp tục|Hoàn tất hồ sơ/ }));
    }

    expect(await screen.findByText(/Cân bằng/)).toBeInTheDocument();
    const put = calls().find((c) => c.method === "PUT")!;
    expect(await put.request.clone().json()).toEqual({ answers: [2, 3, 2, 2, 1] });
  });
});

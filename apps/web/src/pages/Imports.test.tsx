import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import type { StatementImport } from "@/api/imports";
import { calls, fetchMock, json, serve } from "@/test/network";
import Imports from "./Imports";

const base: StatementImport = {
  id: "imp-1",
  fileName: "sao-ke.csv",
  status: "AWAITING_UPLOAD",
  totalRows: null,
  postedRows: null,
  skippedRows: null,
  failureReason: null,
  createdAt: "2026-11-12T10:00:00Z",
  startedAt: null,
  completedAt: null,
  errors: [],
};

const completed: StatementImport = {
  ...base,
  status: "COMPLETED",
  totalRows: 6,
  postedRows: 4,
  skippedRows: 2,
  startedAt: "2026-11-12T10:00:01Z",
  completedAt: "2026-11-12T10:00:02Z",
  errors: [
    { line: 5, reason: "Ngày không hợp lệ: 'not-a-date'." },
    { line: 6, reason: "Số tiền trống, bằng 0 hoặc không đọc được." },
  ],
};

function renderImports() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Imports />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function chooseFile(name = "sao-ke.csv") {
  const input = screen.getByLabelText("Chọn tệp sao kê");
  fireEvent.change(input, { target: { files: [new File(["date,amount\n2026-10-01,-1000\n"], name, { type: "text/csv" })] } });
}

beforeEach(() => {
  fetchMock.mockReset();
});

describe("Imports", () => {
  it("creates an import, sends the file with the API token to a same-origin target, and shows the result", async () => {
    let polls = 0;
    serve(({ method, pathname }) => {
      if (method === "GET" && pathname === "/api/v1/imports") return json([]);
      if (method === "POST" && pathname === "/api/v1/imports")
        return json(
          {
            statementImport: base,
            upload: { url: "/api/v1/imports/imp-1/file", method: "PUT", headers: { "Content-Type": "text/csv" }, expiresAt: "2026-11-12T10:10:00Z", sameOrigin: true },
          },
          201,
        );
      if (method === "PUT" && pathname === "/api/v1/imports/imp-1/file") return json({ ...base, status: "PROCESSING" });
      if (method === "GET" && pathname === "/api/v1/imports/imp-1") return json(++polls === 1 ? { ...base, status: "PROCESSING" } : completed);
      return method === "GET" ? json([]) : undefined;
    });
    renderImports();

    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: /Nhập sao kê/ }));

    expect(await screen.findByText("Đã ghi 4 giao dịch vào sổ", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.getByText(/Dòng 5:/)).toBeInTheDocument();
    expect(screen.getByText(/2 dòng không nhập được/)).toBeInTheDocument();

    const put = calls().find((c) => c.method === "PUT");
    expect(put?.request.headers.get("Authorization")).toBe("Bearer test-access-token");
    expect(put?.request.headers.get("Content-Type")).toBe("text/csv");
    expect(JSON.parse(await calls().find((c) => c.method === "POST")!.request.clone().text())).toEqual({ fileName: "sao-ke.csv" });
  });

  it("never sends the API token to a presigned S3 URL", async () => {
    serve(({ method, pathname }) => {
      if (method === "GET" && pathname === "/api/v1/imports") return json([]);
      if (method === "POST" && pathname === "/api/v1/imports")
        return json(
          {
            statementImport: base,
            upload: { url: "https://jars-dev-statements.s3.amazonaws.com/uploads/u/imp-1.csv?X-Amz-Signature=abc", method: "PUT", headers: { "Content-Type": "text/csv" }, expiresAt: "2026-11-12T10:10:00Z", sameOrigin: false },
          },
          201,
        );
      if (method === "PUT" && pathname === "/uploads/u/imp-1.csv") return new Response(null, { status: 200 });
      if (method === "GET" && pathname === "/api/v1/imports/imp-1") return json(completed);
      return undefined;
    });
    renderImports();

    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: /Nhập sao kê/ }));

    await waitFor(() => expect(calls().some((c) => c.method === "PUT")).toBe(true));
    const put = calls().find((c) => c.method === "PUT")!;
    expect(put.request.url).toContain("X-Amz-Signature=abc");
    expect(put.request.headers.get("Authorization")).toBeNull();
    expect(await screen.findByText("Đã ghi 4 giao dịch vào sổ")).toBeInTheDocument();
  });

  it("explains why a file was rejected", async () => {
    serve(({ method, pathname }) => {
      if (method === "GET" && pathname === "/api/v1/imports") return json([]);
      if (method === "POST")
        return json({ statementImport: base, upload: { url: "/api/v1/imports/imp-1/file", method: "PUT", headers: {}, expiresAt: "2026-11-12T10:10:00Z", sameOrigin: true } }, 201);
      if (method === "PUT") return json(base);
      if (method === "GET" && pathname === "/api/v1/imports/imp-1")
        return json({ ...base, status: "FAILED", failureReason: "Tệp này đã được nhập trước đó.", postedRows: 0, skippedRows: 0 });
      return undefined;
    });
    renderImports();

    chooseFile();
    fireEvent.click(screen.getByRole("button", { name: /Nhập sao kê/ }));

    expect(await screen.findByText("Tệp này đã được nhập trước đó.")).toBeInTheDocument();
    expect(screen.getByText(/Không nhập được sao-ke.csv/)).toBeInTheDocument();
  });

  it("lists earlier imports with their outcome", async () => {
    serve(({ method, pathname }) => (method === "GET" && pathname === "/api/v1/imports" ? json([completed, { ...base, id: "imp-0", fileName: "cu.csv", status: "FAILED" }]) : undefined));
    renderImports();

    expect(await screen.findByText("sao-ke.csv")).toBeInTheDocument();
    expect(screen.getByText(/4 giao dịch · 2 dòng lỗi/)).toBeInTheDocument();
    expect(screen.getByText("Thất bại")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Nhập sao kê/ })).toBeDisabled();
  });
});

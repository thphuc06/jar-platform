# web

React 18 + Vite 8 + TypeScript (strict) + Tailwind 3 + shadcn/ui, dạng PWA. Gọi `jars-api` qua `/api` trên cùng origin.

> **Nguồn gốc:** phần giao diện (49 component shadcn trong `src/components/ui`, layout, theme, PWA, trang Onboarding/Install/NotFound) chép từ repo hackathon `swinhackathon/ius_financial_ui`, vốn phần lớn do Lovable sinh. Phần viết mới trong repo này là **tầng data và auth** (`src/api`, `src/auth`) cùng logic của từng màn hình nối với `jars-api`.

## Trạng thái

| Bước | Nội dung |
|---|---|
| ✅ P1.11a | Đăng nhập (Cognito managed login, PKCE, [ADR-0020](../../docs/04-adr/0020-spa-auth-oidc-client-ts.md)), client có kiểu, Onboarding (`PUT /me/risk-profile`), Profile (`GET /me`, risk profile, logout) |
| ✅ P1.11b | Dashboard (`GET /dashboard`, `/balances/daily`), Money Inbox (cursor, lọc trong URL, sửa giao dịch bằng merge patch) |
| ✅ P1.11c | Spend/Income (`POST /transactions`), Budget Jars (`POST /jars/transfers`), Goals (CRUD); Idempotency-Key theo hành động. Không còn mock |
| Phase 2–3 | Alerts, AI Chat (chưa chép: chúng gọi endpoint App Runner cũ và bảng Supabase) |

Bỏ lại không chép: `lib/supabase.ts` (anon key và user id **gõ cứng**), `lib/auth.ts` (`USER_PASSWORD_AUTH` + client secret trong bundle), `lib/api.ts` (code chết), các file mock, `lovable-tagger`, ảnh OG trên R2 của Lovable, Google Fonts (đã chuyển sang tự host bằng `@fontsource`).

## Chạy

```bash
cp .env.example .env.local   # rồi điền 2 giá trị (xem mục Env)
npm ci --ignore-scripts      # lockfile được commit; không chạy install script nào
npm run dev                  # http://localhost:5173 (bắt buộc cổng này: callback URL của Cognito); /api → localhost:8080
```

Cần backend đang chạy ([local-development](../../docs/05-operations/local-development.md)) và một user trong user pool dev (đăng ký trên managed login).

| Script | Việc |
|---|---|
| `gen:api` | Sinh `src/api/schema.d.ts` từ [`contracts/openapi/jars-api.yaml`](../../contracts/openapi/jars-api.yaml). Chạy sau mỗi lần API đổi |
| `api:check` | CI: fail nếu `schema.d.ts` lệch với contract |
| `typecheck` / `lint` / `test` / `build` | `tsc -b` (strict), ESLint, Vitest (jsdom), build production + service worker |

## Env

Chỉ có giá trị không bí mật (client public không có secret), nhưng repo **không commit file `.env.*` nào** (quy tắc ở `.gitignore` gốc). Tạo `apps/web/.env.local` từ `.env.example`, điền giá trị của user pool dev: giống `apps/jars-api/src/main/resources/application-local.yaml`, hoặc lấy từ output `IssuerUri` / `LocalWebClientId` của stack `jars-dev-auth`. Test không cần file này (giá trị giả nằm trong `test.env` của `vite.config.ts`).

| Biến | Ý nghĩa |
|---|---|
| `VITE_COGNITO_AUTHORITY` | Issuer của user pool; endpoint OIDC đọc từ discovery |
| `VITE_COGNITO_CLIENT_ID` | App client SPA (`LocalWebClientId` của stack `jars-<env>-auth`) |

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `src/api` | `schema.d.ts` (sinh tự động, không sửa tay), `client.ts` (`openapi-fetch` + middleware access token + `ApiError`), `queries.ts` (hook TanStack Query) |
| `src/auth` | `oidc.ts` (cấu hình `UserManager` cho Cognito, `safeReturnTo`), `RequireAuth.tsx` (chặn route khi chưa đăng nhập) |
| `src/pages` | Màn hình; `AuthCallback` xử lý `/auth/callback` và flow F1 |
| `src/components/ui` | Kit shadcn chép từ UI cũ |

Quy ước khi viết code web: [conventions.md](../../docs/engineering/conventions.md) (dòng "Web").

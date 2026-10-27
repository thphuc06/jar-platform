# web

React 18 + Vite + TypeScript + Tailwind + shadcn/ui, dạng PWA.

> **Nguồn gốc:** chuyển từ `swinhackathon/ius_financial_ui`. Repo đó phần lớn do Lovable sinh (138/144 commit của bot `gpt-engineer-app`). Phần do tác giả viết trong repo này là **tầng data/auth** được viết lại để nối với `jars-api` (Phase 1, 2, 4).
> Trạng thái: **chưa copy code**, thực hiện ở bước P1.11.

## Việc cần làm khi chuyển

| Việc | Chi tiết |
|---|---|
| Auth | Bỏ `src/lib/auth.ts` (`USER_PASSWORD_AUTH` + client secret). Dùng Amplify Auth `signInWithRedirect` (managed login + PKCE) |
| Data | Bỏ `src/lib/supabase.ts`, `src/hooks/useSupabaseData.ts`, `src/lib/api.ts` (code chết). Thay bằng client sinh từ `contracts/openapi/jars-api.yaml` (`openapi-typescript` + `openapi-fetch`) bọc trong TanStack Query. Script `"gen:api": "openapi-typescript ../../contracts/openapi/jars-api.yaml -o src/api/schema.d.ts"`; version đã thử ở P1.10: `openapi-typescript` 7.13.0, `openapi-fetch` 0.17.0, `typescript` 5.9.3 (`openapi-typescript` 7 yêu cầu TypeScript 5) |
| Mock | Bỏ `mock-data.ts`, `mock-roadmap.ts`, `mock-saved-roadmap.ts` sau khi có API tương ứng |
| Chat | Viết lại `hooks/useChat.ts` theo SSE named events ([api-contract §3](../../docs/03-api/api-contract.md#3-hợp-đồng-sse)) |
| Dev proxy | `vite.config.ts` proxy `/api` → `http://localhost:8080` |
| Env | Chỉ còn: `VITE_COGNITO_USER_POOL_ID`, `VITE_COGNITO_CLIENT_ID`, `VITE_COGNITO_DOMAIN` (không có secret nào) |

Mapping màn hình → API: [api-contract.md §5](../../docs/03-api/api-contract.md#5-mapping-màn-hình-ui--api).

# Migration từ bản hackathon (Jars/IUS)

> Tài liệu này tổng hợp kết quả đọc 4 repo của org `swinhackathon` ngày 2026-10-23, để sau này biết **cái gì đến từ đâu** và **vì sao bị bỏ**.

## 1. Các repo cũ

| Repo | Nội dung thực tế | Quyết định |
|---|---|---|
| `ius_backend_agent_mcp` (public) | FastAPI proxy SSE; orchestrator Strands trên Bedrock AgentCore; MCP specialist server (planner/service/stock); logic tài chính bằng Python; Lambda CUSUM; Terraform ECS; KB markdown | **Port logic** sang Java; bỏ runtime AI; giữ `kb/*.md` để làm RAG (tùy chọn) |
| `ius_financial_ui` | React + Vite + shadcn, PWA. 138/144 commit do Lovable bot sinh. Gọi **thẳng Supabase**; `lib/api.ts` không được dùng; signup là giả | **Giữ**, chuyển vào `apps/web`, viết lại tầng data |
| `ius_dexter_vnstock_PyPortfolioOpt_intergration` | Dexter (fork, TypeScript/Bun) + vnstock FastAPI + PyPortfolioOpt, chạy trên App Runner | **Bỏ Dexter**; ~~gộp vnstock + PyPortfolioOpt thành `quant-fn` (ADR-0017)~~ → **cũng bỏ** vnstock + PyPortfolioOpt vì giấy phép ([ADR-0024](../04-adr/0024-drop-market-and-quant-fn.md), 2026-11-13) |
| `demo-repository` (private) | **Repo mẫu GitHub tự sinh khi tạo org**: `index.html` 1 dòng, 1 commit. **Không phải UI** | Archive / xóa |

## 2. Bảng ánh xạ thành phần

| Thành phần cũ | Loại | Đích mới |
|---|---|---|
| `backend/app/routes/chat.py` (proxy SSE) | Proxy | `advisor` (SSE trực tiếp từ Spring) |
| `backend/app/services/store.py` (in-memory) | Store tạm | Aurora + JPA |
| `agent/` (Strands graph, router, Cedar policy, encoding gate, synthesizer) | AI orchestration | `advisor` (Spring AI). Router và policy thay bằng tool + guardrails |
| `planner_agent/finance/allocation.py` | Nghiệp vụ | `budgeting` |
| `planner_agent/finance/anomaly.py` (MAD) | Nghiệp vụ | `insights` |
| `planner_agent/finance/goal.py`, `scenario.py` | Nghiệp vụ | `goals` |
| `planner_agent/finance/risk.py`, `suitability.py` | Nghiệp vụ | `identity` (risk) + system prompt/guardrail (suitability) |
| `planner_agent/finance/spend.py`, `recurring.py` | Nghiệp vụ | `insights` |
| `planner_agent/finance/trust.py`, `recalibration.py` | Calibrate mô hình | Bỏ ở MVP (có thể quay lại sau với dữ liệu feedback) |
| `planner_agent/finance/forecast.py` (statsmodels ETS), `oss_adapters.py` (river, pyod, ruptures, darts) | ML | **Bỏ.** Thay bằng baseline forecast (trung bình theo ngày trong tuần) bằng Java |
| `legacy_tools.ingest_statement_vn`, `normalize_and_categorize_txn` | Nghiệp vụ | `imports` (Spring Batch) |
| `service_agent/projection_engine`, `milestone_engine`, `maturity_engine`, `roadmap_compiler`, `candidate_ranker`, `candidate_validator` | Nghiệp vụ | `goals` (roadmap) |
| `service_agent/model_adapter`, `layer2_reasoning_adapter`, `explanation_layer` | LLM | `advisor` |
| `infrastructure/lambda/cusum_worker` (CUSUM + income engine) | Nghiệp vụ, trigger từ Supabase webhook | `insights` (event `TransactionPosted`) |
| `workers/tier1`, `aws-finance-mcp-server`, `aws-planner-agent-service`, `aws-kb-retrieval-server` | Legacy | Bỏ |
| `iac/terraform` (ECS, ALB, Cognito, ECR, CodeBuild) | Infra | `infra/cdk` (viết lại) |
| `schema.sql` | Dữ liệu | Thiết kế lại ([data-model.md §5](../02-architecture/data-model.md#5-khác-gì-so-với-schemasql-của-bản-hackathon)) |
| `backend/seed/seed.json`, `sparkov_category_mapping.json` | Dữ liệu mẫu | Ý tưởng cho `SeedDataRunner` |
| `kb/*.md` (sản phẩm ngân hàng, điều khoản, disclaimer) | Nội dung | RAG tùy chọn (P4.9) + disclaimer cho advisor |
| `vnstock_service.py`, `pypfopt_optimizer.py` | Python quant | ~~`functions/quant-fn`~~ → **bỏ** ([ADR-0024](../04-adr/0024-drop-market-and-quant-fn.md)) |
| `src/types/roadmap.ts` (UI) | Contract | Response của `GET /goals/{id}/roadmap` phải khớp type này |

## 3. Phát hiện bảo mật

Xem [security.md §8](../02-architecture/security.md#8-sự-cố-của-repo-cũ-hackathon-và-việc-cần-làm). Tóm tắt: lộ mật khẩu DB Supabase trong file JSON UTF-16 của repo public; `deploy.env` bị track; endpoint stock agent không có auth; client secret của Cognito nằm ở frontend; commit file zip build.

## 4. Những ý tưởng tốt nên giữ

- **Disclaimer + suitability guard**: ranh giới "giáo dục, không khuyến nghị" chuyển thành Bedrock Guardrails + system prompt.
- **Feedback loop cho cảnh báo** (`anomaly_feedback_log`) chuyển thành `POST /anomalies/{id}/feedback`.
- **Allocation decision log** chuyển thành `allocation_decisions`.
- **Payload roadmap có cấu trúc cho UI** (`RoadmapPayload`) trở thành SSE `event: roadmap`.
- **Cấu trúc tài liệu deploy** (DEPLOY.md, diagram bằng Python `diagrams`) được tiếp nối trong `docs/`.

// Load test of a deployed Jars Platform through CloudFront (k6). Run it with scripts/load-test.ps1, which creates the
// throw-away Cognito users, passes their access tokens in TOKENS and deletes the users afterwards.
//
// Mix per iteration: the screens a user opens most (dashboard, money inbox, jars) and, one time in five, an expense
// with its own Idempotency-Key. The p95 thresholds are the targets of docs/02-architecture/overview.md section 6.
import http from "k6/http";
import { check, sleep } from "k6";

const BASE = __ENV.BASE_URL;
const TOKENS = (__ENV.TOKENS || "").split(",").filter((t) => t.length > 0);
const PEAK = Number(__ENV.PEAK_VUS || 20);
const STEADY = __ENV.STEADY || "2m";

export const options = {
  scenarios: {
    users: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "30s", target: PEAK }, // ramp up (the first requests may wake Aurora)
        { duration: STEADY, target: PEAK }, // steady state: this is what the p95 describes
        { duration: "15s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    "http_req_failed": ["rate<0.01"],
    "http_req_duration{kind:read}": ["p(95)<300"],
    "http_req_duration{kind:write}": ["p(95)<500"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function headers(token, extra) {
  return { headers: Object.assign({ Authorization: `Bearer ${token}` }, extra || {}) };
}

// one GET /me per user creates it with its six default jars; returns the NEC jar of every user
export function setup() {
  if (!BASE || TOKENS.length === 0) {
    throw new Error("BASE_URL and TOKENS are required (use scripts/load-test.ps1)");
  }
  return TOKENS.map((token) => {
    http.get(`${BASE}/api/v1/me`, headers(token));
    const jars = http.get(`${BASE}/api/v1/jars`, headers(token)).json();
    const nec = jars.find((j) => j.code === "NEC");
    return { token, necJarId: nec.id };
  });
}

export default function (users) {
  const user = users[(__VU - 1) % users.length];
  const read = { tags: { kind: "read" } };

  const reads = [
    http.get(`${BASE}/api/v1/dashboard`, Object.assign(headers(user.token), read)),
    http.get(`${BASE}/api/v1/transactions?limit=20`, Object.assign(headers(user.token), read)),
    http.get(`${BASE}/api/v1/jars`, Object.assign(headers(user.token), read)),
  ];
  check(reads, { "reads are 200": (rs) => rs.every((r) => r.status === 200) });

  if (Math.random() < 0.2) {
    const body = JSON.stringify({ type: "EXPENSE", amount: 1000 + Math.floor(Math.random() * 50000), jarId: user.necJarId });
    const posted = http.post(
      `${BASE}/api/v1/transactions`,
      body,
      Object.assign(headers(user.token, { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() }), {
        tags: { kind: "write" },
      }),
    );
    check(posted, { "expense is 201": (r) => r.status === 201 });
  }
  sleep(1);
}

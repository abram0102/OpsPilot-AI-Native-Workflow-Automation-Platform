// k6 load profile for the non-LLM HTTP API (docs/TASKS.md T34).
//
// What this measures and what it deliberately does not. Every endpoint here is synchronous
// request/response: list tickets, create a ticket, read a run's status. None of them calls a
// model. `docs/SECURITY_AND_TESTING.md` §6 is explicit about why that separation matters — "Do
// not confuse LLM run latency with synchronous HTTP latency" — so the run-status scenario reads
// a run that is deliberately left QUEUED. It measures the API's read path, not the agent.
//
// Usage is through `make bench`, which seeds the fixture, starts the API and passes the token.
// Running k6 directly needs BASE_URL, TOKEN and ORG_ID in the environment.

import http from "k6/http";
import { check } from "k6";
import { Trend, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://127.0.0.1:8080";
const TOKEN = __ENV.TOKEN;
const ORG_ID = __ENV.ORG_ID;
const RUN_ID = __ENV.RUN_ID;

if (!TOKEN || !ORG_ID) {
  throw new Error("TOKEN and ORG_ID are required. Run this through `make bench`.");
}

// Per-endpoint trends. The default http_req_duration aggregates every scenario into one number,
// which would hide exactly the thing this benchmark exists to find: one slow endpoint among
// several fast ones.
const listFirstPage = new Trend("endpoint_tickets_list_first_page", true);
const listDeepPage = new Trend("endpoint_tickets_list_deep_page", true);
const createTicket = new Trend("endpoint_tickets_create", true);
const runStatus = new Trend("endpoint_run_status", true);
const errors = new Rate("endpoint_errors");

const headers = {
  Authorization: `Bearer ${TOKEN}`,
  "Content-Type": "application/json",
};

export const options = {
  // §6: "Run 30 virtual users against authenticated CRUD and list endpoints for 10 minutes, then
  // a burst of 100". Shortened here — see benchmarks/README.md. The shape is kept (sustained,
  // then burst) because the burst is what shows pool and thread-pool limits; the duration is
  // cut because this runs on a laptop beside the database it is testing, and ten minutes of that
  // measures thermal throttling as much as the application.
  scenarios: {
    sustained: {
      executor: "constant-vus",
      vus: 30,
      duration: __ENV.SUSTAINED_DURATION || "60s",
      gracefulStop: "10s",
    },
    burst: {
      executor: "constant-vus",
      vus: 100,
      duration: __ENV.BURST_DURATION || "20s",
      startTime: __ENV.SUSTAINED_DURATION || "60s",
      gracefulStop: "10s",
    },
  },
  // Thresholds that describe this system's intent, not aspirations borrowed from elsewhere.
  // §6's "stretch objective: non-LLM API p95 under 500ms" is the stated target; the error rate
  // is the one that should never move.
  thresholds: {
    endpoint_errors: ["rate<0.01"],
    "endpoint_tickets_list_first_page": ["p(95)<500"],
    "endpoint_tickets_create": ["p(95)<500"],
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function record(response, trend) {
  trend.add(response.timings.duration);
  const ok = check(response, { "status is 2xx": (r) => r.status >= 200 && r.status < 300 });
  errors.add(!ok);
  return ok;
}

export default function () {
  // 1. The default page the UI loads. Unfiltered, first page, no cursor — the common case, and
  //    the one whose query plan this benchmark is here to change.
  const first = http.get(`${BASE_URL}/api/v1/organizations/${ORG_ID}/tickets?limit=20`, {
    headers,
    tags: { endpoint: "tickets_list_first_page" },
  });
  record(first, listFirstPage);

  // 2. A deep page, reached by following the cursor the first page returned. Keyset pagination
  //    is supposed to make page 300 cost the same as page 1; this is what checks that claim
  //    under load rather than in a single EXPLAIN.
  if (first.status === 200) {
    let cursor = null;
    try {
      cursor = first.json("nextCursor");
    } catch (_error) {
      cursor = null;
    }
    if (cursor) {
      const deep = http.get(
        `${BASE_URL}/api/v1/organizations/${ORG_ID}/tickets?limit=20&cursor=${encodeURIComponent(cursor)}`,
        { headers, tags: { endpoint: "tickets_list_deep_page" } },
      );
      record(deep, listDeepPage);
    }
  }

  // 3. A write. Creates a customer inline, so it exercises the insert path and the composite
  //    foreign key rather than only appending to one table.
  const created = http.post(
    `${BASE_URL}/api/v1/organizations/${ORG_ID}/tickets`,
    JSON.stringify({
      customerName: `Bench Customer ${__VU}-${__ITER}`,
      subject: `Bench created ${__VU}-${__ITER}`,
      description: "Created by the k6 load profile. Fictional.",
      priority: "MEDIUM",
    }),
    { headers, tags: { endpoint: "tickets_create" } },
  );
  record(created, createTicket);

  // 4. Run status. A QUEUED run, read over and over — the endpoint the run explorer polls. No
  //    model is involved, which is the point: this is API latency, not agent latency.
  if (RUN_ID) {
    const status = http.get(`${BASE_URL}/api/v1/organizations/${ORG_ID}/runs/${RUN_ID}`, {
      headers,
      tags: { endpoint: "run_status" },
    });
    record(status, runStatus);
  }
}

"""Prometheus metrics, served at GET /metrics. The Java service exposes the same names and labels.

Labels are kept low-cardinality: routes are the route templates ("/api/v1/metadata/{metadata_id}"),
never raw paths; requests that match no route are counted under "unmatched".
"""

from prometheus_client import Counter, Histogram

HTTP_REQUESTS = Counter("http_requests", "HTTP requests handled", ["method", "route", "status"])
HTTP_REQUEST_DURATION = Histogram("http_request_duration_seconds", "HTTP request duration", ["method", "route"])
LLM_REQUESTS = Counter("llm_requests", "Calls to the LLM provider", ["outcome"])  # ok | error
LLM_REQUEST_DURATION = Histogram(
    "llm_request_duration_seconds",
    "LLM provider call duration, including retries",
    buckets=(0.5, 1, 2, 3, 5, 8, 13, 21, 34, 60, 120, 300),
)
CLASSIFICATION_CACHE = Counter("classification_cache", "Classification cache lookups", ["result"])  # hit | miss
LOGIN_FAILURES = Counter("login_failures", "Failed logins (wrong username or password)")
LOGIN_THROTTLED = Counter("login_throttled", "Logins refused with 429 by the failed-login limiter")
TARGET_DB_CONNECTIONS = Counter(
    "target_db_connections", "Target database connection attempts", ["outcome"]
)  # ok | error | denied

# Known label values start at 0, so dashboards and rate() see them before the first event.
for _outcome in ("ok", "error"):
    LLM_REQUESTS.labels(_outcome)
for _result in ("hit", "miss"):
    CLASSIFICATION_CACHE.labels(_result)
for _outcome in ("ok", "error", "denied"):
    TARGET_DB_CONNECTIONS.labels(_outcome)

"""Log output: plain text for development, or one JSON object per line (LOG_FORMAT=json).

Every record carries the current request ID. JSON fields: timestamp (UTC, ISO 8601), level,
logger, message, request_id, the access-log fields when present (method, path, status,
duration_ms, client_ip), and exception (the formatted traceback).
"""

import json
import logging
from datetime import UTC, datetime

from app.core.request_context import request_id_var

ACCESS_FIELDS = ("method", "path", "status", "duration_ms", "client_ip")
TEXT_FORMAT = "%(asctime)s %(levelname)s [%(name)s] [%(request_id)s] %(message)s"


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get() or "-"
        return True


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, object] = {
            "timestamp": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        request_id = getattr(record, "request_id", "-")
        if request_id != "-":
            entry["request_id"] = request_id
        for field in ACCESS_FIELDS:
            if hasattr(record, field):
                entry[field] = getattr(record, field)
        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False, default=str)


def configure_logging(level: str, log_format: str) -> None:
    """Install one stdout handler on the root logger; uvicorn's loggers go through it as well."""
    handler = logging.StreamHandler()
    handler.addFilter(RequestIdFilter())
    handler.setFormatter(JsonFormatter() if log_format == "json" else logging.Formatter(TEXT_FORMAT))
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        uvicorn_logger = logging.getLogger(name)
        uvicorn_logger.handlers.clear()
        uvicorn_logger.propagate = True
    # The app writes its own access log line (with request ID and duration); uvicorn's would duplicate it.
    logging.getLogger("uvicorn.access").disabled = True

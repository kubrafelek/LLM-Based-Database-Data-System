"""The current request's ID, available to log records and error handlers."""

import re
import uuid
from contextvars import ContextVar

REQUEST_ID_HEADER = "X-Request-ID"
# A caller-supplied ID is reused (to follow a request across services) only when it is short and
# plain, so it cannot inject log lines or huge values.
_VALID_REQUEST_ID = re.compile(r"[A-Za-z0-9._-]{1,128}")

request_id_var: ContextVar[str | None] = ContextVar("request_id", default=None)


def resolve_request_id(supplied: str | None) -> str:
    if supplied and _VALID_REQUEST_ID.fullmatch(supplied):
        return supplied
    return uuid.uuid4().hex

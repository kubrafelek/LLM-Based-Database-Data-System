"""Request ID, access log and HTTP metrics for every request (pure ASGI, outermost app middleware)."""

import logging
import time

from starlette.datastructures import Headers, MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.metrics import HTTP_REQUEST_DURATION, HTTP_REQUESTS
from app.core.request_context import REQUEST_ID_HEADER, request_id_var, resolve_request_id

access_logger = logging.getLogger("app.access")


def route_template(scope: Scope) -> str:
    """The matched route's template, e.g. "/api/v1/metadata/{metadata_id}", for low-cardinality labels.

    FastAPI includes prefixed routers without copying their routes, so the route in the scope has the
    template relative to its router ("/metadata/{metadata_id}"). Router prefixes are static, so the
    prefix is the part of the actual path in front of the template's segments.
    """
    route = scope.get("route")
    template = getattr(route, "path", None)
    if template is None:
        # Static files under a mount (/ui) share one label; anything else matched no route.
        root_path = scope.get("root_path") or ""
        return f"{root_path}/*" if root_path else "unmatched"
    path_segments = scope["path"].rstrip("/").split("/")  # leading "" for the root
    template_depth = len(template.rstrip("/").split("/")) - 1
    prefix = "/".join(path_segments[: len(path_segments) - template_depth])
    return prefix + template


class ObservabilityMiddleware:
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request_id = resolve_request_id(Headers(scope=scope).get(REQUEST_ID_HEADER))
        scope["request_id"] = request_id  # for the 500 handler, which runs outside this middleware
        token = request_id_var.set(request_id)
        status = 500
        start = time.perf_counter()

        async def send_with_request_id(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                MutableHeaders(scope=message)[REQUEST_ID_HEADER] = request_id
            await send(message)

        try:
            await self.app(scope, receive, send_with_request_id)
        finally:
            duration = time.perf_counter() - start
            method, route = scope["method"], route_template(scope)
            HTTP_REQUESTS.labels(method, route, str(status)).inc()
            HTTP_REQUEST_DURATION.labels(method, route).observe(duration)
            client = scope.get("client")
            # Path only: query strings are not logged.
            access_logger.info(
                "%s %s %d %.1fms",
                method,
                scope["path"],
                status,
                duration * 1000,
                extra={
                    "method": method,
                    "path": scope["path"],
                    "status": status,
                    "duration_ms": round(duration * 1000, 1),
                    "client_ip": client[0] if client else None,
                },
            )
            request_id_var.reset(token)

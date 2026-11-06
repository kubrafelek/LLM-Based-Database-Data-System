import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles

from app.api.dependencies import get_llm_client
from app.api.routes import api_router, legacy_router
from app.core.config import get_settings
from app.core.exceptions import AppError
from app.infrastructure.database.system_database import dispose_engine, get_sessionmaker
from app.services.auth_service import ensure_admin_user

settings = get_settings()
STATIC_DIR = Path(__file__).parent / "static"

logging.basicConfig(
    level=settings.log_level,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    async with get_sessionmaker()() as session:
        await ensure_admin_user(session, settings)
    yield
    if get_llm_client.cache_info().currsize:
        await get_llm_client().aclose()
    await dispose_engine()


docs_enabled = settings.app_env != "production"
app = FastAPI(
    title="LLM Data Discovery",
    description="Discover and classify PII in PostgreSQL databases using an OpenAI-compatible LLM.",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs" if docs_enabled else None,
    redoc_url="/redoc" if docs_enabled else None,
    openapi_url="/openapi.json" if docs_enabled else None,
)

if settings.cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_methods=["GET", "POST", "DELETE"],
        allow_headers=["Authorization", "Content-Type"],
    )


@app.middleware("http")
async def limit_request_body(request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
    content_length = request.headers.get("content-length")
    if content_length and content_length.isdigit() and int(content_length) > settings.max_request_body_bytes:
        return JSONResponse(
            status_code=413,
            content={"detail": "Request body too large"},
        )
    return await call_next(request)


# The UI only loads its own files; the CSP has no 'unsafe-inline', so all rendering is via the DOM.
UI_SECURITY_HEADERS = {
    "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; "
    "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
}


@app.middleware("http")
async def ui_security_headers(request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
    response = await call_next(request)
    if request.url.path.startswith("/ui"):
        response.headers.update(UI_SECURITY_HEADERS)
    return response


@app.exception_handler(Exception)
async def handle_unexpected_error(_: Request, exc: Exception) -> JSONResponse:
    """Anything unexpected: the same JSON shape, no stack trace or driver message in the response."""
    logger.exception("Unhandled error: %s", exc.__class__.__name__)
    return JSONResponse(status_code=500, content={"detail": "Internal Server Error"})


@app.exception_handler(AppError)
async def handle_app_error(_: Request, exc: AppError) -> JSONResponse:
    if exc.status_code >= 500:
        logger.error("%s: %s", exc.__class__.__name__, exc.detail)
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})


app.include_router(api_router)
app.include_router(legacy_router)


@app.get("/health", tags=["health"])
async def health() -> dict[str, str]:
    return {"status": "ok"}


# Browser UI over the same /api/v1 endpoints (same origin, so no CORS needed).
app.mount("/ui", StaticFiles(directory=STATIC_DIR, html=True), name="ui")


@app.get("/", include_in_schema=False)
async def root() -> RedirectResponse:
    return RedirectResponse("/ui/")

from fastapi import APIRouter

from app.api.routes import auth, classification, metadata

ROUTERS = (auth.router, metadata.router, classification.router)

# Documented, versioned API.
api_router = APIRouter(prefix="/api/v1")

# Unversioned aliases using the paths from the case study PDF (/auth, /db/metadata,
# /metadata, /classify). Same handlers, hidden from the OpenAPI docs.
legacy_router = APIRouter()

for router in ROUTERS:
    api_router.include_router(router)
    legacy_router.include_router(router, include_in_schema=False)

FROM python:3.12-slim AS builder

ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /build
COPY requirements.txt .
RUN pip install --prefix=/install -r requirements.txt


# Test image: app + dev dependencies + tests. Used by `docker compose --profile test run --rm tests`.
FROM python:3.12-slim AS test

ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1 PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY --from=builder /install /usr/local
COPY requirements.txt requirements-dev.txt ./
RUN pip install -r requirements-dev.txt
COPY pyproject.toml alembic.ini ./
COPY alembic ./alembic
COPY app ./app
COPY tests ./tests
CMD ["pytest", "-p", "no:cacheprovider"]


FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN useradd --create-home --uid 1000 appuser

WORKDIR /app
COPY --from=builder /install /usr/local
COPY alembic.ini ./
COPY alembic ./alembic
COPY app ./app

USER appuser
EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers"]

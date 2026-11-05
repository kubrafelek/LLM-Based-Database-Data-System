import asyncio
import json
import logging
from collections.abc import Callable
from typing import Any, TypeVar

import httpx

from app.core.config import Settings
from app.core.exceptions import LLMError

logger = logging.getLogger(__name__)

T = TypeVar("T")

RETRYABLE_STATUS_CODES = {408, 409, 429, 500, 502, 503, 504}
# OpenAI answers 429 both for rate limits (retryable) and for an exhausted balance (not).
QUOTA_ERROR_CODES = {"insufficient_quota", "credit_balance_exhausted"}


def _is_quota_error(response: httpx.Response) -> bool:
    try:
        error = response.json().get("error") or {}
    except (ValueError, AttributeError):
        return False
    return isinstance(error, dict) and bool(QUOTA_ERROR_CODES & {error.get("type"), error.get("code")})


def extract_json_object(content: str) -> dict[str, Any]:
    """Parse a JSON object from an LLM reply, tolerating code fences or surrounding text."""
    start, end = content.find("{"), content.rfind("}")
    if start == -1 or end <= start:
        raise ValueError("no JSON object in LLM response")
    try:
        payload = json.loads(content[start : end + 1])
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON in LLM response: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError("LLM response JSON is not an object")
    return payload


class LLMClient:
    """Provider-agnostic client for OpenAI-compatible /chat/completions endpoints.

    Transport failures (timeouts, 429, 5xx) are retried with exponential backoff.
    Replies that are not valid JSON, or that the caller's `parse` function rejects
    with ValueError, are re-requested up to `parse_retries` times.
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        timeout: float,
        max_retries: int = 2,
        parse_retries: int = 2,
        json_mode: bool = True,
        retry_backoff_seconds: float = 0.5,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self.model = model
        self._max_retries = max_retries
        self._parse_retries = parse_retries
        self._json_mode = json_mode
        self._retry_backoff_seconds = retry_backoff_seconds
        self._http = httpx.AsyncClient(
            base_url=base_url.rstrip("/") + "/",
            # Some local providers (e.g. Ollama) need no key; an empty "Bearer " header is invalid.
            headers={"Authorization": f"Bearer {api_key}"} if api_key else {},
            timeout=timeout,
            transport=transport,
        )

    @classmethod
    def from_settings(cls, settings: Settings) -> "LLMClient":
        return cls(
            base_url=settings.llm_base_url,
            api_key=settings.llm_api_key,
            model=settings.llm_model,
            timeout=settings.llm_timeout,
            max_retries=settings.llm_max_retries,
            json_mode=settings.llm_json_mode,
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    async def complete_json(self, messages: list[dict[str, str]], parse: Callable[[dict[str, Any]], T]) -> T:
        body: dict[str, Any] = {"model": self.model, "messages": messages, "temperature": 0}
        if self._json_mode:
            body["response_format"] = {"type": "json_object"}

        attempts = self._parse_retries + 1
        last_error: ValueError | None = None
        for attempt in range(1, attempts + 1):
            content = await self._send(body)
            try:
                return parse(extract_json_object(content))
            except ValueError as exc:
                last_error = exc
                logger.warning("Unusable LLM response (attempt %d/%d): %s", attempt, attempts, exc)
        raise LLMError(f"LLM returned an unusable response after {attempts} attempts: {last_error}")

    async def _send(self, body: dict[str, Any]) -> str:
        error = "LLM request failed"
        for attempt in range(self._max_retries + 1):
            try:
                response = await self._http.post("chat/completions", json=body)
            except httpx.TimeoutException:
                error = "LLM request timed out"
            except httpx.TransportError as exc:
                error = f"LLM provider unreachable: {exc.__class__.__name__}"
            else:
                if response.status_code == 429 and _is_quota_error(response):
                    raise LLMError("LLM provider quota exhausted; check the account's billing/credits")
                if response.status_code in RETRYABLE_STATUS_CODES:
                    error = f"LLM provider returned HTTP {response.status_code}"
                elif response.is_error:
                    raise LLMError(f"LLM provider returned HTTP {response.status_code}")
                else:
                    return self._extract_content(response)

            if attempt < self._max_retries:
                logger.warning("%s; retrying (%d/%d)", error, attempt + 1, self._max_retries)
                await asyncio.sleep(self._retry_backoff_seconds * 2**attempt)
        raise LLMError(error)

    @staticmethod
    def _extract_content(response: httpx.Response) -> str:
        try:
            content = response.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise LLMError("LLM provider returned an unexpected response format") from exc
        if not isinstance(content, str):
            raise LLMError("LLM provider returned an unexpected response format")
        return content

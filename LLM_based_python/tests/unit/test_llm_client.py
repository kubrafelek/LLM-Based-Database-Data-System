import json

import httpx
import pytest

from app.core.exceptions import LLMError
from app.infrastructure.llm.llm_client import LLMClient, extract_json_object
from tests.fakes import chat_completion


def make_client(responses: list, **kwargs) -> tuple[LLMClient, list[httpx.Request]]:
    """Client whose provider returns `responses` in order (httpx.Response or an exception)."""
    seen: list[httpx.Request] = []
    queue = list(responses)

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        item = queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    client = LLMClient(
        base_url="http://llm.test/v1/",
        api_key="key-123",
        model="m",
        timeout=5,
        retry_backoff_seconds=0,
        transport=httpx.MockTransport(handler),
        **kwargs,
    )
    return client, seen


def ok(content: str) -> httpx.Response:
    return httpx.Response(200, json=chat_completion(content))


def identity(payload: dict) -> dict:
    return payload


def test_extract_json_tolerates_code_fences():
    assert extract_json_object('Sure:\n```json\n{"a": 1}\n```') == {"a": 1}


@pytest.mark.parametrize("content", ["no json here", "[1, 2]", "{broken"])
def test_extract_json_rejects_invalid_content(content):
    with pytest.raises(ValueError):
        extract_json_object(content)


async def test_sends_openai_compatible_request():
    client, seen = make_client([ok('{"a": 1}')])
    assert await client.complete_json([{"role": "user", "content": "hi"}], identity) == {"a": 1}

    request = seen[0]
    assert request.url == "http://llm.test/v1/chat/completions"
    assert request.headers["authorization"] == "Bearer key-123"
    body = json.loads(request.content)
    assert body["model"] == "m"
    assert body["temperature"] == 0
    assert body["response_format"] == {"type": "json_object"}


async def test_empty_api_key_sends_no_authorization_header():
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return ok('{"a": 1}')

    client = LLMClient(
        base_url="http://llm.test/v1",
        api_key="",
        model="m",
        timeout=5,
        transport=httpx.MockTransport(handler),
    )
    await client.complete_json([], identity)
    assert "authorization" not in seen[0].headers


async def test_json_mode_can_be_disabled():
    client, seen = make_client([ok('{"a": 1}')], json_mode=False)
    await client.complete_json([], identity)
    assert "response_format" not in json.loads(seen[0].content)


async def test_retries_transient_failures():
    client, seen = make_client([httpx.Response(503), httpx.ConnectTimeout("slow"), ok('{"a": 1}')], max_retries=2)
    assert await client.complete_json([], identity) == {"a": 1}
    assert len(seen) == 3


async def test_gives_up_after_max_retries():
    client, _ = make_client([httpx.Response(429), httpx.Response(429)], max_retries=1)
    with pytest.raises(LLMError, match="HTTP 429"):
        await client.complete_json([], identity)


@pytest.mark.parametrize(
    "error",
    [
        {"type": "insufficient_quota", "code": "credit_balance_exhausted"},
        {"type": "insufficient_quota", "code": "insufficient_quota"},
    ],
)
async def test_exhausted_quota_fails_fast_with_clear_message(error):
    client, seen = make_client([httpx.Response(429, json={"error": error})], max_retries=3)
    with pytest.raises(LLMError, match="quota exhausted"):
        await client.complete_json([], identity)
    assert len(seen) == 1


async def test_rate_limit_429_is_still_retried():
    rate_limited = httpx.Response(429, json={"error": {"type": "requests", "code": "rate_limit_exceeded"}})
    client, seen = make_client([rate_limited, ok('{"a": 1}')], max_retries=1)
    assert await client.complete_json([], identity) == {"a": 1}
    assert len(seen) == 2


async def test_does_not_retry_client_errors():
    client, seen = make_client([httpx.Response(401)], max_retries=3)
    with pytest.raises(LLMError, match="HTTP 401"):
        await client.complete_json([], identity)
    assert len(seen) == 1


async def test_rejects_unexpected_response_shape():
    client, _ = make_client([httpx.Response(200, json={"unexpected": True})])
    with pytest.raises(LLMError, match="unexpected response format"):
        await client.complete_json([], identity)


async def test_re_requests_when_parse_rejects_reply():
    def parse(payload: dict) -> int:
        if "value" not in payload:
            raise ValueError("missing value")
        return payload["value"]

    client, seen = make_client([ok("not json"), ok('{"other": 1}'), ok('{"value": 7}')], parse_retries=2)
    assert await client.complete_json([], parse) == 7
    assert len(seen) == 3


async def test_raises_after_exhausting_parse_retries():
    client, _ = make_client([ok("nope"), ok("still nope")], parse_retries=1)
    with pytest.raises(LLMError, match="unusable response after 2 attempts"):
        await client.complete_json([], identity)

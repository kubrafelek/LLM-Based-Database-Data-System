"""Test doubles shared across test levels."""

import json
from typing import Any

import httpx

from app.infrastructure.llm.llm_client import LLMClient


def chat_completion(content: str) -> dict[str, Any]:
    return {"choices": [{"index": 0, "message": {"role": "assistant", "content": content}}]}


def classification_reply(pii_likelihood: float, **probabilities: float) -> str:
    return json.dumps({"pii_likelihood": pii_likelihood, "classification": probabilities, "reasoning": "fake"})


class FakeLLMProvider:
    """An OpenAI-compatible provider served through httpx.MockTransport.

    Records every request body. Replies email_address when the prompt contains '@',
    otherwise a low-PII distribution.
    """

    def __init__(self, reply: str | None = None) -> None:
        self.requests: list[dict[str, Any]] = []
        self.reply = reply  # fixed reply content, overriding the default behavior

    def handler(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        self.requests.append(body)
        user_prompt = body["messages"][1]["content"]
        if self.reply is not None:
            reply = self.reply
        elif "@" in user_prompt:
            reply = classification_reply(0.97, email_address=0.95, full_name=0.05)
        else:
            reply = classification_reply(0.1, tckn=0.5, ssn=0.5)
        return httpx.Response(200, json=chat_completion(reply))

    def client(self) -> LLMClient:
        return LLMClient(
            base_url="http://llm.test/v1",
            api_key="test",
            model="fake-model",
            timeout=5,
            retry_backoff_seconds=0,
            transport=httpx.MockTransport(self.handler),
        )

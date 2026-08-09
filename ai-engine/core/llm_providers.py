# core/llm_providers.py — swappable LLM providers for the Ignite project builder.
#
# Provider independence is a hard requirement: switching providers must be a
# config change only — never a code change:
#
#   PROJECT_BUILDER_PROVIDER     = "groq" (default) | "nvidia" | any PROVIDER_REGISTRY name
#   PROJECT_BUILDER_MODEL_GROQ   = model id for the Groq provider
#                                  (default: openai/gpt-oss-120b)
#   PROJECT_BUILDER_MODEL_NVIDIA = model id for the NVIDIA NIM provider
#                                  (default: deepseek-ai/deepseek-v4-pro)
#   NVIDIA_NIM_API_KEY           = API key for NVIDIA NIM (nvidia provider only)
#
# Every provider satisfies the LLMProvider protocol, so callers never know which
# vendor is behind it. To add a new provider: write a class with the same
# generate() signature, give it its own PROJECT_BUILDER_MODEL_<NAME> env var,
# and add one entry to PROVIDER_REGISTRY at the bottom of this file.

import json
import os
import time
from typing import Callable, Iterator, Optional, Protocol, Union

NIM_BASE_URL = os.getenv("NVIDIA_NIM_BASE_URL", "https://integrate.api.nvidia.com/v1")
DEFAULT_NIM_MODEL = "deepseek-ai/deepseek-v4-pro"
DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"

MAX_RETRIES = 3
BACKOFF_SECONDS = 2  # 2s → 4s → 8s
REQUEST_TIMEOUT = 90

Messages = list  # [{"role": "system"|"user"|"assistant", "content": str}, ...]

# Receives {"prompt_tokens": int, "completion_tokens": int, "total_tokens": int}
# after each completed call, or None when the vendor reported no usage. For
# streams it fires once the final chunk has been consumed.
UsageCallback = Callable[[Optional[dict]], None]


class LLMProvider(Protocol):
    """Contract every project-builder provider must satisfy."""

    def generate(
        self,
        messages: Messages,
        stream: bool = False,
        max_tokens: int = 4096,
        temperature: float = 0.3,
        usage_callback: Optional[UsageCallback] = None,
    ) -> Union[str, Iterator[str]]:
        ...


class ProviderError(Exception):
    """The LLM provider failed after all retries."""


class ProviderBusyError(ProviderError):
    """Rate limit or quota hit — the user should retry in a little while."""


def _sleep(attempt: int) -> None:
    time.sleep(BACKOFF_SECONDS * (2 ** attempt))


def _usage_dict(usage) -> Optional[dict]:
    """Normalize a vendor usage payload (SDK object or plain dict) into the
    {prompt_tokens, completion_tokens, total_tokens} shape the ledger expects."""
    if usage is None:
        return None
    read = usage.get if isinstance(usage, dict) else lambda k: getattr(usage, k, None)
    return {
        "prompt_tokens": int(read("prompt_tokens") or 0),
        "completion_tokens": int(read("completion_tokens") or 0),
        "total_tokens": int(read("total_tokens") or 0),
    }


class NvidiaNimProvider:
    """OpenAI-compatible chat completions against NVIDIA NIM (DeepSeek)."""

    def __init__(self, api_key: Optional[str] = None, model: Optional[str] = None):
        self.api_key = api_key or os.getenv("NVIDIA_NIM_API_KEY", "")
        self.model = model or os.getenv("PROJECT_BUILDER_MODEL_NVIDIA") or DEFAULT_NIM_MODEL

    def generate(
        self,
        messages: Messages,
        stream: bool = False,
        max_tokens: int = 4096,
        temperature: float = 0.3,
        usage_callback: Optional[UsageCallback] = None,
    ) -> Union[str, Iterator[str]]:
        if stream:
            return self._request_stream(messages, max_tokens, temperature, usage_callback)
        return self._request(messages, max_tokens, temperature, usage_callback)

    def _headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self.api_key}",
            "Accept": "application/json",
        }

    def _payload(self, messages: Messages, max_tokens: int, temperature: float,
                 stream: bool) -> dict:
        payload = {
            "model": self.model,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "stream": stream,
        }
        if "deepseek" in self.model.lower():
            # DeepSeek on NIM: skip the reasoning prologue — faster responses
            # and no <think> blocks in the output.
            payload["chat_template_kwargs"] = {"thinking": False}
        return payload

    def _request(self, messages: Messages, max_tokens: int, temperature: float,
                 usage_callback: Optional[UsageCallback] = None) -> str:
        import requests
        last_error: Exception = ProviderError("NVIDIA NIM request was never attempted")
        for attempt in range(MAX_RETRIES):
            try:
                resp = requests.post(
                    f"{NIM_BASE_URL}/chat/completions",
                    headers=self._headers(),
                    json=self._payload(messages, max_tokens, temperature, stream=False),
                    timeout=REQUEST_TIMEOUT,
                )
            except requests.RequestException as e:
                last_error = ProviderError(f"NVIDIA NIM unreachable: {e}")
                _sleep(attempt)
                continue
            if resp.status_code == 429:
                last_error = ProviderBusyError("NVIDIA NIM rate limit hit")
                _sleep(attempt)
                continue
            if resp.status_code >= 500:
                last_error = ProviderError(f"NVIDIA NIM server error {resp.status_code}")
                _sleep(attempt)
                continue
            if resp.status_code >= 400:
                # Client errors (bad key, unknown model) won't improve with retries.
                raise ProviderError(
                    f"NVIDIA NIM rejected the request ({resp.status_code}): {resp.text[:200]}"
                )
            try:
                body = resp.json()
                content = body["choices"][0]["message"]["content"]
            except (KeyError, IndexError, ValueError) as e:
                last_error = ProviderError(f"NVIDIA NIM returned an unexpected response: {e}")
                _sleep(attempt)
                continue
            if usage_callback:
                usage_callback(_usage_dict(body.get("usage")))
            return content
        raise last_error

    def _request_stream(self, messages: Messages, max_tokens: int, temperature: float,
                        usage_callback: Optional[UsageCallback] = None) -> Iterator[str]:
        import requests
        last_error: Exception = ProviderError("NVIDIA NIM request was never attempted")
        for attempt in range(MAX_RETRIES):
            try:
                resp = requests.post(
                    f"{NIM_BASE_URL}/chat/completions",
                    headers=self._headers(),
                    json=self._payload(messages, max_tokens, temperature, stream=True),
                    stream=True,
                    timeout=REQUEST_TIMEOUT,
                )
            except requests.RequestException as e:
                last_error = ProviderError(f"NVIDIA NIM unreachable: {e}")
                _sleep(attempt)
                continue
            if resp.status_code == 429:
                last_error = ProviderBusyError("NVIDIA NIM rate limit hit")
                _sleep(attempt)
                continue
            if resp.status_code >= 500:
                last_error = ProviderError(f"NVIDIA NIM server error {resp.status_code}")
                _sleep(attempt)
                continue
            if resp.status_code >= 400:
                raise ProviderError(
                    f"NVIDIA NIM rejected the request ({resp.status_code}): {resp.text[:200]}"
                )
            yielded = False
            usage = None
            try:
                for line in resp.iter_lines(decode_unicode=True):
                    if not line or not line.startswith("data:"):
                        continue
                    data = line[len("data:"):].strip()
                    if data == "[DONE]":
                        break
                    try:
                        parsed = json.loads(data)
                    except ValueError:
                        continue
                    # NIM reports usage on the final chunk, alongside an empty delta.
                    usage = parsed.get("usage") or usage
                    try:
                        delta = parsed["choices"][0].get("delta", {}).get("content")
                    except (KeyError, IndexError):
                        continue
                    if delta:
                        yielded = True
                        yield delta
                if usage_callback:
                    usage_callback(_usage_dict(usage))
                return
            except requests.RequestException as e:
                if yielded:
                    # Mid-file drop: the caller must restart this file, not resume it.
                    raise ProviderError(f"NVIDIA NIM stream interrupted: {e}")
                last_error = ProviderError(f"NVIDIA NIM stream failed to start: {e}")
                _sleep(attempt)
        raise last_error


class GroqProvider:
    """Fallback provider using the existing Groq account (same account as core/llm.py)."""

    def __init__(self, model: Optional[str] = None):
        self.model = model or os.getenv("PROJECT_BUILDER_MODEL_GROQ") or DEFAULT_GROQ_MODEL

    def generate(
        self,
        messages: Messages,
        stream: bool = False,
        max_tokens: int = 4096,
        temperature: float = 0.3,
        usage_callback: Optional[UsageCallback] = None,
    ) -> Union[str, Iterator[str]]:
        from groq import Groq
        client = Groq(api_key=os.getenv("GROQ_API_KEY"))
        last_error: Exception = ProviderError("Groq request was never attempted")
        for attempt in range(MAX_RETRIES):
            try:
                response = client.chat.completions.create(
                    model=self.model,
                    messages=messages,
                    temperature=temperature,
                    max_tokens=max_tokens,
                    stream=stream,
                )
            except Exception as e:  # groq SDK raises its own exception hierarchy
                last_error = self._classify_error(e)
                _sleep(attempt)
                continue
            if stream:
                return self._stream_chunks(response, usage_callback)
            if usage_callback:
                usage_callback(_usage_dict(getattr(response, "usage", None)))
            return response.choices[0].message.content.strip()
        raise last_error

    @staticmethod
    def _classify_error(e: Exception) -> ProviderError:
        status = getattr(e, "status_code", None)
        text = str(e).lower()
        # Groq reports free-tier TPM overruns as 413 "rate_limit_exceeded" —
        # retryable once the minute window resets, so classify as busy, not failed.
        if status in (413, 429) or "rate limit" in text or "rate_limit_exceeded" in text:
            return ProviderBusyError(f"Groq rate limit hit: {e}")
        return ProviderError(f"Groq request failed: {e}")

    @staticmethod
    def _stream_chunks(response, usage_callback: Optional[UsageCallback] = None) -> Iterator[str]:
        # Groq reports exact usage on the final stream chunk, under x_groq.usage
        # (chunk.usage also checked for OpenAI-style include_usage payloads).
        usage = None
        for chunk in response:
            x_groq = getattr(chunk, "x_groq", None)
            usage = (getattr(x_groq, "usage", None)
                     or getattr(chunk, "usage", None)
                     or usage)
            if not chunk.choices:  # usage-only trailer chunk has no choices
                continue
            delta = chunk.choices[0].delta.content
            if delta:
                yield delta
        if usage_callback:
            usage_callback(_usage_dict(usage))


# Adding a provider is one entry here (plus its class above).
PROVIDER_REGISTRY: dict[str, type] = {
    "nvidia": NvidiaNimProvider,
    "groq": GroqProvider,
}

DEFAULT_PROVIDER = "groq"


def get_provider() -> LLMProvider:
    """Resolve the active provider from PROJECT_BUILDER_PROVIDER (read per call,
    so a Render env change takes effect on the next request after restart)."""
    name = (os.getenv("PROJECT_BUILDER_PROVIDER") or DEFAULT_PROVIDER).strip().lower()
    provider_cls = PROVIDER_REGISTRY.get(name)
    if provider_cls is None:
        raise ProviderError(
            f"Unknown PROJECT_BUILDER_PROVIDER '{name}' — "
            f"valid options: {', '.join(sorted(PROVIDER_REGISTRY))}"
        )
    return provider_cls()

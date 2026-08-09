# Tests for core/llm_providers.py — no real network calls, requests is mocked.

import json

import pytest

import core.llm_providers as llm_providers
from core.llm_providers import (
    GroqProvider,
    NvidiaNimProvider,
    ProviderBusyError,
    ProviderError,
    get_provider,
)


class FakeResponse:
    def __init__(self, status_code=200, json_data=None, lines=None, text=""):
        self.status_code = status_code
        self._json = json_data
        self._lines = lines or []
        self.text = text

    def json(self):
        return self._json

    def iter_lines(self, decode_unicode=False):
        return iter(self._lines)


def make_post(responses):
    """Build a fake requests.post returning queued responses (or raising queued
    exceptions) in order."""
    calls = []

    def fake_post(url, **kwargs):
        calls.append(kwargs)
        result = responses.pop(0)
        if isinstance(result, Exception):
            raise result
        return result

    fake_post.calls = calls
    return fake_post


@pytest.fixture(autouse=True)
def no_backoff(monkeypatch):
    monkeypatch.setattr(llm_providers, "_sleep", lambda attempt: None)


# ---------------- provider selection ----------------

def test_get_provider_defaults_to_groq(monkeypatch):
    monkeypatch.delenv("PROJECT_BUILDER_PROVIDER", raising=False)
    assert isinstance(get_provider(), GroqProvider)


def test_get_provider_switches_to_nvidia_via_env(monkeypatch):
    monkeypatch.setenv("PROJECT_BUILDER_PROVIDER", "nvidia")
    assert isinstance(get_provider(), NvidiaNimProvider)


def test_get_provider_switches_to_groq_via_env(monkeypatch):
    monkeypatch.setenv("PROJECT_BUILDER_PROVIDER", "groq")
    assert isinstance(get_provider(), GroqProvider)


def test_get_provider_rejects_unregistered_name(monkeypatch):
    monkeypatch.setenv("PROJECT_BUILDER_PROVIDER", "not-a-provider")
    with pytest.raises(ProviderError, match="not-a-provider"):
        get_provider()


# ---------------- per-provider model resolution ----------------

def test_nim_model_id_comes_from_env(monkeypatch):
    monkeypatch.setenv("PROJECT_BUILDER_MODEL_NVIDIA", "deepseek-ai/some-other-model")
    assert NvidiaNimProvider().model == "deepseek-ai/some-other-model"


def test_nim_model_id_default(monkeypatch):
    monkeypatch.delenv("PROJECT_BUILDER_MODEL_NVIDIA", raising=False)
    assert NvidiaNimProvider().model == llm_providers.DEFAULT_NIM_MODEL


def test_groq_model_id_comes_from_env(monkeypatch):
    monkeypatch.setenv("PROJECT_BUILDER_MODEL_GROQ", "llama-3.3-70b-versatile")
    assert GroqProvider().model == "llama-3.3-70b-versatile"


def test_groq_model_id_default(monkeypatch):
    monkeypatch.delenv("PROJECT_BUILDER_MODEL_GROQ", raising=False)
    assert GroqProvider().model == llm_providers.DEFAULT_GROQ_MODEL


def test_providers_ignore_the_old_shared_model_var(monkeypatch):
    # A shared PROJECT_BUILDER_MODEL used to leak one vendor's model id into the
    # other provider when switching; each provider reads only its own variable.
    monkeypatch.setenv("PROJECT_BUILDER_MODEL", "deepseek-ai/deepseek-v4-pro")
    monkeypatch.delenv("PROJECT_BUILDER_MODEL_GROQ", raising=False)
    assert GroqProvider().model == llm_providers.DEFAULT_GROQ_MODEL


# ---------------- NVIDIA NIM non-streaming ----------------

def test_nim_generate_returns_content(monkeypatch):
    import requests
    ok = FakeResponse(200, json_data={
        "choices": [{"message": {"content": "hello world"}}]
    })
    monkeypatch.setattr(requests, "post", make_post([ok]))
    provider = NvidiaNimProvider(api_key="test-key")
    assert provider.generate([{"role": "user", "content": "hi"}]) == "hello world"


def test_nim_disables_thinking_for_deepseek_models(monkeypatch):
    import requests
    ok = FakeResponse(200, json_data={"choices": [{"message": {"content": "x"}}]})
    fake = make_post([ok])
    monkeypatch.setattr(requests, "post", fake)
    provider = NvidiaNimProvider(api_key="test-key", model="deepseek-ai/deepseek-v4-pro")
    provider.generate([{"role": "user", "content": "hi"}])
    assert fake.calls[0]["json"]["chat_template_kwargs"] == {"thinking": False}


def test_nim_omits_thinking_flag_for_other_models(monkeypatch):
    import requests
    ok = FakeResponse(200, json_data={"choices": [{"message": {"content": "x"}}]})
    fake = make_post([ok])
    monkeypatch.setattr(requests, "post", fake)
    provider = NvidiaNimProvider(api_key="test-key", model="meta/llama-3.1-70b-instruct")
    provider.generate([{"role": "user", "content": "hi"}])
    assert "chat_template_kwargs" not in fake.calls[0]["json"]


def test_nim_generate_retries_server_errors_then_succeeds(monkeypatch):
    import requests
    ok = FakeResponse(200, json_data={"choices": [{"message": {"content": "recovered"}}]})
    fake = make_post([FakeResponse(500), FakeResponse(503), ok])
    monkeypatch.setattr(requests, "post", fake)
    provider = NvidiaNimProvider(api_key="test-key")
    assert provider.generate([{"role": "user", "content": "hi"}]) == "recovered"
    assert len(fake.calls) == 3


def test_nim_generate_raises_busy_after_persistent_429(monkeypatch):
    import requests
    fake = make_post([FakeResponse(429)] * llm_providers.MAX_RETRIES)
    monkeypatch.setattr(requests, "post", fake)
    provider = NvidiaNimProvider(api_key="test-key")
    with pytest.raises(ProviderBusyError):
        provider.generate([{"role": "user", "content": "hi"}])


def test_nim_generate_does_not_retry_client_errors(monkeypatch):
    import requests
    fake = make_post([FakeResponse(401, text="bad key")])
    monkeypatch.setattr(requests, "post", fake)
    provider = NvidiaNimProvider(api_key="wrong")
    with pytest.raises(ProviderError):
        provider.generate([{"role": "user", "content": "hi"}])
    assert len(fake.calls) == 1


# ---------------- NVIDIA NIM streaming ----------------

def sse(content):
    return "data: " + json.dumps({"choices": [{"delta": {"content": content}}]})


def test_nim_stream_parses_sse_chunks(monkeypatch):
    import requests
    lines = [sse("<ht"), "", "not-a-data-line", sse("ml>"), "data: [DONE]"]
    monkeypatch.setattr(requests, "post", make_post([FakeResponse(200, lines=lines)]))
    provider = NvidiaNimProvider(api_key="test-key")
    chunks = list(provider.generate([{"role": "user", "content": "hi"}], stream=True))
    assert "".join(chunks) == "<html>"


def test_nim_stream_raises_busy_before_content(monkeypatch):
    import requests
    fake = make_post([FakeResponse(429)] * llm_providers.MAX_RETRIES)
    monkeypatch.setattr(requests, "post", fake)
    provider = NvidiaNimProvider(api_key="test-key")
    with pytest.raises(ProviderBusyError):
        list(provider.generate([{"role": "user", "content": "hi"}], stream=True))


# ---------------- Groq fallback ----------------

def test_groq_classifies_rate_limit_errors():
    err = Exception("Rate limit reached for model")
    assert isinstance(GroqProvider._classify_error(err), ProviderBusyError)


def test_groq_classifies_tpm_413_as_busy():
    # Groq free tier reports tokens-per-minute overruns as HTTP 413 with
    # code rate_limit_exceeded — must be busy (retryable), not a hard failure.
    err = Exception(
        "Error code: 413 - {'error': {'message': 'Request too large for model "
        "`openai/gpt-oss-120b` ... on tokens per minute (TPM): Limit 8000, "
        "Requested 8127', 'type': 'tokens', 'code': 'rate_limit_exceeded'}}"
    )
    err.status_code = 413
    assert isinstance(GroqProvider._classify_error(err), ProviderBusyError)


def test_groq_classifies_413_status_as_busy_even_without_message():
    err = Exception("Payload too large")
    err.status_code = 413
    assert isinstance(GroqProvider._classify_error(err), ProviderBusyError)


def test_groq_classifies_other_errors():
    err = Exception("boom")
    classified = GroqProvider._classify_error(err)
    assert isinstance(classified, ProviderError)
    assert not isinstance(classified, ProviderBusyError)


# ---------------- usage reporting (token ledger hook) ----------------

USAGE = {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}


def test_nim_generate_reports_usage(monkeypatch):
    import requests
    ok = FakeResponse(200, json_data={
        "choices": [{"message": {"content": "hello"}}],
        "usage": USAGE,
    })
    monkeypatch.setattr(requests, "post", make_post([ok]))
    seen = []
    NvidiaNimProvider(api_key="test-key").generate(
        [{"role": "user", "content": "hi"}], usage_callback=seen.append)
    assert seen == [USAGE]


def test_nim_stream_reports_usage_from_final_chunk(monkeypatch):
    import requests
    trailer = "data: " + json.dumps({"choices": [{"delta": {}}], "usage": USAGE})
    lines = [sse("<ht"), sse("ml>"), trailer, "data: [DONE]"]
    monkeypatch.setattr(requests, "post", make_post([FakeResponse(200, lines=lines)]))
    seen = []
    provider = NvidiaNimProvider(api_key="test-key")
    chunks = list(provider.generate([{"role": "user", "content": "hi"}],
                                    stream=True, usage_callback=seen.append))
    assert "".join(chunks) == "<html>"
    assert seen == [USAGE]


class FakeGroqChunk:
    """Shape of a Groq SDK stream chunk: choices[].delta.content + optional
    x_groq.usage trailer."""

    def __init__(self, content=None, usage=None, with_choice=True):
        delta = type("Delta", (), {"content": content})
        choice = type("Choice", (), {"delta": delta})
        self.choices = [choice] if with_choice else []
        self.x_groq = (type("XGroq", (), {"usage": type("Usage", (), USAGE)})
                       if usage else None)


def test_groq_stream_reports_usage_from_x_groq_trailer():
    chunks = [FakeGroqChunk("<ht"), FakeGroqChunk("ml>"),
              FakeGroqChunk(usage=USAGE, with_choice=False)]
    seen = []
    out = "".join(GroqProvider._stream_chunks(iter(chunks), seen.append))
    assert out == "<html>"
    assert seen == [USAGE]


def test_groq_stream_reports_none_when_vendor_sends_no_usage():
    seen = []
    out = "".join(GroqProvider._stream_chunks(iter([FakeGroqChunk("x")]), seen.append))
    assert out == "x"
    assert seen == [None]

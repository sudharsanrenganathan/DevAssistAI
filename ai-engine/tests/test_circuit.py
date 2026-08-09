# Tests for services/circuit.py and the /circuit/ask route — the LLM is always
# mocked (via circuit._llm_generate); no API calls, no database.

import asyncio
import json
import os

import pytest

os.environ.setdefault("GROQ_API_KEY", "test-key")  # Groq() refuses a None key

import api.api_server as srv
import services.circuit as circuit
from services.token_ledger import QuotaExceededError


class FakeLLM:
    """Stands in for circuit._llm_generate: queued responses, calls recorded."""

    def __init__(self, responses=None, error=None):
        self.responses = list(responses or [])
        self.error = error
        self.calls = []

    def __call__(self, prompt, system, user_email, max_tokens):
        self.calls.append({"prompt": prompt, "system": system,
                           "user_email": user_email, "max_tokens": max_tokens})
        if self.error:
            raise self.error
        return self.responses.pop(0)


def use_llm(monkeypatch, fake):
    monkeypatch.setattr(circuit, "_llm_generate", fake)
    return fake


VALID_ARRAY_SPEC = {
    "status": "ok",
    "title": "Binary Search",
    "viz_type": "array",
    "intro": "Binary search halves a <b>sorted</b> array each step.",
    "data": {"algorithm": "binary-search",
             "values": [2, 5, 8, 12, 16, 23, 38], "target": 23},
    "steps": [
        {"short": "Start", "note": "Search space is the whole array.",
         "state": {"lo": 0, "hi": 6, "mid": -1}},
        {"short": "mid = 3", "note": "arr[3] = <b>12</b> &lt; 23 — go right.",
         "state": {"lo": 0, "hi": 6, "mid": 3}},
        {"short": "Found at 5", "note": "arr[5] = <b>23</b> — found!",
         "state": {"lo": 4, "hi": 6, "mid": 5, "found": True}},
    ],
    "suggestions": ["worst case?", "show bubble sort"],
    "interactive": {"kind": "target-input", "label": "target"},
}


def make_spec(**overrides):
    spec = json.loads(json.dumps(VALID_ARRAY_SPEC))
    spec.update(overrides)
    return spec


# ---------------- create_circuit: happy paths ----------------

def test_short_prompt_short_circuits_without_llm_call(monkeypatch):
    fake = use_llm(monkeypatch, FakeLLM())
    result = circuit.create_circuit("  a ")
    assert result["status"] == "needs_detail"
    assert result["message"]
    assert fake.calls == []


def test_valid_array_spec_wrapped_in_think_and_fences(monkeypatch):
    raw = ("<think>simulating...</think>\n"
           "```json\n" + json.dumps(VALID_ARRAY_SPEC) + "\n```")
    fake = use_llm(monkeypatch, FakeLLM([raw]))
    result = circuit.create_circuit("explain binary search", "user@example.com")
    assert result["status"] == "ok"
    c = result["circuit"]
    assert c["title"] == "Binary Search"
    assert c["viz_type"] == "array"
    assert c["data"]["algorithm"] == "binary-search"
    assert len(c["steps"]) == 3
    assert c["steps"][2]["state"]["found"] is True
    assert c["interactive"] == {"kind": "target-input", "label": "target"}
    assert fake.calls[0]["user_email"] == "user@example.com"
    assert fake.calls[0]["max_tokens"] == circuit.COMPLETION_TOKENS


def test_context_is_prepended_to_the_prompt(monkeypatch):
    fake = use_llm(monkeypatch, FakeLLM([json.dumps(VALID_ARRAY_SPEC)]))
    circuit.create_circuit("now I am in 5th standard",
                           context="User: become prime minister\nCircuit: drew PM roadmap")
    sent = fake.calls[0]["prompt"]
    assert sent.startswith("Conversation so far")
    assert "become prime minister" in sent
    assert "New request:\nnow I am in 5th standard" in sent


def test_context_is_capped_to_most_recent_chars(monkeypatch):
    fake = use_llm(monkeypatch, FakeLLM([json.dumps(VALID_ARRAY_SPEC)]))
    circuit.create_circuit("explain binary search",
                           context=("x" * circuit.MAX_CONTEXT_CHARS) + "KEEP-THE-TAIL")
    sent = fake.calls[0]["prompt"]
    assert "KEEP-THE-TAIL" in sent
    assert sent.count("x") <= circuit.MAX_CONTEXT_CHARS


def test_needs_detail_passthrough(monkeypatch):
    raw = json.dumps({"status": "needs_detail", "message": "Which algorithm?"})
    use_llm(monkeypatch, FakeLLM([raw]))
    result = circuit.create_circuit("do the thing please")
    assert result == {"status": "needs_detail", "message": "Which algorithm?"}


def test_interview_questions_are_sanitized_and_capped(monkeypatch):
    raw = json.dumps({"status": "interview", "message": "Need <b>details</b> first",
                      "questions": [
                          {"q": "What class are you in? <script>x</script>",
                           "options": ["5th", "10th", "College", "", "Working", "Other", "7th"]},
                          {"q": "  ", "options": ["ignored"]},
                          "not-a-dict",
                          {"q": "How much time daily?", "options": []},
                      ]})
    use_llm(monkeypatch, FakeLLM([raw]))
    result = circuit.create_circuit("how do I become prime minister of India?")
    assert result["status"] == "interview"
    assert "<b>" not in result["message"]
    assert len(result["questions"]) == 2
    q0 = result["questions"][0]
    assert "script" not in q0["q"] or "<" not in q0["q"]
    assert q0["options"] == ["5th", "10th", "College", "Working"]  # capped at 5, blanks dropped
    assert result["questions"][1] == {"q": "How much time daily?", "options": []}


def test_interview_without_questions_is_retried(monkeypatch):
    bad = json.dumps({"status": "interview", "message": "hmm", "questions": []})
    fake = use_llm(monkeypatch, FakeLLM([bad, json.dumps(VALID_ARRAY_SPEC)]))
    result = circuit.create_circuit("how do I become an astronaut?")
    assert result["status"] == "ok"
    assert len(fake.calls) == 2


# ---------------- create_circuit: retry & failure ----------------

def test_retries_once_with_fix_json_reprompt(monkeypatch):
    fake = use_llm(monkeypatch, FakeLLM(
        ["sorry, no JSON here", json.dumps(VALID_ARRAY_SPEC)]))
    result = circuit.create_circuit("explain binary search")
    assert result["status"] == "ok"
    assert len(fake.calls) == 2
    assert "rejected" in fake.calls[1]["prompt"]
    assert "explain binary search" in fake.calls[1]["prompt"]


def test_raises_circuit_error_when_json_never_valid(monkeypatch):
    fake = use_llm(monkeypatch, FakeLLM(["garbage", "more garbage"]))
    with pytest.raises(circuit.CircuitError):
        circuit.create_circuit("explain binary search")
    assert len(fake.calls) == 2


def test_unavailable_sentinel_raises_without_retry(monkeypatch):
    fake = use_llm(monkeypatch, FakeLLM(["⚠️ AI temporarily unavailable"]))
    with pytest.raises(circuit.CircuitError):
        circuit.create_circuit("explain binary search")
    assert len(fake.calls) == 1


def test_empty_reply_is_retried_once(monkeypatch):
    # Reasoning models can spend the whole completion budget before emitting
    # content — an empty reply must get the fix-your-JSON retry, not a hard fail.
    fake = use_llm(monkeypatch, FakeLLM(["", json.dumps(VALID_ARRAY_SPEC)]))
    result = circuit.create_circuit("explain binary search")
    assert result["status"] == "ok"
    assert len(fake.calls) == 2


def test_quota_error_propagates_unwrapped(monkeypatch):
    use_llm(monkeypatch, FakeLLM(error=QuotaExceededError("user", "budget spent")))
    with pytest.raises(QuotaExceededError):
        circuit.create_circuit("explain binary search")


# ---------------- validation & sanitization ----------------

def test_unknown_viz_type_falls_back_to_text_steps(monkeypatch):
    spec = make_spec(viz_type="hologram")
    use_llm(monkeypatch, FakeLLM([json.dumps(spec)]))
    c = circuit.create_circuit("explain quantum stuff")["circuit"]
    assert c["viz_type"] == "text-steps"
    assert c["data"] == {}
    assert c["interactive"]["kind"] == "none"


def test_bad_array_data_downgrades_to_text_steps(monkeypatch):
    spec = make_spec(data={"values": "not-a-list", "target": "x"})
    use_llm(monkeypatch, FakeLLM([json.dumps(spec)]))
    c = circuit.create_circuit("explain binary search")["circuit"]
    assert c["viz_type"] == "text-steps"
    assert c["data"] == {}


def test_bars_data_gets_default_algorithm_slug(monkeypatch):
    spec = make_spec(viz_type="bars",
                     data={"values": [42, 17, 89, 5], "algorithm": "Bubble Sort!"},
                     interactive={"kind": "values-input", "label": ""})
    use_llm(monkeypatch, FakeLLM([json.dumps(spec)]))
    c = circuit.create_circuit("how does bubble sort work")["circuit"]
    assert c["viz_type"] == "bars"
    assert c["data"]["algorithm"] == "bubble-sort"
    assert c["interactive"] == {"kind": "values-input", "label": "values"}


def test_disallowed_html_is_escaped_but_whitelist_kept(monkeypatch):
    spec = make_spec(intro='Hi <script>alert(1)</script> <b>bold</b> '
                           '<span class="mono">lo=0</span> <img src=x onerror=y>')
    spec["steps"][0]["note"] = '<a href="http://evil">click</a> then <b>swap</b>'
    spec["steps"][0]["short"] = "Start <i>now</i>"
    use_llm(monkeypatch, FakeLLM([json.dumps(spec)]))
    c = circuit.create_circuit("explain binary search")["circuit"]
    assert "<script>" not in c["intro"]
    assert "&lt;script&gt;" in c["intro"]
    assert "<b>bold</b>" in c["intro"]
    assert '<span class="mono">lo=0</span>' in c["intro"]
    assert "<img" not in c["intro"]
    assert "<a" not in c["steps"][0]["note"]
    assert "<b>swap</b>" in c["steps"][0]["note"]
    assert c["steps"][0]["short"] == "Start now"


def test_steps_capped_and_blank_steps_dropped(monkeypatch):
    steps = [{"short": f"s{i}", "note": f"n{i}", "state": {}}
             for i in range(circuit.MAX_STEPS + 10)]
    steps.insert(0, {"short": "   ", "note": "no label", "state": {}})
    spec = make_spec(steps=steps)
    use_llm(monkeypatch, FakeLLM([json.dumps(spec)]))
    c = circuit.create_circuit("explain binary search")["circuit"]
    assert len(c["steps"]) == circuit.MAX_STEPS
    assert c["steps"][0]["short"] == "s0"


def test_all_blank_steps_is_rejected_then_retried(monkeypatch):
    bad = make_spec(steps=[{"short": " ", "note": " ", "state": {}}])
    fake = use_llm(monkeypatch, FakeLLM([json.dumps(bad),
                                         json.dumps(VALID_ARRAY_SPEC)]))
    result = circuit.create_circuit("explain binary search")
    assert result["status"] == "ok"
    assert len(fake.calls) == 2


def test_suggestions_are_plain_text_and_capped(monkeypatch):
    spec = make_spec(suggestions=["<b>one</b>", "two", "  ", "three",
                                  "four", "five"])
    use_llm(monkeypatch, FakeLLM([json.dumps(spec)]))
    c = circuit.create_circuit("explain binary search")["circuit"]
    assert c["suggestions"][0] == "one"
    assert len(c["suggestions"]) <= circuit.MAX_SUGGESTIONS


def test_oversized_state_is_replaced_with_empty_dict(monkeypatch):
    spec = make_spec()
    spec["steps"][0]["state"] = {"junk": "x" * (circuit.MAX_STATE_JSON_CHARS + 10)}
    use_llm(monkeypatch, FakeLLM([json.dumps(spec)]))
    c = circuit.create_circuit("explain binary search")["circuit"]
    assert c["steps"][0]["state"] == {}


# ---------------- /circuit/ask route ----------------

def run(coro):
    return asyncio.run(coro)


def body_of(json_response):
    return json.loads(json_response.body)


def test_circuit_ask_forwards_prompt_email_and_context(monkeypatch):
    seen = {}

    def fake_create(prompt, user_email="", context=""):
        seen["prompt"], seen["user_email"], seen["context"] = prompt, user_email, context
        return {"status": "ok", "circuit": {"title": "T"}}

    monkeypatch.setattr(circuit, "create_circuit", fake_create)
    req = srv.CircuitAskRequest(prompt="binary search", context="earlier chat",
                                user_email="user@example.com")
    result = run(srv.circuit_ask(req))
    assert result == {"status": "ok", "circuit": {"title": "T"}}
    assert seen == {"prompt": "binary search", "user_email": "user@example.com",
                    "context": "earlier chat"}


def test_circuit_ask_quota_returns_429(monkeypatch):
    def deny(prompt, user_email="", context=""):
        raise QuotaExceededError("user", "budget spent")

    monkeypatch.setattr(circuit, "create_circuit", deny)
    req = srv.CircuitAskRequest(prompt="binary search",
                                user_email="user@example.com")
    response = run(srv.circuit_ask(req))
    assert response.status_code == 429
    body = body_of(response)
    assert body["status"] == "quota"
    assert body["scope"] == "user"


def test_circuit_ask_circuit_error_returns_502(monkeypatch):
    def boom(prompt, user_email="", context=""):
        raise circuit.CircuitError("model returned junk twice")

    monkeypatch.setattr(circuit, "create_circuit", boom)
    req = srv.CircuitAskRequest(prompt="binary search")
    response = run(srv.circuit_ask(req))
    assert response.status_code == 502
    assert body_of(response)["status"] == "error"

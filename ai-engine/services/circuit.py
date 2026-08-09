# services/circuit.py — business logic for Circuit (visual step-by-step answers).
#
# One call: create_circuit(prompt) -> a validated "circuit spec" dict that the
# frontend knows how to render and animate (see design-mockups/circuit/SPEC.md).
# The LLM never returns HTML/JS — only the strict JSON structure below; a
# malformed reply gets exactly one "fix your JSON" retry, then a friendly error.
# All generation goes through core/llm.py:safe_generate, so the daily token
# ledger (budget check + usage recording) applies automatically.

import json
import re
from typing import List

from pydantic import BaseModel, ValidationError

FEATURE_NAME = "Circuit"

VIZ_TYPES = ("array", "bars", "flowchart", "text-steps")
MIN_PROMPT_CHARS = 3
MAX_PROMPT_CHARS = 2000
MAX_CONTEXT_CHARS = 1500
MAX_STEPS = 25
MAX_SUGGESTIONS = 4
MAX_ARRAY_VALUES = 24
MAX_STATE_JSON_CHARS = 2000
# gpt-oss reasoning models spend completion tokens on hidden reasoning BEFORE
# the JSON (measured ~1.1K tokens for a sorting ask); 2048 got truncated
# mid-JSON with finish_reason=length, so the budget must cover both.
COMPLETION_TOKENS = 4096

DEFAULT_DETAIL_MESSAGE = (
    "Could you tell me a bit more about what you'd like to see? For example: "
    '"binary search", "how does HTTPS work", or '
    '"roadmap to become a frontend developer".'
)

# Client-side re-simulation (custom test cases with zero extra tokens) is only
# possible when the frontend recognises the algorithm id in data.algorithm.
_INTERACTIVE_BY_VIZ = {"array": ("target-input", "target"),
                       "bars": ("values-input", "values")}

SYSTEM_PROMPT = f"""You are {FEATURE_NAME}, an expert visual explainer inside DevAssist AI.
The user asks a question — an algorithm, a concept, or a plan/roadmap — and you
answer with a step-by-step animated diagram spec the app renders and plays.

Respond with STRICT JSON only — no markdown fences, no commentary:
{{
  "status": "ok",
  "title": "short title for the canvas header",
  "viz_type": "array | bars | flowchart | text-steps",
  "intro": "one friendly paragraph explaining the core idea",
  "data": {{ ...viz-type-specific, see below... }},
  "steps": [
    {{"short": "step-list label, plain text, max 60 chars",
      "note": "caption shown under the canvas for this step",
      "state": {{ ...viz-type-specific, see below... }}}}
  ],
  "suggestions": ["2-4 short follow-up questions the user might click"],
  "interactive": {{"kind": "none | target-input | values-input", "label": ""}}
}}

Choose viz_type:
1. "array" — searching a sorted array (binary search and friends).
   data: {{"algorithm": "binary-search", "values": [about 15 sorted ints], "target": int}}
   step state: {{"lo": int, "hi": int, "mid": int}}; add "found": true on the success
   step, "empty": true if the search space becomes empty; mid is -1 on setup steps.
   IMPORTANT: lo and hi are the search space BEFORE this step's comparison at mid
   (so mid is always between lo and hi); the discard happens in the NEXT step's lo/hi.
   interactive: {{"kind": "target-input", "label": "target"}}
2. "bars" — sorting an array (bubble sort, insertion sort, selection sort...).
   data: {{"algorithm": "bubble-sort", "values": [5 or 6 ints between 5 and 99]}}
   step state: {{"a": [the full array AFTER this step], "cmp": [indexes compared],
   "swap": true|false, "srt": first index of the sorted tail (use the array length
   while nothing is sorted yet)}}.
   interactive: {{"kind": "values-input", "label": "values"}}
3. "flowchart" — roadmaps, learning paths, processes, multi-stage journeys.
   data: {{"stages": [{{"title": "...", "desc": "one sentence", "duration": "~3 weeks",
   "tags": ["2-4 short keywords"],
   "items": ["3-6 concrete mini-steps to actually DO in this stage"]}}]}}
   with 5 to 10 stages. A personal roadmap MUST begin at the asker's CURRENT
   situation (use their answers) and end at their goal, with realistic durations
   and small actionable items at every stage.
   step state: {{"k": stage index}}; start with an overview step {{"k": -1}} and end
   with a wrap-up step where k equals the number of stages.
   interactive: {{"kind": "none", "label": ""}}
4. "text-steps" — ANY other topic (how HTTPS works, recursion, OAuth, system
   design...). Explain it as 5-12 clear numbered steps; data: {{}} and state: {{}}.
   interactive: {{"kind": "none", "label": ""}}

Rules:
- If the question is a PERSONAL goal, career or life roadmap ("how do I become X",
  "roadmap for me to ...") and you do NOT yet know the asker's current situation
  (age/class/education, experience, time available, location if it matters),
  do NOT guess — respond FIRST with:
  {{"status": "interview",
    "message": "one friendly sentence saying you need a few details to draw their map",
    "questions": [{{"q": "short question", "options": ["2-5 short tap options"]}}]}}
  Ask 3-5 questions maximum — only ones whose answers change the roadmap.
  When the message already contains the asker's details or answers, draw the
  circuit — never ask again.
- When a "Conversation so far" block is provided, the new request CONTINUES that
  conversation: reuse everything you already know about the asker and the topic
  (never re-ask answered questions), and draw the updated or extended circuit
  the request implies — e.g. "I am in 5th standard" means redraw the previous
  circuit adjusted to that fact.
- Tell the story gradually: one comparison, one swap, one stage per step. 3-{MAX_STEPS} steps.
- Keep the whole response compact — at most 18 steps and every note under 220
  characters. A response that is too long gets cut off and wasted.
- Simulate algorithms EXACTLY: every lo/hi/mid value and every swap must be correct
  for the data you chose. Double-check the arithmetic before answering.
- The ONLY HTML allowed in "intro" and "note": <b>...</b> and
  <span class="mono">...</span>. No other tags, no attributes, no links.
- "short" and "suggestions" are plain text, no HTML.
- If the question is too vague to visualize, respond instead with:
  {{"status": "needs_detail", "message": "a friendly question asking what they'd like to see"}}"""


class CircuitError(Exception):
    """The model could not produce a usable circuit spec."""


class CircuitStep(BaseModel):
    short: str
    note: str
    state: dict = {}


class CircuitInteractive(BaseModel):
    kind: str = "none"
    label: str = ""


class CircuitSpecModel(BaseModel):
    title: str
    viz_type: str
    intro: str
    data: dict = {}
    steps: List[CircuitStep]
    suggestions: List[str] = []
    interactive: CircuitInteractive = CircuitInteractive()


def _llm_generate(prompt: str, system: str, user_email: str, max_tokens: int) -> str:
    # Lazy import: core/llm.py builds the Groq client at import time, which
    # keeps this module importable (and unit-testable) without an API key.
    from core.llm import safe_generate
    return safe_generate(prompt, system=system, user_email=user_email,
                         max_tokens=max_tokens)


def _extract_json(raw: str) -> dict:
    """Pull the first JSON object out of a model response, tolerating <think>
    blocks (reasoning models) and markdown fences around the JSON."""
    text = re.sub(r"<think>.*?</think>", "", raw or "", flags=re.DOTALL).strip()
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        raise ValueError("no JSON object found in model response")
    return json.loads(text[start:end + 1])


_TAG_WHITELIST = re.compile(r"</?b>|<span class=\"?mono\"?>|</span>", re.IGNORECASE)


def _escape_angles(text: str) -> str:
    return text.replace("<", "&lt;").replace(">", "&gt;")


def _sanitize_html(text: str) -> str:
    """Keep only the whitelisted <b> and <span class="mono"> tags; every other
    angle bracket is escaped so model output can never inject markup."""
    text = str(text)
    parts, last = [], 0
    for match in _TAG_WHITELIST.finditer(text):
        parts.append(_escape_angles(text[last:match.start()]))
        parts.append(match.group(0))
        last = match.end()
    parts.append(_escape_angles(text[last:]))
    return "".join(parts).strip()


def _plain_text(text: str) -> str:
    """Strip ALL tags, for fields the UI treats as plain text."""
    return _escape_angles(re.sub(r"<[^>]*>", "", str(text))).strip()


def _slug(value) -> str:
    return re.sub(r"[^a-z0-9-]+", "-", str(value or "").lower()).strip("-")


def _is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _valid_array_data(data: dict) -> bool:
    values = data.get("values")
    if not isinstance(values, list) or not 2 <= len(values) <= MAX_ARRAY_VALUES:
        return False
    if not all(_is_number(v) for v in values):
        return False
    if not _is_number(data.get("target")):
        return False
    data["algorithm"] = _slug(data.get("algorithm")) or "binary-search"
    return True


def _valid_bars_data(data: dict) -> bool:
    values = data.get("values")
    if not isinstance(values, list) or not 2 <= len(values) <= MAX_ARRAY_VALUES:
        return False
    if not all(_is_number(v) and v > 0 for v in values):
        return False
    data["algorithm"] = _slug(data.get("algorithm")) or "bubble-sort"
    return True


def _safe_state(state: dict) -> dict:
    if not isinstance(state, dict):
        return {}
    try:
        if len(json.dumps(state)) > MAX_STATE_JSON_CHARS:
            return {}
    except (TypeError, ValueError):
        return {}
    return state


def _validate_interview(payload: dict) -> dict:
    """Sanitize the model's data-collection questions (personal roadmaps ask
    the user 3-5 quick questions before sketching)."""
    questions = []
    for entry in (payload.get("questions") or [])[:5]:
        if not isinstance(entry, dict):
            continue
        q = _plain_text(entry.get("q") or "")[:200]
        if not q:
            continue
        options = [_plain_text(o)[:60] for o in (entry.get("options") or [])[:5]
                   if str(o).strip()]
        questions.append({"q": q, "options": [o for o in options if o]})
    if not questions:
        raise ValueError("interview response contained no usable questions")
    return {"status": "interview",
            "message": _plain_text(payload.get("message")
                                   or "A few quick questions so I can draw YOUR map:")[:300],
            "questions": questions}


def _validate_spec(payload: dict) -> dict:
    """Turn a raw model payload into the response the API returns, or raise
    ValueError so the caller can retry once with a fix-your-JSON reprompt."""
    if payload.get("status") == "needs_detail":
        return {"status": "needs_detail",
                "message": _plain_text(payload.get("message") or DEFAULT_DETAIL_MESSAGE)[:400]}
    if payload.get("status") == "interview":
        return _validate_interview(payload)

    try:
        model = CircuitSpecModel(**payload)
    except ValidationError as e:
        raise ValueError(f"circuit spec failed validation: {e}") from e

    steps = [s for s in model.steps if s.short.strip() and s.note.strip()][:MAX_STEPS]
    if not steps:
        raise ValueError("circuit spec contained no usable steps")

    viz = model.viz_type.strip().lower()
    if viz not in VIZ_TYPES:
        viz = "text-steps"
    # A broken viz payload downgrades to text-steps instead of failing: the
    # steps still play, the user still gets an answer.
    data = dict(model.data)
    if viz == "array" and not _valid_array_data(data):
        viz, data = "text-steps", {}
    if viz == "bars" and not _valid_bars_data(data):
        viz, data = "text-steps", {}
    if viz == "text-steps":
        data = {}

    kind, default_label = _INTERACTIVE_BY_VIZ.get(viz, ("none", ""))
    label = _plain_text(model.interactive.label)[:40] or default_label

    suggestions = [_plain_text(s)[:80] for s in model.suggestions if str(s).strip()]
    return {
        "status": "ok",
        "circuit": {
            "title": (_plain_text(model.title) or FEATURE_NAME)[:120],
            "viz_type": viz,
            "intro": _sanitize_html(model.intro)[:1500],
            "data": data,
            "steps": [{"short": _plain_text(s.short)[:120],
                       "note": _sanitize_html(s.note)[:700],
                       "state": _safe_state(s.state)} for s in steps],
            "suggestions": [s for s in suggestions if s][:MAX_SUGGESTIONS],
            "interactive": {"kind": kind, "label": label},
        },
    }


def create_circuit(prompt: str, user_email: str = "", context: str = "") -> dict:
    """Ask the LLM for a circuit spec. Returns {"status":"ok","circuit":{...}},
    an interview/needs_detail dict, or raises QuotaExceededError/CircuitError.
    context carries the session's recent conversation so follow-ups like
    "I am in 5th standard" continue the ongoing topic."""
    cleaned = (prompt or "").strip()[:MAX_PROMPT_CHARS]
    if len(cleaned) < MIN_PROMPT_CHARS:
        return {"status": "needs_detail", "message": DEFAULT_DETAIL_MESSAGE}

    context = (context or "").strip()[-MAX_CONTEXT_CHARS:]
    if context:
        cleaned = (f"Conversation so far in this circuit session (oldest first):\n"
                   f"{context}\n\nNew request:\n{cleaned}")

    user_prompt = cleaned
    last_error: Exception = ValueError("circuit generation was never attempted")
    for _ in range(2):  # one retry with a fix-your-JSON reprompt
        raw = _llm_generate(user_prompt, SYSTEM_PROMPT, user_email, COMPLETION_TOKENS)
        if raw and raw.startswith("⚠️"):
            # safe_generate's in-band failure sentinel — retrying here would
            # just repeat the 3 retries it already did.
            raise CircuitError("the model is temporarily unavailable")
        try:
            if not (raw or "").strip():
                # Reasoning models can burn the whole completion budget before
                # emitting content — empty replies are worth one retry.
                raise ValueError("the model returned an empty reply")
            return _validate_spec(_extract_json(raw))
        except ValueError as e:
            last_error = e
            user_prompt = (
                f"{cleaned}\n\nYour previous reply was rejected: {e}\n"
                "Reply again with ONLY the corrected strict JSON — "
                "no fences, no commentary.")
    raise CircuitError(f"the model did not return a usable circuit spec: {last_error}")

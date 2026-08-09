# core/llm.py  — drop-in replacement for your current safe_generate

from groq import Groq
import os
from dotenv import load_dotenv
import time

from services.token_ledger import check_budget, estimate_tokens, record_usage

load_dotenv()

client = Groq(api_key=os.getenv("GROQ_API_KEY"))

AVAILABLE_MODELS = [
    "openai/gpt-oss-120b",
    "openai/gpt-oss-20b",
    "groq/compound",
    "groq/compound-mini",
    "qwen/qwen3-32b",
    "meta-llama/llama-4-scout-17b-16e-instruct"
]

DEFAULT_MODEL = "openai/gpt-oss-120b"


MAX_COMPLETION_TOKENS = 1024


def safe_generate(prompt: str, model: str = None, system: str = None,
                  user_email: str = "", max_tokens: int = None) -> str:
    # Every feature that reaches Groq goes through here, so this is the single
    # choke point for the daily token budget: reject pre-flight if the
    # estimated size would exceed a budget, record the provider-reported usage
    # after. QuotaExceededError propagates to the route.
    completion_tokens = max_tokens or MAX_COMPLETION_TOKENS
    prompt_chars = len(prompt) + len(system or "")
    check_budget(user_email, estimate_tokens(prompt_chars, completion_tokens))

    messages = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": prompt})

    for attempt in range(3):
        try:
            response = client.chat.completions.create(
                model=model or DEFAULT_MODEL,
                messages=messages,
                temperature=0.35,
                max_tokens=completion_tokens,
                top_p=0.9,
                frequency_penalty=0.1,
                presence_penalty=0.1,
            )
            usage = getattr(response, "usage", None)
            if usage is not None:
                record_usage(user_email, {
                    "prompt_tokens": getattr(usage, "prompt_tokens", 0),
                    "completion_tokens": getattr(usage, "completion_tokens", 0),
                    "total_tokens": getattr(usage, "total_tokens", 0),
                })
            return response.choices[0].message.content.strip()

        except Exception as e:
            print(f"⚠️ Retry {attempt+1} failed ({model or DEFAULT_MODEL}): {e}")
            time.sleep(1)

    return "⚠️ AI temporarily unavailable"
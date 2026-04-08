# core/llm.py  — drop-in replacement for your current safe_generate

from groq import Groq
import os
from dotenv import load_dotenv
import time

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


def safe_generate(prompt: str, model: str = None, system: str = None) -> str:
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
                max_tokens=1024,
                top_p=0.9,
                frequency_penalty=0.1,
                presence_penalty=0.1,
            )
            return response.choices[0].message.content.strip()

        except Exception as e:
            print(f"⚠️ Retry {attempt+1} failed ({model or DEFAULT_MODEL}): {e}")
            time.sleep(1)

    return "⚠️ AI temporarily unavailable"
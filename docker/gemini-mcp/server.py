"""Minimal, self-contained MCP server wrapping the Gemini REST API.

Dev tooling only. The API key is supplied via the GEMINI_API_KEY env var
(env-ref, never baked into the image or config — project iron rule). Exposes a
small read/generate surface over the Google Generative Language v1beta API.
"""

import os

import httpx
from mcp.server.fastmcp import FastMCP

API_BASE = "https://generativelanguage.googleapis.com/v1beta"
API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
# Default model; override per-call or via GEMINI_MODEL env.
DEFAULT_MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash").strip()

mcp = FastMCP("gemini")


def _client() -> httpx.Client:
    if not API_KEY:
        raise RuntimeError("GEMINI_API_KEY is not set in the server environment")
    return httpx.Client(
        base_url=API_BASE,
        headers={"x-goog-api-key": API_KEY, "Content-Type": "application/json"},
        timeout=120.0,
    )


@mcp.tool()
def gemini_list_models() -> str:
    """List Gemini models available to the configured API key (name + display name)."""
    with _client() as c:
        r = c.get("/models")
        r.raise_for_status()
        models = r.json().get("models", [])
    lines = [
        f"{m.get('name')}  —  {m.get('displayName')} "
        f"(in {m.get('inputTokenLimit', '?')} / out {m.get('outputTokenLimit', '?')})"
        for m in models
    ]
    return "\n".join(lines) if lines else "(no models returned)"


@mcp.tool()
def gemini_generate(
    prompt: str,
    model: str = "",
    system_instruction: str = "",
    temperature: float = 1.0,
) -> str:
    """Generate text with a Gemini model.

    Args:
        prompt: The user prompt / question.
        model: Model id, e.g. "gemini-2.5-flash" or "gemini-2.5-pro".
               Defaults to the server's DEFAULT_MODEL (gemini-2.5-flash).
        system_instruction: Optional system instruction to steer the model.
        temperature: Sampling temperature (0.0–2.0).
    """
    model = model.strip() or DEFAULT_MODEL
    model_path = model if model.startswith("models/") else f"models/{model}"
    body: dict = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": temperature},
    }
    if system_instruction.strip():
        body["systemInstruction"] = {"parts": [{"text": system_instruction}]}

    with _client() as c:
        r = c.post(f"/{model_path}:generateContent", json=body)
        r.raise_for_status()
        data = r.json()

    candidates = data.get("candidates", [])
    if not candidates:
        feedback = data.get("promptFeedback", {})
        return f"(no candidates returned) promptFeedback={feedback}"
    parts = candidates[0].get("content", {}).get("parts", [])
    text = "".join(p.get("text", "") for p in parts)
    return text or "(empty response)"


if __name__ == "__main__":
    mcp.run()

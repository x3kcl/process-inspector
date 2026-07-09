"""Minimal, self-contained MCP server wrapping the GitHub Models inference API.

Dev tooling only — a second-opinion LLM surface (a sibling to the gemini-mcp
server). The credential is supplied via the GITHUB_PERSONAL_ACCESS_TOKEN env var
(env-ref, never baked into the image or config — project iron rule). The PAT must
carry the `models:read` permission. Exposes a small read/generate surface over the
OpenAI-compatible GitHub Models API (https://models.github.ai).
"""

import os

import httpx
from mcp.server.fastmcp import FastMCP

API_BASE = "https://models.github.ai"
# Reuse the same token the github MCP uses; must have `models:read`.
API_KEY = os.environ.get("GITHUB_PERSONAL_ACCESS_TOKEN", "").strip()
# Default model; override per-call or via COPILOT_MODEL env. Publisher/model form.
DEFAULT_MODEL = os.environ.get("COPILOT_MODEL", "openai/gpt-4o").strip()

mcp = FastMCP("copilot")


def _client() -> httpx.Client:
    if not API_KEY:
        raise RuntimeError(
            "GITHUB_PERSONAL_ACCESS_TOKEN is not set in the server environment"
        )
    return httpx.Client(
        base_url=API_BASE,
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
        timeout=120.0,
    )


@mcp.tool()
def copilot_list_models() -> str:
    """List models available via GitHub Models to the configured token (id + name)."""
    with _client() as c:
        r = c.get("/catalog/models")
        r.raise_for_status()
        models = r.json()
    if isinstance(models, dict):
        models = models.get("models", models.get("data", []))
    lines = [
        f"{m.get('id')}  —  {m.get('name', m.get('friendly_name', ''))} "
        f"[{m.get('publisher', '?')}]"
        for m in models
    ]
    return "\n".join(lines) if lines else "(no models returned)"


@mcp.tool()
def copilot_generate(
    prompt: str,
    model: str = "",
    system_instruction: str = "",
    temperature: float = 1.0,
) -> str:
    """Generate text with a GitHub Models model (OpenAI-compatible chat completion).

    Args:
        prompt: The user prompt / question.
        model: Model id in publisher/model form, e.g. "openai/gpt-4o" or
               "openai/o3-mini". Defaults to the server's DEFAULT_MODEL.
        system_instruction: Optional system message to steer the model.
        temperature: Sampling temperature (0.0–2.0).
    """
    model = model.strip() or DEFAULT_MODEL
    messages: list[dict] = []
    if system_instruction.strip():
        messages.append({"role": "system", "content": system_instruction})
    messages.append({"role": "user", "content": prompt})
    body: dict = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
    }

    with _client() as c:
        r = c.post("/inference/chat/completions", json=body)
        r.raise_for_status()
        data = r.json()

    choices = data.get("choices", [])
    if not choices:
        return f"(no choices returned) raw={data}"
    text = choices[0].get("message", {}).get("content", "")
    return text or "(empty response)"


if __name__ == "__main__":
    mcp.run()

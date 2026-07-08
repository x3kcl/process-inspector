# Self-contained Gemini MCP server (dev tooling). No secrets baked in —
# GEMINI_API_KEY is injected at `docker run` time via an env-ref in .mcp.json.
FROM python:3.12-slim

RUN pip install --no-cache-dir "mcp>=1.2" "httpx>=0.27"

WORKDIR /app
COPY server.py .

# stdio transport — MCP host talks over stdin/stdout.
ENTRYPOINT ["python", "-u", "server.py"]

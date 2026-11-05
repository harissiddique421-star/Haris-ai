"""
MYRAA Desktop Control Agent — FastAPI entrypoint.

Single dispatch endpoint POST /execute { tool, args } -> { result } | { error }.
MYRAA's Node bridge owns the worker, chooses a loopback port and supplies a
private session credential. Start MYRAA normally; standalone requests fail
closed when that credential is absent. Browsers use the Node consent API.
"""

from __future__ import annotations

import logging
import os
import sys
from contextlib import asynccontextmanager
from typing import Any, Dict

from fastapi import FastAPI, Request
from pydantic import BaseModel

from . import __version__
from .session_auth import SESSION_AUTH
from .owner_watchdog import start_owner_watchdog
from .registry import DESKTOP_TOOL_NAMES, TOOLS, ToolError, load_all
from .perception import collect_snapshot

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("myraa.desktop")


# Load all tool modules so their handlers register before the app starts.
load_all()
log.info("Loaded %d desktop tools: %s", len(TOOLS), ", ".join(sorted(TOOLS)))


@asynccontextmanager
async def lifespan(app: FastAPI):
    start_owner_watchdog()
    log.info("MYRAA Desktop Control Agent v%s starting up.", __version__)
    try:
        from .tools_applications import warm_app_index

        warm_app_index()  # installed-app index in the background: fast "open X"
    except Exception as error:  # never block start-up on the index
        log.warning("App index warm-up failed: %s", error)
    yield
    log.info("MYRAA Desktop Control Agent stopped.")


app = FastAPI(
    title="MYRAA Desktop Control Agent",
    version=__version__,
    description="JARVIS-style desktop automation backend for MYRAA.",
    lifespan=lifespan,
)

@app.middleware("http")
async def authenticate_session(request: Request, call_next):
    return await SESSION_AUTH(request, call_next)


class ExecuteRequest(BaseModel):
    tool: str
    args: Dict[str, Any] = {}


class ExecuteResponse(BaseModel):
    ok: bool
    result: Any = None
    error: str | None = None
    tool: str


@app.get("/health")
def health() -> Dict[str, Any]:
    return {
        "status": "ok",
        "protocol": 2,
        "authenticated": True,
        "name": "MYRAA Desktop Control Agent",
        "version": __version__,
        "tools": sorted(TOOLS.keys()),
        "tool_count": len(TOOLS),
    }


@app.get("/tools")
def list_tools() -> Dict[str, Any]:
    return {"tools": sorted(TOOLS.keys()), "count": len(TOOLS)}


@app.get("/observe")
def observe() -> Dict[str, Any]:
    """Return a privacy-limited local desktop metadata snapshot."""
    return collect_snapshot()


@app.post("/execute", response_model=ExecuteResponse)
def execute(req: ExecuteRequest) -> ExecuteResponse:
    tool = req.tool
    args = req.args or {}
    log.info("EXEC tool=%s args=%s", tool if tool in TOOLS else "unknown", _short_args(args))

    if tool not in TOOLS:
        known = ", ".join(sorted(TOOLS.keys()))
        return ExecuteResponse(
            ok=False,
            error=f"Unknown tool '{tool}'. Known tools: {known}",
            tool=tool,
        )

    handler = TOOLS[tool]
    try:
        out = handler(args)
    except ToolError as e:
        log.warning("ToolError in %s", tool)
        return ExecuteResponse(ok=False, error=e.message, tool=tool)
    except Exception as e:  # noqa: BLE001
        log.error("Unhandled error in %s (%s)", tool, type(e).__name__)
        return ExecuteResponse(
            ok=False,
            error=f"Internal error in {tool}: {e}",
            tool=tool,
        )

    # Handlers return dicts like {"result": "..."}; pass the whole payload.
    log.info("DONE tool=%s result_type=%s", tool, type(out).__name__)

    return ExecuteResponse(ok=True, result=out, tool=tool)


def _short_args(args: Dict[str, Any]) -> str:
    """Even a supplied field name can contain a secret; log only the count."""
    return "{count=" + str(len(args)) + "}"


def main() -> None:
    """Allow `python -m desktop_agent.main` to launch uvicorn."""
    import uvicorn

    host = os.environ.get("MYRAA_AGENT_HOST", "127.0.0.1")
    port = int(os.environ.get("MYRAA_AGENT_PORT", "8765"))
    if host not in {"127.0.0.1", "::1", "localhost"}:
        raise ValueError("Desktop worker must listen on loopback.")
    start_owner_watchdog()
    log.info("Launching uvicorn on %s:%d", host, port)
    uvicorn.run(
        app,
        host=host,
        port=port,
        reload=False,
        log_level="info",
    )


if __name__ == "__main__":
    main()

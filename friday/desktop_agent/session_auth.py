"""Private backend-to-worker authentication; browsers use the Node consent API."""
from __future__ import annotations

import os
import re
import secrets

from starlette.requests import Request
from starlette.responses import JSONResponse


class SessionAuthentication:
    def __init__(self, token: str):
        self._expected = token.encode("ascii") if re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token) else None

    @property
    def configured(self) -> bool:
        return self._expected is not None

    async def __call__(self, request: Request, call_next):
        host = request.headers.get("host", "").lower()
        match = re.fullmatch(r"(?:127\.0\.0\.1|localhost|\[::1\])(?::([0-9]{1,5}))?", host)
        valid_host = bool(match and (match.group(1) is None or 0 < int(match.group(1)) <= 65535))
        if (not request.client or request.client.host not in {"127.0.0.1", "::1"}
                or not valid_host or request.headers.get("origin") is not None):
            return JSONResponse({"error": "Desktop worker accepts private loopback sessions only."}, status_code=403)
        if not self._expected:
            return JSONResponse({"error": "Start the desktop worker through MYRAA."}, status_code=503)
        scheme, _, supplied = request.headers.get("authorization", "").partition(" ")
        if scheme.lower() != "bearer" or not secrets.compare_digest(supplied.encode("utf-8"), self._expected):
            return JSONResponse({"error": "Desktop session authentication required."}, status_code=401,
                                headers={"WWW-Authenticate": "Bearer"})
        return await call_next(request)


# Consume the session credential before any user applications/scripts can be
# spawned. Neither this token nor provider credentials belong in their env.
_inherited = os.environ.pop("MYRAA_AGENT_SESSION_TOKEN", "")
for _name in list(os.environ):
    if re.search(r"(?:^|_)(?:API_KEY|TOKEN|SECRET|SECRET_KEY|ACCESS_KEY|PASSWORD|CREDENTIALS?|PRIVATE_KEY)$", _name, re.I):
        os.environ.pop(_name, None)
SESSION_AUTH = SessionAuthentication(_inherited)
_inherited = ""

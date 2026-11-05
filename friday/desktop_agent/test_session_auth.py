from __future__ import annotations
import unittest
from unittest.mock import Mock, patch
import psutil
from starlette.requests import Request
from starlette.responses import JSONResponse
from desktop_agent.session_auth import SessionAuthentication
from desktop_agent.owner_watchdog import owners_alive

TOKEN = "fixture_only_session_0000000000000000000000"


class SessionAuthenticationTests(unittest.IsolatedAsyncioTestCase):
    async def dispatch(self, *, token=TOKEN, authorization=None, origin=None, host="127.0.0.1:8765", client="127.0.0.1"):
        headers = [(b"host", host.encode())]
        if authorization is not None:
            headers.append((b"authorization", authorization.encode()))
        if origin is not None:
            headers.append((b"origin", origin.encode()))
        request = Request({"type": "http", "method": "POST", "path": "/execute", "raw_path": b"/execute", "scheme": "http",
                           "query_string": b"", "headers": headers, "client": (client, 1234), "server": ("127.0.0.1", 8765)})
        called = []

        async def next_request(_request):
            called.append(True)
            return JSONResponse({"ok": True})

        response = await SessionAuthentication(token)(request, next_request)
        return response.status_code, len(called)

    async def test_unconfigured_and_wrong_sessions_never_reach_tools(self):
        self.assertEqual(await self.dispatch(token=""), (503, 0))
        self.assertEqual(await self.dispatch(), (401, 0))
        self.assertEqual(await self.dispatch(authorization="Bearer wrong"), (401, 0))
        self.assertEqual(await self.dispatch(authorization="Bearer non-ascii-é"), (401, 0))

    async def test_valid_private_session_reaches_the_handler(self):
        self.assertEqual(await self.dispatch(authorization="Bearer " + TOKEN), (200, 1))

    async def test_browser_origins_are_rejected_even_with_a_valid_token(self):
        for origin in ["http://localhost:3000", "https://example.test", "null", ""]:
            self.assertEqual(await self.dispatch(authorization="Bearer " + TOKEN, origin=origin), (403, 0))

    async def test_nonloopback_or_rebound_hosts_and_clients_are_rejected(self):
        for host in ["example.test:8765", "127.0.0.1.example.test", "localhost:999999", "localhost/path"]:
            self.assertEqual(await self.dispatch(authorization="Bearer " + TOKEN, host=host), (403, 0))
        self.assertEqual(await self.dispatch(authorization="Bearer " + TOKEN, client="192.168.1.2"), (403, 0))


class OwnerWatchdogTests(unittest.TestCase):
    def test_owner_identity_includes_creation_time_to_detect_pid_reuse(self):
        process = Mock()
        process.is_running.return_value = True
        process.create_time.return_value = 42.0
        process.status.return_value = psutil.STATUS_RUNNING
        with patch("desktop_agent.owner_watchdog.psutil.Process", return_value=process):
            self.assertTrue(owners_alive([(1234, 42.0)]))
            self.assertFalse(owners_alive([(1234, 41.0)]))

    def test_missing_parent_fails_closed(self):
        with patch("desktop_agent.owner_watchdog.psutil.Process", side_effect=psutil.NoSuchProcess(1234)):
            self.assertFalse(owners_alive([(1234, 42.0)]))

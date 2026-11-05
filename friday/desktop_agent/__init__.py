"""MYRAA Desktop Control Agent.

A local FastAPI service exposing JARVIS-style desktop automation tools that
MYRAA's Node bridge (server.ts) calls over HTTP. This module package only
hosts tool code. Start MYRAA normally so its backend creates an authenticated,
owned loopback session; this service is not a standalone browser API.
"""

__version__ = "1.0.0"

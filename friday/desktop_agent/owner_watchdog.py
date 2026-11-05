"""Exit this worker when its owning backend/launcher exits; never kill user apps."""
from __future__ import annotations
import os
import threading
import time
import psutil

_owner_text = os.environ.pop("MYRAA_AGENT_PARENT_PID", "")
_started = False


def owners_alive(identities: list[tuple[int, float]]) -> bool:
    try:
        for pid, created in identities:
            process = psutil.Process(pid)
            if not process.is_running() or process.create_time() != created or process.status() == psutil.STATUS_ZOMBIE:
                return False
        return True
    except (psutil.NoSuchProcess, psutil.AccessDenied, OSError):
        return False


def start_owner_watchdog() -> None:
    global _started
    if _started or not _owner_text:
        return
    _started = True
    try:
        owner = int(_owner_text)
        if owner <= 0 or owner == os.getpid():
            raise ValueError("Invalid worker owner.")
        # In a frozen build, our immediate parent is the PyInstaller launcher.
        # Watching both identities also handles a launcher-only termination.
        identities = [(pid, psutil.Process(pid).create_time()) for pid in {owner, os.getppid()}]
    except (ValueError, psutil.Error, OSError):
        os._exit(0)
        return

    def watch() -> None:
        while owners_alive(identities):
            time.sleep(0.5)
        os._exit(0)  # this worker only; launched user applications stay open

    threading.Thread(target=watch, name="myraa-owner-watch", daemon=True).start()

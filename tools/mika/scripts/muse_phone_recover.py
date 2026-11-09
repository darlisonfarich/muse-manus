#!/usr/bin/env python3
"""Read-only MCP reconnection helper for Muse Manus.

Never submits phone_launch_app, phone_run, phone_tap or any other write.
The external Muse client must explicitly call this after reconnect and retain
the original request_id/commands; the Android server cannot reconnect it.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request

TAILSCALE_CGNAT = ipaddress.ip_network("100.64.0.0/10")


class RecoveryError(Exception):
    pass


def validate_endpoint(url: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme not in ("http", "https") or parsed.path != "/mcp"
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or not parsed.hostname):
        raise RecoveryError("Use an explicit local/Tailscale URL ending in /mcp")
    hostname = parsed.hostname.lower()
    try:
        address = ipaddress.ip_address(hostname)
        allowed = address.is_private or address.is_loopback or address in TAILSCALE_CGNAT
    except ValueError:
        allowed = hostname == "localhost" or hostname.endswith(".ts.net")
    if not allowed:
        raise RecoveryError("Only local/private or Tailscale MCP endpoints are allowed")
    return url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class McpReader:
    def __init__(self, url: str, token: str, timeout: float = 12):
        self.url = validate_endpoint(url)
        if not token or any(c in token for c in "\r\n"):
            raise RecoveryError("Missing or invalid MUSE_MCP_TOKEN")
        self.token = token
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def call(self, name: str, args: dict) -> dict:
        # Hard allowlist: this helper has no path that can issue a write.
        if name not in ("phone_status", "phone_reconcile", "phone_observe"):
            raise RecoveryError("Write tool prohibited during reconciliation")
        message = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                   "params": {"name": name, "arguments": args}}
        request = urllib.request.Request(
            self.url, data=json.dumps(message, separators=(",", ":")).encode(),
            headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json",
                     "Accept": "application/json"}, method="POST")
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                if response.headers.get_content_type() != "application/json":
                    raise RecoveryError("Unexpected MCP response type")
                raw = response.read(1_048_577)
                if len(raw) > 1_048_576:
                    raise RecoveryError("MCP response too large")
                decoded = json.loads(raw)
                if not isinstance(decoded, dict) or "error" in decoded:
                    raise RecoveryError("MCP RPC error")
                result = decoded.get("result")
                if not isinstance(result, dict) or result.get("isError") is True:
                    raise RecoveryError("MCP tool failed")
                payload = result.get("structuredContent")
                if not isinstance(payload, dict):
                    raise RecoveryError("MCP structuredContent missing")
                return payload
        except (urllib.error.URLError, OSError, ValueError) as exc:
            if isinstance(exc, RecoveryError):
                raise
            raise RecoveryError("MCP connection/response unavailable; no request was retried") from None


def recover(client: McpReader, request_id: str, kind: str, commands=None) -> dict:
    if not 8 <= len(request_id) <= 64 or not all(
            c.isascii() and (c.isalnum() or c in "_-") for c in request_id):
        raise RecoveryError("Invalid original request_id")
    if kind not in ("action", "batch"):
        raise RecoveryError("kind must be action or batch")
    if kind == "batch":
        if not isinstance(commands, list) or not 1 <= len(commands) <= 30:
            raise RecoveryError("Exact original commands must be supplied for a batch")
    elif commands is not None:
        raise RecoveryError("Action reconciliation does not accept commands")

    status = client.call("phone_status", {})
    query = {"kind": kind, "request_id": request_id}
    if kind == "batch":
        query["commands"] = commands
    journal = client.call("phone_reconcile", query)

    # Do not treat a missing journal record as proof the original command was
    # not admitted. Never auto-retry the old ID OR create a new write ID.
    report = {
        "decision": "manual_reconciliation_required",
        "safeToReplay": False,
        "status": {
            "active": status.get("active"),
            "pendingIntents": status.get("pendingIntents"),
            "launchInFlight": status.get("launchInFlight"),
            "launchClosePending": status.get("launchClosePending"),
        },
        "journal": {
            key: journal.get(key) for key in (
                "ok", "kind", "journalState", "checkpointState", "completed",
                "total", "nextIndex", "nextJournalState", "executionUnknown",
                "requiresFreshObservation", "error", "retryable")
            if key in journal
        },
        "observation": "deferred",
    }
    # An ongoing Binder launch can change the UI after observation. An unknown
    # launchInFlight field (older APK) is also not a safe freshness guarantee.
    if status.get("launchInFlight") is False:
        observed = client.call("phone_observe", {})
        report["observation"] = (
            "requires_user" if observed.get("error") == "needs_user"
            else "available" if observed.get("ok") is not False else "unavailable"
        )
        # No OCR/text/screenshot payload is ever written to stdout or disk.
    return report


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Read-only Muse Manus MCP recovery; never replays commands")
    p.add_argument("--url", required=True, help="Local or tailnet MCP URL ending in /mcp")
    p.add_argument("--request-id", required=True, help="Original stable request_id")
    p.add_argument("--kind", choices=("action", "batch"), required=True)
    p.add_argument("--commands-file", help="Required for batch: original JSON array of commands")
    args = p.parse_args(argv)
    try:
        token = os.environ.get("MUSE_MCP_TOKEN", "")
        commands = None
        if args.kind == "batch":
            if not args.commands_file:
                raise RecoveryError("batch requires --commands-file")
            commands = json.loads(Path(args.commands_file).read_text(encoding="utf-8"))
        report = recover(McpReader(args.url, token), args.request_id, args.kind, commands)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (RecoveryError, OSError, ValueError) as exc:
        # Never expose raw protocol content or tokens in failure messages.
        print(json.dumps({"error": str(exc), "safeToReplay": False}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

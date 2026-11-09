"""Offline tests for the read-only MCP reconciliation client."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "muse_phone_recover.py"
spec = importlib.util.spec_from_file_location("muse_phone_recover_test", SCRIPT)
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)

ID = "ger_spotify_A_20261108"


class FakeMcp:
    def __init__(self, *, launch=False, outcome="pending_unknown", observe_error=None):
        self.calls = []
        self.launch = launch
        self.outcome = outcome
        self.observe_error = observe_error

    def call(self, name, arguments):
        self.calls.append((name, arguments))
        if name == "phone_status":
            return {"ok": True, "pendingIntents": 0, "launchInFlight": self.launch}
        if name == "phone_reconcile":
            return {"ok": True, "journalState": self.outcome,
                    "executionUnknown": self.outcome != "completed_ok",
                    "requiresFreshObservation": True}
        if name == "phone_observe":
            return {"ok": self.observe_error is None,
                    "error": self.observe_error, "sensitiveText": "private-screenshot-content"}
        raise AssertionError("write or unexpected tool was called: " + name)


class RecoveryTest(unittest.TestCase):
    def test_hung_launch_checks_status_and_journal_only(self):
        client = FakeMcp(launch=True)
        report = recovery.recover(client, ID, "action")
        self.assertEqual(["phone_status", "phone_reconcile"], [n for n, _ in client.calls])
        self.assertEqual("deferred", report["observation"])
        self.assertFalse(report["safeToReplay"])

    def test_completed_launch_observes_once_but_never_replays(self):
        client = FakeMcp(outcome="completed_ok")
        report = recovery.recover(client, ID, "action")
        self.assertEqual(["phone_status", "phone_reconcile", "phone_observe"],
                         [n for n, _ in client.calls])
        self.assertEqual("available", report["observation"])
        self.assertFalse(report["safeToReplay"])
        self.assertNotIn("sensitiveText", str(report))

    def test_missing_or_uncertain_journal_does_not_trigger_new_id(self):
        for result in ("not_seen", "pending_unknown", "completed_unconfirmed", "journal_corrupt"):
            with self.subTest(result=result):
                client = FakeMcp(outcome=result)
                report = recovery.recover(client, ID, "action")
                self.assertFalse(report["safeToReplay"])
                self.assertEqual(ID, client.calls[1][1]["request_id"])
                self.assertTrue(all(name in {"phone_status", "phone_reconcile", "phone_observe"}
                                    for name, _ in client.calls))

    def test_sensitive_observation_requires_user(self):
        client = FakeMcp(observe_error="needs_user")
        report = recovery.recover(client, ID, "action")
        self.assertEqual("requires_user", report["observation"])
        self.assertFalse(report["safeToReplay"])

    def test_batch_requires_exact_original_commands_and_is_read_only(self):
        commands = [{"name": "phone_launch_app",
                     "arguments": {"package": "com.spotify.music"}}]
        client = FakeMcp()
        report = recovery.recover(client, ID, "batch", commands)
        self.assertEqual(commands, client.calls[1][1]["commands"])
        self.assertFalse(report["safeToReplay"])
        with self.assertRaises(recovery.RecoveryError):
            recovery.recover(client, ID, "batch", None)

    def test_unknown_status_does_not_assume_launch_settled(self):
        class OldClient(FakeMcp):
            def call(self, name, arguments):
                result = super().call(name, arguments)
                if name == "phone_status":
                    result.pop("launchInFlight", None)
                return result
        client = OldClient()
        report = recovery.recover(client, ID, "action")
        self.assertEqual("deferred", report["observation"])
        self.assertEqual(2, len(client.calls))

    def test_endpoint_rejects_public_targets_or_secret_in_url(self):
        for url in ("https://example.com/mcp", "https://u:token@100.64.0.1/mcp",
                    "http://8.8.8.8/mcp", "http://127.0.0.1/other",
                    "http://100.64.0.1/mcp?auth=secret"):
            with self.subTest(url=url), self.assertRaises(recovery.RecoveryError):
                recovery.validate_endpoint(url)
        self.assertEqual("http://100.64.1.2:8765/mcp",
                         recovery.validate_endpoint("http://100.64.1.2:8765/mcp"))


if __name__ == "__main__":
    unittest.main()

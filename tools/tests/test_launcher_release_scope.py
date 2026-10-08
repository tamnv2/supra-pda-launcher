"""Fail-closed scope, actor and version tests for future Launcher releases."""
import importlib.util
import json
import pathlib
import unittest

source = pathlib.Path(__file__).resolve().parents[1] / "launcher_release_owner_gate.py"
spec = importlib.util.spec_from_file_location("owner_gate", source)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

KEY1 = "a" * 64
KEY2 = "b" * 64

def valid(**changes):
    request = dict(version="0.3.26", scope="ALL", models="", devices="",
                   confirmation="DUYET 0.3.26 ALL", actor="tamnv2",
                   event="workflow_dispatch", ref="refs/heads/main",
                   repository="tamnv2/supra-pda-launcher", run_id="123456")
    request.update(changes)
    return gate.build_approval(**request)

class ReleaseScopeTests(unittest.TestCase):
    def assert_blocked(self, error, **changes):
        with self.assertRaisesRegex(ValueError, error):
            valid(**changes)

    def test_all_requires_no_selector(self):
        approval = valid()
        self.assertEqual(approval["version_code"], 326)
        self.assertEqual(approval["scope"], "ALL")
        self.assertEqual(approval["targets"], [])
        self.assertFalse(approval["enabled"])
        self.assertTrue(approval["required"])
        self.assert_blocked("ALL_MUST_NOT_HAVE_TARGETS", models="DT50")
        self.assert_blocked("ALL_MUST_NOT_HAVE_TARGETS", devices=KEY1)

    def test_models_scope(self):
        approval = valid(scope="MODEL", models="dt50,NLS-MT90",
                         confirmation="DUYET 0.3.26 MODEL")
        self.assertEqual(approval["targets"], ["DT50", "NLS-MT90"])
        self.assert_blocked("MODEL_SCOPE_REQUIRES_MODELS_ONLY",
                            scope="MODEL", confirmation="DUYET 0.3.26 MODEL")
        self.assert_blocked("TOO_MANY_OR_DUPLICATE_TARGETS",
                            scope="MODEL", models="DT50,dt50",
                            confirmation="DUYET 0.3.26 MODEL")

    def test_device_scope_accepts_only_registered_device_key_shape(self):
        approval = valid(scope="DEVICE", devices=f"{KEY1}, {KEY2}",
                         confirmation="DUYET 0.3.26 DEVICE")
        self.assertEqual(approval["targets"], [KEY1, KEY2])
        self.assert_blocked("INVALID_PDA_DEVICE_KEY",
                            scope="DEVICE", devices="MT9055GL2WEDK00125",
                            confirmation="DUYET 0.3.26 DEVICE")
        self.assert_blocked("TOO_MANY_OR_DUPLICATE_TARGETS",
                            scope="DEVICE", devices=f"{KEY1},{KEY1}",
                            confirmation="DUYET 0.3.26 DEVICE")

    def test_explicit_owner_identity(self):
        self.assert_blocked("OWNER_IDENTITY_REQUIRED", actor="someone-else")
        self.assert_blocked("OWNER_IDENTITY_REQUIRED", repository="other/fork")
        self.assert_blocked("MANUAL_MAIN_RELEASE_ONLY", event="push")
        self.assert_blocked("MANUAL_MAIN_RELEASE_ONLY", ref="refs/tags/v0.3.26")
        self.assert_blocked("OWNER_CONFIRMATION_VERSION_SCOPE_MISMATCH",
                            confirmation="DUYET 0.3.26 DEVICE")

    def test_old_version_never_republished(self):
        self.assert_blocked("ALREADY_RELEASED_FOUNDATION_OR_OLDER",
                            version="0.3.25", confirmation="DUYET 0.3.25 ALL")
        self.assert_blocked("VERSION_FORMAT_INVALID",
                            version="v0.3.26", confirmation="DUYET v0.3.26 ALL")
        self.assert_blocked("VERSION_CODE_OVERLAP",
                            version="0.3.100", confirmation="DUYET 0.3.100 ALL")

    def test_workflow_cannot_auto_publish_and_never_mutates_fleet_channel(self):
        workflow = (pathlib.Path(__file__).resolve().parents[2]
                    / ".github/workflows/release.yml").read_text()
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("environment: launcher-production", workflow)
        self.assertIn("Verify explicit owner identity", workflow)
        self.assertIn("launcher_release_owner_gate.py", workflow)
        self.assertIn("owner_confirmation:", workflow)
        self.assertNotIn("\\n  push:", workflow)
        self.assertNotIn("Publish fixed Launcher update channel", workflow)
        self.assertNotIn("--clobber", workflow.split("gh release create", 1)[-1])
        self.assertIn("launcher-rollout-receipt.json", workflow)
        self.assertIn("ROLLOUT_DEVICE_KEYS:", workflow)

    def test_scope_digest_tamper_protection(self):
        a = valid(scope="DEVICE", devices=f"{KEY1},{KEY2}",
                  confirmation="DUYET 0.3.26 DEVICE")
        b = valid(scope="DEVICE", devices=KEY1,
                  confirmation="DUYET 0.3.26 DEVICE")
        self.assertNotEqual(a["scope_sha256"], b["scope_sha256"])
        self.assertNotIn("MT9055", json.dumps(a))

if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Fail-closed owner authorization and scope specification for Launcher Releases.

No GitHub push/tag can create a Release; only a manually dispatched workflow
from the official repository by the owner can pass this verifier.

Device targeting uses existing PDA Registry's SHA-256 DeviceKey, NOT raw
Serial/MEID/IMEI.  The signed APK itself is the same for all supported PDA;
only the backend's explicit authorized policy enables rollout.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path


OWNER_LOGIN = "tamnv2"
REPOSITORY = "tamnv2/supra-pda-launcher"
FOUNDATION_CODE = 325
MAX_TARGETS = 200
VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
MODEL = re.compile(r"^[A-Z0-9][A-Z0-9._-]{1,99}$")
DEVICE_KEY = re.compile(r"^[0-9a-f]{64}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def version_code(version: str) -> int:
    m = VERSION.fullmatch(version.strip())
    if not m:
        raise ValueError("VERSION_FORMAT_INVALID")
    major, minor, patch = map(int, m.groups())
    if minor > 99 or patch > 99 or major > 100_000:
        raise ValueError("VERSION_CODE_OVERLAP")
    code = major * 10000 + minor * 100 + patch
    if code <= FOUNDATION_CODE:
        raise ValueError("ALREADY_RELEASED_FOUNDATION_OR_OLDER")
    return code


def target_list(value: str) -> list[str]:
    return [s.strip() for s in re.split(r"[,\n;]+", value or "") if s.strip()]


def build_approval(
    *, version: str, scope: str, models: str, devices: str,
    confirmation: str, actor: str, event: str, ref: str,
    repository: str, run_id: str,
) -> dict:
    if actor != OWNER_LOGIN or repository != REPOSITORY:
        raise ValueError("OWNER_IDENTITY_REQUIRED")
    if event != "workflow_dispatch" or ref != "refs/heads/main":
        raise ValueError("MANUAL_MAIN_RELEASE_ONLY")
    code = version_code(version)
    if scope not in ("ALL", "MODEL", "DEVICE"):
        raise ValueError("OWNER_MUST_SELECT_EXACT_SCOPE")
    if confirmation.strip() != f"DUYET {version} {scope}":
        raise ValueError("OWNER_CONFIRMATION_VERSION_SCOPE_MISMATCH")

    model_values = target_list(models)
    device_values = target_list(devices)
    if scope == "ALL":
        if model_values or device_values:
            raise ValueError("ALL_MUST_NOT_HAVE_TARGETS")
        targets: list[str] = []
    elif scope == "MODEL":
        if device_values or not model_values:
            raise ValueError("MODEL_SCOPE_REQUIRES_MODELS_ONLY")
        targets = [x.upper() for x in model_values]
        if any(not MODEL.fullmatch(x) for x in targets):
            raise ValueError("INVALID_PDA_MODEL")
    else:
        if model_values or not device_values:
            raise ValueError("DEVICE_SCOPE_REQUIRES_KEYS_ONLY")
        targets = [x.lower() for x in device_values]
        if any(not DEVICE_KEY.fullmatch(x) for x in targets):
            raise ValueError("INVALID_PDA_DEVICE_KEY_USE_REGISTRY_NOT_RAW_SERIAL")
    if len(targets) > MAX_TARGETS or len(set(targets)) != len(targets):
        raise ValueError("TOO_MANY_OR_DUPLICATE_TARGETS")

    return {
        "schema": "supra.launcher.owner_approval.v1",
        "version": version,
        "version_code": code,
        "scope": scope,
        "targets": targets,
        "required": True,
        # Publishing a signed APK is distinct from activating its rollout.
        # The root-only Inventory policy must be set to enabled=true
        # for the exact approved scope after the signed SHA is verified.
        "enabled": False,
        "sha256": None,
        "policy_id": f"owner-v{version.replace('.', '-')}-{scope.lower()}",
        "approved_by": OWNER_LOGIN,
        "approved_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "approval_event": "workflow_dispatch",
        "approval_run": (
            f"https://github.com/{REPOSITORY}/actions/runs/{run_id}"
            if run_id.isdecimal() else None
        ),
        "scope_sha256": hashlib.sha256(
            json.dumps({"version": version, "scope": scope, "targets": targets},
                       ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest(),
        "release_state": "SIGNED_ARTIFACT_APPROVED__ROLLOUT_REQUIRES_MATCHING_ROOT_POLICY",
    }


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--output", type=Path, help="Write approved rollout descriptor")
    p.add_argument("--finalize", type=Path, help="Fill SHA-256 after APK signing")
    p.add_argument("--apk", type=Path, help="Signed APK, used with --finalize")
    args = p.parse_args()
    if args.finalize:
        if not args.apk or not args.apk.is_file() or not args.finalize.is_file():
            p.error("Finalization requires an existing signed APK and approval file")
        approval = json.loads(args.finalize.read_text())
        if approval.get("schema") != "supra.launcher.owner_approval.v1":
            raise SystemExit("RELEASE_APPROVAL_SCHEMA_INVALID")
        approval["sha256"] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        if not SHA256.fullmatch(approval["sha256"]):
            raise SystemExit("SIGNATURE_APK_SHA_INVALID")
        args.finalize.write_text(json.dumps(approval, indent=2, ensure_ascii=False) + "\n")
        print("OWNER_APPROVED_SCOPED_APK_DIGEST=PASS")
        return

    if args.output is None:
        p.error("--output required")
    approval = build_approval(
        version=os.environ.get("RELEASE_VERSION", ""),
        scope=os.environ.get("ROLLOUT_SCOPE", ""),
        models=os.environ.get("ROLLOUT_MODELS", ""),
        devices=os.environ.get("ROLLOUT_DEVICE_KEYS", ""),
        confirmation=os.environ.get("OWNER_CONFIRMATION", ""),
        actor=os.environ.get("GITHUB_ACTOR", ""),
        event=os.environ.get("GITHUB_EVENT_NAME", ""),
        ref=os.environ.get("GITHUB_REF", ""),
        repository=os.environ.get("GITHUB_REPOSITORY", ""),
        run_id=os.environ.get("GITHUB_RUN_ID", ""),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(approval, indent=2, ensure_ascii=False) + "\n")
    print(
        f"OWNER_RELEASE_SCOPE_APPROVED=PASS version={approval['version']} "
        f"scope={approval['scope']} devices_or_models={len(approval['targets'])} "
        "rollout_enabled=false"
    )


if __name__ == "__main__":
    main()

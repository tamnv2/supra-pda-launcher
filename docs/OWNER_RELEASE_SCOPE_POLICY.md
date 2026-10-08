# Owner authorization policy — Launcher v0.3.26 onward

**Effective immediately for every version after v0.3.25. Never infer scope from the previous release.**

## Required question before ANY version publication

The assistant or release operator MUST ask the owner for the following decision **for each new version**:

> Phiên bản Launcher vX.Y.Z cần cập nhật phạm vi nào?
>
> 1. **ALL** — toàn bộ PDA.
> 2. **MODEL** — các dòng PDA cụ thể (ví dụ `DT50`, `NLS-MT90`).
> 3. **DEVICE** — danh sách Serial/MEID cụ thể (cần owner liệt kê).
>
> Sau khi chốt đối tượng, xác nhận có bắt buộc cập nhật không (mặc định bản Launcher hiện tại là bắt buộc).
> Chỉ thực hiện phát hành sau khi owner xác nhận phiên bản và danh sách đối tượng cuối cùng.

- Do not assume `ALL` if the owner gives no scope; do not inherit from a previous release.
- If `MODEL`, enumerate each exact model in the PDA Registry, preview matching fleet.
- If `DEVICE`, look up the requested raw Serial/MEID in the authorized `PDA_Devices` Registry and use its existing 64-character `DeviceKey`. **Never put raw Serial, MEID or IMEI into the public repository or GitHub Release.** Reject unknown or duplicate devices; resolve mismatches with the owner.
- Owner decisions are version-specific. An approval for v0.3.26 is invalid for v0.3.27, and changing scope or targets requires owner to approve again.
- New signed releases must not change the fleet-wide `launcher-channel` (pinned to v0.3.25).
- Keep the existing mandatory update behavior for members of the approved scope; never force nonmembers to update.

## Enforced GitHub release procedure

1. Validate APK changes and CI; produce owner-facing proposed `version`, selected model list or resolved device list, affected count, release notes, and any operational risk. **Ask owner and obtain explicit approval.**
2. Owner logs in to GitHub as `tamnv2` and opens **Actions → Release APK (Owner scope approval required) → Run workflow** on `main`, with:
   - `version`: a version strictly newer than v0.3.25.
   - `rollout_scope`: exactly `ALL`, `MODEL`, or `DEVICE`.
   - `rollout_models`: exact Registry model list, only for MODEL.
   - `rollout_device_keys`: 64-character SHA-256 DeviceKeys, only for DEVICE.
   - `owner_confirmation`: `DUYET 0.3.26 MODEL` (matching the version/scope exactly).
3. The release workflow checks actual GitHub actor `tamnv2`, `workflow_dispatch`, and `refs/heads/main`, rejects invalid/empty/conflicting selectors, duplicates, release versions <=0.3.25, and existing release tags **before restoring signing credentials**.
4. Builds and verifies the signed APK. Creates a publicly safe `launcher-rollout-receipt.json` with `version`, `scope`, count and SHA-256 digest of the exact authorized target list; the raw keys are intentionally absent. Publishes a version-specific GitHub Release **without activating rollout**.
5. Authenticated Inventory ROOT reviews the same owner decision and publishes the corresponding rule with `enabled:true`, `required:true`, exact `version_code`, SHA-256 of the signed APK, policy ID, scope and targets at `PUT /api/admin/launcher/update-policies`. Backend verifies every enabled rule against the owner's public release receipt before accepting. **Fail closed** if the receipt is missing, scope/target hash/version/SHA mismatches, or the owner hasn't approved.
6. Test the target cohort and nonmembers through `GET /downloads/launcher/manifest?device_key=<registered-key>`. A model/device not included must still be served baseline v0.3.25 or its separately authorized more specific applicable policy.
7. Log decision and release run; keep the policy revision for rollback. Pausing/deactivating a policy is permitted without another release; **releasing a new version or expanding its audience requires renewed owner approval**.

## Release separation: build vs rollout

A GitHub Release is only a signed downloadable artifact. It does not update anyone by itself.
The `launcher-channel` remains pinned to v0.3.25 and serves the previous v0.3.24 baseline.
Only an owner-approved, authenticated server policy enables v0.3.26+ targeting. Never update the shared channel to a newer version for model/device-targeted rollouts.

The `launcher-production` GitHub Environment should have `tamnv2` configured as a required reviewer by the repository owner in GitHub Settings → Environments. **This extra Environment protection is not automatically configured by committing YAML.** The workflow itself also independently checks the actual GitHub actor.

## Rollback and safety

- Disable/revoke the relevant rule with Root policy revision control. The Launcher can clear previously cached required-update gates after a successful manifest refresh; offline devices cannot refresh instantly.
- Do not invoke any special background polling or push merely to advertise new versions. Existing Launcher update cadence and stability constraints remain.
- CI validates source/workflow and signing, but a new PDA hardware/ROM behavior requires field testing.

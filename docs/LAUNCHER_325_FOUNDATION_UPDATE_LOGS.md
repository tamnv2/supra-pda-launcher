# v0.3.25 — Launcher common foundation (STAGING, NOT RELEASED)

Owner approved: 2026-10-08. **The main channel remains v0.3.24 until CI, backend deploy and physical PDA validation.** Do not merge or change release/version.txt until owner release decision.

## One APK for MT90 and Urovo DT50

- v0.3.25 is installed on all PDA once.
- Next releases can target ALL, MODEL (DT50/NLS-MT90), or DEVICE (registered SHA-256 DeviceKey mapped to Serial/MEID in PDA_Devices Sheet).
- v0.3.24 clients do not pass DeviceKey; backend always serves stable v0.3.25 foundation.
- On v0.3.25+, GET `/downloads/launcher/manifest?device_key=<sha256>` returns a release chosen using authoritative Core PDA registry plus revisioned Root-only rules.
- The client keeps the existing mandatory update modal and checksum-based installer; a policy rollback can clear the cached forced gate on successful recheck.
- This is **bounded scheduled detection**, not instantaneous push: update check on launcher resume, user Update, and when visible (same clock Handler, actual HTTP max once/hour, five-minute retry when an update was previously required). Background/foreground GMS push not introduced without physical compatibility/battery evidence.

## Deep battery data: DT50 only

- MainActivity shows battery normally on all models, but only Urovo DT50 writes raw battery samples, sysfs probe, charging transition, trend and firmware diagnostics.
- Other models skip all battery sampling, BatteryManager field uploads and dumpsys/sysfs collection.
- DT50-only periodic JobScheduler cadence changes from 15 minutes to 3 hours. Trigger-based charging records remain, deep probe is opt-in by model.
- Existing full snapshot is time-bounded; do not use battery voltage to fabricate percent.

## Log schedule

- Device/day/slot deterministic SHA-derived offset in [-15,+15] minutes:
  - slot 13:30 => 13:15–13:45 Asia/Ho_Chi_Minh
  - slot 21:30 => 21:15–21:45 Asia/Ho_Chi_Minh
- Android 11: OS `AlarmManager.setExactAndAllowWhileIdle` (two one-shot alarms/day), with `JobScheduler` for network upload; on Android 12+ without exact alarm permission, fall back to inexact alarm.
- No minute/hour polling for log upload. Android Doze/network/power-off mean **exact elapsed delivery cannot be promised**; preserve device actual/scheduled/server/Drive times in logs.
- Local JSONL split `launcher-log-YYYY-MM-DD-s1330.jsonl`, `-s2130.jsonl`. Events after evening cutover go to the following morning bucket. Old unsent v0.3.24 files are migrated by the existing backlog scanner.
- After server 30s/device throttling, one-shot deferred JobScheduler drains backlog, retry marker persists exponential backoff, no busy timer.
- Post to `/api/pda/launcher/logs`. Backend archives to `PDA Management/<YYYY-MM-DD>` under Drive folder `1Gm-O5nl_ITXZJ1SaLDAOeYaYcpmJ16YO`; Inventory/Agent log folder unchanged. Deferred Drive retry preserves the Launcher folder.

## Drive-confirmed deletion and bounded log storage

- Cloudflare `POST /api/pda/launcher/logs` returns `200 DRIVE_SYNCED` **only after Drive acknowledges the uploaded file**; a `202 BUFFERED` is not a Drive receipt.
- Launcher retains the local `.jsonl` with a persisted `.buffered` marker after a server-only buffer ACK. The next existing upload window uses small `GET /api/pda/launcher/logs/status` to check the matching DeviceKey and bundle ID, rather than re-uploading the full content.
- Only an explicit `DRIVE_SYNCED` confirmation authorizes local deletion. A local `.sent` marker fences interrupted cleanup and is removed only after the JSONL has gone; legacy v0.3.24 `.sent` files still on disk are revalidated against server receipts.
- Unsent files are never deleted because they are over 14 days old. Orphan marker files may be cleared after 7 days; the data file survives.
- A busy slot rotates into `-s1330-pNNN.jsonl` and `-s2130-pNNN.jsonl` segments (up to 140 events / ~70KB per segment) so that no event is silently omitted by the 180-event backend payload limit. An invalid JSONL is retained rather than sending a truncated replacement.
- DT50's 3-hour sampling job no longer performs upload checks; networking occurs in existing alarm-triggered JobScheduler jobs, bounded server retry jobs, or no more than once per existing scheduled window when a buffer is pending.
- Lightweight charging-state events on DT50 no longer open dozens of sysfs nodes; the full sysfs/battery source comparison remains available for the 3-hour deep diagnostic sample.
- Server Drive retry keeps the same `PDA Management/YYYY-MM-DD` destination. Its date comes from the bucket date, not potentially older final event timestamps.

**Risks / physical QA:** Android alarm delivery can slip in Doze; the log arrives as soon as the OS executes the one-shot job with connectivity. If a device remains offline indefinitely, unsent files persist and disk utilization can grow, so physical tests must check backlog size and include a low-space warning instead of silently deleting evidence. Never claim measured battery savings until a physical DT50 and MT90 are compared against v0.3.24.

## Required validation matrix before deployment

1. CI Java debug+release and Worker TypeScript + governance guards.
2. Physical Android 11 DT50 and MT90: same signed package and permissions, no unintended mandatory update, no regression in Accessibility/installer/app switching.
3. v0.3.24 auto-update foundation on two models only after backend ready; new v0.3.25 accurately chooses ALL/MODEL/DEVICE; nonmembers see no popup; release pause/rollback clears gate.
4. DT50 faulty 52%: capture full/non-full samples and compare to independent reading. MT90: zero `battery`, `battery_signal`, `battery_sample` and `dt50_sysfs_probe` events in launcher log.
5. Trigger 13:15–13:45 and 21:15–21:45, boot, timezone changes, offline, Doze, installer, interrupted upload, duplicate retry, 100-device burst, archived day folder, buffered retry destination.
6. Inspect launcher power usage and Worker/DO/GDrive request counts against v0.3.24 for equivalent workload.
7. Explicit owner authorization before production release/version change, beta deployment, or fleet-wide mandatory update.

## Backend policy workflow

Root-only API `GET/PUT /api/admin/launcher/update-policies`, optimistic revision and SHA-256 integrity, will be wired after backend review. Example future rule:
```json
{
  "expected_revision": 0,
  "rules": [{
    "id": "dt50-fieldtest-0326",
    "scope": "MODEL",
    "targets": ["DT50"],
    "version": "0.3.26",
    "version_code": 326,
    "sha256": "<real signed APK SHA256>",
    "enabled": true,
    "required": true
  }]
}
```
Never record raw Serial/MEID/IMEI in this public GitHub repo. Select device key by looking up the registered PDA using authorized registry/Sheet access. Do not enable a rule until its release APK and matching digest exist.

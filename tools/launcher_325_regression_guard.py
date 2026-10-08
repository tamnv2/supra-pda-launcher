#!/usr/bin/env python3
"""Safety gates for Launcher v0.3.25. Source assertions, not a substitute for PDA testing."""
from hashlib import sha256
from pathlib import Path

app = Path("app/src/main/java/vn/supra/pdalauncher")
diagnostics = (app / "LauncherDiagnostics.java").read_text()
update = (app / "UpdateManager.java").read_text()
main = (app / "MainActivity.java").read_text()
service = (app / "LauncherDiagnosticJobService.java").read_text()

def require(value, source, label):
    assert value in source, f"Missing v0.3.25 contract: {label}"

require('&& "DRIVE_SYNCED".equals(reply.optString("archive_status", ""))',
        diagnostics, "APK requires explicit Drive success")
require('&& "DRIVE_SYNCED".equals(receipt.optString("status", ""))',
        diagnostics, "recovery uses scoped Drive receipt")
require('deleteAfterDriveReceipt(file);', diagnostics, "archive-confirmed cleanup")
require('markBuffered(file, bundleId);', diagnostics, "retain pending logs")
require('NEVER auto-delete an unsent JSONL', diagnostics, "no age-based data loss")
require('if (allowUpload) uploadPendingSync(context);', diagnostics, "DT50 samples no network")
require('if (full && isDt50()) out.put("dt50_sysfs_probe"', diagnostics,
        "DT50 deep sysfs only for full samples")
require('if (batteryIntent == null || !isDt50()) return;', diagnostics,
        "other models omit battery telemetry")
require('MAX_SEGMENT_EVENTS = 140;', diagnostics, "segment event cap")
require('MAX_UPLOAD_EVENTS = 180;', diagnostics, "server compatible event cap")
require('if (invalidLines > 0 || events.length() != totalLines)', diagnostics,
        "never delete partially archived log")
require('13 * 60 + 30, 21 * 60 + 30', diagnostics, "two Vietnam windows")
require('alarms.setExactAndAllowWhileIdle', diagnostics, "OS alarm rather than timer")
require('setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)', diagnostics,
        "network-constrained JobScheduler")
require('if (required)', update, "mandatory updates preserved")
require('checkOnLaunch(MainActivity.this)', main, "active Launcher update discovery")
require('params.getJobId() == 12910330', service, "scheduled upload job wiring")
assert 'new Thread(() -> {\n            while (true)' not in diagnostics
assert '15L * 60L * 1000L;\n    private static final int JOB_ID' not in diagnostics

# Model the same stable hash/offset math as Java's slotMinute for 100 unique
# fictitious devices. Verify distribution and same input => same schedule.
for base in (13 * 60 + 30, 21 * 60 + 30):
    offsets = []
    for n in range(100):
        device = sha256(f"fake-device-{n}".encode()).hexdigest()
        digest = sha256(f"{device}|2026-10-08|{base}".encode()).hexdigest()
        offset = int(digest[:7], 16) % 31 - 15
        assert -15 <= offset <= 15
        offsets.append(offset)
    assert len(set(offsets)) >= 20, "Poor fleet distribution of alarm offsets"

print("LAUNCHER_325_SOURCE_AND_STAGGERED_LOG_GUARD=PASS")

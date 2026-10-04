# Build & Release

## CI build

Every push or pull request to `main` runs **Android CI**.

Artifacts:
- `supra-pda-launcher-debug`
- `supra-pda-launcher-release-unsigned`

CI uses Gradle 8.9 through `gradle/actions/setup-gradle`, so a Gradle Wrapper is not required for GitHub Actions.

## One-time signing setup

Create these repository secrets in:

**Settings → Secrets and variables → Actions → New repository secret**

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The production keystore must stay the same for all future versions so Android can update the installed app.

## Signed release

The **Release APK** workflow:

1. Restores the signing key from GitHub Actions Secrets.
2. Builds the signed release APK.
3. Verifies the APK signature with `apksigner`.
4. Creates a SHA-256 checksum.
5. Publishes both files to GitHub Releases.

It can run in either way:

- Push a tag such as `v0.3.0`.
- GitHub web → **Actions → Release APK → Run workflow** → enter `0.3.0`.

## Versioning

Version format: `x.y.z`.

Version code:

`major * 10000 + minor * 100 + patch`

Examples:

- 0.3.0 → 300
- 0.3.1 → 301
- 1.0.0 → 10000

## In-app update

**Cài đặt quản trị → Kiểm tra cập nhật** checks the latest GitHub Release.

When a newer release exists:

1. The launcher downloads the APK.
2. Android may request permission for SUPRA PDA to install unknown apps.
3. Android opens the package installer.
4. The APK updates over the installed version when the package name and signing key match.

Application ID must remain `vn.supra.pdalauncher`.

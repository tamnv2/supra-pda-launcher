# Build & Release

## CI build

Every push or pull request to `main` runs **Android CI** and produces a temporary debug APK artifact.

## Signed release

The **Release APK** workflow builds an APK signed with one stable production key and publishes it to GitHub Releases.

Required repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit the keystore or signing passwords to the repository.

## Versioning

Release workflow accepts `x.y.z`, for example `0.3.0`.

Version code is calculated as:

`major * 10000 + minor * 100 + patch`

Examples:

- 0.3.0 → 300
- 0.3.1 → 301
- 1.0.0 → 10000

## In-app update

Admin Settings → **Kiểm tra cập nhật** checks the latest GitHub Release.

If a newer APK exists:

1. The launcher downloads the release APK.
2. Android may request permission for SUPRA PDA to install unknown apps.
3. Android opens the system package installer.
4. The update installs over the existing app only when package name and signing key match.

The application ID must remain `vn.supra.pdalauncher`.

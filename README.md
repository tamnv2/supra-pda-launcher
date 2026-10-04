# SUPRA PDA Launcher

Custom Android Launcher dành cho PDA kho SUPRA.

## Mục tiêu

- Thay thế màn hình Home mặc định của PDA.
- Chỉ hiển thị các ứng dụng được quản trị viên cho phép.
- Khu vực quản trị được bảo vệ bằng mật khẩu.
- Cho phép bật/tắt ứng dụng và tự lưu cấu hình.
- Có chế độ chống lách bằng Accessibility để đưa người dùng về Home khi mở ứng dụng ngoài danh sách.
- Hỗ trợ quy trình build APK và phát hành bản cập nhật qua GitHub Actions/GitHub Releases.

## Trạng thái

- Baseline UI: v0.2.0-test.
- Package: `vn.supra.pdalauncher`.
- Min SDK: Android 6 (API 23).
- Target/Compile SDK: API 35.
- Java 17.

## Build

GitHub Actions:
- `build.yml`: build APK kiểm thử cho push/PR.
- `release.yml`: build APK release ký bằng GitHub Actions Secrets khi tạo tag `v*`.

> Không commit keystore hoặc mật khẩu ký APK vào repository.

## Cập nhật ứng dụng

Ứng dụng có thể kiểm tra GitHub Releases để phát hiện phiên bản mới. Vì Launcher không phải Device Owner, Android vẫn yêu cầu người dùng xác nhận cài APK cập nhật.

## Bảo mật

Repository không được chứa khóa ký APK, mật khẩu thật hoặc secret vận hành. Các secret release phải lưu trong **Settings → Secrets and variables → Actions**.

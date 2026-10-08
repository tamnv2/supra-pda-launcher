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
- `build.yml`: build APK kiểm thử và kiểm tra quy tắc duyệt phiên bản/phạm vi cho push/PR.
- `release.yml`: **chỉ phát hành thủ công bởi tài khoản GitHub owner `tamnv2`**. Mỗi phiên bản mới phải được owner duyệt phạm vi `ALL`, `MODEL` hoặc `DEVICE` cụ thể. Push/tag/chỉnh `release/version.txt` không tự phát hành.
- Phiên bản `v0.3.25` vẫn là nền tảng chung. Từ `v0.3.26`, GitHub Release chỉ chứa APK đã ký và biên nhận phạm vi owner duyệt; cập nhật trên máy chỉ được kích hoạt bởi chính sách backend ROOT trùng hoàn toàn với phạm vi đó.
- Hướng dẫn chi tiết: [Quy định owner duyệt phạm vi cập nhật](docs/OWNER_RELEASE_SCOPE_POLICY.md).

> Không commit keystore hoặc mật khẩu ký APK vào repository.

## Cập nhật ứng dụng

Ứng dụng có thể kiểm tra GitHub Releases để phát hiện phiên bản mới. Vì Launcher không phải Device Owner, Android vẫn yêu cầu người dùng xác nhận cài APK cập nhật.

## Signing

Repo có thể giữ ở chế độ public. Keystore và mật khẩu ký APK không commit vào source; 4 giá trị signing được lưu trong **Settings → Secrets and variables → Actions**.

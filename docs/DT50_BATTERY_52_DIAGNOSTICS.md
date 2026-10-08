# DT50 – Chẩn đoán báo pin đứng 52% (2026-10-08)

## Phạm vi

Chỉ bổ sung đọc và ghi log. **Chưa thay nguồn phần trăm pin trên màn hình Launcher**, không sửa cảnh báo pin yếu hay logic sạc. Không thay đổi API, tần suất upload, quyền Android hoặc lập lịch JobScheduler.

- Mỗi mẫu log 15 phút đang có: ghi `ACTION_BATTERY_CHANGED`, `BatteryManager`, dữ liệu pin sysfs gọn trên dòng DT50 và so sánh phần trăm giữa các nguồn.
- Mỗi giờ và khi khởi chạy: giữ mẫu sysfs/dumpsys chi tiết hiện có, bổ sung các trường điện áp và thời gian sạc/xả (nếu firmware cấp).
- `battery_trend`: thời gian % không đổi, mức thay đổi điện áp tính bằng mV, việc đổi trạng thái sạc khi % vẫn giữ nguyên. `possible_stale_percentage` chỉ là **nghi vấn**, không phải đo được % pin thực tế.
- `source_comparison`: `broadcast_percent`, `manager_percent`, `sysfs_percent` (khi đọc được), `sources_disagree`. `independent_accuracy_verified=false` để tránh dùng nhầm số liệu tương quan thành thẩm định chính xác.
- File log ở local, dùng chung gói gửi một lần trong cửa sổ cuối ngày và cơ chế retry của Launcher. Không gửi mới sau mỗi mẫu 15 phút.

## Cách kiểm tra một máy lỗi

1. Ghi nhận model, Android/firmware, phiên bản Launcher và số định danh thiết bị qua mã vạch đã có (không ghi IMEI/MEID trong log pin).
2. Mở máy, tháo sạc, sử dụng bình thường 60–90 phút. Quan sát xem máy vẫn 52% khi điện áp và dòng điện thay đổi không.
3. Cắm sạc và theo dõi thêm 60–90 phút, ghi lại thời điểm đổi trạng thái sạc, mức % Android và lúc pin báo đầy. Không cố xả đến sập nguồn.
4. Sau thời điểm gửi log cuối ngày (theo múi giờ Việt Nam), lấy file log theo cơ chế hiện có và lọc theo `battery_sample`, `battery_sample_full`, `battery_signal`, `battery_trend` và `source_comparison`.
5. So sánh cùng thời điểm với DT50 hoạt động bình thường. Nếu tất cả nguồn đều đứng 52%, cần kiểm tra firmware/fuel gauge, bo mạch tiếp xúc pin hoặc chính viên pin. Nếu có nguồn độc lập đọc ổn định khác biệt, mới đánh giá dùng cho giao diện.

## Đọc kết quả

| Quan sát | Hướng xử lý |
| --- | --- |
| Broadcast đứng 52%, BatteryManager/sysfs biến thiên hợp lý | Kiểm tra nguồn thay thế, kiểm thử chéo nhiều tình huống trước khi đổi UI |
| Cả ba đều 52%, điện áp/dòng thay đổi | Có dấu hiệu sai ở tầng nguồn đo/firmware; Launcher không thể suy ra % chính xác chỉ từ 52 |
| Không đọc được sysfs | Bình thường trên thiết bị hạn chế quyền; ghi nhận `_state`, `_readable_supplies`; không yêu cầu root |
| Chỉ điện áp thay đổi | Không được biến điện áp thành % tuyến tính; điện áp phụ thuộc tải, nhiệt độ, hoá học và quá trình sạc |

## Giới hạn

Phần trăm pin là ước lượng do bộ quản lý năng lượng cung cấp; không có quyền đọc cảm biến độc lập thần kỳ. Dữ liệu nguồn gốc giống nhau không thể tự xác minh chéo. Không can thiệp firmware, reset/calibrate pin từ ứng dụng hoặc xin quyền root để thử nghiệm.

**Không nâng version/release trên nhánh này**: bản cập nhật đang bị bắt buộc cho các PDA. Cần qua CI và một máy DT50 lỗi trước khi phát hành rộng.

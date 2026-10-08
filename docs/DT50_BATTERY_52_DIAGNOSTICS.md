# DT50 – Chẩn đoán báo pin đứng 52% (2026-10-08)

## Phạm vi

Chỉ bổ sung đọc và ghi log. **Chưa thay nguồn phần trăm pin trên màn hình Launcher**, không sửa cảnh báo pin yếu hay logic sạc. Bản v0.3.25 dùng chung; lịch upload thay bằng hai mốc 13:30/21:30 (+/-15 phút) và sampling JobScheduler chỉ còn trên DT50.

- Mỗi mẫu log định kỳ khoảng 3 giờ trên DT50: ghi `ACTION_BATTERY_CHANGED`, `BatteryManager`, dữ liệu pin sysfs gọn trên dòng DT50 và so sánh phần trăm giữa các nguồn.
- Khoảng mỗi 3 giờ với DT50 và khi khởi chạy: mẫu sysfs/dumpsys chi tiết và trường điện áp/dung lượng (nếu firmware cung cấp). Các model khác hoàn toàn không gửi dữ liệu pin.
- `battery_trend`: thời gian % không đổi, mức thay đổi điện áp tính bằng mV, việc đổi trạng thái sạc khi % vẫn giữ nguyên. `possible_stale_percentage` chỉ là **nghi vấn**, không phải đo được % pin thực tế.
- `source_comparison`: `broadcast_percent`, `manager_percent`, `sysfs_percent` (khi đọc được), `sources_disagree`. `independent_accuracy_verified=false` để tránh dùng nhầm số liệu tương quan thành thẩm định chính xác.
- File log ở local, tạo hai phân đoạn gửi theo lịch 13:30 và 21:30 (+/-15 phút theo từng PDA), có one-shot retry khi cần. Không upload theo mỗi mẫu.

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

**Không nâng version/release trên nhánh này**: bản v0.3.25 sẽ là nền tảng dùng chung và chưa phát hành khi chưa đạt CI/kiểm thử. Cần qua CI và một máy DT50 lỗi trước khi phát hành rộng.


## Log bổ sung để xây dựng bản thử nghiệm v0.3.25

| Nguồn | Dữ liệu thu thập |
| --- | --- |
| Android Broadcast | raw level/scale, %, trạng thái sạc, plugged, nhiệt độ, điện áp, health, cờ pin yếu |
| BatteryManager | %, dòng hiện tại/trung bình, điện lượng còn lại (nếu có), charge counter, trạng thái sạc, thời gian sạc còn lại (nếu có) |
| Sysfs DT50 | từng node pin; capacity, status, voltage_now/avg/ocv, current_now/avg, charge_now/full/full_design, energy_now/full/full_design, nhiệt độ, cycle_count |
| Đối chiếu | ba nguồn %, node sysfs, mức chênh, cờ bất đồng; không coi độc lập khi cùng fuel gauge |
| Diễn tiến | số phút % đứng yên, đổi cắm/rút sạc, khoảng biến động điện áp, thay đổi charge counter, nghi vấn pin đứng 52% |
| Firmware DT50 | build ID, display, incremental, board, model, hardware; không thu IMEI/MEID/serial thô |

Đã giới hạn thời gian gọi dumpsys battery để tránh làm treo hàng đợi log. Không thêm API request, quyền hệ thống hay lịch polling mới. Giữ chu kỳ lấy mẫu khoảng 3 giờ chỉ trên DT50 (JobScheduler có thể trễ), dữ liệu chuyển trạng thái sạc ghi theo sự kiện; upload theo hai cửa sổ 13:15–13:45 và 21:15–21:45 (giờ Việt Nam).

Luồng truyền hiện tại: Launcher lưu log local -> inventory-beta.supra.cc.cd/api/pda/launcher/logs -> Inventory service lưu tạm và archive qua Google Drive -> PDA Management/YYYY-MM-DD. Trên Drive tìm file có tên tiền tố scheduled_android_launcher-, xem reason launcher_daily_diagnostic và model DT50. Nếu không thấy, kiểm tra cả hai thư mục cùng tên ngày vì Drive hiện còn tình trạng trùng thư mục. Không ghi nhận dữ liệu nhạy cảm hay đánh giá % pin thật bằng công thức từ điện áp.

Cần kiểm thử và phê duyệt trước khi phát hành rộng v0.3.25. Trước hết thử trên một DT50 đang lỗi, ghi log khi rút sạc/sạc lại, xác minh phần trăm vẫn 52% trong khi cảm biến khác thay đổi. Không đổi màn hình pin hoặc cảnh báo pin yếu.
